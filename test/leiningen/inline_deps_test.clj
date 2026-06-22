(ns leiningen.inline-deps-test
  (:require [leiningen.inline-deps :as inline-deps]
            [clojure.test :as t]))

(def ^:private aot-configured? #'inline-deps/aot-configured?)

(t/deftest aot-configured?-test
  (t/are [expected aot] (= expected (boolean (aot-configured? aot)))
    false nil
    false []
    false '()
    true  :all
    true  '[my.ns.one my.ns.two]
    true  '[my.ns.one]))
