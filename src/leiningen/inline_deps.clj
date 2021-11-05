(ns leiningen.inline-deps
  (:require [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [mranderson.core :as c]
            [mranderson.util :as u])
  (:import [java.util UUID]))

(defn- lookup-opt
  ([opt-key cli-opts project-opts]
   (lookup-opt opt-key cli-opts project-opts nil))

  ([opt-key cli-opts project-opts not-found]
   (if-let [cli-subseq (seq (drop-while #(not= % opt-key) cli-opts))]
     (second cli-subseq)
     (get project-opts opt-key not-found))))

(defn- generate-default-project-prefix []
  (str "mranderson" (->  (UUID/randomUUID)
                         str
                         (.substring 0 8))))

(defn- lein-project->ctx
  [{:keys [root target-path name version mranderson]} args]
  (let [cli-opts                    (map edn/read-string args)
        project-prefix              (some-> (lookup-opt :project-prefix cli-opts mranderson
                                                        (generate-default-project-prefix))
                                            clojure.core/name)
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage cli-opts mranderson)
        prefix-exclusions           (lookup-opt :prefix-exclusions cli-opts mranderson)
        srcdeps-relative            (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        project-source-dirs         (filter fs/directory? (.listFiles (fs/file (str target-path "/srcdeps/"))))]
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
     :unresolved-tree             (lookup-opt :unresolved-tree cli-opts mranderson)
     :overrides                   (lookup-opt :overrides cli-opts mranderson)
     :expositions                 (lookup-opt :expositions cli-opts mranderson)
     :watermark                   (lookup-opt :watermark cli-opts mranderson :mranderson/inlined)}))

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
  [{:keys [repositories dependencies target-path] :as project} & args]
  (c/copy-source-files (u/determine-source-dirs project) target-path)
  (let [{:keys [pprefix] :as ctx} (lein-project->ctx project args)
        paths                     (initial-paths target-path pprefix)]
    (c/mranderson repositories dependencies ctx paths)))
