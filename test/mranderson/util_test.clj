(ns mranderson.util-test
  (:require [mranderson.util :as u]
            [clojure.test :as t]))

(t/deftest test-removes-subdirs
  (t/testing "handles subdirs where one dir-name is a prefix of another"
    (t/are [expected dirs] (t/is (= expected (u/remove-subdirs dirs)))
      ["hiccup"] ["hiccup/foo" "hiccup"]
      ["hiccup" "hiccup2"] ["hiccup/foo" "hiccup" "hiccup2"])))

(t/deftest flatten-prefixed-imports-test
  (t/are [expected import-fragment] (t/is (= expected (u/flatten-prefixed-imports import-fragment)))
    ["(:import java.util.Date)" "(:import java.util.Date)"] "(:import java.util.Date)"
    ["(:import [java.util Date])" "(:import java.util.Date)"] "(:import [java.util Date])"
    ["(:import [java.util Date] java.util.UUID (java.util.zip ZipEntry ZipFile))" "(:import java.util.Date java.util.UUID java.util.zip.ZipEntry java.util.zip.ZipFile)"] "(:import [java.util Date] java.util.UUID (java.util.zip ZipEntry ZipFile))"))
