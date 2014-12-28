(defproject thomasa/mranderson "0.3.0"
  :description "Leiningen plugin to download and use some dependencies as source."
  :url "https://github.com/benedekfazekas/mranderson"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :plugins [[thomasa/mranderson "0.3.0"]]
  :dependencies [^:source-dep [com.cemerick/pomegranate "0.3.0"]
                 ^:source-dep [org.clojure/tools.namespace "0.2.7"]
                 ^:source-dep [me.raynes/fs "1.4.4"]])
