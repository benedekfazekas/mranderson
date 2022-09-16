(def project-version "0.5.4-SNAPSHOT")

(defproject thomasa/mranderson project-version
  :description "Dependency inlining and shadowing tool."
  :url "https://github.com/benedekfazekas/mranderson"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in :leiningen
  :java-source-paths ["java-src"]
  :javac-options ~(if (re-find #"^1\.8\." (System/getProperty "java.version"))
                    ["-source" "8" "-source" "8"]
                    ;; https://saker.build/blog/javac_source_target_parameters/index.html / https://archive.md/JH260
                    ["--release" "8"])
  :filespecs [{:type :bytes :path "mranderson/project.clj" :bytes ~(slurp "project.clj")}]
  :dependencies [^:inline-dep [clj-commons/pomegranate "1.2.1"]
                 ^:inline-dep [org.clojure/tools.namespace "1.3.0"]
                 ^:inline-dep [clj-commons/fs "1.6.310"]
                 ^:inline-dep [rewrite-clj "1.1.45"]
                 [org.clojure/clojure "1.10.3" :scope "provided"]
                 [org.pantsbuild/jarjar "1.7.2"]]
  :mranderson {:project-prefix "mranderson.inlined"}
  :profiles {:dev {:dependencies [[leiningen-core "2.9.10"]]}
             :eastwood {:plugins [[jonase/eastwood "1.3.0"]]
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
                      :dependencies [[lambdaisland/kaocha "1.69.1069"]
                                     [lambdaisland/kaocha-cloverage "1.0.75"]]}}
  :aliases {"kaocha" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner"]
            "kaocha-watch" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--watch"]
            "kaocha-coverage" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--plugin" "cloverage"]})
