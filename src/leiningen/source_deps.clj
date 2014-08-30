(ns leiningen.source-deps
  (:require [cemerick.pomegranate.aether :as aether]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace.move :refer [move-ns]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]])
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

(defn- download-source! [target-path dep]
  ;;(fs/mkdirs target-path)
  (let [art-name (-> dep first name (str/split #"/") last)
        art-version (-> dep second (str/replace "." "v"))]
    (println "target-path: " target-path " dep: " dep)
    (println "art-name: " art-name " art-version: " art-version)
    (doseq [clj-file (unzip (-> dep meta :file) target-path)]
      (let [old-ns (->> clj-file (fs/file target-path) read-file-ns-decl second)]
        (move-ns old-ns  (symbol (str "deps." (str/replace art-name #"[\.-_]" "") "." art-version "." old-ns)) target-path ["src"])))))

(defn source-deps
  "Dependencies as source used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories source-dependencies target-path] :as project} & args]
  (let [dependencies (map #(concat % [:classifier "sources"]) source-dependencies);; is sources needed?
        dep-hierarchy (->> (aether/resolve-dependencies :coordinates dependencies :repositories repositories)
                           (aether/dependency-hierarchy dependencies))]
    (clojure.pprint/pprint dep-hierarchy)
    ;;(clojure.walk/postwalk (fn [node] (println "node meta: " (meta node)) node) dep-hierarchy)
    (doall (map (partial download-source! (fs/file "src")) (keys dep-hierarchy)))))
