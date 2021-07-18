(ns mranderson.plugin
  (:require [mranderson.util :refer [first-src-path clojure-source-files source-dep?]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]))

(defn- find-gen-class-ns [found file]
  (let [ns-decl (read-file-ns-decl file)]
    (if (.contains ^String (apply str ns-decl) ":gen-class")
      (conj found (-> file read-file-ns-decl second))
      found)))

(defn middleware
  "Handles :gen-class instances in deps which need AOT"
  [{:keys [source-paths root aot dependencies srcdeps-project-hacks] :as project}]
  (if srcdeps-project-hacks
    (let [src (first-src-path root source-paths)
          new-aot (reduce find-gen-class-ns aot (clojure-source-files [src]))]
      (-> project
          (assoc :dependencies (remove source-dep? dependencies))
          (assoc :aot new-aot)))
    project))
