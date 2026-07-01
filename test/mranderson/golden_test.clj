(ns mranderson.golden-test
  "Golden-master checks on the *shape* of generated output (#79): for a pinned
  dependency inlined under a fixed prefix, the set of produced source files and
  the namespace each was rewritten to. Pinned deps + a fixed prefix make it
  deterministic, so any change in what MrAnderson emits - for instance from a
  future resolver swap (#41) - surfaces here as a diff.

  It intentionally golden-masters the output *structure* (paths + namespaces),
  not full file contents: the structure is what a resolution/prefixing change
  perturbs, and it sidesteps the intra-file nondeterminism (e.g. metadata map
  ordering) that makes full-source diffing brittle.

  There is a fixture for each inlining mode - resolved-tree (the flat default)
  and unresolved-tree (the deeply nested layout, #79's part D) - so both code
  paths are covered.

  If a change here is intentional, regenerate the goldens with
  `(regenerate-goldens!)` below and commit the updated EDN."
  (:require [mranderson.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [me.raynes.fs :as fs]
            [clojure.test :refer [deftest testing is]])
  (:import [java.io File]))

(def ^:private golden-prefix "golden.inlined")

(def ^:private prefix-path
  ;; the prefix as a relative path, e.g. "golden/inlined"
  (str/replace golden-prefix "." "/"))

(def ^:private fixtures
  "Each fixture inlines a pinned dependency under `golden-prefix` and is checked
  against `test-resources/golden/<name>.edn`."
  [{:name "puget-1.0.2"            :dep '[mvxcvi/puget "1.0.2"] :opts {}}
   {:name "puget-1.0.2-unresolved" :dep '[mvxcvi/puget "1.0.2"] :opts {:unresolved-tree true}}])

(defn- golden-file [fixture]
  (str "test-resources/golden/" (:name fixture) ".edn"))

(defn- generated-structure
  "Walks the shadowed output under `srcdeps` (the `golden-prefix` subtree) and
  returns a sorted vector of `{:path <srcdeps-relative, /-separated> :ns
  <declared ns symbol>}` for every Clojure source file."
  [^File srcdeps]
  (->> (file-seq srcdeps)
       (filter (fn [^File f] (re-find #"\.cljc?$|\.cljs$" (.getName f))))
       (map (fn [^File f]
              {:path (str/replace-first (str f) (str srcdeps "/") "")
               :ns   (second (read-file-ns-decl f))}))
       (filter #(str/starts-with? (:path %) (str prefix-path "/")))
       (sort-by :path)
       vec))

(defn- inline-structure
  "Inlines `fixture`'s dependency under the fixed prefix into a fresh temp dir and
  returns its generated structure, cleaning up afterwards."
  [{:keys [dep opts]}]
  (let [tmp (doto (File/createTempFile "mranderson-golden" "") (.delete) (.mkdirs))]
    (try
      (core/inline-deps (merge {:dependencies                [dep]
                                :project-prefix              golden-prefix
                                :skip-repackage-java-classes true
                                :target-path                 (str tmp)}
                               opts))
      (generated-structure (io/file tmp "srcdeps"))
      (finally (fs/delete-dir tmp)))))

(defn regenerate-goldens!
  "Rewrites every committed golden EDN from a fresh inlining run. Call from a REPL
  after an intentional change to MrAnderson's output, then commit the result."
  []
  (doseq [fixture fixtures]
    ;; compute the structure first (inlining logs to stdout), then capture only
    ;; the pretty-printed EDN
    (let [structure (inline-structure fixture)]
      (spit (golden-file fixture) (with-out-str (pp/pprint structure))))))

(deftest golden-output-test
  (doseq [{:keys [name] :as fixture} fixtures]
    (testing name
      (is (= (edn/read-string (slurp (golden-file fixture)))
             (inline-structure fixture))
          "generated output shape changed; if intentional, run (regenerate-goldens!) and commit the updated golden"))))
