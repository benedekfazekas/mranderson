(ns mranderson.main
  "Command-line interface to MrAnderson. A thin wrapper over
  `mranderson.core/inline-deps` so MrAnderson can be run directly, without
  Leiningen - and, down the road, as a GraalVM native image (see #36).

  Run it with the dependency coordinates to inline as positional arguments:

      clojure -M -m mranderson.main -p com.example.inlined -s src \\
        org.clojure/tools.namespace:1.5.1 rewrite-clj:1.2.54

  No `:gen-class` here on purpose: it would make MrAnderson's own plugin AOT this
  namespace during the self-inlining build. The native-image build (#36) will AOT
  it in its own build context instead."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [mranderson.core :as core]))

(def ^:private cli-options
  [["-p" "--project-prefix PREFIX" "Namespace/path prefix for the shadowed deps (default: random)"]
   ["-s" "--source-path PATH" "Project source dir to copy into srcdeps and rewrite (repeatable; omit to just shadow the deps)"
    :multi true :default [] :default-desc "" :update-fn conj]
   ["-t" "--target-path PATH" "Build target directory (default: target)"]
   [nil "--unresolved-tree" "Use unresolved-tree mode (deeply nested isolation)"]
   [nil "--skip-java-repackage" "Skip jarjar repackaging of bundled Java classes"]
   [nil "--prefix-exclusions EDN" "EDN vector of import prefixes to leave untouched"
    :parse-fn edn/read-string]
   [nil "--watermark KW" "Metadata key stamped on inlined namespaces, or 'nil' to disable (default: :mranderson/inlined)"
    :parse-fn (fn [s] (when-not (= "nil" s) (keyword (str/replace s #"^:" ""))))]
   [nil "--print-deps-tree" "Print the dependency tree that would be inlined, then exit"]
   [nil "--report" "After inlining, print a per-file report of what was rewritten"]
   ["-h" "--help" "Print this help and exit"]])

(defn- usage [summary]
  (str/join
   \newline
   ["Usage: mranderson [options] coordinate..."
    ""
    "Inline and shadow the given dependency coordinates so they cannot interfere"
    "with the dependencies of downstream consumers."
    ""
    "Coordinates are Maven-style: group/artifact:version (or artifact:version),"
    "e.g. org.clojure/tools.namespace:1.5.1"
    ""
    "Options:"
    summary]))

(defn parse-coordinate
  "Parses a `group/artifact:version` (or `artifact:version`) coordinate string
  into a `[lib version]` vector. Throws on a string with no version segment."
  [coord]
  (let [idx (str/last-index-of coord ":")]
    (when-not idx
      (throw (ex-info (str "invalid coordinate (expected lib:version): " coord) {:coordinate coord})))
    [(symbol (subs coord 0 idx)) (subs coord (inc idx))]))

(defn options->opts
  "Maps the parsed CLI `options` map and positional dependency `coords` to the
  options map `mranderson.core/inline-deps` expects."
  [options coords]
  (cond-> {:dependencies (mapv parse-coordinate coords)}
    (:project-prefix options)        (assoc :project-prefix (:project-prefix options))
    (seq (:source-path options))     (assoc :source-paths (:source-path options))
    (:target-path options)           (assoc :target-path (:target-path options))
    (:unresolved-tree options)       (assoc :unresolved-tree true)
    (:skip-java-repackage options)   (assoc :skip-repackage-java-classes true)
    (contains? options :prefix-exclusions) (assoc :prefix-exclusions (:prefix-exclusions options))
    (contains? options :watermark)   (assoc :watermark (:watermark options))
    (:print-deps-tree options)       (assoc :print-deps-tree true)
    (:report options)                (assoc :report true)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary)) (System/exit 0))

      errors
      (binding [*out* *err*]
        (doseq [e errors] (println e))
        (println (usage summary))
        (System/exit 1))

      (empty? arguments)
      (binding [*out* *err*]
        (println "error: no dependency coordinates given")
        (println (usage summary))
        (System/exit 1))

      :else
      (try
        (core/inline-deps (options->opts options arguments))
        (System/exit 0)
        (catch Exception e
          (binding [*out* *err*] (println "error:" (.getMessage e)))
          (System/exit 1))))))
