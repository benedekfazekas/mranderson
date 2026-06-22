(ns leiningen.inline-deps-test
  (:require [leiningen.inline-deps :as inline-deps]
            [clojure.test :as t]))

(def ^:private aot-configured? #'inline-deps/aot-configured?)
(def ^:private lookup-opt #'inline-deps/lookup-opt)
(def ^:private lein-project->ctx #'inline-deps/lein-project->ctx)

(t/deftest aot-configured?-test
  (t/are [expected aot] (= expected (boolean (aot-configured? aot)))
    false nil
    false []
    false '()
    true  :all
    true  '[my.ns.one my.ns.two]
    true  '[my.ns.one]))

(t/deftest lookup-opt-test
  (t/testing "a CLI option wins over the project map, which wins over the default"
    (t/is (= "from-cli"
             (lookup-opt :project-prefix '(:project-prefix "from-cli") {:project-prefix "from-proj"} "default")))
    (t/is (= "from-proj"
             (lookup-opt :project-prefix '() {:project-prefix "from-proj"} "default")))
    (t/is (= "default"
             (lookup-opt :project-prefix '() {} "default")))))

(t/deftest lein-project->ctx-test
  (let [project {:root        "/p"
                 :target-path "/p/target"
                 :name        "my-proj"
                 :version     "1.2.3"
                 :mranderson  {:project-prefix           "proj.inlined"
                               :unresolved-tree          false
                               :skip-javaclass-repackage true}}
        ;; a CLI flag that should override the project map's :unresolved-tree
        ctx     (lein-project->ctx project [":unresolved-tree" "true"])]
    (t/testing "project name/version pass through"
      (t/is (= "my-proj" (:pname ctx)))
      (t/is (= "1.2.3" (:pversion ctx))))
    (t/testing "prefix comes from the :mranderson config"
      (t/is (= "proj.inlined" (:pprefix ctx))))
    (t/testing "a CLI option overrides the project map"
      (t/is (true? (:unresolved-tree ctx))))
    (t/testing "the lein option name :skip-javaclass-repackage maps to :skip-repackage-java-classes"
      (t/is (true? (:skip-repackage-java-classes ctx))))
    (t/testing "srcdeps is computed relative to the target path"
      (t/is (= "target/srcdeps" (:srcdeps ctx))))
    (t/testing "watermark defaults to :mranderson/inlined"
      (t/is (= :mranderson/inlined (:watermark ctx))))))
