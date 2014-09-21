(ns lein-source-deps.plugin
  (:require [lein-source-deps.util :refer [first-src-path clojure-source-files]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]))

(defn- find-gen-class-ns [found file]
  (let [ns-decl (read-file-ns-decl file)]
    (if (.contains (apply str ns-decl) ":gen-class")
      (conj found (-> file read-file-ns-decl second))
      found)))

(defn middleware
  "Handles :gen-class instances in deps which need AOT"
  [{:keys [source-paths root aot deps-aot] :as project}]
  (if deps-aot
    (let [src (first-src-path root source-paths)
          new-aot (reduce find-gen-class-ns aot (clojure-source-files [src]))]
      (assoc project :aot new-aot))
    project))
