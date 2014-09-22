(ns mranderson.util
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn clojure-source-files [dirs]
  (->> dirs
       (map io/file)
       (filter #(.exists ^File %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (.endsWith (.getName file) ".clj"))))
       (map #(.getCanonicalFile ^File %))))

(defn first-src-path [root source-paths]
  (apply str (drop (inc (count root)) (first source-paths))))

(defn source-dep? [dependency]
  (:source-dep (meta dependency)))
