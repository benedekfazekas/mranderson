(ns ^:no-doc mranderson.zloc
  "Thin wrappers over rewrite-clj navigation that also skip reader-discard
  (`#_`, aka uneval) nodes. Rewrite-clj skips whitespace and comments on its
  own, but not discarded forms, so without these wrappers `mranderson.move`
  would have to guard against `:uneval` nodes at every step (and could still
  rewrite symbols buried inside a discarded form). Centralizing it here keeps
  that concern in one place."
  (:require [rewrite-clj.zip :as z]))

(defn uneval?
  "True if `zloc` is positioned on a reader-discard (`#_`) node."
  [zloc]
  (= :uneval (z/tag zloc)))

(defn- uninteresting?
  "Whitespace, comment or reader-discard - nodes navigation should step over."
  [zloc]
  (or (z/whitespace-or-comment? zloc)
      (uneval? zloc)))

(defn skip-uninteresting
  "Skip rightward over whitespace, comments and reader-discard nodes, starting
  from `zloc`."
  [zloc]
  (z/skip z/right* uninteresting? zloc))

(defn down
  "Like `z/down`, but also steps over reader-discard nodes."
  [zloc]
  (some-> zloc z/down* skip-uninteresting))

(defn right
  "Like `z/right`, but also steps over reader-discard nodes."
  [zloc]
  (some-> zloc z/right* skip-uninteresting))

(defn in-uneval?
  "True if `zloc` is a reader-discard node or sits anywhere inside one. Unlike a
  bare `uneval?` tag check, this also catches nodes nested within a discarded
  form."
  [zloc]
  (loop [loc zloc]
    (cond
      (nil? loc)   false
      (uneval? loc) true
      :else        (recur (z/up* loc)))))

(defn find-next-depth-first
  "Like `z/find-next-depth-first`, but never matches a node that lies inside a
  reader-discard (`#_`) form, so discarded code is left untouched."
  [zloc pred]
  (z/find-next-depth-first zloc #(and (not (in-uneval? %)) (pred %))))
