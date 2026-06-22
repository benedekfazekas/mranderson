(ns mranderson.dependency-tree-test
  "Pure (network-free) tests for the dependency-tree walking and ordering
  machinery that drives both resolved-tree and unresolved-tree processing."
  (:require [clojure.test :as t]
            [mranderson.dependency.tree :as tree]))

(t/deftest walk-deps-test
  (t/testing "visits every node depth-first with an incrementing level"
    (let [visited (atom [])]
      (tree/walk-deps '{[a "1"] {[b "1"] nil
                                 [c "1"] {[d "1"] nil}}}
                      (fn [dep level] (swap! visited conj [(first dep) level])))
      (t/is (= '[[a 0] [b 1] [c 1] [d 2]] @visited)))))

(t/deftest evict-subtrees-test
  (t/testing "removes the named roots (any version) and their subtrees, at any depth"
    (t/is (= '{[a "1"] {[b "1"] nil}}
             (tree/evict-subtrees '{[a "1"] {[clj "1.8.0"] nil
                                             [b "1"]       {[clj "1.5.1"] nil}}}
                                  '#{clj})))))

(t/deftest topological-order-test
  (t/testing "assigns a deterministic rank per dependency, shallower (depended-upon) first"
    ;; a linear chain: a has b as a dep, b has c
    (t/is (= '{a 0 b 1 c 2}
             (tree/topological-order '{[a "1"] {[b "1"] {[c "1"] nil}}})))))

(t/deftest walk-dep-tree-test
  (t/testing "depth-first: pre-fn on the way down, post-fn on the way up, with paths threaded down"
    (let [events (atom [])
          pre    (fn [_ctx paths dep]
                   (swap! events conj [:pre (first dep) paths])
                   ;; pre-result, and the paths handed to the children
                   [(first dep) (conj paths (first dep))])
          post   (fn [_ctx pre-result _paths]
                   (swap! events conj [:post pre-result]))]
      (tree/walk-dep-tree '{[a "1"] {[b "1"] nil}} pre post [] nil)
      ;; the exact sequence pins the depth-first pre/post order, including the
      ;; fact that a node's post-fn runs only after its whole subtree
      (t/is (= '[[:pre a []]
                 [:pre b [a]]
                 [:post b]
                 [:post a]]
               @events)))))

(t/deftest walk-ordered-deps-test
  (t/testing "pre-fn in order, post-fn in reverse, accumulating parent-clj-dirs"
    (let [pre-order  (atom [])
          post-order (atom [])
          post-paths (atom nil)
          pre        (fn [_ctx _paths dep]
                       (swap! pre-order conj (first dep))
                       [(first dep) {:parent-clj-dirs [(first dep)]}])
          post       (fn [_ctx pre-result paths]
                       (swap! post-order conj pre-result)
                       (reset! post-paths (:parent-clj-dirs paths)))]
      ;; an (ordered) flat list of deps, as produced for resolved-tree mode
      (tree/walk-ordered-deps (array-map '[a "1"] nil '[b "1"] nil '[c "1"] nil)
                              pre post {:parent-clj-dirs []} nil)
      (t/is (= '[a b c] @pre-order) "pre-fn runs in order")
      (t/is (= '[c b a] @post-order) "post-fn runs in reverse order")
      (t/is (= '[a b c] @post-paths)
            "post-fn sees every node's parent-clj-dirs accumulated"))))
