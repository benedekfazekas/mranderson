(ns mranderson.util-test
  (:require [mranderson.util :as util]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(t/deftest clean-name-version-test
  (t/are [expected pname pversion] (= expected (util/clean-name-version pname pversion))
    "mranderson010"   "mranderson"  "0.1.0"
    "mranderson010"   "mr-anderson" "0.1.0"
    ;; a "n/a" version must not leak the slash into the prefix (see #64)
    "mrandersonna"    "mranderson"  "n/a"
    "mranderson000"   "mranderson"  "0.0.0"))

(t/deftest duplicated-files-test
  (t/is (= {}
           (util/duplicated-files [(str (fs/file "a" "b" "c"))
                                   (str (fs/file "a" "b" "d"))]))
        "no duplicates")
  (t/is (= {(str (fs/file "a" "b" "c")) 2}
           (util/duplicated-files [(str (fs/file "a" "b" "c"))
                                   (str (fs/file "a" "b" "c"))]))
        "exact absolute duplicates")
  (t/is (= {(str (fs/file "a" "b" "c")) 3}
           (util/duplicated-files [(str (fs/file "a" "b" "c"))
                                   (str (io/file "a" "b" "c"))
                                   (str (io/file "a" "x" ".." "b" "c"))]))
        "equivalent duplicates"))

(t/deftest assert-no-duplicate-files-test
  (t/is (nil? (util/assert-no-duplicate-files [(str (fs/file "a" "b" "c"))
                                              (str (fs/file "a" "b" "d"))]))
        "no throw on no dupes")
  (t/is (= {:dupes {(str (fs/file "a" "b" "c")) 2}}
           (try
             (util/assert-no-duplicate-files [(str (fs/file "a" "b" "c"))
                                             (str (fs/file "a" "b" "c"))])
             (catch Throwable ex
               (ex-data ex))))
        "throw on dupes"))

(t/deftest test-normalize-dirs
  (t/testing "normalizes and removes subdirs where one dir-name is a prefix of another"
    (t/are [expected dirs] (t/is (= (mapv #(-> % fs/file str) expected)
                                    (util/normalize-dirs dirs)))
      ["hiccup"]           ["hiccup/foo" "hiccup"]
      ["hiccup" "hiccup2"] ["hiccup/foo" "hiccup" "hiccup2"]
      ["hiccup" "hiccup2"] ["hiccup/foo/.." "hiccup/bar" (str (fs/file "hiccup/bingo")) "hiccup2/bar/.."])))

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

