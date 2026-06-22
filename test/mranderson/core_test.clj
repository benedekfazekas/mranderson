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

(deftest t-resolved-tree-repackages-java-classes
  ;; cljfmt pulls in the pure-Java diffutils, so inlining it (without skipping
  ;; the java-class repackaging) exercises the jarjar pipeline end to end. This
  ;; only works now that the repackaging operates on the configured `srcdeps`
  ;; (the temp dir) rather than a hardcoded `target/srcdeps`.
  (with-mranderson
    [project {:dependencies '[^:inline-dep [cljfmt "0.7.0"]]
              :files        []
              :opts         {:unresolved-tree false}}]
    (let [{:keys [working-directory]} project
          name-version    (util/clean-name-version "mranderson-test" "0.1.0-SNAPSHOT")
          class-file?     (fn [^File f] (and (.isFile f) (string/ends-with? (.getName f) ".class")))
          all-class-files (->> (file-seq working-directory) (filter class-file?))]

      (testing "the dependency tree contributes java .class files"
        (is (seq all-class-files)))

      (testing "every remaining .class file lives under the repackaged prefix"
        (is (every? #(string/includes? (str %) (str "/" name-version "/")) all-class-files)))

      (testing "diffutils' difflib package was repackaged under the prefix, original removed"
        (is (.exists (io/file working-directory name-version "difflib")))
        (is (not (.exists (io/file working-directory "difflib"))))))))

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

(def ^:private prefix-occurrences #'sut/prefix-occurrences)
(def ^:private rewrite-java-imports #'sut/rewrite-java-imports)
(def ^:private import-fragment #'sut/import-fragment)

(deftest t-import-fragment
  (testing "extracts the (:import ...) form from a namespace declaration"
    (is (= "(:import [com.example Widget])"
           (import-fragment "(ns foo\n  (:import [com.example Widget]))\n(defn bar [])")))
    (is (= "(:import [com.example Widget Gadget]\n           java.util.UUID)"
           (import-fragment (str "(ns foo\n"
                                 "  (:require [clojure.string :as str])\n"
                                 "  (:import [com.example Widget Gadget]\n"
                                 "           java.util.UUID))")))))
  (testing "returns nil when there is no :import form"
    (is (nil? (import-fragment "(ns foo (:require [clojure.string :as str]))")))))

(deftest t-prefix-occurrences
  (testing "prefixes each token, leaving occurrences already preceded by a dot alone"
    (is (= "(PRE.com.example.Widget)"
           (prefix-occurrences "(com.example.Widget)" ["com.example.Widget"] "PRE")))
    (is (= "(PRE.com.example.Widget PRE.com.example.Gadget)"
           (prefix-occurrences "(com.example.Widget com.example.Gadget)"
                               ["com.example.Widget" "com.example.Gadget"] "PRE"))))
  (testing "a token already preceded by a dot is not re-prefixed"
    ;; the leading char before the token must not be a dot
    (is (= "(a.com.example.Widget)"
           (prefix-occurrences "(a.com.example.Widget)" ["com.example.Widget"] "PRE")))))

(deftest t-rewrite-java-imports
  (let [prefix "mrt010"]
    (testing "prefix-list import: package is prefixed in the import, class name in the body"
      (let [content     (str "(ns foo\n"
                             "  (:import [com.example Widget]))\n"
                             "(defn make [] (com.example.Widget/create))")
            orig-import "(:import [com.example Widget])"
            class-names ["com.example.Widget"]]
        (is (= (str "(ns foo\n"
                    "  (:import [mrt010.com.example Widget]))\n"
                    "(defn make [] (mrt010.com.example.Widget/create))")
               (rewrite-java-imports content orig-import class-names prefix)))))

    (testing "fully-qualified class import is prefixed when repackaged"
      (is (= "(ns foo\n  (:import mrt010.com.example.Widget))"
             (rewrite-java-imports "(ns foo\n  (:import com.example.Widget))"
                                   "(:import com.example.Widget)"
                                   ["com.example.Widget"] prefix))))

    (testing "a blank import fragment leaves the content untouched"
      (is (= "(ns foo)" (rewrite-java-imports "(ns foo)" "" ["com.example.Widget"] prefix))))

    (testing "every class in a prefix-list is repackaged: the package is prefixed, no split"
      (is (= "(ns foo\n  (:import [mrt010.com.example Widget Gadget]))"
             (rewrite-java-imports "(ns foo\n  (:import [com.example Widget Gadget]))"
                                   "(:import [com.example Widget Gadget])"
                                   ["com.example.Widget" "com.example.Gadget"] prefix))))

    (testing "#52: a class sharing a package with a repackaged one but not itself repackaged is left alone"
      ;; clj-tuple ships some `clojure.lang.*` class, so `clojure.lang` shows up in
      ;; the package set, but core `clojure.lang.Var`/`Compiler` must not be touched.
      (let [content     "(ns riddley.compiler\n  (:import [clojure.lang Var Compiler]))"
            orig-import "(:import [clojure.lang Var Compiler])"
            class-names ["clojure.lang.Tuple"]]   ;; the only actually-repackaged class
        (is (= content (rewrite-java-imports content orig-import class-names prefix))
            "Var/Compiler are not repackaged, so the import is unchanged")))

    (testing "#33: a mixed import is split so each class gets the right package"
      ;; `JavaClass` is a real java class (repackaged here); `Deftyped` is a
      ;; deftype-generated class left under its original package for the namespace
      ;; move to prefix separately.
      (let [content     "(ns user\n  (:import [com.acme.impl Deftyped JavaClass]))"
            orig-import "(:import [com.acme.impl Deftyped JavaClass])"
            class-names ["com.acme.impl.JavaClass"]]
        (is (= "(ns user\n  (:import [com.acme.impl Deftyped] [mrt010.com.acme.impl JavaClass]))"
               (rewrite-java-imports content orig-import class-names prefix)))))))

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
