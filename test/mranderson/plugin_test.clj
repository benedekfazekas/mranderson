(ns mranderson.plugin-test
  (:require [mranderson.plugin :as sut]
            [clojure.test :refer [deftest testing is]])
  (:import java.io.File))

(def ^:private find-gen-class-ns #'sut/find-gen-class-ns)

(defn- ns-file ^File [content]
  (doto (File/createTempFile "mranderson-plugin" ".clj")
    (spit content)))

(deftest find-gen-class-ns-test
  (testing "a namespace with a :gen-class clause is collected for AOT"
    (let [f (ns-file "(ns real.gc (:gen-class))")]
      (try (is (= #{'real.gc} (find-gen-class-ns #{} f)))
           (finally (.delete f)))))
  (testing "a :gen-class mentioned only in a docstring is ignored (not a real clause)"
    (let [f (ns-file "(ns doc.gc \"talks about :gen-class in passing\")")]
      (try (is (= #{} (find-gen-class-ns #{} f)))
           (finally (.delete f)))))
  (testing "a namespace without gen-class is left alone"
    (let [f (ns-file "(ns plain.ns (:require [clojure.string]))")]
      (try (is (= #{} (find-gen-class-ns #{} f)))
           (finally (.delete f))))))
