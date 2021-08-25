(ns mranderson.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [me.raynes.fs :as fs]
            [mranderson.dependency.resolver :as dr]
            [mranderson.dependency.tree :as t]
            [mranderson.move :as move]
            [mranderson.util :as u])
  (:import java.util.UUID
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

;; inlined from leiningen source
(defn- print-dep [dep level]
  (u/info (apply str (repeat (* 2 level) \space)) (pr-str dep)))

(defn- zip-target-file
  [target-dir entry-path]
  (let [entry-path (str/replace-first (str entry-path) #"^/" "")]
    (fs/file target-dir entry-path)))

(defn- unzip
  "Takes the path to a zipfile source and unzips it to target-dir."
  ([source]
   (unzip source (name source)))
  ([source target-dir]
   (let [zip (ZipFile. (fs/file source))
         entries (enumeration-seq (.entries zip))
         entry-pred (fn entry-pred [^java.util.zip.ZipEntry entry]
                      (not (or (.isDirectory entry)
                               (str/includes? (str entry) "META-INF"))))]
     (doseq [entry entries
             :when (entry-pred entry)
             :let  [f (zip-target-file target-dir entry)]]
       (fs/mkdirs (fs/parent f))
       (io/copy (.getInputStream zip entry) f))
     (->> entries
          (filter entry-pred)
          (map #(.getName ^ZipEntry %))
          (filter #(or (.endsWith ^String % ".clj")
                       (.endsWith ^String % ".cljc")
                       (.endsWith ^String % ".cljs")))))))

(defn- cljfile->prefix [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join ".")))

(defn- possible-prefixes [clj-files]
  (->> clj-files
       (map cljfile->prefix)
       (remove #(str/blank? %))
       (remove #(= "clojure.core" %))
       (reduce #(if (%1 %2) (assoc %1 %2 (inc (%1 %2))) (assoc %1 %2 1) ) {})
                                        ;(filter #(< 1 (val %)))
       (map first)
       (map #(str/replace % "_" "-"))))

(defn- replacement-prefix [pprefix src-path art-name art-version underscorize?]
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

(defn- replacement [prefix postfix underscorize?]
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

(defn- import-fragment-left [^String clj-source]
  (let [index-of-import (.indexOf clj-source ":import")]
    (when (> index-of-import -1)
      (drop (loop [ns-decl-fragment (reverse (take index-of-import clj-source))
                   index-of-open-bracket 1]
              (cond (= \( (first ns-decl-fragment))
                    (- index-of-import index-of-open-bracket)

                    (not (re-matches #"\s" (-> ns-decl-fragment first str)))
                    (count clj-source)

                    :default (recur (rest ns-decl-fragment) (inc index-of-open-bracket)))) clj-source))))

(defn- import-fragment [clj-source]
  (let [import-fragment-left (import-fragment-left clj-source)]
    (when (and import-fragment-left (> (count import-fragment-left) 0))
      (loop [index 1
             open-close 0]
        (if (> open-close 0)
          (apply str (take index import-fragment-left))
          (recur (inc index) (cond (= \( (nth import-fragment-left index))
                                   (dec open-close)

                                   (= \) (nth import-fragment-left index))
                                   (inc open-close)

                                   :default open-close)))))))

(defn- retrieve-import [file-prefix file]
  (let [cont (slurp (fs/file file-prefix file))
        import-fragment (import-fragment cont)]
    (when-not (str/blank? import-fragment)
      [file import-fragment])))

(defn- find-orig-import [imports file]
  (or (loop [imps imports]
        (let [imp (first imps)]
          (if (.endsWith (str file) (str (first imp)))
            (second imp)
            (recur (rest imps))))) ""))

(defn- class-deps-jar!
  "creates jar containing the deps class files"
  []
  (u/info "jaring all class file dependencies into target/class-deps.jar")
  (with-open [file (io/output-stream "target/class-deps.jar")
              zip (ZipOutputStream. file)
              writer (io/writer zip)]
    (let [class-files (u/class-files)]
      (binding [*out* writer]
        (doseq [class-file class-files]
          (with-open [input (io/input-stream class-file)]
            (.putNextEntry zip (ZipEntry. (u/remove-2parents class-file)))
            (io/copy input zip)
            (flush)
            (.closeEntry zip)))))))

(defn- replace-class-deps! []
  (u/info "deleting directories with class files in target/srcdeps...")
  (doseq [class-dir (u/java-class-dirs)]
    (fs/delete-dir (str "target/srcdeps/" class-dir))
    (u/info "  " class-dir " deleted"))
  (u/info "unzipping repackaged class-deps.jar into target/srcdeps")
  (unzip (fs/file "target/class-deps.jar") (fs/file "target/srcdeps/")))

(defn- filter-clj-files [imports package-names]
  (if (seq package-names)
    (->> (filter (fn [[_ import]] (some #(str/includes? import %) package-names)) imports)
         (map first)
         (map (partial str "target/srcdeps/")))
    []))

(defn- prefix-dependency-imports! [pname pversion pprefix prefix src-path srcdeps]
  (let [cleaned-name-version (u/clean-name-version pname pversion)
        prefix (some-> (first prefix)
                       (str/replace "-" "_")
                       (str/replace "." "/"))
        clj-dep-path (u/relevant-clj-dep-path src-path prefix pprefix)
        clj-files (u/clojure-source-files-relative clj-dep-path)
        imports (->> clj-files
                     (reduce #(conj %1 (retrieve-import srcdeps (u/remove-2parents %2))) [])
                     (remove nil?)
                     doall)
        class-names (map u/class-file->fully-qualified-name (u/class-files))
        package-names (->> class-names
                           (map u/class-name->package-name)
                           set)
        clj-files (filter-clj-files imports package-names)]
    (when (seq clj-files)
      (u/info (format "    prefixing imports in clojure files in '%s' ..." (str/join ":" clj-dep-path)))
      (u/debug "      class-names" class-names)
      (u/debug "      package-names" package-names)
      (u/debug "      imports" imports)
      (u/debug "      clj files" (str/join ":" clj-files))
      (doseq [file clj-files]
        (let [old         (slurp (fs/file file))
              orig-import (find-orig-import imports file)
              new-import  (reduce #(str/replace %1 (re-pattern (str "([^\\.])" %2)) (str "$1" cleaned-name-version "." %2)) orig-import package-names)
              uuid        (str (UUID/randomUUID))
              new         (str/replace old orig-import uuid)
              new         (reduce #(str/replace %1 (re-pattern (str "([^\\.])" %2)) (str "$1" cleaned-name-version "." %2)) new class-names)
              new         (str/replace new uuid new-import)]
          (when-not (= old new)
            (u/debug "file: " file " orig import:" orig-import " new import:" new-import)
            (spit file new))))
      (u/info "    prefixing imports: done"))))

(defn- update-artifact!
  [{:keys [pname pversion pprefix skip-repackage-java-classes srcdeps prefix-exclusions project-source-dirs expositions watermark]}
   {:keys [art-name-cleaned art-version clj-files clj-dirs dep]}
   {:keys [src-path parent-clj-dirs branch]}]
  (let [repl-prefix      (replacement-prefix pprefix src-path art-name-cleaned art-version nil)
        prefixes         (apply dissoc (reduce #(assoc %1 %2 (str (replacement repl-prefix %2 nil))) {} (possible-prefixes clj-files)) prefix-exclusions)
        expose?          (first (filter (partial t/path-pred branch dep) expositions))
        all-deps-dirs    (->> (concat
                               [src-path]
                               (map fs/file clj-dirs)
                               parent-clj-dirs)
                              vec
                              (mapv str)
                              u/remove-subdirs
                              (mapv fs/file))]
    (u/info (format "  munge source files of %s artifact on branch %s exposed %s." art-name-cleaned branch (boolean expose?)))
    (u/debug "    proj-source-dirs" project-source-dirs " clj files" clj-files "clj dirs" clj-dirs " path to dep" src-path "parent-clj-dirs: " parent-clj-dirs)
    (u/debug "    modified namespace prefix: " repl-prefix)
    (u/debug "    src path: " src-path)
    (u/debug "    parent clj dirs: " (str/join ":" parent-clj-dirs))
    (u/debug "    all dirs: " all-deps-dirs)
    (u/debug (format "    modified dependency name: %s modified version string: %s" art-name-cleaned art-version))
    (when-not skip-repackage-java-classes
      (if (str/ends-with? (str src-path) (u/sym->file-name pprefix))
        (doall
         (map #(prefix-dependency-imports! pname pversion pprefix % (str src-path) srcdeps) prefixes))
        (prefix-dependency-imports! pname pversion pprefix nil (str src-path) srcdeps)))
    (doseq [clj-file clj-files]
      (if-let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
        (let [new-ns (replacement repl-prefix old-ns nil)]
          (u/debug "    new ns:" new-ns)
          (move/move-ns old-ns new-ns srcdeps (u/file->extension (str clj-file)) all-deps-dirs watermark)
          (when (or (str/ends-with? src-path (u/sym->file-name pprefix)) expose?)
            (move/replace-ns-symbol-in-source-files old-ns new-ns (u/file->extension (str clj-file)) project-source-dirs nil)))
        ;; a clj file without ns
        (when-not (= "project.clj" clj-file)
          (let [old-path (fs/file srcdeps clj-file)
                new-path (str (u/sym->file-name pprefix) "/" art-name-cleaned "/" art-version "/" clj-file)]
            (fs/copy+ old-path (fs/file srcdeps new-path))
            ;; replace occurrences of file path references
            (doseq [file (u/clojure-source-files [srcdeps])]
              (update-path-in-file file clj-file new-path))
            ;; remove old file
            (fs/delete old-path)))))))

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
              (u/warn "  removing duplicated file with namespace mismatch:" to-delete)
              (.delete (fs/file srcdeps to-delete)))
            (filter match? files))
          ;; Only one file means nothing to do (but it might still be in the
          ;; wrong place).
          files))
      by-ns)))

(defn- unzip-artifact! [{:keys [srcdeps]} {:keys [src-path branch]} dep]
  (let [art-name         (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version      (str "v" (-> dep second (str/replace "." "v")))
        _                (u/info "unzipping [" art-name-cleaned " [" art-version "]]")
        clj-files        (->> (unzip (-> dep meta :file) srcdeps)
                              (remove-invalid-duplicates! srcdeps)
                              (doall))
        clj-dirs         (u/clj-files->dirs srcdeps clj-files)]
    (u/debug (format "resolving transitive dependencies for %s:" art-name))
    [{:art-name-cleaned art-name-cleaned
      :art-version      art-version
      :clj-files        clj-files
      :clj-dirs         clj-dirs
      :dep              dep}
     {:src-path        (fs/file src-path (str/replace (str/join "/" [art-name-cleaned art-version]) "-" "_"))
      :branch          (conj branch (first dep))
      :parent-clj-dirs (map fs/file clj-dirs)}]))

(defn copy-source-files
  [source-paths target-path]
  (fs/copy-dir (first source-paths) (str target-path "/srcdeps")))

(defn- mranderson-unresolved-deps!
  "Unzips and transforms files in an unresolved dependency tree."
  [unresolved-deps-tree paths ctx]
  (let [unresolved-deps-tree (t/evict-subtrees unresolved-deps-tree '#{org.clojure/clojure org.clojure/clojurescript})]
    (u/info "in UNRESOLVED-TREE mode, working on an unresolved dependency tree")
    (t/walk-deps unresolved-deps-tree print-dep)
    (t/walk-dep-tree unresolved-deps-tree unzip-artifact! update-artifact! paths ctx)))

(defn- mranderson-resolved-deps!
  "Unzips and transforms files in a resolved dependency tree.

  Creates a topological order based on the expanded tree. Flattens out the resolved tree into a list like data structure
  ordered by the expanded tree based topological order. Processes this ordered list with `walk-ordered-deps` that first
  unzips all deps and collects their contextual info and then performs the source transformation in
  reverse topological order."
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
    (u/info "in RESOLVED-TREE mode, working on a resolved dependency tree")
    (t/walk-deps resolved-deps print-dep)
    (t/walk-ordered-deps
     ordered-resolved-deps
     unzip-artifact!
     update-artifact!
     paths
     ctx)))

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
  - expositions: transient dependencies made available for the project source files in unresolved tree mode
  - watermark: meta flag to mark inlined dependencies"

  [repositories dependencies {:keys [skip-repackage-java-classes unresolved-tree pname pversion overrides] :as ctx} paths]
  (let [source-dependencies         (filter u/source-dep? dependencies)
        resolved-deps-tree          (dr/resolve-source-deps repositories source-dependencies)
        overrides                   (or (and unresolved-tree overrides) {})
        unresolved-deps-tree        (dr/expand-dep-hierarchy repositories resolved-deps-tree overrides)]
    (u/info "retrieve dependencies and munge clojure source files")
    (if unresolved-tree
      (mranderson-unresolved-deps! unresolved-deps-tree paths ctx)
      (mranderson-resolved-deps! resolved-deps-tree unresolved-deps-tree paths ctx))
    (when-not (or skip-repackage-java-classes (empty? (u/class-files)))
      (class-deps-jar!)
      (u/apply-jarjar! pname pversion)
      (replace-class-deps!))))
