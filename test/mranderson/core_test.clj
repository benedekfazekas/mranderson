(ns mranderson.core-test
  (:require [mranderson.test :refer [with-mranderson]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]))

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
