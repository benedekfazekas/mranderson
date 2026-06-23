(ns mranderson.plugin
  "Leiningen middleware for MrAnderson. Active only under the
  `plugin.mranderson/config` profile (see `mranderson.profiles`): drops the
  source-deps from `:dependencies` and pulls any `:gen-class` namespaces in the
  inlined sources into `:aot` so they get compiled. Leiningen-only; not used by
  the `mranderson.core` entry point."
  (:require [mranderson.util :refer [first-src-path clojure-source-files source-dep?]]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]))

(defn- find-gen-class-ns
  "Reducer over source files: conj the namespace symbol of `file` onto `found`
  when its `ns` form contains a `:gen-class` clause, else return `found`
  unchanged. Matches the `:gen-class` keyword in the parsed form, not the text,
  so a docstring or comment that merely mentions it doesn't count."
  [found file]
  (let [ns-decl (read-file-ns-decl file)]
    (if (and ns-decl (some #{:gen-class} (tree-seq coll? seq ns-decl)))
      (conj found (second ns-decl))
      found)))

(defn middleware
  "Leiningen middleware, active only when `:srcdeps-project-hacks` is set (by the
  `plugin.mranderson/config` profile). Removes the inlined source-deps from
  `:dependencies` and adds any `:gen-class` namespaces found in the inlined
  sources to `:aot` so they're compiled. Returns `project` unchanged otherwise."
  [{:keys [source-paths root aot dependencies srcdeps-project-hacks] :as project}]
  (if srcdeps-project-hacks
    (let [src (first-src-path root source-paths)
          new-aot (reduce find-gen-class-ns aot (clojure-source-files [src]))]
      (-> project
          (assoc :dependencies (remove source-dep? dependencies))
          (assoc :aot new-aot)))
    project))
