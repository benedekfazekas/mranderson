(ns mranderson.zloc-test
  (:require [mranderson.zloc :as sut]
            [rewrite-clj.zip :as z]
            [clojure.test :as t]))

(t/deftest down-and-right-skip-discards
  (t/testing "down skips a leading reader-discard"
    (t/is (= 'a (-> "(#_skip a b)" z/of-string sut/down z/sexpr))))
  (t/testing "right skips a reader-discard between siblings"
    (t/is (= 'b (-> "(a #_skip b)" z/of-string sut/down sut/right z/sexpr))))
  (t/testing "down/right skip a double discard (#_#_)"
    (t/is (= 'c (-> "(#_#_a b c)" z/of-string sut/down z/sexpr)))))

(t/deftest in-uneval?-detects-nesting
  (let [loc (z/of-string "(:require #_[a.b :as c] [a.b :as r])")
        discarded (z/find-next-depth-first loc #(= 'c (try (z/sexpr %) (catch Exception _ nil))))
        live      (z/find-next-depth-first loc #(= 'r (try (z/sexpr %) (catch Exception _ nil))))]
    (t/is (true? (sut/in-uneval? discarded)) "a node inside #_ is flagged")
    (t/is (false? (sut/in-uneval? live)) "a live node is not flagged")))

(t/deftest find-next-depth-first-skips-discarded
  (let [loc (z/of-string "(:require #_[a.b :as c] [a.b :as r])")
        hit (sut/find-next-depth-first loc #(= 'a.b (try (z/sexpr %) (catch Exception _ nil))))]
    (t/is (some? hit) "the live a.b is found")
    (t/is (false? (sut/in-uneval? hit)) "and it is the live one, not the discarded one")))
