(defproject thomasa/mranderson "0.4.6"
  :description "Leiningen plugin to download and use some dependencies as source."
  :url "https://github.com/benedekfazekas/mranderson"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in :leiningen
  :plugins [[thomasa/mranderson "0.4.5"]]
  :java-source-paths ["java-src"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :filespecs [{:type :bytes :path "mranderson/project.clj" :bytes ~(slurp "project.clj")}]
  :dependencies [^:source-dep [com.cemerick/pomegranate "0.3.0"]
                 ^:source-dep [org.clojure/tools.namespace "0.2.11"]
                 ^:source-dep [me.raynes/fs "1.4.6"]
                 [com.googlecode.jarjar/jarjar "1.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]]}})
