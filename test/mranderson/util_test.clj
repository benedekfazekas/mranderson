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
(defn- temp-dir! []
  (doto (java.io.File/createTempFile "mranderson-util" "")
    (.delete)
    (.mkdirs)))

(t/deftest path-helpers-respect-srcdeps-root
  ;; the repackaging path helpers used to assume a literal `target/srcdeps`
  ;; layout (they dropped the first two path segments); they now take an
  ;; explicit `srcdeps` root so they work with any target directory.
  (let [srcdeps (temp-dir!)]
    (try
      (spit (doto (io/file srcdeps "full" "json.class") (-> .getParentFile .mkdirs)) "")
      (spit (io/file srcdeps "full" "json$fn.class") "")
      (spit (doto (io/file srcdeps "com" "example" "Widget.class") (-> .getParentFile .mkdirs)) "")
      ;; a source file living under the same root must be ignored by class-files
      (spit (doto (io/file srcdeps "full" "aws" "core.clj") (-> .getParentFile .mkdirs)) "(ns full.aws.core)")

      (t/testing "srcdeps-relative strips the srcdeps prefix"
        (t/is (= "full/json.class"
                 (util/srcdeps-relative srcdeps (io/file srcdeps "full" "json.class")))))

      (t/testing "class-files finds only the .class files under srcdeps"
        (t/is (= #{"full/json.class" "full/json$fn.class" "com/example/Widget.class"}
                 (set (map #(util/srcdeps-relative srcdeps %) (util/class-files srcdeps))))))

      (t/testing "java-class-dirs lists the top-level dirs that hold class files"
        (t/is (= #{"full" "com"} (util/java-class-dirs srcdeps))))

      (t/testing "class-file->fully-qualified-name uses the srcdeps-relative path"
        (t/is (= "com.example.Widget"
                 (util/class-file->fully-qualified-name srcdeps (io/file srcdeps "com" "example" "Widget.class"))))
        (t/is (= "full.json"
                 (util/class-file->fully-qualified-name srcdeps (io/file srcdeps "full" "json.class")))))

      (finally
        (fs/delete-dir srcdeps)))))

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

