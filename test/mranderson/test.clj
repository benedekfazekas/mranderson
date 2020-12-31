(ns mranderson.test
  (:require [mranderson.core :refer [mranderson]]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io])
  (:import (java.io File)))

;; ## Fixtures

(def ^:private repositories
  [["central" {:url "https://repo1.maven.org/maven2/", :snapshots false}]
   ["clojars" {:url "https://repo.clojars.org/"}]])

;; ## Helpers

(defmacro ^:private with-temp-dir
  "Create a temporary directory, run the body, then clean up the directory."
  [sym & body]
  `(let [f# (doto (File/createTempFile "mranderson" "")
              (.delete)
              (.mkdirs))
         ~sym f#]
     (try
       (do ~@body)
       (finally
         (fs/delete-dir f#)))))

(defn ^:private create-files!
  "Create files and return root directories that contain them."
  [working-directory files]
  (->> (for [{:keys [path content]} files]
         (let [dirname     (first (.split ^String path "/"))
               target-file (io/file working-directory path)]
           (.. target-file (getParentFile) (mkdirs))
           (spit target-file  content)
           (io/file working-directory dirname)))
       (distinct)
       (doall)))

(defmacro ^:private with-project
  "Set up a temporary directory with a structure that can be used by
   mranderson."
  [[sym files] & body]
  `(with-temp-dir tmp#
     (let [working-directory#  (doto (io/file tmp# "srcdeps")
                                (.mkdirs))
           source-directories# (create-files! working-directory# ~files)
           prefix#             (str "mranderson_test" (rand-int 99999))
           ~sym                {:prefix             prefix#
                                :ns-prefix          (.replace prefix# "_" "-")
                                :working-directory  working-directory#
                                :source-directories source-directories#}]
       ~@body)))

(defn- project->context
  "Convert the given project map into a context map for mranderson."
  [{:keys [^File working-directory
           source-directories
           prefix]}
   & [opts]]
  (merge
    {:pprefix                     prefix
     :pname                       "mranderson-test"
     :pversion                    "0.1.0-SNAPSHOT"

     :project-source-dirs         source-directories
     :srcdeps                     (.getPath working-directory)

     :skip-repackage-java-classes false
     :unresolved-tree             false

     :watermark                   :mranderson/inlined
     :expositions                 nil
     :overrides                   nil
     :prefix-exclusions           nil}
    opts))

(defn- project->paths
  "Convert the given project map into a paths map for mranderson."
  [{:keys [^File working-directory prefix]}]
  {:src-path        (io/file working-directory prefix)
   :parent-clj-dirs []
   :branch          []})

;; ## Run

(defn with-mranderson*
  [{:keys [dependencies files opts]} f]
  (with-project [project files]
    (mranderson repositories
                dependencies
                (project->context project opts)
                (project->paths project))
    (f project)))

(defmacro with-mranderson
  [[project {:keys [dependencies files opts] :as spec}] & body]
  `(with-mranderson* ~spec (fn [~project] ~@body)))
