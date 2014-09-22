(ns leiningen.source-deps
  (:require [lein-source-deps.util :refer [first-src-path clojure-source-files source-dep?]]
            [cemerick.pomegranate.aether :as aether]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace.move :refer [move-ns replace-ns-symbol]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [clojure.pprint :as pp]
            [leiningen.core.main :refer [info debug]])
  (:import [java.util.zip ZipFile]))

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

(defn- unzip&update-artifact! [srcdeps src-path dep-hierarchy dep]
  (let [art-name (-> dep first name (str/split #"/") last)
        art-name-cleaned (str/replace art-name #"[\.-_]" "")
        art-version (str "v" (-> dep second (str/replace "." "v")))
        clj-files (unzip (-> dep meta :file) srcdeps)
        repl-prefix (replacement-prefix "srcdeps" src-path art-name-cleaned art-version nil)
        prefixes (reduce #(assoc %1 %2 (str (replacement repl-prefix %2 nil))) {} (possible-prefixes clj-files))]
    (info (format "retrieving %s artifact. modified dependency name: %s modified version string: %s" art-name art-name-cleaned art-version))
    (info "   modified namespace prefix: " repl-prefix)
    (doseq [clj-file clj-files]
      (let [old-ns (->> clj-file (fs/file srcdeps) read-file-ns-decl second)
            new-ns (replacement repl-prefix old-ns nil)
            new-deftype (replacement repl-prefix old-ns true)]
        ;; fixing generated classes/deftypes
        (when (.contains (name old-ns) "-")
          (doseq [file (clojure-source-files [srcdeps])]
            (update-deftypes file old-ns new-deftype)))
        ;; move actual ns-s
        (move-ns old-ns new-ns srcdeps [srcdeps])))
    ;; fixing prefixes
    (doseq [file (clojure-source-files [srcdeps])]
      (doall (map (partial update-file file prefixes) (keys prefixes))))
    ;; recur on transitive deps, omit clojure itself
    (when-let [trans-deps (dep-hierarchy dep)]
      (info (format "resolving transitive dependencies for %s:" art-name))
      (pp/pprint trans-deps)
      (->> trans-deps
           keys
           (remove #(= (first %) (symbol "org.clojure/clojure")))
           (map (partial unzip&update-artifact! srcdeps (fs/file src-path (str/join "/" ["deps" art-name-cleaned art-version])) trans-deps))
           doall))))

(defn source-deps
  "Dependencies as source used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories dependencies source-paths root target-path] :as project} & args]
  (fs/copy-dir (first source-paths) (str target-path "/srcdeps"))
  (let [source-dependencies (filter source-dep? dependencies)
        srcdeps-relative (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        dep-hierarchy (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
                           (aether/dependency-hierarchy source-dependencies))]
    (doall (map (partial unzip&update-artifact! srcdeps-relative (fs/file target-path "srcdeps") dep-hierarchy) (keys dep-hierarchy)))))
