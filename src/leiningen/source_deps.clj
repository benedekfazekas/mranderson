(ns leiningen.source-deps
  (:require [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [mranderson.core :as c]
            [mranderson.util :as u]))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn- lein-project->ctx
  [{:keys [root target-path name version mranderson]} args]
  (let [opts                        (map #(edn/read-string %) args)
        project-prefix              (lookup-opt :project-prefix opts)
        pprefix                     (or (and project-prefix (clojure.core/name project-prefix))
                                        (u/clean-name-version "mranderson" (u/mranderson-version)))
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage opts)
        prefix-exclusions           (lookup-opt :prefix-exclusions opts)
        srcdeps-relative            (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        project-source-dirs         (filter fs/directory? (.listFiles (fs/file (str target-path "/srcdeps/"))))
        unresolved-deps-hierarchy   (lookup-opt :unresolved-dependency-hierarchy opts)]
    (u/debug "skip repackage" skip-repackage-java-classes)
    (u/debug "project mranderson" (prn-str mranderson))
    (u/info "project prefix: " pprefix)
    {:pname                       name
     :pversion                    version
     :pprefix                     pprefix
     :skip-repackage-java-classes skip-repackage-java-classes
     :srcdeps                     srcdeps-relative
     :prefix-exclusions           prefix-exclusions
     :project-source-dirs         project-source-dirs
     :unresolved-deps-hierarchy   unresolved-deps-hierarchy
     :overrides                   (:overrides mranderson)
     :expositions                 (:expositions mranderson)}))

(defn- initial-paths [target-path pprefix]
  {:src-path        (fs/file target-path "srcdeps" (u/sym->file-name pprefix))
   :parent-clj-dirs []
   :branch          []})

(defn source-deps
  "Dependencies as source: used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [{:keys [repositories dependencies source-paths target-path] :as project} & args]
  (c/copy-source-files source-paths target-path)
  (let [{:keys [pprefix] :as ctx} (lein-project->ctx project args)
        paths                     (initial-paths target-path pprefix)]
    (c/mranderson repositories dependencies ctx paths)))
