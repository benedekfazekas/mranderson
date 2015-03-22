(ns leiningen.source-deps
  (:require [mranderson.util :refer :all]
            [cemerick.pomegranate.aether :as aether]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace.move :refer [move-ns]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [clojure.pprint :as pp]
            [leiningen.core.main :refer [info debug]]
            [clojure.edn :as edn])
  (:import [java.util.zip ZipFile ZipEntry ZipOutputStream]
           [java.util UUID]))

(defn- zip-target-file
  [target-dir entry-path]
  ;; remove leading slash in case some bonehead created a zip with absolute
  ;; file paths in it.
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
          (filter #(.endsWith % ".clj"))))))

(defn- cljfile->prefix [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join ".")))

(defn- possible-prefixes [clj-files]
  (->> clj-files
       (map cljfile->prefix)
       (remove #(str/blank? %))
       (reduce #(if (%1 %2) (assoc %1 %2 (inc (%1 %2))) (assoc %1 %2 1) ) {})
       (filter #(< 1 (val %)))
       (map first)))

(defn- replacement-prefix [src src-path art-name art-version underscorize?]
  (let [path (->> (str/split (str src-path) #"/")
                  (drop-while #(not= src %))
                  rest
                  (map #(if underscorize? % (str/replace % "_" "-"))))]
    (->> ["deps" art-name art-version]
         (concat path)
         (str/join "."))))

(defn- replacement [prefix postfix underscorize?]
  (->> (if underscorize? (-> postfix
                             str
                             (str/replace "-" "_"))
           postfix)
       vector
       (concat [prefix])
       (str/join "." )
       symbol))

(defn- update-file [file prefixes prefix]
  (let [old (slurp file)
        new (-> (str/replace old (re-pattern (str "(\\[\\s*)" prefix "(\\s+\\[?)")) (str "$1" (prefixes prefix) "$2"))
                (str/replace (re-pattern (str "(\\s+)" prefix)) (str "$1" (prefixes prefix))))]
    (when-not (= old new)
      (spit file new))))

(defn- update-deftypes [file old-ns new-deftype]
  (let [old (slurp file)
        old-deftype-prefix (-> old-ns name (str/replace "-" "_"))
        new (str/replace old (re-pattern (str "(\\s+)" old-deftype-prefix)) (str "$1" (name new-deftype)))]
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

(defn- garble-import! [file-prefix file import-fragment uuid]
  (let [old (slurp (fs/file file-prefix file))
        new (if import-fragment
              (str/replace old import-fragment (str "\"" uuid "\""))
              old)]
    (when-not (= old new)
      (spit (fs/file file-prefix file) new))))

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

(defn- degarble-imports! [imports file uuid]
  (let [old (slurp file)
        orig-import (find-orig-import imports file)
        new (str/replace old (str "\"" uuid "\"") orig-import)]
    (when-not (= old new)
      (spit file new))))

(defn- fix-reference-in-imports [srcdeps repl-prefix imps clj-file]
  (if-let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
    (map #(vector (first %) (str/replace (second %) (str " " (name old-ns)) (str " " (name (replacement repl-prefix old-ns nil))))) imps)
    imps))

(defn- unzip&update-artifact! [pname pversion uuid srcdeps src-path dep-hierarchy prefix-exclusions dep]
  (let [art-name (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version (str "v" (-> dep second (str/replace "." "v")))
        clj-files (doall (unzip (-> dep meta :file) srcdeps))
        repl-prefix (replacement-prefix "srcdeps" src-path art-name-cleaned art-version nil)
        prefixes (apply dissoc (reduce #(assoc %1 %2 (str (replacement repl-prefix %2 nil))) {} (possible-prefixes clj-files)) prefix-exclusions)
        imports (->> clj-files
                     (reduce #(conj %1 (retrieve-import srcdeps %2)) [])
                     (remove nil?)
                     doall)
        fixed-imports (reduce (partial fix-reference-in-imports srcdeps repl-prefix) imports clj-files)]
    (info (format "  retrieving %s artifact."  art-name))
    (debug (format "    modified dependency name: %s modified version string: %s" art-name-cleaned art-version))
    (debug "   modified namespace prefix: " repl-prefix)
    (doseq [clj-file clj-files]
      (when-let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)]
        (let [import (find-orig-import imports clj-file)
              new-ns (replacement repl-prefix old-ns nil)
              new-deftype (replacement repl-prefix old-ns true)]
          (debug "    new ns:" new-ns)
          ;; garble imports
          (when-not (str/blank? import)
            (garble-import! srcdeps clj-file import uuid))
          ;; fixing generated classes/deftypes
          (when (.contains (name old-ns) "-")
            (doseq [file (clojure-source-files [srcdeps])]
              (update-deftypes file old-ns new-deftype)))
          ;; move actual ns-s
          (move-ns old-ns new-ns srcdeps [srcdeps]))))
    ;; fixing prefixes, degarble imports
    (doseq [file (clojure-source-files [srcdeps])]
      (doall (map (partial update-file file prefixes) (keys prefixes)))
      (degarble-imports! fixed-imports file uuid))
    ;; recur on transitive deps, omit clojure itself
    (when-let [trans-deps (dep-hierarchy dep)]
      (debug (format "resolving transitive dependencies for %s:" art-name))
      (debug trans-deps)
      (->> trans-deps
           keys
           (remove #(= (first %) (symbol "org.clojure/clojure")))
           (map (partial unzip&update-artifact! pname pversion uuid srcdeps (fs/file src-path (str/join "/" ["deps" art-name-cleaned art-version])) trans-deps prefix-exclusions))
           doall))))

(defn- class-deps-jar!
  "creates jar containing the deps class files"
  []
  (info "jaring all class file dependencies into target/class-deps.jar")
  (with-open [file (io/output-stream "target/class-deps.jar")
              zip (ZipOutputStream. file)
              writer (io/writer zip)]
    (let [class-files (class-files)]
      (binding [*out* writer]
        (doseq [class-file class-files]
          (with-open [input (io/input-stream class-file)]
            (.putNextEntry zip (ZipEntry. (remove-2parents class-file)))
            (io/copy input zip)
            (flush)
            (.closeEntry zip)))))))

(defn- replace-class-deps! []
  (info "deleting directories with class files in target/srcdeps...")
  (doseq [class-dir (->> (java-class-dirs)
                         (map #(str/split % #"\."))
                         (map first)
                         set)]
    (fs/delete-dir (str "target/srcdeps/" class-dir))
    (info "  " class-dir " deleted"))
  (info "unzipping repackaged class-deps.jar into target/srcdeps")
  (unzip (fs/file "target/class-deps.jar") (fs/file "target/srcdeps/")))

(defn- prefix-dependency-imports! [pname pversion srcdeps]
  (info "prefixing imports in clojure files...")
  (let [cleaned-name-version (clean-name-version pname pversion)
        java-dirs (java-class-dirs)
        clj-files (clojure-source-files-relative ["target/srcdeps"])
        imports (->> clj-files
                     (reduce #(conj %1 (retrieve-import srcdeps (remove-2parents %2))) [])
                     (remove nil?)
                     doall)
        class-names (map class-file->fully-qualified-name (class-files))]
    (debug "java-dirs" java-dirs)
    (doseq [file clj-files]
      (let [old (slurp (fs/file file))
            orig-import (find-orig-import imports file)
            new-import (reduce #(str/replace %1 %2 (str cleaned-name-version "." %2)) orig-import java-dirs)
            uuid (str (UUID/randomUUID))
            new (str/replace old orig-import uuid)
            new (reduce #(str/replace %1 (re-pattern %2) (str cleaned-name-version "." %2)) new class-names)
            new (str/replace new uuid new-import)]
        (when-not (= old new)
          (debug "file" file)
          (debug "orig-import" orig-import "new-import" new-import)
          (spit file new))))))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn source-deps
  "Dependencies as source: used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories dependencies source-paths root target-path name version] :as project} & args]
  (fs/copy-dir (first source-paths) (str target-path "/srcdeps"))
  (let [source-dependencies (filter source-dep? dependencies)
        srcdeps-relative (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        dep-hierarchy (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
                           (aether/dependency-hierarchy source-dependencies))
        uuid (str (UUID/randomUUID))
        opts (map #(edn/read-string %) args)
        prefix-exclusions (lookup-opt :prefix-exclusions opts)
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage opts)
        srcdeps (fs/file target-path "srcdeps")]
    (debug "skip repackage" skip-repackage-java-classes)
    (info "retrieve dependencies and munge clojure source files")
    (doall (map (partial unzip&update-artifact! name version uuid srcdeps-relative srcdeps dep-hierarchy prefix-exclusions) (keys dep-hierarchy)))
    (when-not skip-repackage-java-classes
      (prefix-dependency-imports! name version srcdeps)
      (class-deps-jar!)
      (apply-jarjar! name version)
      (replace-class-deps!))))
