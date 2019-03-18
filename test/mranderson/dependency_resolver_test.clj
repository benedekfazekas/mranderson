(ns mranderson.dependency-resolver-test
  (:require [mranderson.dependency-resolver :as sut]
            [mranderson.tree :as tree]
            [clojure.test :as t]))

(def repos
  [["central" {:url       "https://repo1.maven.org/maven2/"
               :snapshots false}]
   ["clojars" {:url "https://repo.clojars.org/"}]])

(def example-dep-single
  '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]] nil})

(def example-dep-multiple
  '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]] nil
    [fipp "0.6.10"] nil})

(def example-dep-transient-cljs
  '[[cljfmt "0.6.3"]])

(defn assert-file-meta [dep level]
  (t/is (-> dep meta :file) (format "%s does not have file meta info" dep)))

(t/deftest expands-single-dep-into-unresolved-tree
  (let [expanded-single (sut/expand-dep-hierarchy repos example-dep-single {})]
    (t/is (= '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]]
               {[fipp "0.6.10"]              {[org.clojure/clojure "1.8.0"]          nil
                                              [org.clojure/core.rrb-vector "0.0.11"] {[org.clojure/clojure "1.5.1"] nil}}
                [mvxcvi/arrangement "1.1.1"] nil}}
             expanded-single)
          "failed to expand mvxcvi/puget \"1.0.2\" into an unresolved dependency tree")
    (tree/walk-deps expanded-single assert-file-meta)))

(t/deftest expands-multiple-deps-into-unresolved-tree
  (t/is (= '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]]
             {[fipp "0.6.10"]              {[org.clojure/clojure "1.8.0"]          nil,
                                            [org.clojure/core.rrb-vector "0.0.11"] {[org.clojure/clojure "1.5.1"] nil}}
              [mvxcvi/arrangement "1.1.1"] nil}
             [fipp "0.6.10"] {[org.clojure/clojure "1.8.0"]          nil
                              [org.clojure/core.rrb-vector "0.0.11"] {[org.clojure/clojure "1.5.1"] nil}}}
           (sut/expand-dep-hierarchy repos example-dep-multiple {}))
        "failed to expand multiple deps into an unresolved dependency tree"))

(t/deftest expand-deps-with-overriding-transient-dep
  (let [expanded-w-override (sut/expand-dep-hierarchy repos example-dep-multiple '{[mvxcvi/puget fipp] [fipp "0.6.14"]})]
    (t/is (= '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]]
               {[fipp "0.6.14"]              {[org.clojure/clojure "1.8.0"]          nil,
                                              [org.clojure/core.rrb-vector "0.0.13"] {[org.clojure/clojure "1.5.1"] nil}}
                [mvxcvi/arrangement "1.1.1"] nil}
               [fipp "0.6.10"] {[org.clojure/clojure "1.8.0"]          nil
                                [org.clojure/core.rrb-vector "0.0.11"] {[org.clojure/clojure "1.5.1"] nil}}}
             expanded-w-override)
          "failed to expand multiple deps with override or override interfered with sibling dependency")
    (tree/walk-deps expanded-w-override assert-file-meta)))

(t/deftest expand-deps-with-overriding-transient-dep-deep
  (t/is (= '{[mvxcvi/puget "1.0.2" :exclusions [[org.clojure/clojure]]]
             {[fipp "0.6.10"]              {[org.clojure/clojure "1.8.0"]          nil,
                                            [org.clojure/core.rrb-vector "0.0.13"] {[org.clojure/clojure "1.5.1"] nil}}
              [mvxcvi/arrangement "1.1.1"] nil}
             [fipp "0.6.10"] {[org.clojure/clojure "1.8.0"]          nil
                              [org.clojure/core.rrb-vector "0.0.11"] {[org.clojure/clojure "1.5.1"] nil}}}
           (sut/expand-dep-hierarchy repos example-dep-multiple '{[mvxcvi/puget fipp org.clojure/core.rrb-vector]
                                                                  [org.clojure/core.rrb-vector "0.0.13"]}))
        "failed to expand multiple deps with override and override a two level deep transient dependency"))

(t/deftest cljs-and-its-dependencies-evicted
  (t/is (= '{[cljfmt "0.6.3"]
             {[com.googlecode.java-diff-utils/diffutils "1.3.0"] nil
              [org.clojure/tools.cli "0.3.7"] nil
              [org.clojure/tools.reader "1.2.2"] nil
              [rewrite-cljs "0.4.4"] nil
              [rewrite-clj "0.6.0"] nil}}
           (tree/evict-subtrees (sut/resolve-source-deps repos example-dep-transient-cljs) '#{org.clojure/clojure org.clojure/clojurescript}))))
