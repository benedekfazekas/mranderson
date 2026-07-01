(ns mranderson.golden-test
  "Golden-master check on the *shape* of generated output (#79): for a pinned
  dependency inlined under a fixed prefix, the set of produced source files and
  the namespace each was rewritten to. Pinned deps + a fixed prefix make it
  deterministic, so any change in what MrAnderson emits - for instance from a
  future resolver swap (#41) - surfaces here as a diff.

  It intentionally golden-masters the output *structure* (paths + namespaces),
  not full file contents: the structure is what a resolution/prefixing change
  perturbs, and it sidesteps the intra-file nondeterminism (e.g. metadata map
  ordering) that makes full-source diffing brittle.

  If a change here is intentional, regenerate the golden with
  `regenerate-golden!` below and commit the updated EDN."
  (:require [mranderson.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [me.raynes.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]))

(def ^:private golden-prefix "golden.inlined")
(def ^:private golden-dep '[mvxcvi/puget "1.0.2"])
(def ^:private golden-file "test-resources/golden/puget-1.0.2.edn")

(def ^:private prefix-path
  ;; the prefix as a relative path, e.g. "golden/inlined"
  (str/replace golden-prefix "." "/"))

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
  "Inlines `golden-dep` under a fixed prefix into a fresh temp dir and returns
  its generated structure, cleaning up afterwards."
  []
  (let [tmp (doto (File/createTempFile "mranderson-golden" "") (.delete) (.mkdirs))]
    (try
      (core/inline-deps {:dependencies                [golden-dep]
                         :project-prefix              golden-prefix
                         :skip-repackage-java-classes true
                         :target-path                 (str tmp)})
      (generated-structure (io/file tmp "srcdeps"))
      (finally (fs/delete-dir tmp)))))

(defn regenerate-golden!
  "Rewrites the committed golden EDN from a fresh inlining run. Call from a REPL
  after an intentional change to MrAnderson's output, then commit the result."
  []
  ;; compute the structure first (inlining logs to stdout), then capture only the
  ;; pretty-printed EDN
  (let [structure (inline-structure)]
    (spit golden-file (with-out-str (pp/pprint structure)))))

(deftest puget-golden-test
  (is (= (edn/read-string (slurp golden-file))
         (inline-structure))
      "generated output shape changed; if intentional, run (regenerate-golden!) and commit the updated golden"))
