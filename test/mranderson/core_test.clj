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

(deftest t-unresolved-tree
  ;; Unresolved-tree mode nests each transitive dependency under its parent
  ;; (fipp under puget), unlike the flat resolved-tree layout. This whole mode
  ;; was previously untested end to end.
  (with-mranderson
    [project {:dependencies '[^:inline-dep [mvxcvi/puget "1.0.2"]]
              :files        [{:path    "mrt/main.clj"
                              :content "(ns mrt.main (:require [puget.printer :as p]))"}]
              :opts         {:skip-repackage-java-classes true
                             :unresolved-tree             true}}]
    (let [{:keys [working-directory prefix ns-prefix]} project
          puget-dir      (io/file working-directory prefix "puget" "v1v0v2")
          puget-printer  (io/file puget-dir "puget" "printer.clj")
          nested-fipp    (io/file puget-dir "fipp" "v0v6v10" "fipp" "ednize.clj")
          nested-fipp-ns (str ns-prefix ".puget.v1v0v2.fipp.v0v6v10.fipp")]

      (testing "the dependency is shadowed under the project prefix"
        (is (.exists puget-printer))
        (is (string/includes? (slurp puget-printer)
                              (str ns-prefix ".puget.v1v0v2.puget.printer"))))

      (testing "a transitive dep is nested under its parent (the unresolved-tree hallmark)"
        (is (.exists nested-fipp))
        (is (string/includes? (slurp nested-fipp) (str nested-fipp-ns ".ednize"))))

      (testing "cross-dependency references resolve to the nested prefix"
        (is (some #(string/includes? (slurp %) nested-fipp-ns)
                  (filter #(.endsWith (.getName ^File %) ".clj") (file-seq puget-dir)))
            "puget should reference fipp via its nested shadow prefix"))

      (testing "the project's own require is rewritten to the shadowed dependency"
        (is (string/includes? (slurp (io/file working-directory "mrt" "main.clj"))
                              (str ns-prefix ".puget.v1v0v2.puget.printer")))))))

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

(deftest t-project-java-imports-are-rewritten
  ;; #92: the consuming project itself imports a class from an inlined dependency
  ;; (difflib, pulled in via cljfmt); that import must be prefixed too.
  (with-mranderson
    [project {:dependencies '[^:inline-dep [cljfmt "0.7.0"]]
              :files        [{:path    "mrt/main.clj"
                              :content "(ns mrt.main\n  (:import [difflib DiffUtils]))"}]
              :opts         {:unresolved-tree false}}]
    (let [{:keys [working-directory]} project
          name-version (util/clean-name-version "mranderson-test" "0.1.0-SNAPSHOT")
          main         (io/file working-directory "mrt" "main.clj")]
      (is (string/includes? (slurp main) (str "[" name-version ".difflib DiffUtils]"))
          "the project's import of an inlined java class is prefixed")
      (is (not (string/includes? (slurp main) "[difflib DiffUtils]"))
          "the original, unprefixed import is gone"))))

(deftest t-top-level-namespace-java-imports-are-rewritten
  ;; #54: clj-tuple has a top-level `clj-tuple` namespace that imports the
  ;; `clojure.lang.*` classes it generates; the per-dependency pass skips
  ;; top-level namespaces, so its imports weren't being prefixed.
  (with-mranderson
    [project {:dependencies '[^:inline-dep [clj-tuple "0.2.2"]]
              :files        []
              :opts         {:unresolved-tree false}}]
    (let [{:keys [working-directory]} project
          name-version (util/clean-name-version "mranderson-test" "0.1.0-SNAPSHOT")
          tuple-file   (->> (file-seq working-directory)
                            (filter #(= "clj_tuple.clj" (.getName ^File %)))
                            first)]
      (is (some? tuple-file) "clj_tuple.clj should have been inlined")
      (is (string/includes? (slurp tuple-file) (str name-version ".clojure.lang"))
          "the top-level namespace's import of a repackaged class is prefixed")
      (is (not (re-find #"\[clojure\.lang " (slurp tuple-file)))
          "the original, unprefixed clojure.lang import is gone"))))

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

    (testing "#97: a repackaged class referenced in the body is prefixed even with no :import form"
      (is (= "(ns foo)\n(defn make [] (mrt010.com.example.Widget. 1))"
             (rewrite-java-imports "(ns foo)\n(defn make [] (com.example.Widget. 1))"
                                   "" ["com.example.Widget"] prefix)))
      (testing "but a class that isn't repackaged is left alone"
        (is (= "(ns foo)\n(defn make [] (com.example.Widget. 1))"
               (rewrite-java-imports "(ns foo)\n(defn make [] (com.example.Widget. 1))"
                                     "" ["com.other.Thing"] prefix)))))

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

;; ## Unit coverage for small, previously-untested helpers

(def ^:private replacement #'sut/replacement)
(def ^:private update-path-in-file #'sut/update-path-in-file)
(def ^:private remove-invalid-duplicates! #'sut/remove-invalid-duplicates!)

(deftest t-replacement
  (testing "joins prefix and postfix into a fully-qualified namespace symbol"
    (is (= 'mrt.instaparse.v1v4v12.instaparse.core
           (replacement "mrt.instaparse.v1v4v12" 'instaparse.core nil))))
  (testing "underscorizes dashes in the postfix only when asked (java package form)"
    (is (= 'mrt.foo_bar.baz (replacement "mrt" 'foo-bar.baz true)))
    (is (= 'mrt.foo-bar.baz (replacement "mrt" 'foo-bar.baz nil)))))

(deftest t-update-path-in-file
  (let [dir (temp-dir "mranderson-update-path")
        f   (io/file dir "a.clj")]
    (try
      (spit f "(load \"old/path/thing\") ; see old/path/thing")
      (update-path-in-file f "old/path/thing" "new/prefixed/thing")
      (is (= "(load \"new/prefixed/thing\") ; see new/prefixed/thing" (slurp f))
          "every occurrence of the old path is replaced")
      (testing "a file without the path is left untouched"
        (spit f "(ns unrelated)")
        (update-path-in-file f "old/path/thing" "new/prefixed/thing")
        (is (= "(ns unrelated)" (slurp f))))
      (finally (fs/delete-dir dir)))))

(deftest t-remove-invalid-duplicates
  ;; #44: a dependency ships the same namespace in two places; keep the file
  ;; whose path matches its ns, delete the misplaced duplicate.
  (let [dir (temp-dir "mranderson-dupes")]
    (try
      (spit (doto (io/file dir "riddley" "compiler.clj") (-> .getParentFile .mkdirs))
            "(ns riddley.compiler)")
      ;; a misplaced duplicate of the same ns at the package root
      (spit (io/file dir "compiler.clj") "(ns riddley.compiler)")
      ;; an unrelated, unique file
      (spit (io/file dir "other.clj") "(ns other)")
      (let [kept (set (remove-invalid-duplicates! dir ["riddley/compiler.clj" "compiler.clj" "other.clj"]))]
        (testing "the misplaced duplicate is deleted from disk"
          (is (not (.exists (io/file dir "compiler.clj")))))
        (testing "the correctly-located file survives on disk"
          (is (.exists (io/file dir "riddley" "compiler.clj"))))
        (testing "returns the surviving files (correctly-placed dup + the unique one)"
          (is (= #{"riddley/compiler.clj" "other.clj"} kept))))
      (finally (fs/delete-dir dir)))))

(deftest t-mixed-deftype-java-import-is-split
  ;; #33: claypoole's main namespace imports both PriorityThreadpool (a deftype,
  ;; which moves with its namespace) and PriorityThreadpoolImpl (a real java
  ;; class, repackaged by jarjar) from a single package. They need different
  ;; prefixes, so the import must be split - and the split has to survive the
  ;; namespace move having already prefixed the shared package.
  (with-mranderson
    [project {:dependencies '[^:inline-dep [com.climate/claypoole "1.1.4"]]
              :files         []
              :opts          {:unresolved-tree false}}]
    (let [{:keys [working-directory prefix]} project
          name-version (util/clean-name-version "mranderson-test" "0.1.0-SNAPSHOT")
          content      (slurp (io/file working-directory prefix
                                       "claypoole" "v1v1v4" "com" "climate" "claypoole.clj"))]
      (testing "the java class is imported from its jarjar-repackaged package"
        (is (string/includes?
             content
             (str "[" name-version ".com.climate.claypoole.impl PriorityThreadpoolImpl]"))))
      (testing "the deftype class stays under the namespace-moved package"
        (is (string/includes?
             content
             (str "[" prefix ".claypoole.v1v1v4.com.climate.claypoole.impl PriorityThreadpool]"))))
      (testing "the java class is not left under the namespace prefix (the #33 bug)"
        (is (not (string/includes?
                  content
                  (str prefix ".claypoole.v1v1v4.com.climate.claypoole.impl PriorityThreadpoolImpl"))))))))
