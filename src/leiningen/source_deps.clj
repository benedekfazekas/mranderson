(ns leiningen.source-deps
  (:require [leiningen.ubersource :as ubers]
            [me.raynes.fs :as fs]))

(defn source-deps
  "Dependencies as source used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [project & args]
  (let [source-deps-as-deps (reduce #(assoc %1 :dependencies (%2 %1)) (select-keys project [:repositories :source-dependencies :target-path]) [:source-dependencies])
        source-deps (map #(assoc (select-keys source-deps-as-deps [:repositories :target-path]) :dependencies [%]) (:dependencies source-deps-as-deps))
        deps-dir (fs/file "src" "deps")]
    (when-not (fs/exists? deps-dir)
      (fs/mkdirs deps-dir))
    (doseq [source-dep source-deps]
      (prn source-dep)
      (ubers/ubersource source-dep args)
      (let [from (str (:target-path project) "/ubersource")
            to (str "src/deps/" (-> source-dep :dependencies ffirst name) "-" (-> source-dep :dependencies first second))]
        (println "from: " from)
        (println "to: " to)
        (fs/rename from to)))))
