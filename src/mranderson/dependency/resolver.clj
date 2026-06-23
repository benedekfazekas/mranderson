(ns mranderson.dependency.resolver
  "Dependency resolution via pomegranate/aether.

  `resolve-source-deps` produces the resolved tree (Maven conflict resolution has
  run, each coordinate pinned to one version); `expand-dep-hierarchy` re-expands
  it into the unresolved tree where the same library may appear in many branches
  at different versions. These two trees feed the two inlining modes."
  (:require [cemerick.pomegranate.aether :as aether]
            [mranderson.dependency.tree :as t]))

(defn resolve-source-deps
  "Resolves `source-dependencies` against `repositories` and returns the resolved
  dependency hierarchy (a nested `[name version] -> subtree` map). Maven conflict
  resolution has already run, so each coordinate is pinned to a single version
  even where it appears in multiple branches."
  [repositories source-dependencies]
  (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
       (aether/dependency-hierarchy source-dependencies)))

(defn expand-dep-hierarchy
  "Re-expands `dep-hierarchy` into a full unresolved dependency tree.

  Seeds from the top-level coordinates and recursively re-resolves each node's
  own dependencies, so the same library may appear at many paths with different
  versions. `overrides` can force a particular version at a given path while the
  tree is built (see `walk&expand-deps`)."
  [repositories dep-hierarchy overrides]
  (t/walk&expand-deps
   (zipmap (keys dep-hierarchy) (repeat nil))
   (partial resolve-source-deps repositories)
   overrides))
