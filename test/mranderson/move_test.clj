(ns mranderson.move-test
  (:require [mranderson.move :as sut]
            [clojure.test :as t]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def ex-a-4
  "(ns example.a.four)

(defn foo []
  (println \"nuf said\"))")

(def ex-5
  "(ns example.five)")

(def ex-3
  "(ns example.three
  (:require [example.five :as five]))")

(def ex-2
  "(ns
  ^{:added \"0.0.1\"}
  example.two
  (:require [example.three :as three]
            [example.a.four :as four]
            [example.a
             [foo]
             [bar]]))

(defn foo []
  (example.a.four/foo))

(def delayed-four
  (pritnln \"example.a.four/\")
  (do
    (require 'example.a.four)
    (resolve 'example.a.four/foo)))")

(def ex-1
  "(ns example.one
  (:require [example.two :as two]
            [example.three :as three]))

(defn foo [m]
  (:example.a.four/bar m)
  (example.a.four/foo))")

(defn- create-temp-dir! [dir-name]
  (let [temp-file (File/createTempFile dir-name nil)]
    (.delete temp-file)
    (.mkdirs temp-file)
    temp-file))

(defn- create-source-file! [^File file ^String content]
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
        file-one      (create-source-file! (io/file example-dir "one.clj") ex-1)
        file-two      (create-source-file! (io/file example-dir "two.clj") ex-2)
        file-three    (create-source-file! (io/file example-dir "three.clj") ex-3)
        old-file-four (create-source-file! (io/file a-dir "four.clj") ex-a-4)
        new-file-four (io/file example-dir "b" "four.clj")]

    (let [file-three-last-modified (.lastModified file-three)]

      (Thread/sleep 1500) ;; ensure file timestamps are different

      (sut/move-ns 'example.a.four 'example.b.four src-dir ".clj" [src-dir])

      ;; (println "affected after move")
      ;; (doseq [a [file-one file-two new-file-four]]
      ;;   (println (.getAbsolutePath a))
      ;;   (prn (slurp a)))
      ;; (println "unaffected after move")
      ;; (println (.getAbsolutePath file-three))
      ;; (prn (slurp file-three))

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
      (t/is (= 5 (count (re-seq #"example.b.four" (slurp file-two))))
            "all occurances of old ns should be replace with new")
      (t/is (re-find #"\"example.b.four/\"" (slurp file-two))
            "type of occurence is retained if string")
      (t/is (re-find #"\(:example.b.four/" (slurp file-one))
            "type of occurence is retained if keyword")
      (t/is (re-find #"\[example\.b\s*\[foo\]\s*\[bar\]\]" (slurp file-two))
            "prefixes should be replaced"))))
