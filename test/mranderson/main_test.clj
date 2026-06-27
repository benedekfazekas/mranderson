(ns mranderson.main-test
  (:require [mranderson.main :as sut]
            [clojure.tools.cli :as cli]
            [clojure.test :refer [deftest testing is]]))

(deftest parse-coordinate-test
  (testing "group/artifact:version"
    (is (= ['org.clojure/tools.namespace "1.5.1"]
           (sut/parse-coordinate "org.clojure/tools.namespace:1.5.1"))))
  (testing "artifact:version (no group)"
    (is (= ['rewrite-clj "1.2.54"] (sut/parse-coordinate "rewrite-clj:1.2.54"))))
  (testing "only the last colon splits, so a version may not, but the lib may contain dots/slashes"
    (is (= ['com.acme/lib "1.0.0"] (sut/parse-coordinate "com.acme/lib:1.0.0"))))
  (testing "a coordinate without a version throws"
    (is (thrown? clojure.lang.ExceptionInfo (sut/parse-coordinate "no-version")))))

;; parse args the way -main does, so the tests exercise the real cli-options spec
(def ^:private cli-options @#'sut/cli-options)

(defn- parse [args]
  (let [{:keys [options arguments]} (cli/parse-opts args cli-options)]
    (sut/options->opts options arguments)))

(deftest options->opts-test
  (testing "flags and positional coordinates map onto inline-deps options"
    (is (= {:dependencies '[[org.clojure/tools.namespace "1.5.1"] [rewrite-clj "1.2.54"]]
            :project-prefix "com.example.inlined"
            :source-paths ["src" "src-extra"]
            :target-path "build"
            :unresolved-tree true
            :skip-repackage-java-classes true
            :report true}
           (parse ["-p" "com.example.inlined"
                   "-s" "src" "-s" "src-extra"
                   "-t" "build"
                   "--unresolved-tree"
                   "--skip-java-repackage"
                   "--report"
                   "org.clojure/tools.namespace:1.5.1"
                   "rewrite-clj:1.2.54"]))))
  (testing "minimal invocation: just coordinates, no source paths"
    (is (= {:dependencies '[[org.clojure/data.xml "0.2.0-alpha6"]]}
           (parse ["org.clojure/data.xml:0.2.0-alpha6"]))))
  (testing "print-deps-tree flag"
    (is (true? (:print-deps-tree (parse ["--print-deps-tree" "rewrite-clj:1.2.54"])))))
  (testing "prefix-exclusions parses as EDN"
    (is (= ["classlojure"] (:prefix-exclusions (parse ["--prefix-exclusions" "[\"classlojure\"]" "x:1"])))))
  (testing "watermark keyword and the 'nil' disabling form"
    (is (= :my/mark (:watermark (parse ["--watermark" ":my/mark" "x:1"]))))
    (is (contains? (parse ["--watermark" "nil" "x:1"]) :watermark))
    (is (nil? (:watermark (parse ["--watermark" "nil" "x:1"])))))
  (testing "watermark is left to the inline-deps default when not given"
    (is (not (contains? (parse ["x:1"]) :watermark)))))
