(ns mranderson.core
  "Orchestrates the dependency-inlining pipeline: resolve dependencies, unzip
  each artifact under `target/srcdeps`, move and rewrite the namespaces (via
  `mranderson.move`), and repackage any bundled Java `.class` files through
  jarjar. `inline-deps` is the Leiningen-free entry point; `mranderson` is the
  lower-level workhorse it and the Leiningen task both call into. See
  doc/design.md for the architecture."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [me.raynes.fs :as fs]
            [mranderson.dependency.resolver :as dr]
            [mranderson.dependency.tree :as t]
            [mranderson.move :as move]
            [mranderson.log :as log]
            [mranderson.util :as u])
  (:import java.io.File
           java.util.UUID
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

;; inlined from leiningen source
(defn- print-dep [dep level]
  (log/info (apply str (repeat (* 2 level) \space)) (pr-str dep)))

(defn- zip-target-file
  [target-dir entry-path]
  (let [entry-path (str/replace-first (str entry-path) #"^/" "")]
    (fs/file target-dir entry-path)))

(defn- unzip
  "Unzips the jar/zip at `source` into `target-dir` (defaults to `(name
  source)`), skipping `META-INF` and `clj-kondo.exports` entries. Returns the
  seq of unzipped Clojure source entry names (`.clj`/`.cljc`/`.cljs`)."
  ([source]
   (unzip source (name source)))
  ([source target-dir]
   (with-open [zip (ZipFile. (fs/file source))]
     (let [entries (enumeration-seq (.entries zip))
           entry-pred (fn entry-pred [^java.util.zip.ZipEntry entry]
                        (not (or (.isDirectory entry)
                                 (str/includes? (str entry) "META-INF")
                                 (str/includes? (str entry) "clj-kondo.exports"))))
           clj-files (transient [])]
       (doseq [entry entries
               :when (entry-pred entry)
               :let  [f (zip-target-file target-dir entry)
                       entry-name (.getName ^ZipEntry entry)]]
         (fs/mkdirs (fs/parent f))
         (with-open [in (.getInputStream zip entry)]
           (io/copy in f))
         (when (or (.endsWith ^String entry-name ".clj")
                   (.endsWith ^String entry-name ".cljc")
                   (.endsWith ^String entry-name ".cljs"))
           (conj! clj-files entry-name)))
       (persistent! clj-files)))))

(defn- replacement-prefix
  "Builds the dotted namespace prefix an artifact's namespaces move under:
  `pprefix`, then the portion of `src-path` below the prefix dir, then
  `art-name` and `art-version`. When `underscorize?` is falsey, underscores are
  turned back into dashes (namespace form rather than file-path form)."
  [pprefix src-path art-name art-version underscorize?]
  (let [path (->> (str/split (str src-path) #"/")
                  (drop-while #(not= (-> (u/sym->file-name pprefix)
                                         (str/split #"/")
                                         last)
                                     %))
                  rest
                  (concat [pprefix])
                  (map #(if underscorize? % (str/replace % "_" "-"))))]
    (->> [art-name art-version]
         (concat path)
         (str/join "."))))

(defn- replacement
  "Joins `prefix` and `postfix` into a new namespace symbol. When `underscorize?`
  is truthy, dashes in `postfix` become underscores."
  [prefix postfix underscorize?]
  (->> (if underscorize?
         (-> postfix
             str
             (str/replace "-" "_"))
         postfix)
       vector
       (concat [prefix])
       (str/join "." )
       symbol))

(defn- update-path-in-file [file old-path new-path]
  (let [old (slurp file)
        new (str/replace old old-path new-path)]
    (when-not (= old new)
      (spit file new))))

(defn- java-class-fqns
  "Fully-qualified names of every bundled `.class` under `srcdeps` (their
  original packages, before jarjar relocates them). The namespace move leaves
  references to these untouched so the java-import pass can point them at the
  repackaged classes. See #97."
  [srcdeps]
  (into #{} (map (partial u/class-file->fully-qualified-name srcdeps)) (u/class-files srcdeps)))

(defn- import-fragment-left
  "Returns the suffix of `clj-source` starting at the `(` that opens the
  `(:import ...)` form, or nil if there is no `:import`. The left edge from which
  `import-fragment` slices out the whole import form."
  [^String clj-source]
  (let [index-of-import (.indexOf clj-source ":import")]
    (when (> index-of-import -1)
      (drop (loop [ns-decl-fragment (reverse (take index-of-import clj-source))
                   index-of-open-bracket 1]
              (cond (= \( (first ns-decl-fragment))
                    (- index-of-import index-of-open-bracket)

                    (not (re-matches #"\s" (-> ns-decl-fragment first str)))
                    (count clj-source)

                    :else (recur (rest ns-decl-fragment) (inc index-of-open-bracket)))) clj-source))))

(defn- import-fragment
  "Extracts the full `(:import ...)` form from `clj-source` as a string by
  balancing parentheses, or nil if there is none."
  [clj-source]
  (let [import-fragment-left (import-fragment-left clj-source)
        frag-count (count import-fragment-left)]
    (when (and import-fragment-left (> frag-count 0))
      (loop [index 1
             open-close 0]
        (cond
          (> open-close 0)
          (apply str (take index import-fragment-left))

          (>= index frag-count)
          nil

          :else
          (recur (inc index) (cond (= \( (nth import-fragment-left index))
                                   (dec open-close)

                                   (= \) (nth import-fragment-left index))
                                   (inc open-close)

                                   :else open-close)))))))

(defn- class-deps-jar!
  "creates `jar-file` containing the class files found under `srcdeps`"
  [srcdeps jar-file]
  (log/info "jaring all class file dependencies into" (str jar-file))
  (with-open [file (io/output-stream jar-file)
              zip (ZipOutputStream. file)
              writer (io/writer zip)]
    (let [class-files (u/class-files srcdeps)]
      (binding [*out* writer]
        (doseq [class-file class-files]
          (with-open [input (io/input-stream class-file)]
            (.putNextEntry zip (ZipEntry. (u/srcdeps-relative srcdeps class-file)))
            (io/copy input zip)
            (flush)
            (.closeEntry zip)))))))

(defn- delete-class-files!
  "Deletes the `.class` files under `dir`, then prunes any directories left
  empty. Other files (notably `.clj`/`.cljc`/`.cljs` sources) are preserved, so
  a dependency's compiled classes can be removed before unpacking the repackaged
  ones without clobbering sibling sources that happen to share a top-level
  directory with them, e.g. when an inlined lib's root namespace matches the
  project's own (see #88)."
  [^File dir]
  (when (.isDirectory dir)
    ;; reverse of the pre-order file-seq visits children before their parents,
    ;; so a directory is only checked for emptiness once its class files are gone
    (doseq [^File f (reverse (file-seq dir))]
      (cond
        (and (.isFile f) (str/ends-with? (.getName f) ".class"))
        (.delete f)

        (and (.isDirectory f) (empty? (.listFiles f)))
        (.delete f)))))

(defn- replace-class-deps!
  "Deletes the original `.class` files under `srcdeps` and unzips the
  jarjar-repackaged `jar-file` in their place."
  [srcdeps jar-file]
  (log/info "deleting class files in" (str srcdeps) "...")
  (doseq [class-dir (u/java-class-dirs srcdeps)]
    (delete-class-files! (fs/file srcdeps class-dir))
    (log/info "  " class-dir " class files deleted"))
  (log/info "unzipping repackaged" (str jar-file) "into" (str srcdeps))
  (unzip (fs/file jar-file) (fs/file srcdeps)))

(defn- prefix-occurrences
  "Prefixes each token in `tokens` with `cleaned-name-version` wherever it occurs
  in `s` and is not already preceded by a dot (so an occurrence that is already
  part of a longer, prefixed name is left alone)."
  [s tokens cleaned-name-version]
  (reduce (fn [acc token]
            (str/replace acc
                         (re-pattern (str "([^\\.])" token))
                         (str "$1" cleaned-name-version "." token)))
          s
          tokens))

(defn- repackaged-java-package
  "If `class` imported under `pkg` is one of the repackaged java classes
  (`class-names`, the original fully-qualified `.class` names), returns that
  class's original java package; otherwise nil.

  The package is matched as a suffix of `pkg`, not exactly: when a java class
  shares a package with a Clojure namespace (e.g. claypoole's
  `com.climate.claypoole.impl`), the namespace move has already prefixed `pkg`
  by the time imports are rewritten, so the bare java package only appears at
  the end of `pkg`. See #33."
  [pkg class class-names]
  (some (fn [fqn]
          (when (str/ends-with? fqn (str "." class))
            (let [jpkg (subs fqn 0 (- (count fqn) (count class) 1))]
              (when (or (= pkg jpkg) (str/ends-with? pkg (str "." jpkg)))
                jpkg))))
        class-names))

(defn- rewrite-import-spec
  "Rewrites a single `(:import …)` spec class-exactly, returning a seq of specs.

  A prefix-list `[pkg C1 C2 …]` is split so the repackaged java classes move to
  their jarjar package (`prefix` + the original java package) while the rest stay
  under `pkg`. This avoids prefixing classes that merely share a package with a
  repackaged one (#52) and keeps deftype-generated classes - prefixed separately
  by the namespace move - under the (namespace-prefixed) package they already
  have (#33). A fully-qualified class symbol is handled the same way."
  [spec class-names prefix]
  (cond
    (vector? spec)
    (let [pkg     (str (first spec))
          classes (rest spec)]
      (if (empty? classes)
        [spec]
        (let [grouped (group-by #(repackaged-java-package pkg (str %) class-names) classes)
              others  (seq (get grouped nil))]
          (cond-> []
            others (conj (into [(first spec)] others))
            true   (into (for [[jpkg cls] (dissoc grouped nil)]
                           (into [(symbol (str prefix "." jpkg))] cls)))))))

    (symbol? spec)
    (let [s    (str spec)
          jfqn (some #(when (or (= s %) (str/ends-with? s (str "." %))) %) class-names)]
      [(if jfqn (symbol (str prefix "." jfqn)) spec)])

    :else
    [spec]))

(defn- rewrite-import-form
  "Parses the `(:import …)` form text and rewrites each spec class-exactly,
  splitting mixed prefix-lists. Returns the rewritten form as a string, or the
  original text unchanged if it can't be parsed."
  [orig-import class-names prefix]
  (try
    (let [form (edn/read-string orig-import)]
      (pr-str (cons :import (mapcat #(rewrite-import-spec % class-names prefix) (rest form)))))
    (catch Exception _
      orig-import)))

(defn- rewrite-java-imports
  "Rewrites `content` so references to repackaged Java classes are prefixed with
  `cleaned-name-version`. The `(:import …)` fragment (`orig-import`) is parsed and
  rewritten class-exactly (see `rewrite-import-spec`); in the rest of the file
  classes appear fully qualified, so the exact class names are prefixed. The
  import fragment is swapped out for a placeholder while the body is rewritten so
  it isn't processed twice.

  When there is no `(:import …)` form the body is still rewritten: a file can
  reference a repackaged class fully qualified in its body without importing it
  (e.g. `(org.httpkit.PrefixThreadFactory. …)`), and those references must be
  prefixed too or they throw `ClassNotFoundException` at runtime. See #97."
  [content orig-import class-names cleaned-name-version]
  (if (str/blank? orig-import)
    (prefix-occurrences content class-names cleaned-name-version)
    (let [class-set  (set class-names)
          new-import (rewrite-import-form orig-import class-set cleaned-name-version)
          uuid       (str (UUID/randomUUID))]
      (-> content
          (str/replace orig-import uuid)
          (prefix-occurrences class-names cleaned-name-version)
          (str/replace uuid new-import)))))

(defn- import-excluded?
  "True if `file`'s path (relative to `srcdeps`) falls under one of the
  `prefix-exclusions` packages, which are left untouched when prefixing imports."
  [prefix-exclusions srcdeps file]
  (let [rel (u/srcdeps-relative srcdeps file)]
    (boolean (some #(str/includes? rel (str (u/sym->file-name %) "/")) prefix-exclusions))))

(defn- prefix-java-imports!
  "Rewrites Java `:import`s (and fully-qualified body references) of repackaged
  classes across every inlined source file under `srcdeps` - both the
  dependencies and the consuming project (#54, #92). The single Java-import pass,
  run after the namespaces have moved but while the class files are still in
  their original (unprefixed) locations, so their fully-qualified names are
  known. Files under `prefix-exclusions` are skipped."
  [pname pversion srcdeps prefix-exclusions]
  (let [cleaned-name-version (u/clean-name-version pname pversion)
        class-names          (map (partial u/class-file->fully-qualified-name srcdeps) (u/class-files srcdeps))]
    (when (seq class-names)
      (doseq [^File file (u/clojure-source-files [srcdeps])
              :when      (not (import-excluded? prefix-exclusions srcdeps file))]
        (let [old (slurp file)
              new (rewrite-java-imports old (import-fragment old) class-names cleaned-name-version)]
          (when-not (= old new)
            (log/debug "    prefixing java imports in" (str file))
            (spit file new)))))))

(defn- update-artifact!
  "Moves an artifact's namespaces and collects the resulting renames.

  In resolved-tree mode the renames are returned and applied later in a single
  global pass (the rename map is 1:1, so no per-artifact dir scoping is needed).
  In unresolved-tree mode the same namespace can be copied to many nested
  locations with location-dependent prefixes, so references are rewritten within
  this artifact's subtree right here, and `nil` is returned.

  Java `:import`s are handled separately by the global `prefix-java-imports!`
  pass once all class files are unpacked."
  [{:keys [pprefix srcdeps project-source-dirs expositions watermark unresolved-tree]}
   {:keys [art-name-cleaned art-version clj-files clj-dirs dep]}
   {:keys [src-path parent-clj-dirs branch]}]
  (let [repl-prefix (replacement-prefix pprefix src-path art-name-cleaned art-version nil)
        expose?     (first (filter (partial t/path-pred branch dep) expositions))]
    (log/info (format "  munge source files of %s artifact on branch %s exposed %s." art-name-cleaned branch (boolean expose?)))
    (log/debug "    modified namespace prefix: " repl-prefix)
    ;; Move every namespace's file(s) and collect the renames; handle the rare
    ;; no-`ns` files inline.
    (let [renames (reduce
                   (fn [renames clj-file]
                     (if-not (.exists (fs/file srcdeps clj-file)) ; some cljc may have moved with its platform file
                       renames
                       (if-let [old-ns (some->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
                         (let [new-ns (replacement repl-prefix old-ns nil)
                               ext    (u/file->extension (str clj-file))]
                           (log/debug "    new ns:" new-ns)
                           (move/move-ns-files! old-ns new-ns srcdeps ext)
                           (conj renames {:old-sym old-ns :new-sym new-ns :extension ext :watermark watermark}))
                         ;; a clj file without ns
                         (do
                           (when-not (= "project.clj" clj-file)
                             (let [old-path (fs/file srcdeps clj-file)
                                   new-path (str (u/sym->file-name pprefix) "/" art-name-cleaned "/" art-version "/" clj-file)]
                               (fs/copy+ old-path (fs/file srcdeps new-path))
                               ;; replace occurrences of file path references
                               (doseq [file (u/clojure-source-files [srcdeps])]
                                 (update-path-in-file file clj-file new-path))
                               ;; remove old file
                               (fs/delete old-path)))
                           renames))))
                   []
                   clj-files)]
      (if-not unresolved-tree
        renames
        (let [all-deps-dirs (->> (concat [src-path] (map fs/file clj-dirs) parent-clj-dirs)
                                 vec (mapv str) u/normalize-dirs (mapv fs/file))
              java-classes  (java-class-fqns srcdeps)]
          (move/replace-ns-symbols-in-source-files renames all-deps-dirs java-classes)
          (when (or (str/ends-with? src-path (u/sym->file-name pprefix)) expose?)
            (move/replace-ns-symbols-in-source-files
             (mapv #(assoc % :watermark nil) renames)
             project-source-dirs
             java-classes))
          nil)))))

(defn- remove-invalid-duplicates!
  "See issue #44. Some artifacts package duplicate files in weird locations
   (e.g. riddley has `riddley.compiler` in the root of the package) which
   causes a mismatch of expectations when moving files, resulting in
   exceptions."
  [srcdeps clj-files]
  (let [by-ns (group-by
                #(->> (fs/file srcdeps %)
                      (read-file-ns-decl)
                      (second))
                clj-files)]
    (mapcat
      (fn [[nspace files]]
        (if (and nspace (next files))
          ;; A namespace has been duplicated, so we need to find the files that
          ;; actually exist. There might be more than one file, in case of
          ;; CLJ/CLJS duplication.
          (let [expected-prefix (u/sym->file-name nspace)
                match? #{(str expected-prefix ".clj")
                         (str expected-prefix ".cljs")
                         (str expected-prefix ".cljc")}]
            (doseq [to-delete (remove match? files)]
              (log/warn "  removing duplicated file with namespace mismatch:" to-delete)
              (.delete (fs/file srcdeps to-delete)))
            (filter match? files))
          ;; Only one file means nothing to do (but it might still be in the
          ;; wrong place).
          files))
      by-ns)))

(defn- order-by-extension
  "Make sure that first clj and cljs files considered, cljc files only after them."
  [clj-files]
  (sort-by #(str/replace % ".cljc" ".cljx") clj-files))

(defn- unzip-artifact!
  "Unzips a single artifact's jar into `srcdeps` and returns a tuple of its
  artifact info (cleaned name, version, clj files and dirs, dep) and the child
  context (`src-path`, `branch`, `parent-clj-dirs`) the tree walk threads into
  this artifact's own dependencies. The pre-fn half of the walk callback pair,
  with `update-artifact!` as the post-fn."
  [{:keys [srcdeps]} {:keys [src-path branch]} dep]
  (let [art-name         (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version      (str "v" (-> dep second (str/replace "." "v")))
        _                (log/info "unzipping [" art-name-cleaned " [" art-version "]]")
        clj-files        (->> (unzip (-> dep meta :file) srcdeps)
                              (remove-invalid-duplicates! srcdeps)
                              order-by-extension
                              (doall))
        clj-dirs         (u/clj-files->dirs srcdeps clj-files)]
    (log/debug (format "resolving transitive dependencies for %s:" art-name))
    [{:art-name-cleaned art-name-cleaned
      :art-version      art-version
      :clj-files        clj-files
      :clj-dirs         clj-dirs
      :dep              dep}
     {:src-path        (fs/file src-path (str/replace (str/join "/" [art-name-cleaned art-version]) "-" "_"))
      :branch          (conj branch (first dep))
      :parent-clj-dirs (map fs/file clj-dirs)}]))

(defn copy-source-files
  "Copies each of `source-paths` into `<target-path>/<target-suffix>`
  (`target-suffix` defaults to `\"srcdeps\"`). Stages the project's own sources
  alongside the inlined deps so their references can be rewritten too."
  ([source-paths target-path]
   (copy-source-files source-paths target-path "srcdeps"))

  ([source-paths target-path target-suffix]
   (let [to (-> target-path (io/file target-suffix) .toString)]
     (doseq [source-path source-paths]
       (fs/copy-dir-into source-path to)))))

(defn- mranderson-unresolved-deps!
  "Unzips and transforms files in an unresolved dependency tree."
  [unresolved-deps-tree paths ctx]
  (let [unresolved-deps-tree (t/evict-subtrees unresolved-deps-tree '#{org.clojure/clojure org.clojure/clojurescript org.clojure/core.rrb-vector})]
    (log/info "in UNRESOLVED-TREE mode, working on an unresolved dependency tree")
    (t/walk-deps unresolved-deps-tree print-dep)
    (t/walk-dep-tree unresolved-deps-tree unzip-artifact! update-artifact! paths ctx)))

(defn- mranderson-resolved-deps!
  "Unzips and transforms files in a resolved dependency tree.

  Flattens the resolved tree into a topologically ordered list (the ordering is
  vestigial now - see doc/design.md), unzips every artifact and moves its
  namespaces via `walk-ordered-deps`, then applies all the collected renames in a
  single global pass so each source file is parsed only once."
  [resolved-deps unresolved-deps paths ctx]
  (let [unresolved-deps-topo-order (t/topological-order unresolved-deps)
        topo-comparator            (fn [[l] [r]]
                                     (compare (get unresolved-deps-topo-order l Long/MAX_VALUE)
                                              (get unresolved-deps-topo-order r Long/MAX_VALUE)))
        resolved-deps              (t/evict-subtrees resolved-deps '#{org.clojure/clojure org.clojure/clojurescript})
        ordered-resolved-deps      (->> (tree-seq map? (fn [m] (concat (keys m) (vals m))) resolved-deps)
                                        (filter vector?)
                                        (reduce (fn [m dep] (assoc m dep nil)) {})
                                        (into (sorted-map-by topo-comparator)))]
    (log/info "in RESOLVED-TREE mode, working on a resolved dependency tree")
    (t/walk-deps resolved-deps print-dep)
    ;; Each artifact moves its files and returns its renames; rewrite every
    ;; reference across all sources in a single pass, so each file is parsed once
    ;; rather than once per artifact whose dir scope overlaps it.
    (let [renames (->> (t/walk-ordered-deps ordered-resolved-deps unzip-artifact! update-artifact! paths ctx)
                       (apply concat))]
      (move/replace-ns-symbols-in-source-files renames [(:srcdeps ctx)] (java-class-fqns (:srcdeps ctx))))))

(defn mranderson
  "Inline and shadow dependencies so they can not interfere with other libraries' dependencies.

  `repositories` to resolve dependencies, `dependencies` list of dependencies to inline and shadow, `ctx` for opts and project specific attributes, `paths` for project specific paths.

  `ctx` in detail:
  - pname: project name
  - pversion: project version
  - pprefix: project prefix, defaults to mranderson{rnd}
  - skip-repackage-java-classes: Skips shadowing java classes part of a dependency if true
  - prefix-exclusions: prefixes to exclude when prefixing imports for java classes
  - unresolved-tree: switch to unresolved tree mode if true
  - overrides: overrides in the unresolved tree in unresolved tree mode
  - expositions: transitive dependencies made available for the project source files in unresolved tree mode
  - watermark: meta flag to mark inlined dependencies"

  [repositories dependencies {:keys [skip-repackage-java-classes unresolved-tree pname pversion overrides srcdeps prefix-exclusions] :as ctx} paths]
  (let [source-dependencies         (filter u/source-dep? dependencies)
        resolved-deps-tree          (dr/resolve-source-deps repositories source-dependencies)
        overrides                   (or (and unresolved-tree overrides) {})
        unresolved-deps-tree        (dr/expand-dep-hierarchy repositories resolved-deps-tree overrides)]
    (log/info "retrieve dependencies and munge clojure source files")
    (if unresolved-tree
      (mranderson-unresolved-deps! unresolved-deps-tree paths ctx)
      (mranderson-resolved-deps! resolved-deps-tree unresolved-deps-tree paths ctx))
    (when-not (or skip-repackage-java-classes (empty? (u/class-files srcdeps)))
      (let [jar-file (fs/file (fs/parent srcdeps) "class-deps.jar")]
        ;; rewrite java imports across all inlined sources (deps + the project's
        ;; own files) while the class files are still in their original
        ;; (unprefixed) locations, so their names are known
        (prefix-java-imports! pname pversion srcdeps prefix-exclusions)
        (class-deps-jar! srcdeps jar-file)
        (u/apply-jarjar! pname pversion srcdeps jar-file)
        (replace-class-deps! srcdeps jar-file)))))

(def default-repositories
  "Maven repositories used to resolve dependencies when none are supplied."
  [["central" {:url "https://repo1.maven.org/maven2/" :snapshots false}]
   ["clojars" {:url "https://repo.clojars.org/"}]])

(defn- default-project-prefix []
  (str "mranderson" (subs (str (UUID/randomUUID)) 0 8)))

(defn- mark-inline
  "Every entry in `:dependencies` is meant to be inlined, so tag each coordinate
  with the `^:inline-dep` meta that `mranderson` filters on. This spares callers
  from having to remember the meta tag (which is also awkward to express via the
  CLI)."
  [dependencies]
  (mapv #(vary-meta % assoc :inline-dep true) dependencies))

(defn inline-deps
  "Inline and shadow `:dependencies` so they cannot interfere with the
  dependencies of downstream consumers.

  This is the Leiningen-free counterpart of the `leiningen.inline-deps` plugin
  task: it takes a plain options map instead of a Leiningen project map, which
  makes it usable from a `tools.build` build script:

      (require '[mranderson.core :as mranderson])
      (mranderson/inline-deps
       {:project-prefix \"com.example.inlined\"
        :source-paths   [\"src\"]
        :dependencies   '[[org.clojure/tools.namespace \"1.5.1\"]]})

  or directly as a Clojure CLI tool function (`clojure -T:mranderson inline-deps`).

  The project's own `:source-paths` and the resolved dependency sources are
  copied into `<target-path>/srcdeps`, with the dependency namespaces (and
  references to them, including those in the project's own sources) rewritten
  under `:project-prefix`.

  Options:

  - `:dependencies`    (required) vector of `[lib version & kvs]` coordinates to
                       inline. Every entry is treated as an inline dep, so there
                       is no need to attach `^:inline-dep` meta yourself.
  - `:source-paths`    (required) the project's own source directories to copy
                       into `srcdeps` and rewrite.
  - `:project-prefix`  namespace/path prefix for the shadowed deps. Defaults to a
                       random `mranderson<hex>` prefix; set a stable value for
                       reproducible output.
  - `:target-path`     build target directory. Defaults to `\"target\"`; output is
                       written to `<target-path>/srcdeps`.
  - `:repositories`    Maven repositories used for resolution. Defaults to Maven
                       Central and Clojars (see `default-repositories`).
  - `:pname`           project name, used when repackaging bundled Java classes.
                       Defaults to `\"mranderson\"`.
  - `:pversion`        project version, used when repackaging bundled Java
                       classes. Defaults to `\"0.0.0\"`.
  - `:skip-repackage-java-classes` skip jarjar repackaging of bundled `.class`
                       files. Defaults to `false`.
  - `:prefix-exclusions` seq of prefixes to leave untouched when rewriting Java
                       imports.
  - `:unresolved-tree` use unresolved-tree mode (deeply nested isolation).
                       Defaults to `false`.
  - `:overrides`       overrides for unresolved-tree mode.
  - `:expositions`     transitive deps to expose to the project's sources in
                       unresolved-tree mode.
  - `:watermark`       meta key marking inlined namespaces. Defaults to
                       `:mranderson/inlined`.

  Returns the resolved project prefix (handy when it was generated)."
  [{:keys [dependencies source-paths project-prefix target-path repositories
           pname pversion skip-repackage-java-classes prefix-exclusions
           unresolved-tree overrides expositions watermark]
    :or   {target-path                 "target"
           repositories                default-repositories
           pname                       "mranderson"
           pversion                    "0.0.0"
           skip-repackage-java-classes false
           unresolved-tree             false
           watermark                   :mranderson/inlined}}]
  (assert (seq dependencies) ":dependencies must be a non-empty collection")
  (assert (seq source-paths) ":source-paths must be a non-empty collection")
  (let [target-path (str target-path)
        pprefix     (or (some-> project-prefix name) (default-project-prefix))]
    ;; The project's own sources have to live next to the inlined deps so that
    ;; their references can be rewritten too.
    (copy-source-files source-paths target-path)
    (let [srcdeps             (str target-path "/srcdeps")
          ;; At this point srcdeps only holds the copied project sources; the
          ;; prefix dir for the deps does not exist yet.
          project-source-dirs (filter fs/directory? (or (.listFiles (io/file srcdeps)) []))
          ctx                 {:pname                       pname
                               :pversion                    (str pversion)
                               :pprefix                     pprefix
                               :skip-repackage-java-classes skip-repackage-java-classes
                               :srcdeps                     srcdeps
                               :prefix-exclusions           prefix-exclusions
                               :project-source-dirs         project-source-dirs
                               :unresolved-tree             unresolved-tree
                               :overrides                   overrides
                               :expositions                 expositions
                               :watermark                   watermark}
          paths               {:src-path        (fs/file target-path "srcdeps" (u/sym->file-name pprefix))
                               :parent-clj-dirs []
                               :branch          []}]
      (mranderson repositories (mark-inline dependencies) ctx paths)
      pprefix)))
