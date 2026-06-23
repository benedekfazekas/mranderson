(ns mranderson.dependency.tree
  "Walking and ordering of dependency trees.

  A dependency tree here is a nested map of `[name version] -> subtree`. This
  namespace provides depth-first walks for both modes (`walk-dep-tree` for
  unresolved, `walk-ordered-deps` for resolved), tree expansion
  (`walk&expand-deps`), subtree eviction, and topological ordering."
  (:require [clojure.tools.namespace.dependency :as dep]))

;; inlined from leiningen source
(defn walk-deps
  "Depth-first walk of a dependency tree, calling `(f dep level)` for each node
  (root level defaults to 0). Side-effecting; ignores `f`'s return value."
  ([deps f level]
     (doseq [[dep subdeps] deps]
       (f dep level)
       (when subdeps
         (walk-deps subdeps f (inc level)))))
  ([deps f]
   (walk-deps deps f 0)))

(defn path-pred
  "True when appending `dep`'s name to `path` equals `k`, i.e. `dep` sits at path
  `k` in the tree. Used to match overrides/expositions to a node by its full
  path."
  [path dep k]
  (= k (conj path (first dep))))

(defn walk&expand-deps
  "Walks a dependency tree and expands it with all dependencies as walking.

  This essentially means that an unresolved dependency tree is created where all nodes hold their originally
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
           (let [[_override-k override-v] (first (filter (comp (partial path-pred path dep) key) overrides))
                 [dep subdeps] (first (resolve-dep-fn [(or override-v dep)]))]
             [dep
              (when (seq subdeps)
                (walk&expand-deps subdeps resolve-dep-fn overrides (conj path (first dep))))]))
         deps)
        (into {})))
  ([deps resolve-dep-fn overrides]
     (walk&expand-deps deps resolve-dep-fn overrides [])))

(defn evict-subtrees
  "Evict subtrees from a dependency tree.

  `subtree-roots` are defined as a set of dependency names (for example `#{'org.clojure/clojure 'org.clojure/clojurescript}`
  without their versions. Tested on a resolved tree. Assumed that it would evict all subtrees from an unresolved dependency
  tree."
  [deps subtree-roots]
  (->> (map
        (fn [[dep subdeps]]
          (let [subdeps (remove (comp subtree-roots ffirst) subdeps)]
            [dep (when (seq subdeps) (evict-subtrees subdeps subtree-roots))]))
        deps)
       (into {})))

(defn walk-dep-tree
  "Depth-first walk of a dependency tree (used by unresolved-tree mode),
  side-effecting.

  For each node calls `(pre-fn ctx paths dep)`, which must return
  `[pre-result new-paths]`; `new-paths` is threaded down into the subtree. After
  the subtree is processed, calls `(post-fn ctx pre-result paths)` with this
  node's original `paths`. Return values are discarded."
  [deps pre-fn post-fn paths ctx]
  (doseq [[dep subdeps] deps]
    (let [[pre-result new-paths] (pre-fn ctx paths dep)]
      (when subdeps
        (walk-dep-tree subdeps pre-fn post-fn new-paths ctx))
      (post-fn ctx pre-result paths))))

(defn walk-ordered-deps
  "Walks a flat, topologically ordered map of dependencies (used by
  resolved-tree mode).

  First calls `(pre-fn ctx paths dep)` for every dep, collecting their results.
  Then calls `post-fn` for each dep in reverse order, threading into `paths` the
  `:parent-clj-dirs` accumulated across all the pre-results, and returns a vector
  of the `post-fn` results in that (reversed) order. Unlike `walk-dep-tree`, this
  returns its `post-fn` results rather than discarding them - resolved mode
  applies the collected renames in one global pass."
  [deps pre-fn post-fn paths ctx]
  (->> (keys deps)
       (map (partial pre-fn ctx paths))
       ((juxt (partial reduce (fn [clj-dirs [_ {:keys [parent-clj-dirs]}]] (into clj-dirs parent-clj-dirs)) []) reverse))
       ((fn [[clj-dirs deps]]
          (mapv (fn [[pre-result _]] (post-fn ctx pre-result (update paths :parent-clj-dirs concat clj-dirs))) deps)))))

(defn- create-dep-graph
  "Builds a `clojure.tools.namespace.dependency` graph from a nested dep tree,
  declaring each child a dependency of its parent. Top-level nodes are made to
  depend on nil so isolated roots still appear in the topological sort."
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

(defn topological-order
  "Returns a map of each dependency in `dep-tree` to its position (an integer) in
  topological order. Used to order the resolved tree before walking it."
  [dep-tree]
  (zipmap (dep/topo-sort (create-dep-graph dep-tree)) (range)))
