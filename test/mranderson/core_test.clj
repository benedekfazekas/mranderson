(ns mranderson.core-test
  (:require [mranderson.core :as sut]
            [mranderson.test :refer [with-mranderson]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]
            [me.raynes.fs :as fs])
  (:import java.io.File))

;; ## Fixtures

(def dependencies
  '[^:inline-dep [org.clojure/data.xml "0.2.0-alpha6"]
    ^:inline-dep [riddley "0.1.12"]
    ^:inline-dep [cljfmt "0.7.0"]])

(def clojure-namespace-file
  {:path   "mrt/main.clj"
   :content (str "(ns mrt.main\n"
                 "  (:require [riddley.walk :as rw]"
                 "            [clojure.data.xml :as xml]))")})

(def clojure-resource-file
  {:path   "mrt/resource.clj"
   :content (pr-str [{:position {:x 1, :y 1}, :color :red}])})

;; ## Helpers

(defn- ->file
  [{:keys [working-directory]} {:keys [path]}]
  (io/file working-directory path))

;; ## Tests

(deftest t-resolved-tree-and-skip-java-repackage-classes
  (with-mranderson
    [project {:dependencies dependencies
              :files        [clojure-namespace-file
                             clojure-resource-file]
              :opts         {:skip-repackage-java-classes true
                             :unresolved-tree             false}}]
    (let [{:keys [working-directory prefix ns-prefix]} project]

      (testing "Clojure source file was correctly updated"
        (let [riddley-prefix (str ns-prefix ".riddley.v0v1v12.riddley")
              xml-prefix     (str ns-prefix ".dataxml.v0v2v0-alpha6.clojure.data.xml")
              main-file      (->file project clojure-namespace-file)]
          (is (= (-> (:content clojure-namespace-file)
                     (string/replace "riddley" riddley-prefix)
                     (string/replace "clojure.data.xml" xml-prefix))
                 (slurp main-file)))))

      (testing "Clojure resource file was not changed"
        (let [resource-file (->file project clojure-resource-file)]
          (is (= (:content clojure-resource-file)
                 (slurp resource-file)))))

      (testing "Dependency source file was correctly updated"
        (let [riddley-file (io/file working-directory
                                    prefix
                                    "riddley"
                                    "v0v1v12"
                                    "riddley"
                                    "walk.clj")]
          (is (string/starts-with?
                (slurp riddley-file)
                (str "(ns ^{:mranderson/inlined true} " ns-prefix ".riddley.v0v1v12.riddley.walk\n"
                     "  (:refer-clojure :exclude [macroexpand])\n"
                     "  (:require\n"
                     "    [" ns-prefix ".riddley.v0v1v12.riddley.compiler :as cmp]))"))))))))

(deftest t-copy-source-files
  (testing "Can merge files across overlapping dirs"
    (let [dir-a "test-resources/a"
          dir-b "test-resources/b"
          intermediate-dir "same-name"
          target-dir "test-resources/c/srcdeps"
          filename-1 "f"
          filename-2 "g"
          expected-1 (io/file target-dir intermediate-dir filename-1)
          expected-2 (io/file target-dir intermediate-dir filename-2)
          cleanup! #(fs/delete-dir (File. target-dir))]

      (cleanup!)

      ;; Note that both files have a different parent dir but a same intermediate dir,
      ;; so a correct impl will merge these dirs:
      (assert (.exists (io/file dir-a intermediate-dir filename-1)))
      (assert (.exists (io/file dir-b intermediate-dir filename-2)))

      (assert (not (.exists expected-1)))
      (assert (not (.exists expected-2)))

      (try
        (sut/copy-source-files [dir-a dir-b]
                               "test-resources/c")

        (is (.exists expected-1)
            (str expected-1))
        (is (.exists expected-2)
            (str expected-2))

        (finally
          (cleanup!))))))
