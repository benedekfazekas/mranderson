(ns mranderson.util-test
  (:require [mranderson.util :as util]
            [clojure.test :as t]))

(t/deftest test-removes-subdirs
  (t/testing "handles subdirs where one dir-name is a prefix of another"
    (t/are [expected dirs] (t/is (= expected (util/remove-subdirs dirs)))
      ["hiccup"] ["hiccup/foo" "hiccup"]
      ["hiccup" "hiccup2"] ["hiccup/foo" "hiccup" "hiccup2"])))
