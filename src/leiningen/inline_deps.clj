(ns leiningen.inline-deps
  (:require [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [mranderson.core :as c]
            [mranderson.util :as u])
  (:import [java.util UUID]))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn- generate-default-project-prefix []
  (str "mranderson" (->  (UUID/randomUUID)
                         str
                         (.substring 0 8))))

(defn- lein-project->ctx
  [{:keys [root target-path name version mranderson]} args]
  (let [opts                        (map #(edn/read-string %) args)
        project-prefix-opt          (lookup-opt :project-prefix opts)
        project-prefix              (or (and project-prefix-opt (clojure.core/name project-prefix-opt))
                                        (and (nil? project-prefix-opt) (:project-prefix mranderson))
                                        (generate-default-project-prefix))
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage opts)
        prefix-exclusions           (lookup-opt :prefix-exclusions opts)
        srcdeps-relative            (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        project-source-dirs         (filter fs/directory? (.listFiles (fs/file (str target-path "/srcdeps/"))))
        unresolved-tree-opt         (lookup-opt :unresolved-tree opts)
        unresolved-tree             (or unresolved-tree-opt (and (nil? unresolved-tree-opt) (:unresolved-tree mranderson)))]
    (u/debug "skip repackage" skip-repackage-java-classes)
    (u/debug "project mranderson" (prn-str mranderson))
    (u/info "project prefix: " project-prefix)
    {:pname                       name
     :pversion                    version
     :pprefix                     project-prefix
     :skip-repackage-java-classes skip-repackage-java-classes
     :srcdeps                     srcdeps-relative
     :prefix-exclusions           prefix-exclusions
     :project-source-dirs         project-source-dirs
     :unresolved-tree             unresolved-tree
     :overrides                   (:overrides mranderson)
     :expositions                 (:expositions mranderson)
     :watermark                   (:watermark mranderson :mranderson/inlined)}))

(defn- initial-paths [target-path pprefix]
  {:src-path        (fs/file target-path "srcdeps" (u/sym->file-name pprefix))
   :parent-clj-dirs []
   :branch          []})

(defn inline-deps
  "Inline and shadow dependencies so they can not interfere with other libraries' dependencies.

  Available options:

  :project-prefix           string    Project prefix to use when shadowing
  :skip-javaclass-repackage boolean   If true Jar Jar Links won't be used to repackage java classes
  :prefix-exclusions        list      List of prefixes that should not be processed in imports
  :unresolved-tree          boolean   Enforces unresolved tree mode"
  [{:keys [repositories dependencies source-paths target-path] :as project} & args]
  (c/copy-source-files source-paths target-path)
  (let [{:keys [pprefix] :as ctx} (lein-project->ctx project args)
        paths                     (initial-paths target-path pprefix)]
    (c/mranderson repositories dependencies ctx paths)))
