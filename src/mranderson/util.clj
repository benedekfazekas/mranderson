(ns mranderson.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [clojure.set :as s]
            [mranderson.log :as log])
  (:import [java.io File]
           [org.pantsbuild.jarjar Rule]
           [mranderson.util JjMainProcessor]
           [org.pantsbuild.jarjar.util StandaloneJarProcessor]))

(defn clojure-source-files-relative
  ([dirs excl-dir]
     (let [excl-dirs (when excl-dir (map #(str % "/" excl-dir) dirs))]
       (->> dirs
            (map io/file)
            (filter #(.exists ^File %))
            (mapcat file-seq)
            (remove (fn [file]
                      (some #(.startsWith (str file) %) excl-dirs) ))
            (filterv (fn [^File file]
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

(defn relevant-clj-dep-path [srcdeps src-path prefix pprefix]
  (let [pprefix-path-frag (sym->file-name pprefix)
        srcdeps-root      (str srcdeps "/")]
    (if-not (str/ends-with? src-path pprefix-path-frag)
      (vector (str srcdeps-root
                   pprefix-path-frag
                   (-> src-path
                       (str/split (re-pattern pprefix-path-frag))
                       last)))
      [(str srcdeps-root prefix)])))

(defn clojure-source-files [dirs]
  (->> dirs
       clojure-source-files-relative
       (map #(.getCanonicalFile ^File %))))

(defn class-files [srcdeps]
  (let [dir (io/file srcdeps)
        subdirs (some-> (.listFiles ^File dir) seq)]
    (->> subdirs
         (filter #(.isDirectory ^File %))
         (mapcat file-seq)
         (filterv (fn [^File file]
                    (and (.isFile file)
                         (.endsWith (.getName file) ".class")))))))

(defn srcdeps-relative
  "Returns the path of `file` relative to the `srcdeps` root, using forward
  slashes. `file` is expected to live under `srcdeps`, as everything produced by
  walking the srcdeps tree does."
  ^String [srcdeps file]
  (let [root (str srcdeps)
        path (str file)]
    (-> (if (str/starts-with? path root)
          (subs path (count root))
          path)
        (str/replace #"^[/\\]+" ""))))

(defn class-file->fully-qualified-name [srcdeps file]
  (->> (-> (srcdeps-relative srcdeps file)
           (str/split #"\.")
           first
           (str/split #"/"))
       (str/join ".")))

(defn class-name->package-name [class-name]
  (->> (str/split class-name #"\.")
       butlast
       (str/join ".")))

(defn java-class-dirs
  "lists subdirs of `srcdeps` which contain .class files"
  [srcdeps]
  (reduce #(->> (str/split (srcdeps-relative srcdeps %2) #"/")
                first
                ((partial conj %1))) #{} (class-files srcdeps)))

(defn clean-name-version
  "Builds an identifier-safe prefix out of `pname` and `pversion`.

  Strips every non-alphanumeric character so that names or versions
  containing characters like `/` (e.g. a version of \"n/a\") don't leak
  into the generated package/namespace prefix and break imports."
  [pname pversion]
  (str (str/replace pname #"[^a-zA-Z0-9]" "")
       (str/replace pversion #"[^a-zA-Z0-9]" "")))

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

(defn apply-jarjar! [pname pversion srcdeps jar-file]
  (let [java-dirs (java-class-dirs srcdeps)
        name-version (clean-name-version pname pversion)
        rules (map (partial create-rule name-version) java-dirs)
        processor (JjMainProcessor. rules false false)
        jar-file (io/file jar-file)]
    (log/info (format "prefixing %s in %s with %s" java-dirs jar-file name-version))
    (StandaloneJarProcessor/run jar-file jar-file processor)))

(defn mranderson-version []
  (let [v (-> (io/resource "mranderson/project.clj")
              slurp
              edn/read-string
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

(defn duplicated-files
  "Returns map of duplicates in `files`, key is fully qualified file as string, value is num occurrences.
  If no duplicates, empty map is returned."
  [files]
  (->> files
       (map #(-> % str fs/normalized str))
       (frequencies)
       (filterv #(> (second %) 1))
       (into {})))

(defn assert-no-duplicate-files
  "Throw internal error if there are any duplicates in `files`."
  [files]
  (let [dupes (duplicated-files files)]
    (when (seq dupes)
      (throw (ex-info "internal error: found unexpected duplicates in files" {:dupes dupes})))))

(defn normalize-dirs
  "Returns `dirs` (as strings) normalized, deduped and without subdirs"
  [dirs]
  (->> dirs
       (map #(-> % str fs/normalized str))
       sort
       distinct
       (reduce (fn [ds dir]
                 (let [last-dir (last ds)]
                   (if (and last-dir (fs/child-of? last-dir dir))
                     ds
                     (conj ds dir))))
               [])))

(defn clj-files->dirs
  [prefix clj-files]
  (->> (map cljfile->dir clj-files)
       (remove str/blank?)
       (map (fn [clj-dir] (str prefix "/" clj-dir)))
       set))

(defn determine-source-dirs [{:keys [source-paths]
                              {:keys [included-source-paths]} :mranderson}]
  (case included-source-paths
    (nil :first) (take 1 source-paths)
    :source-paths source-paths
    (do
      (assert (vector? included-source-paths)
              (pr-str included-source-paths))
      included-source-paths)))
