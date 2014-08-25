(ns leiningen.source-deps
  (:require [leiningen.ubersource :as ubers]
            [me.raynes.fs :as fs]))

(defn- create-modules-dir-structure! [project source-dep]
  (let [source-dep-name (-> source-dep :dependencies ffirst name)
        source-dep-version (-> source-dep :dependencies first second)
        from (str (:target-path project) "/ubersource")
        to (str (:target-path project) "/source-deps/" source-dep-name "-" source-dep-version)]
    (when-not (fs/exists? to)
      (fs/mkdirs to))
    (fs/rename from to)

    (fs/delete-dir (str to "/clojure"))
    (let [original-dep (str to "/" source-dep-name "/" source-dep-version)]
      (fs/mkdir (str to "/modules"))
      (doseq [dir (fs/list-dir to)]
        (when-not (or (= "modules" dir) (= source-dep-name dir))
          (fs/copy-dir (str to "/" dir) (str to "/modules"))
          (fs/delete-dir (str to "/" dir))))
      (doseq [dir (fs/list-dir original-dep)]
        (fs/copy-dir (str original-dep "/" dir) to)
        (fs/delete-dir (str original-dep "/" dir)))
      (fs/delete-dir (str to "/" source-dep-name)))))

(defn source-deps
  "Dependencies as source used as if part of the project itself.

   Somewhat node.js & npm style dependency handling."
  [project & args]
  (let [source-deps-as-deps (reduce #(assoc %1 :dependencies (%2 %1)) (select-keys project [:repositories :source-dependencies :target-path]) [:source-dependencies])
        source-deps (map #(assoc (select-keys source-deps-as-deps [:repositories :target-path]) :dependencies [%]) (:dependencies source-deps-as-deps))
        deps-dir (fs/file "src" "deps")]
    (doseq [source-dep source-deps]
      (ubers/ubersource source-dep args)
      (create-modules-dir-structure! project source-dep))))
