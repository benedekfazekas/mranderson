(ns mranderson.tree
  (:require [clojure.tools.namespace.dependency :as dep]
            [mranderson.util :as u]))

;; inlined from leiningen source
(defn walk-deps
  ([deps f level]
     (doseq [[dep subdeps] deps]
       (f dep level)
       (when subdeps
         (walk-deps subdeps f (inc level)))))
  ([deps f]
   (walk-deps deps f 0)))

(defn- path-pred [path dep override-k]
  (= override-k (conj path (first dep))))

(defn walk&expand-deps
  "Walks a dep hierarchy and expands it with all dependencies as walking.

  This essentially means that it creates an unresolved dependency where all nodes hold their originally
  defined dependencies as children recursively.

  Understands overrides in terms of versions so a node can be overridden at any point of the tree while it is being built.
  This means that if the old version of the same dependency has different dependencies in its turn than the new version
  being enforced by overriding the dependencies of the new version will be present in the subtree.

  Overrides is a map where keys are paths as vectors to a dependency in an unresolved tree and values are dependencies
  as a vector. for example
  ```
  {[mvxcvi/puget fipp] [fipp 0.6.14]}
  ```
  "
  ([deps resolve-dep-fn overrides path]
   (->> (map
         (fn [[dep _]]
           (let [[override-k override-v] (first (filter (comp (partial path-pred path dep) key) overrides))
                 [dep subdeps] (first (resolve-dep-fn [(or override-v dep)]))]
             [dep
              (when (seq subdeps)
                (walk&expand-deps subdeps resolve-dep-fn overrides (conj path (first dep))))]))
         deps)
        (into {})))
  ([deps f overrides]
     (walk&expand-deps deps f overrides [])))

(defn walk-dep-tree
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

(defn walk-ordered-deps
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

(defn topological-order [dep-tree]
  (zipmap (dep/topo-sort (create-dep-graph dep-tree)) (range)))
