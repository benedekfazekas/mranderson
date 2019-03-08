(ns leiningen.source-deps
  (:require [mranderson.util :as u]
            [mranderson.move :as move]
            [cemerick.pomegranate.aether :as aether]
            [me.raynes.fs :as fs]
            [leiningen.deps :as ldeps]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.walk :as walk]
            [clojure.edn :as edn])
  (:import [java.util.zip ZipFile ZipEntry ZipOutputStream]
           [java.util UUID]))

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
         entries (enumeration-seq (.entries zip))]
     (doseq [entry entries :when (not (.isDirectory ^java.util.zip.ZipEntry entry))
             :let [f (zip-target-file target-dir entry)]]
       (fs/mkdirs (fs/parent f))
       (io/copy (.getInputStream zip entry) f))
     (->> entries
          (filter #(not (.isDirectory ^java.util.zip.ZipEntry %)))
          (map #(.getName %))
          (filter #(or (.endsWith % ".clj")
                       (.endsWith % ".cljc")
                       (.endsWith % ".cljs")))))))

(defn- cljfile->prefix [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join ".")))

(defn- cljfile->dir [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join "/")))

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

(defn- import-fragment-left [clj-source]
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
  (doseq [class-dir (->> (u/java-class-dirs)
                         (map #(str/split % #"\."))
                         (map first)
                         set)]
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

(defn- expand-dep
  [repositories deps-node]
  (u/debug "deps-node: " deps-node)
  (let [res (if (map? deps-node)
          (->> (map
                (fn [[k _]]
                  (->> (aether/resolve-dependencies :coordinates [k] :repositories repositories)
                       (aether/dependency-hierarchy [k])))
                deps-node)
               (into {}))
          deps-node)]
    (u/debug "res: " res)
    res))

(defn- expand-dep-hierarchy
  [repositories dep-hierarchy]
  (walk/prewalk
   (partial expand-dep repositories)
   (zipmap (keys dep-hierarchy) (repeat nil))))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn- update-artifact!
  [{:keys [pname pversion pprefix skip-repackage-java-classes srcdeps prefix-exclusions project-source-dirs]}
   {:keys [art-name-cleaned art-version clj-files clj-dirs]}
   {:keys [src-path parent-clj-dirs]}]
  (let [repl-prefix      (replacement-prefix pprefix src-path art-name-cleaned art-version nil)
        prefixes         (apply dissoc (reduce #(assoc %1 %2 (str (replacement repl-prefix %2 nil))) {} (possible-prefixes clj-files)) prefix-exclusions)
        all-dirs         (->> [(if (str/ends-with? src-path (u/sym->file-name pprefix))
                                 project-source-dirs
                                 [])]
                              (apply
                               concat
                               [src-path]
                               (map fs/file clj-dirs)
                               parent-clj-dirs)
                              vec)]
    (u/info (format "  munge source files of %s artifact."  art-name-cleaned))
    (u/debug "    proj-source-dirs" project-source-dirs " clj files" clj-files "clj dirs" clj-dirs " path to dep" src-path "parent-clj-dirs: " parent-clj-dirs)
    (u/debug "   modified namespace prefix: " repl-prefix)
    (u/debug "    src path: " src-path)
    (u/debug "    parent clj dirs: " (str/join ":" parent-clj-dirs))
    (u/debug "    all dirs: " all-dirs)
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
          (move/move-ns old-ns new-ns srcdeps (u/file->extension (str clj-file)) all-dirs))
        ;; a clj file without ns
        (when-not (= "project.clj" clj-file)
          (let [old-path (str "target/srcdeps/" clj-file)
                new-path (str (u/sym->file-name pprefix) "/" art-name-cleaned "/" art-version "/" clj-file)]
            (fs/copy+ old-path (str "target/srcdeps/" new-path))
            ;; replace occurrences of file path references
            (doseq [file (u/clojure-source-files [srcdeps])]
              (update-path-in-file file clj-file new-path))
            ;; remove old file
            (fs/delete old-path)))))))

(defn- unzip-artifact! [{:keys [srcdeps]} {:keys [src-path]} dep]
  (let [art-name         (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version      (str "v" (-> dep second (str/replace "." "v")))
        clj-files        (doall (unzip (-> dep meta :file) srcdeps))
        clj-dirs         (->> (map cljfile->dir clj-files)
                              (remove str/blank?)
                              (map (fn [clj-dir] (str srcdeps "/" clj-dir)))
                              set)]
    (u/info "unzipping [" art-name-cleaned " [" art-version "]]")
    (u/debug (format "resolving transitive dependencies for %s:" art-name))
    [{:art-name-cleaned art-name-cleaned
      :art-version      art-version
      :clj-files        clj-files
      :clj-dirs         clj-dirs}
     {:src-path        (fs/file src-path (str/replace (str/join "/" [art-name-cleaned art-version]) "-" "_"))
      :parent-clj-dirs (map fs/file clj-dirs)}]))

(defn- walk-dep-tree
  "Walks a dependency tree in depth first order.

  Applies `pre-fn` on node before going down a level. `pre-fn` calculates and returns its own context and paths,
  these are passed down to the next level. After the subtree is processed `post-fn` gets applied using the context,
  paths returned by `pre-fn` for the same node."
  [deps pre-fn post-fn paths ctx]
  (doseq [[dep subdeps] deps]
    (when-not (#{'org.clojure/clojure 'org.clojure/clojurescript} (first dep))
      (let [[pre-result new-paths] (pre-fn ctx paths dep)]
        (when subdeps
          (walk-dep-tree subdeps pre-fn post-fn new-paths ctx))
        (post-fn ctx pre-result paths)))))

(defn- walk-ordered-deps
  "Walks a flat list of dependencies.

  Applies `pre-fn` on all the dependencies and collects the `pre-fn` returned contextual values and paths.
  Runs `post-fn` on all the dependencies in a reverse order using the `pre-fn` results and paths."
  [deps pre-fn post-fn paths ctx]
  (let [deps (remove #(#{'org.clojure/clojure 'org.clojure/clojurescript} (first %)) (keys deps))]
    (->> (map (partial pre-fn ctx paths) deps)
         ((juxt (partial reduce (fn [clj-dirs [_ {:keys [parent-clj-dirs]}]] (into clj-dirs parent-clj-dirs)) []) reverse))
         ((fn [[clj-dirs deps]]
            (dorun (map (fn [[pre-result _]] (post-fn ctx pre-result (update paths :parent-clj-dirs concat clj-dirs))) deps)))))))

(defn- create-dep-graph
  ([graph deps level]
   (reduce
    (fn [graph [dep subdeps]]
      (if subdeps
        (create-dep-graph (reduce (fn [g sd] (dep/depend g (ffirst sd) (first dep))) graph subdeps) subdeps (inc level))
        (if (= 0 level)
          (dep/depend graph nil (first dep))
          graph)))
    graph
    deps))
  ([deps]
   (create-dep-graph (dep/graph) deps 0)))

(defn- lein-project->ctx
  [{:keys [root target-path name version]} args]
  (let [opts                        (map #(edn/read-string %) args)
        project-prefix              (lookup-opt :project-prefix opts)
        pprefix                     (or (and project-prefix (clojure.core/name project-prefix)) (u/clean-name-version "mranderson" (u/mranderson-version)))
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage opts)
        prefix-exclusions           (lookup-opt :prefix-exclusions opts)
        srcdeps-relative            (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        project-source-dirs         (filter fs/directory? (.listFiles (fs/file (str target-path "/srcdeps/"))))
        unresolved-deps-hierarchy    (lookup-opt :unresolved-dependency-hierarchy opts)]
    (u/debug "skip repackage" skip-repackage-java-classes)
    (u/info "project prefix: " pprefix)
    {:pname                       name
     :pversion                    version
     :pprefix                     pprefix
     :skip-repackage-java-classes skip-repackage-java-classes
     :srcdeps                     srcdeps-relative
     :prefix-exclusions           prefix-exclusions
     :project-source-dirs         project-source-dirs
     :unresolved-deps-hierarchy   unresolved-deps-hierarchy}))

(defn- initial-paths [target-path pprefix]
  {:src-path        (fs/file target-path "srcdeps" (u/sym->file-name pprefix))
   :parent-clj-dirs []})

(defn- mranderson-unresolved-deps!
  "Unzips and tranforms files in dependencies in a unresolved dependency tree."
  [unresolved-deps-tree paths ctx]
  (u/info "working on an unresolved dependency hierarchy")
  (#'ldeps/walk-deps unresolved-deps-tree #'ldeps/print-dep)
  (walk-dep-tree unresolved-deps-tree unzip-artifact! update-artifact! paths ctx))

(defn- mranderson-resolved-deps!
  "Unzips and tranforms files in dependencies in a resolved dependency tree.

  Creates a topological order based on the expanded tree flatten out the resolved tree in a list like data structure
  ordered by the expanded tree based topological order process this ordered list with `walk-ordered-deps` that first
  unzips all deps and collects their contextual info and then performs the source transformation in
  reverse topological order"
  [resolved-deps unresolved-deps paths ctx]
  (let [unresolved-deps-topo-order (zipmap (dep/topo-sort (create-dep-graph unresolved-deps)) (range))
        topo-comparator            (fn [[l] [r]]
                                     (compare (get unresolved-deps-topo-order l Long/MAX_VALUE)
                                              (get unresolved-deps-topo-order r Long/MAX_VALUE)))
        ordered-resolved-deps      (->> (tree-seq map? (fn [m] (concat (keys m) (vals m))) resolved-deps)
                                        (filter vector?)
                                        (reduce (fn [m dep] (assoc m dep nil)) {})
                                        (into (sorted-map-by topo-comparator)))]
    (u/info "working on a resolved dep hierarchy")
    (#'ldeps/walk-deps resolved-deps #'ldeps/print-dep)
    (walk-ordered-deps
     ordered-resolved-deps
     unzip-artifact!
     update-artifact!
     paths
     ctx)))

(defn- copy-source-files
  [source-paths target-path]
  (fs/copy-dir (first source-paths) (str target-path "/srcdeps")))

(defn- mranderson [repositories dependencies {:keys [skip-repackage-java-classes unresolved-deps-hierarchy pname pversion] :as ctx} paths]
  (let [source-dependencies         (filter u/source-dep? dependencies)
        resolved-deps-tree          (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
                                         (aether/dependency-hierarchy source-dependencies))
        unresolved-deps-tree        (expand-dep-hierarchy repositories resolved-deps-tree)]
        (u/info "retrieve dependencies and munge clojure source files")
    (if unresolved-deps-hierarchy
      (mranderson-unresolved-deps! unresolved-deps-tree paths ctx)
      (mranderson-resolved-deps! resolved-deps-tree unresolved-deps-tree paths ctx))
    (when-not (or skip-repackage-java-classes (empty? (u/class-files)))
      (class-deps-jar!)
      (u/apply-jarjar! pname pversion)
      (replace-class-deps!))))

(defn source-deps
  "Dependencies as source: used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories dependencies source-paths target-path] :as project} & args]
  (copy-source-files source-paths target-path)
  (let [{:keys [pprefix] :as ctx} (lein-project->ctx project args)
        paths                     (initial-paths target-path pprefix)]
    (mranderson repositories dependencies ctx paths)))
