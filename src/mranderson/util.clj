(ns mranderson.util
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [leiningen.core.main :as lein-main]
            [clojure.set :as s])
  (:import [java.io File]
           [org.pantsbuild.jarjar Rule]
           [mranderson.util JjPackageRemapper JjMainProcessor]
           [org.pantsbuild.jarjar.util StandaloneJarProcessor]))

(defn info [& args]
  (apply lein-main/info args))

(defn warn [& args]
  (apply lein-main/warn args))

(defn debug [& args]
  (apply lein-main/debug args))

(defn clojure-source-files-relative
  ([dirs excl-dir]
     (let [excl-dirs (when excl-dir (map #(str % "/" excl-dir) dirs))]
       (->> dirs
            (map io/file)
            (filter #(.exists ^File %))
            (mapcat file-seq)
            (remove (fn [file]
                      (some #(.startsWith (str file) %) excl-dirs) ))
            (filter (fn [^File file]
                      (let [file-name (.getName file)]
                        (and (.isFile file)
                             (or
                              (.endsWith file-name ".cljc")
                              (.endsWith file-name ".cljs")
                              (.endsWith file-name ".clj")))))))))
  ([dirs]
     (clojure-source-files-relative dirs nil)))

(defn sym->file-name
  [sym]
  (-> (name sym)
      (str/replace "-" "_")
      (str/replace "." File/separator)))

(defn relevant-clj-dep-path [src-path prefix pprefix]
  (let [pprefix-path-frag (sym->file-name pprefix)]
    (if-not (str/ends-with? src-path pprefix-path-frag)
      (vector (str "target/srcdeps/"
                   pprefix-path-frag
                   (-> src-path
                       (str/split (re-pattern pprefix-path-frag))
                       last)))
      [(str "target/srcdeps/" prefix)])))

(defn clojure-source-files [dirs]
  (->> dirs
       clojure-source-files-relative
       (map #(.getCanonicalFile ^File %))))

(defn class-files []
  (->> "target/srcdeps"
       io/file
       (#(.listFiles %))
       (filter #(.isDirectory %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (.endsWith (.getName file) ".class"))))))

(defn class-file->fully-qualified-name [file]
  (->> (-> file
           str
           (str/split #"\.")
           first
           (str/split #"/"))
       (drop 2)
       (str/join ".")))

(defn- class-name->package-name [class-name]
  (->> (str/split class-name #"\.")
       butlast
       (str/join ".")))

(def ^:private not-to-prefix?
  #{"clojure.lang"})

(defn ->package-names [class-names]
  (->> (map class-name->package-name class-names)
       (remove not-to-prefix?)
       set))

(defn java-class-dirs
  "lists subdirs of target/srcdeps which contain .class files"
  []
  (reduce #(->> (str/split (str %2) #"/")
                (drop 2)
                first
                ((partial conj %1))) #{} (class-files)))

(defn clean-name-version
  [pname pversion]
  (str (str/replace pname #"[\._-]" "")
       (str/replace pversion #"[\._-]" "")))

(defn first-src-path [root source-paths]
  (apply str (drop (inc (count root)) (first source-paths))))

(defn source-dep? [dependency]
  (let [{:keys [source-dep inline-dep]} (meta dependency)]
    (or source-dep inline-dep)))

(defn- create-rule [name-version java-dir]
  (let [rule (Rule.)]
    (. rule setPattern (str java-dir ".**"))
    (. rule setResult (str name-version "." java-dir ".@1"))
    rule))

(defn apply-jarjar! [pname pversion]
  (let [java-dirs (java-class-dirs)
        name-version (clean-name-version pname pversion)
        rules (map (partial create-rule name-version) java-dirs)
        processor (JjMainProcessor. rules false false)
        jar-file (io/file (str "target/class-deps.jar"))]
    (info (format "prefixing %s in target/class-deps.jar with %s" java-dirs name-version))
    (StandaloneJarProcessor/run jar-file jar-file processor)))

(defn remove-2parents [file]
  (->> (str/split (str file) #"/")
       (drop 2)
       (str/join "/")))

(defn mranderson-version []
  (let [v (-> (io/resource "mranderson/project.clj")
              slurp
              read-string
              (nth 2))]
    (assert (string? v)
            (str "Something went wrong, version is not a string: " v))
    v))

(defn file->extension
  [file]
  (re-find #"\.clj[cs]?$" file))

(defn extension->platform
  [extension-of-moved]
  (some->> (#{".cljs" ".clj"} extension-of-moved)
           rest
           (apply str)
           keyword))

(defn platform-comp [platform]
  (when platform
    (->>  #{platform}
          (s/difference #{:cljs :clj})
          first)))

(defn- cljfile->dir [clj-file]
  (->> (str/split clj-file #"/")
       butlast
       (str/join "/")))

(defn remove-subdirs [dirs]
  (->> (sort dirs)
       (reduce (fn [ds dir]
                 (let [last-dir (last ds)]
                   (if (and last-dir (fs/child-of? last-dir dir))
                     ds
                     (conj ds dir)))) [])))

(defn clj-files->dirs
  [prefix clj-files]
  (->> (map cljfile->dir clj-files)
       (remove str/blank?)
       (map (fn [clj-dir] (str prefix "/" clj-dir)))
       set))
