(ns mranderson.move-test
  (:require [mranderson.move :as sut]
            [clojure.test :as t]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def ex-a-4
  "(comment \"foobar comment here\")

(ns example.a.four)

(defn foo []
  (println \"nuf said\"))

(deftype FourType [field])

(deftype FooType [])")

(def ex-5
  "(ns example.five
  (:import [example.with_dash.six SomeType SomeRecord]))

(defn- use-type-record []
  (SomeType. :type)
  (SomeRecord. :record))")

(def ex-3
  "(ns example.three
  (:require [example.five :as five]))

(defn use-ex-six-fully-qualified []
  (example.with_dash.six.SomeType. :type)
  (example.with_dash.six.SomeRecord. :record))")

(def ex-2
  "(ns
  ^{:added \"0.0.1\"}
  example.two
  (:require [example.three :as three]
            [example.a.four :as four]
            [example.a
             [foo]
             [bar]])
  (:import [example.a.four FourType]
           example.a.four.FooType))

(defn foo []
  (example.a.four/foo))

(defn cljs-foo
  \"This is valid in cljs i am told.\"
  []
  (example.a.four.foo))

(def delayed-four
  (do
    (require 'example.a.four)
    (resolve 'example.a.four/foo)))

(defn my-four-type
  ^example.a.four.FourType
  [^example.a.four.FourType t]
  t)")

(def ex-1
  "(ns example.one
  (:require [example.two :as two]
            [example.three :as three]))

(defn foo [m]
  (:example.a.four/bar m)
  (example.a.four/foo))")

(def ex-6-with-dash
  "(ns example.with-dash.six)

(deftype SomeType [field])

(defrecord SomeRecord [field])")

(def ex-edn
  "{:foo \"bar\"}")

(def ex-cljc
  "(ns example.cross
  #?@(:clj
  [(:require [example.seven :as seven-clj])]
  :cljs
  [(:require [example.seven :as seven-cljs])]))")

(def ex-cljc-expected
  "(ns example.cross
  #?@(:clj
  [(:require [example.clj.seven :as seven-clj])]
  :cljs
  [(:require [example.cljs.seven :as seven-cljs])]))")

(def ex-seven-clj "(ns example.seven)")

(def ex-seven-cljs "(ns example.seven)")

(def ex-data-readers
  "{xml/ns clojure.data.xml.name/uri-symbol
 xml/element clojure.data.xml.node/tagged-element}")

(def ex-data-readers-expected
  "{xml/ns clojure.data.xml.name/uri-symbol
 xml/element clojure.moved.data.xml.node/tagged-element}")

(def medley-user-example
  "(ns example.user.medley
 (:require [medley.core :as medley]))")

(def medley-stub "(ns medley.core)")

(def medley-stub-moved-expected "(ns ^{:inlined true} moved.medley.core)")

(def medley-user-expected
  "(ns example.user.medley
 (:require [moved.medley.core :as medley]))")

(def example-eight
  "(ns example.eight)
 (deftype EightType [])
 (deftype TypeEight [])")

(def example-nine
  "(ns example.nine
 (:import [example.eight EightType]
           example.eight.TypeEight)
 (:require [example.eight :as eight]))")

(def example-nine-expected
  "(ns example.nine
 (:import [with_dash.example.eight EightType]
           with_dash.example.eight.TypeEight)
 (:require [with-dash.example.eight :as eight]))")

(def example-meta-kw1
  "(ns ^:some-meta example.metakw1)")

(def example-meta-kw2
  "(ns ^:some-meta example.metakw2)")

(def expected-moved-metakw1
  "(ns ^:some-meta moved.metakw1)")

(def expected-moved-metakw2-watermark
  "(ns ^{:some-meta true :inlined true} moved.metakw2)")

(def example-meta-map1
  "(ns ^{:one 1 :zeta 42 :two 2} example.metamap1)")

(def example-meta-map2
  "(ns ^{:one 1 :zeta 42 :two 2} example.metamap2)")

(def expected-moved-metamap1
  "(ns ^{:one 1 :zeta 42 :two 2} moved.metamap1)")

(def expected-moved-metamap2-watermark
  "(ns ^{:one 1 :zeta 42 :two 2 :inlined true} moved.metamap2)")

(defn- create-temp-dir! [dir-name]
  (let [temp-file (File/createTempFile dir-name nil)]
    (.delete temp-file)
    (.mkdirs temp-file)
    temp-file))

(defn- create-source-file! ^File [^File file ^String content]
  (.delete file)
  (.mkdirs (.getParentFile file))
  (.createNewFile file)
  (spit file content)
  file)

;; this test is a slightly rewritten version of the original test for c.t.namespace.move from https://github.com/clojure/tools.namespace/blob/master/src/test/clojure/clojure/tools/namespace/move_test.clj
(t/deftest move-ns-test
  (let [temp-dir      (create-temp-dir! "tools-namespace-t-move-ns")
        src-dir       (io/file temp-dir "src")
        example-dir   (io/file temp-dir "src" "example")
        a-dir         (io/file temp-dir "src" "example" "a")
        with-dash-dir (io/file temp-dir "src" "example" "with_dash")
        file-one      (create-source-file! (io/file example-dir "one.clj") ex-1)
        file-two      (create-source-file! (io/file example-dir "two.clj") ex-2)
        file-three    (create-source-file! (io/file example-dir "three.clj") ex-3)
        old-file-four (create-source-file! (io/file a-dir "four.clj") ex-a-4)
        new-file-four (io/file example-dir "b" "four.clj")
        file-five     (create-source-file! (io/file example-dir "five.clj") ex-5)
        old-file-six  (create-source-file! (io/file with-dash-dir "six.clj") ex-6-with-dash)
        new-file-six  (io/file example-dir "prefix" "with_dash" "six.clj")
        file-edn      (create-source-file! (io/file example-dir "edn.clj") ex-edn)
        file-cljc     (create-source-file! (io/file example-dir "cross.cljc") ex-cljc)
        file-data-readers (create-source-file! (io/file example-dir "data_readers.cljc") ex-data-readers)
        medley-dir   (io/file src-dir "medley")
        file-medley-user (create-source-file! (io/file example-dir "user" "medley.clj") medley-user-example)
        file-medley-stub-moved (io/file src-dir "moved" "medley" "core.clj")
        file-nine    (create-source-file! (io/file example-dir "nine.clj") example-nine)
        file-three-last-modified (.lastModified file-three)
        file-moved-metakw1 (io/file src-dir "moved" "metakw1.clj")
        file-moved-metakw2 (io/file src-dir "moved" "metakw2.clj")
        file-moved-metamap1 (io/file src-dir "moved" "metamap1.clj")
        file-moved-metamap2 (io/file src-dir "moved" "metamap2.clj")]
    (create-source-file! (io/file example-dir "seven.clj") ex-seven-clj)
    (create-source-file! (io/file example-dir "seven.cljs") ex-seven-cljs)
    (create-source-file! (io/file medley-dir "core.clj") medley-stub)
    (create-source-file! (io/file example-dir "eight.clj") example-eight)
    (create-source-file! (io/file example-dir "metakw1.clj") example-meta-kw1)
    (create-source-file! (io/file example-dir "metakw2.clj") example-meta-kw2)
    (create-source-file! (io/file example-dir "metamap1.clj") example-meta-map1)
    (create-source-file! (io/file example-dir "metamap2.clj") example-meta-map2)

    (Thread/sleep 1500) ;; ensure file timestamps are different
    (t/testing "move ns simple case, no dash, no deftype, defrecord"
      (sut/move-ns 'example.a.four 'example.b.four src-dir ".clj" [src-dir] nil)

      ;; (println "affected after move")
      ;; (doseq [a [file-one file-two new-file-four]]
      ;;   (println (.getAbsolutePath a))
      ;;   (prn (slurp a)))
      ;; (println "unaffected after move")
      ;; (doseq [a [file-three file-edn]]
      ;;   (println (.getAbsolutePath a))
      ;;   (prn (slurp a)))

      (t/is (.exists new-file-four)
            "new file should exist")
      (t/is (not (.exists old-file-four))
            "old file should not exist")
      (t/is (not (.exists (.getParentFile old-file-four)))
            "old empty directory should not exist")
      (t/is (= file-three-last-modified (.lastModified file-three))
            "unaffected file should not have been modified")
      (t/is (not-any? #(.contains (slurp %) "example.a.four")
                      [file-one file-two file-three new-file-four])
            "affected files should not refer to old ns")
      (t/is (.contains (slurp file-one) "(example.b.four/foo)")
            "file with a reference to ns in body should refer with a symbol")
      (t/is (every? #(.contains (slurp %) "example.b.four")
                    [file-one file-two new-file-four])
            "affected files should refer to new ns")
      (t/is (= 9 (count (re-seq #"example.b.four" (slurp file-two))))
            "all occurances of old ns should be replace with new")
      (t/is (re-find #"\(:example.b.four/" (slurp file-one))
            "type of occurence is retained if keyword")
      (t/is (re-find #"\[example\.b\s*\[foo\]\s*\[bar\]\]" (slurp file-two))
            "prefixes should be replaced")
      (t/is (= ex-data-readers (slurp file-data-readers))
            "cljc file w/o ns macro is unchanged")
      (t/is (= ex-edn (slurp file-edn))
            "clj file wo/ ns macro is unchanged"))

    (t/testing "testing import deftype no dash, dash in the prefix"
      (sut/move-ns 'example.eight 'with-dash.example.eight src-dir ".clj" [src-dir] nil)
      (t/is (= (slurp file-nine) example-nine-expected)))

    (t/testing "move ns with dash, deftype, defrecord, import"
      (sut/move-ns 'example.with-dash.six 'example.prefix.with-dash.six src-dir ".clj" [src-dir] :inlined)
      (t/is (.contains (slurp new-file-six) ":inlined true")
            "file that was moved should have :inlined metadata watermark")
      (t/is (not-any? #(.contains (slurp %) ":inlined true")
                      [file-one file-two file-three file-five file-nine])
            "files that were not moved should not have :inlined metadata watermark")

      ;; (println "affected after move")
      ;; (doseq [a [file-three file-five new-file-six new-file-four]]
      ;;   (println (.getAbsolutePath a))
      ;;   (prn (slurp a)))

      (t/is (.exists new-file-six)
            "new file should exist")
      (t/is (not (.exists old-file-six))
            "old file should not exist")
      (t/is (not-any? #(.contains (slurp %) "example.with_dash.six")
                      [file-five file-three])
            "affected files should not refer to old ns in imports or body")
      (t/is (every? #(.contains (slurp %) "example.prefix.with_dash.six")
                    [file-five file-three])
            "affected files should refer to new ns"))

    (t/testing "testing cljc file using :clj/cljs macros in require depending on same ns in clj and cljs"
      (sut/move-ns 'example.seven 'example.clj.seven src-dir ".clj" [src-dir] nil)
      (sut/move-ns 'example.seven 'example.cljs.seven src-dir ".cljs" [src-dir] nil)

      (t/is (= (slurp file-cljc) ex-cljc-expected)))

    (t/testing "testing alias is first section of two section namespace"
      (sut/move-ns 'medley.core 'moved.medley.core src-dir ".clj" [src-dir] :inlined)
      (t/is (= (slurp file-medley-stub-moved) medley-stub-moved-expected))
      (t/is (= (slurp file-medley-user) medley-user-expected)))
    (t/testing "testing cljc file without ns macro, with a replacement"
      (create-source-file! (io/file (io/file temp-dir "src" "clojure" "data" "xml") "node.clj") "(ns clojure.data.xml.node)")
      (sut/move-ns 'clojure.data.xml.node 'clojure.moved.data.xml.node src-dir ".clj" [src-dir] nil)
      (t/is (= (slurp file-data-readers) ex-data-readers-expected)))

    (t/testing "namespace metadata correct on ns move"
      (sut/move-ns 'example.metakw1 'moved.metakw1 src-dir ".clj" [src-dir] nil)
      (t/is (= (slurp file-moved-metakw1) expected-moved-metakw1))
      (sut/move-ns 'example.metakw2 'moved.metakw2 src-dir ".clj" [src-dir] :inlined)
      (t/is (= (slurp file-moved-metakw2) expected-moved-metakw2-watermark))
      (sut/move-ns 'example.metamap1 'moved.metamap1 src-dir ".clj" [src-dir] nil)
      (t/is (= (slurp file-moved-metamap1) expected-moved-metamap1))
      (sut/move-ns 'example.metamap2 'moved.metamap2 src-dir ".clj" [src-dir] :inlined)
      (t/is (= (slurp file-moved-metamap2) expected-moved-metamap2-watermark)))))
