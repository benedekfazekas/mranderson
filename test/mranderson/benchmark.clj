(ns mranderson.benchmark
  "Ad-hoc perf harness for the namespace-rewriting engine. Not part of the normal
  suite - run explicitly:

    lein test :only mranderson.benchmark/bench-rewriting

  It inlines a realistic dependency tree and reports how many times
  `replace-ns-symbol-in-source-files` runs (once per moved namespace, N) and the
  total number of source-file scans those calls perform (sum of dir-set sizes).
  If total-scans / files is ~N, the rewriting is doing O(files x namespaces) work."
  (:require [clojure.test :refer [deftest]]
            [mranderson.core :as core]
            [mranderson.move :as move]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs])
  (:import java.io.File))

(def ^:private clojure-source-files-all #'move/clojure-source-files-all)

(defn- temp-dir [p]
  (doto (File/createTempFile p "") (.delete) (.mkdirs)))

(defn- count-source-files [^File dir]
  (->> (file-seq dir)
       (filter #(and (.isFile ^File %)
                     (re-find #"\.cljc?$|\.cljs$" (.getName ^File %))))
       count))

(defn- inline! [deps src target]
  (let [opts {:dependencies                deps
              :source-paths                [(str src)]
              :target-path                 (str target)
              :project-prefix              "bench.inlined"
              :skip-repackage-java-classes true}
        t0   (System/nanoTime)]
    (core/inline-deps opts)
    (/ (- (System/nanoTime) t0) 1e6)))

(defn run-bench [label deps]
  (let [mk    (fn [] (let [s (temp-dir "bench-src")
                           m (io/file s "bench" "main.clj")]
                       (.mkdirs (.getParentFile m))
                       (spit m "(ns bench.main)")
                       s))
        ;; warm: resolve + populate ~/.m2 so the timed run isn't dominated by downloads
        warm-s (mk) warm-t (temp-dir "bench-target")
        _      (inline! deps warm-s warm-t)
        files  (count-source-files (io/file warm-t "srcdeps"))
        _      (do (fs/delete-dir warm-s) (fs/delete-dir warm-t))
        ;; clean timed run
        time-s (mk) time-t (temp-dir "bench-target")
        ms     (inline! deps time-s time-t)
        _      (do (fs/delete-dir time-s) (fs/delete-dir time-t))
        ;; instrumented run: count rewrite passes and total file-reads. With the
        ;; global single pass this should be 1 pass and ~1 read per file; a
        ;; regression to per-artifact passes would show up as many of both.
        calls   (atom 0) scans (atom 0) rw-ms (atom 0.0)
        inst-s (mk) inst-t (temp-dir "bench-target")
        orig    move/replace-ns-symbols-in-source-files]
    (with-redefs [move/replace-ns-symbols-in-source-files
                  (fn [renames dirs]
                    (swap! calls inc)
                    (swap! scans + (count (clojure-source-files-all dirs)))
                    (let [t (System/nanoTime)
                          r (orig renames dirs)]
                      (swap! rw-ms + (/ (- (System/nanoTime) t) 1e6))
                      r))]
      (inline! deps inst-s inst-t))
    (fs/delete-dir inst-s) (fs/delete-dir inst-t)
    (println (format (str "\n[BENCH %s]\n  inline time (total): %.0f ms\n  files (F): %d\n"
                          "  rewrite passes: %d   total file-reads: %d (~%.1f per file)\n"
                          "  time in rewriting: %.0f ms  (%.0f%% of total)\n")
                     label ms files @calls @scans
                     (double (/ @scans (max 1 files)))
                     @rw-ms (* 100.0 (/ @rw-ms ms))))))

(deftest ^:benchmark bench-rewriting
  ;; MrAnderson's own dependency tree - a realistic, reproducible large workload.
  (run-bench "mranderson-deps"
             '[[clj-commons/pomegranate "1.2.25"]
               [org.clojure/tools.namespace "1.5.1"]
               [clj-commons/fs "1.6.312"]
               [rewrite-clj "1.2.54"]]))
