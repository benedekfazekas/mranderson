(def project-version "0.6.2-SNAPSHOT")

(defproject thomasa/mranderson project-version
  :description "Dependency inlining and shadowing tool."
  :url "https://github.com/benedekfazekas/mranderson"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  :eval-in :leiningen
  :java-source-paths ["java-src"]
  :javac-options ~(if (re-find #"^1\.8\." (System/getProperty "java.version"))
                    ["-source" "8" "-source" "8"]
                    ;; https://saker.build/blog/javac_source_target_parameters/index.html / https://archive.md/JH260
                    ["--release" "8"])
  :filespecs [{:type :bytes :path "mranderson/project.clj" :bytes ~(slurp "project.clj")}]
  :dependencies [^:inline-dep [clj-commons/pomegranate "1.2.25"]
                 ^:inline-dep [org.clojure/tools.namespace "1.5.1"]
                 ^:inline-dep [clj-commons/fs "1.6.312"]
                 ^:inline-dep [rewrite-clj "1.2.54"]
                 [org.clojure/clojure "1.10.3" :scope "provided"]
                 [org.pantsbuild/jarjar "1.7.2"]]
  :mranderson {:project-prefix "mranderson.inlined"}
  :profiles {:dev {:dependencies [[leiningen-core "2.12.0"]]}
             ;; Clojure version profiles, exercised by the CI matrix.
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.1"]]}
             :eastwood {:plugins [[jonase/eastwood "1.4.3"]]
                        :eastwood {:exclude-linters [:no-ns-form-found]}}
             :mranderson-plugin {:plugins [[thomasa/mranderson ~project-version]]}
             ;; copy of plugin.mranderson/config profile, needed here so mrandersoned pom/jar can be built for mranderson itself
             ;; see Makefile for usage
             :mranderson-profile ^:leaky {:omit-source true
                                          :source-paths ["target/srcdeps"]
                                          :filespecs [{:type :paths :paths ["target/srcdeps"]}]
                                          :auto-clean false
                                          :srcdeps-project-hacks true
                                          :middleware [mranderson.plugin/middleware]
                                          :jar-exclusions [#"(?i)^META-INF/.*"]}
             :kaocha {:eval-in :sub-process
                      :dependencies [[lambdaisland/kaocha "1.91.1392"]
                                     [lambdaisland/kaocha-cloverage "1.1.89"]]}}
  ;; the perf benchmark (mranderson.benchmark) is excluded from normal runs; run
  ;; it explicitly with `lein test :benchmark`.
  :test-selectors {:default   (complement :benchmark)
                   :benchmark :benchmark}
  :aliases {"kaocha" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--skip-meta" ":benchmark"]
            "kaocha-watch" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--watch" "--skip-meta" ":benchmark"]
            "kaocha-coverage" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--plugin" "cloverage" "--skip-meta" ":benchmark"]})
