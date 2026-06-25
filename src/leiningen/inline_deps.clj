(ns leiningen.inline-deps
  "The `lein inline-deps` task: reads MrAnderson options off the CLI and the
  project map and calls into `mranderson.core`. Just a Leiningen wrapper; the
  engine runs fine without it via `mranderson.core/inline-deps`."
  (:require [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [mranderson.core :as c]
            [mranderson.util :as u]
            [mranderson.log :as log])
  (:import [java.util UUID]))

(defn- lookup-opt
  "Resolves option `opt-key` with precedence: CLI args > project `:mranderson`
  map > `not-found`. `cli-opts` is the parsed CLI arg sequence (`:key val ...`);
  the value is the element following `opt-key`. `project-opts` is the project
  map's `:mranderson` section."
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

(defn- aot-configured? [aot]
  (or (= :all aot)
      (and (coll? aot) (seq aot))))

(defn- warn-on-aot
  "AOT compilation produces `.class` files for the project and its deps. When
  inlining those, MrAnderson can end up repeating the prefix on already-compiled
  namespaces (see #89), so warn the user that disabling `:aot` avoids the trouble."
  [{:keys [aot]}]
  (when (aot-configured? aot)
    (log/warn "WARNING: `:aot` is set in this project. Inlining AOT-compiled namespaces is known to produce broken prefixes (see https://github.com/benedekfazekas/mranderson/issues/89). Consider disabling `:aot` while inlining dependencies.")))

(defn- lein-project->ctx
  "Builds the engine `ctx` map from a Leiningen `project` and the raw CLI `args`.
  Parses `args` as EDN, resolves each option via `lookup-opt` (CLI > project
  `:mranderson` > default), and maps Leiningen-facing names onto the engine's:
  e.g. `:project-prefix` -> `:pprefix` (defaulting to a random prefix) and
  `:skip-javaclass-repackage` -> `:skip-repackage-java-classes`. Also derives the
  `target-path`-relative `srcdeps` directory. See `mranderson.core/mranderson`
  for the `ctx` keys."
  [{:keys [root target-path name version mranderson]} args]
  (let [cli-opts                    (map edn/read-string args)
        project-prefix              (some-> (lookup-opt :project-prefix cli-opts mranderson
                                                        (generate-default-project-prefix))
                                            clojure.core/name)
        skip-repackage-java-classes (lookup-opt :skip-javaclass-repackage cli-opts mranderson)
        prefix-exclusions           (lookup-opt :prefix-exclusions cli-opts mranderson)
        srcdeps-relative            (str (apply str (drop (inc (count root)) target-path)) "/srcdeps")
        project-source-dirs         (filter fs/directory? (or (.listFiles (fs/file (str target-path "/srcdeps/"))) []))]
    (log/debug "skip repackage" skip-repackage-java-classes)
    (log/debug "project mranderson" (prn-str mranderson))
    (log/info "project prefix: " project-prefix)
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
     :watermark                   (lookup-opt :watermark cli-opts mranderson :mranderson/inlined)
     :print-deps-tree             (lookup-opt :print-deps-tree cli-opts mranderson)}))

(defn- initial-paths [target-path pprefix]
  {:src-path        (fs/file target-path "srcdeps" (u/sym->file-name pprefix))
   :parent-clj-dirs []
   :branch          []})

(defn inline-deps
  "Inline and shadow dependencies so they can not interfere with other libraries' dependencies.

  Options may be given on the command line (`:key val`) or under `:mranderson`
  in the project map; the command line wins.

  Available options:

  :project-prefix           string    Project prefix to use when shadowing (default: mranderson{rnd})
  :skip-javaclass-repackage boolean   If true Jar Jar Links won't be used to repackage java classes
  :prefix-exclusions        list      List of prefixes that should not be processed in imports
  :unresolved-tree          boolean   Enforces unresolved tree mode
  :overrides                map       Dependency overrides, unresolved-tree mode only
  :expositions              list      Transitive deps exposed to the project's own sources, unresolved-tree mode only
  :watermark                keyword   Metadata key added to inlined namespaces (default: :mranderson/inlined; nil to disable)
  :print-deps-tree          boolean   Print the dependency tree that would be inlined, then exit without inlining"
  [{:keys [repositories dependencies target-path] :as project} & args]
  (let [{:keys [pprefix print-deps-tree] :as ctx} (lein-project->ctx project args)]
    (if print-deps-tree
      (c/print-deps-tree repositories dependencies ctx)
      (do
        (warn-on-aot project)
        (c/copy-source-files (u/determine-source-dirs project) target-path)
        (c/mranderson repositories dependencies ctx (initial-paths target-path pprefix))))))
