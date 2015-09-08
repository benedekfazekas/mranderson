(ns mranderson.util
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.string :as str]
            [leiningen.core.main :refer [info]]
            [clojure.java.io :as io])
  (:import [java.io File]
           [com.tonicsystems.jarjar Rule]
           [mranderson.util JjPackageRemapper JjMainProcessor]
           [com.tonicsystems.jarjar.ext_util StandaloneJarProcessor]))

(defn clojure-source-files-relative
  ([dirs excl-dir]
     (let [excl-dirs (when excl-dir (map #(str % "/" excl-dir) dirs))]
       (->> dirs
            (map io/file)
            (filter #(.exists ^File %))
            (mapcat file-seq)
            (remove (fn [file]
                      (some #(.startsWith (str file) %) excl-dirs) ))
            (filter (fn [^File file]
                      (let [file-name (.getName file)]
                        (and (.isFile file)
                             (or
                              (.endsWith file-name ".cljc")
                              (.endsWith file-name ".clj")))))))))
  ([dirs]
     (clojure-source-files-relative dirs nil)))

(defn relevant-clj-dep-path [src-path prefix pprefix]
  (if (.endsWith src-path "target/srcdeps")
    [(str "target/srcdeps/" prefix)]
    (vector (str "target/srcdeps/"
                 pprefix
                 (-> src-path
                     (str/split #"target/srcdeps")
                     last)))))

(defn clojure-source-files [dirs]
  (->> dirs
       clojure-source-files-relative
       (map #(.getCanonicalFile ^File %))))

(defn class-files []
  (->> "target/srcdeps"
       io/file
       (#(.listFiles %))
       (filter #(.isDirectory %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (.endsWith (.getName file) ".class"))))))

(defn class-file->fully-qualified-name [file]
  (->> (-> file
           str
           (str/split #"\.")
           first
           (str/split #"/"))
       (drop 2)
       (str/join ".")))

(defn class-name->package-name [class-name]
  (->> (str/split class-name #"\.")
       butlast
       (str/join ".")))

(defn java-class-dirs
  "lists subdirs of target/srcdeps which contain .class files"
  []
  (reduce #(->> (str/split (str %2) #"/")
                (drop 2)
                (take 2)
                (str/join ".")
                ((partial conj %1))) #{} (class-files)))

(defn clean-name-version
  [pname pversion]
  (str (str/replace pname #"[\._-]" "")
       (str/replace pversion #"[\._-]" "")))

(defn first-src-path [root source-paths]
  (apply str (drop (inc (count root)) (first source-paths))))

(defn source-dep? [dependency]
  (:source-dep (meta dependency)))

(defn- create-rule [name-version java-dir]
  (let [rule (Rule.)]
    (. rule setPattern (str java-dir ".**"))
    (. rule setResult (str name-version "." java-dir ".@1"))
    rule))

(defn apply-jarjar! [pname pversion]
  (let [java-dirs (java-class-dirs)
        name-version (clean-name-version pname pversion)
        rules (map (partial create-rule name-version) java-dirs)
        processor (JjMainProcessor. rules false false)
        jar-file (io/file (str "target/class-deps.jar"))]
    (info (format "prefixing %s in target/class-deps.jar with %s" java-dirs name-version))
    (StandaloneJarProcessor/run jar-file jar-file processor)))

(defn remove-2parents [file]
  (->> (str/split (str file) #"/")
       (drop 2)
       (str/join "/")))

(defn mranderson-version []
  (let [v (-> (io/resource "mranderson/project.clj")
              slurp
              read-string
              (nth 2))]
    (assert (string? v)
            (str "Something went wrong, version is not a string: " v))
    v))
