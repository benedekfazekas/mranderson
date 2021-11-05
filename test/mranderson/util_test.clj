(ns mranderson.util-test
  (:require [mranderson.util :as util]
            [clojure.test :as t]))

(t/deftest test-removes-subdirs
  (t/testing "handles subdirs where one dir-name is a prefix of another"
    (t/are [expected dirs] (t/is (= expected (util/remove-subdirs dirs)))
      ["hiccup"] ["hiccup/foo" "hiccup"]
      ["hiccup" "hiccup2"] ["hiccup/foo" "hiccup" "hiccup2"])))

(t/deftest determine-source-dirs
  (t/are [desc input expected] (t/testing desc
                                 (t/is (= expected (util/determine-source-dirs input)))
                                 true)
    "In absence of options"
    {:source-paths ["src"]
     :test-paths   ["test"]}
    ["src"]

    "In absence of options and multiple source-paths, takes just the first, since that's the traditional behavior"
    {:source-paths ["src" "plugin"]
     :test-paths   ["test"]}
    ["src"]

    "Can accept `:source-paths`"
    {:source-paths ["src" "plugin"]
     :test-paths   ["test"]
     :mranderson   {:included-source-paths :source-paths}}
    ["src" "plugin"]

    "Can accept a fixed list"
    {:source-paths ["src" "plugin"]
     :test-paths   ["test"]
     :mranderson   {:included-source-paths ["plugin"]}}
    ["plugin"]

    "Can accept a fixed list that has nothing to do with :source-paths"
    {:source-paths ["src" "plugin"]
     :test-paths   ["test"]
     :mranderson   {:included-source-paths ["test" "foo"]}}
    ["test" "foo"]))
