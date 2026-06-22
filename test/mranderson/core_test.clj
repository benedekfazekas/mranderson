(ns mranderson.core-test
  (:require [mranderson.core :as sut]
            [mranderson.test :refer [with-mranderson]]
            [mranderson.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]
            [me.raynes.fs :as fs])
  (:import java.io.File))

;; ## Fixtures

(def dependencies
  '[^:inline-dep [org.clojure/data.xml "0.2.0-alpha6"]
    ^:inline-dep [instaparse "1.4.12"]
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
                    "    [" ns-prefix ".riddley.v0v1v12.riddley.compiler :as cmp]))")))))

      (testing "Dependency source file"
        (testing "with clj extension was correctly updated"
          (let [content (slurp (io/file working-directory prefix "instaparse" "v1v4v12" "instaparse" "transform.clj"))]
            (is (string/starts-with? content (str "(ns " ns-prefix ".instaparse.v1v4v12.instaparse.transform")))))
        (testing "with cljc extension was correctly updated"
          (let [content (slurp (io/file working-directory prefix "instaparse" "v1v4v12" "instaparse" "transform.cljc"))]
           (is (string/starts-with? content (str "(ns " ns-prefix ".instaparse.v1v4v12.instaparse.transform")))))))))

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

(defn- temp-dir [prefix]
  (doto (File/createTempFile prefix "")
    (.delete)
    (.mkdirs)))

(def ^:private delete-class-files! #'sut/delete-class-files!)

(deftest t-delete-class-files
  (let [root (temp-dir "mranderson-delete-class")]
    (try
      ;; a dependency's compiled classes...
      (spit (doto (io/file root "full" "json.class") (-> .getParentFile .mkdirs)) "")
      (spit (io/file root "full" "json$fn.class") "")
      ;; ...sharing a top-level dir with the project's own sources (see #88)
      (spit (doto (io/file root "full" "aws" "core.clj") (-> .getParentFile .mkdirs)) "(ns full.aws.core)")
      ;; a dir that holds nothing but class files
      (spit (doto (io/file root "only" "classes" "A.class") (-> .getParentFile .mkdirs)) "")

      (delete-class-files! (io/file root "full"))
      (delete-class-files! (io/file root "only"))

      (testing "class files are removed"
        (is (not (.exists (io/file root "full" "json.class"))))
        (is (not (.exists (io/file root "full" "json$fn.class"))))
        (is (not (.exists (io/file root "only" "classes" "A.class")))))

      (testing "sibling source files are preserved"
        (is (.exists (io/file root "full" "aws" "core.clj")))
        (is (= "(ns full.aws.core)" (slurp (io/file root "full" "aws" "core.clj")))))

      (testing "directories left empty are pruned"
        (is (not (.exists (io/file root "only")))))

      (finally
        (fs/delete-dir root)))))

(deftest t-inline-deps
  (let [src    (temp-dir "mranderson-inline-src")
        target (temp-dir "mranderson-inline-target")
        main   (io/file src "mrt" "main.clj")]
    (try
      (.mkdirs (.getParentFile main))
      (spit main "(ns mrt.main (:require [riddley.walk :as rw]))")
      (let [prefix  (sut/inline-deps
                     {:dependencies                '[[riddley "0.1.12"]]
                      :source-paths                [(str src)]
                      :target-path                 (str target)
                      :project-prefix              "mranderson.inline.test"
                      :skip-repackage-java-classes true})
            srcdeps (io/file target "srcdeps")]

        (testing "returns the resolved project prefix"
          (is (= "mranderson.inline.test" prefix)))

        (testing "the inlined dependency is shadowed under the prefix"
          (let [walk (io/file srcdeps "mranderson" "inline" "test"
                              "riddley" "v0v1v12" "riddley" "walk.clj")]
            (is (.exists walk))
            (is (string/starts-with?
                 (slurp walk)
                 "(ns ^{:mranderson/inlined true} mranderson.inline.test.riddley.v0v1v12.riddley.walk"))))

        (testing "references in the project's own sources are rewritten"
          (let [copied (io/file srcdeps "mrt" "main.clj")]
            (is (.exists copied))
            (is (string/includes?
                 (slurp copied)
                 "mranderson.inline.test.riddley.v0v1v12.riddley.walk")))))
      (finally
        (fs/delete-dir src)
        (fs/delete-dir target)))))

(deftest t-inline-deps-generates-a-prefix-when-none-is-given
  (let [src    (temp-dir "mranderson-inline-src")
        target (temp-dir "mranderson-inline-target")
        main   (io/file src "mrt" "main.clj")]
    (try
      (.mkdirs (.getParentFile main))
      (spit main "(ns mrt.main (:require [riddley.walk :as rw]))")
      (let [prefix (sut/inline-deps
                    {:dependencies                '[[riddley "0.1.12"]]
                     :source-paths                [(str src)]
                     :target-path                 (str target)
                     :skip-repackage-java-classes true})]
        (is (string/starts-with? prefix "mranderson"))
        (is (.exists (io/file target "srcdeps" (util/sym->file-name prefix)))))
      (finally
        (fs/delete-dir src)
        (fs/delete-dir target)))))
