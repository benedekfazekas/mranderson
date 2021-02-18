(defproject thomasa/mranderson "0.5.4-SNAPSHOT"
  :description "Dependency inlining and shadowing tool."
  :url "https://github.com/benedekfazekas/mranderson"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in :leiningen
  :plugins [[thomasa/mranderson "0.5.3"]]
  :java-source-paths ["java-src"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :filespecs [{:type :bytes :path "mranderson/project.clj" :bytes ~(slurp "project.clj")}]
  :dependencies [^:inline-dep [com.cemerick/pomegranate "0.4.0"]
                 ^:inline-dep [org.clojure/tools.namespace "0.3.0-alpha3"]
                 ^:inline-dep [me.raynes/fs "1.4.6"]
                 ^:inline-dep [rewrite-clj "0.6.1"]
                 ^:inline-dep [parallel "0.10"]
                 [com.googlecode.jarjar/jarjar "1.3"]]
  :mranderson {:project-prefix "mranderson.inlined"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [leiningen-core "2.9.1"]]}
             :kaocha {:dependencies [[lambdaisland/kaocha "0.0-418"]
                                     [lambdaisland/kaocha-cloverage "0.0-32"]]}}
  :aliases {"kaocha" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner"]
            "kaocha-watch" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--watch"]
            "kaocha-coverage" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner" "--plugin" "cloverage"]})
