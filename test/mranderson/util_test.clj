(ns mranderson.util-test
  (:require [clojure.test :as t]
            [mranderson.util :as u]))

(t/deftest removes-subdirs-test
  (t/testing "handles subdirs where one dir-name is a prefix of another"
    (t/are [expected dirs] (t/is (= expected (u/remove-subdirs dirs)))
      ["hiccup"] ["hiccup/foo" "hiccup"]
      ["hiccup" "hiccup2"] ["hiccup/foo" "hiccup" "hiccup2"])))

(t/deftest ->package-names-test
  (let [class-names ["foo.bar.PersistentMapProxy$IEquality"
                     "foo.bar.BazPersistentMapProxy$IEquality"
                     "foo.bar.baz.PersistentMapProxy$IEquality"
                     "bar.goo.BigClass"]
        expected-packages #{"foo.bar" "foo.bar.baz" "bar.goo"}]
    (t/testing "class names turned into package names"
      (t/is (= expected-packages (u/->package-names class-names))))
    (t/testing "clojure.lang is filtered out"
      (t/is (= expected-packages (u/->package-names (cons "clojure.lang.PersistentUnrolledVector$Card1$1" class-names)))))))
