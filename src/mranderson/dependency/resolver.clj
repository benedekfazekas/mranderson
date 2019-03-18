(ns mranderson.dependency.resolver
  (:require [cemerick.pomegranate.aether :as aether]
            [mranderson.dependency.tree :as t]))

(defn resolve-source-deps
  "Retrieves the given dependencies using the given `repositories`"
  [repositories source-dependencies]
  (->> (aether/resolve-dependencies :coordinates source-dependencies :repositories repositories)
       (aether/dependency-hierarchy source-dependencies)))

(defn expand-dep-hierarchy
  "Expands the first level of the given dep hierarchy into an unresolved dependency tree.

  Understands overrides so a node can be overriden at any point of the tree while it is being built."
  [repositories dep-hierarchy overrides]
  (t/walk&expand-deps
   (zipmap (keys dep-hierarchy) (repeat nil))
   (partial resolve-source-deps repositories)
   overrides))
