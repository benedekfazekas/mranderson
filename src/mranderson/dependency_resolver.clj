(ns mranderson.dependency-resolver
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.walk :as walk]
            [mranderson.util :as u]))

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

(defn expand-dep-hierarchy
  [repositories dep-hierarchy]
  (walk/prewalk
   (partial expand-dep repositories)
   (zipmap (keys dep-hierarchy) (repeat nil))))

(defn resolve-source-deps
  [source-dependencies repositories]
  (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
       (aether/dependency-hierarchy source-dependencies)))
