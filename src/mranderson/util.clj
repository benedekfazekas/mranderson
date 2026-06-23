(ns mranderson.util
  "Filesystem, class-file and jarjar helpers shared across the pipeline.

  Two concerns live here: locating and normalizing Clojure source dirs and
  files, and the Java side of inlining - finding bundled `.class` files and
  relocating their packages via jarjar under a name+version prefix (distinct from
  the namespace `:project-prefix`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [clojure.set :as s]
            [mranderson.log :as log])
  (:import [java.io File]
           [org.pantsbuild.jarjar Rule]
           [mranderson.util JjMainProcessor]
           [org.pantsbuild.jarjar.util StandaloneJarProcessor]))

(defn clojure-source-files-relative
  "Returns all .clj/.cljc/.cljs files under `dirs` as a vector of File, left
  as-is (not canonicalized). When `excl-dir` is given, any file under
  `<dir>/<excl-dir>` for each of `dirs` is excluded."
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
  "Converts a namespace symbol to its relative file path (no extension), munging
  dashes to underscores and dots to the platform file separator."
  [sym]
  (-> (name sym)
      (str/replace "-" "_")
      (str/replace "." File/separator)))

(defn clojure-source-files
  "Like `clojure-source-files-relative`, but returns each file canonicalized."
  [dirs]
  (->> dirs
       clojure-source-files-relative
       (map #(.getCanonicalFile ^File %))))

(defn class-files
  "Returns a vector of all `.class` files found under the subdirectories of
  `srcdeps` (files directly in the root are ignored)."
  [srcdeps]
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

(defn class-file->fully-qualified-name
  "Returns the fully-qualified (dotted) class name for a `.class` `file`, derived
  from its path relative to `srcdeps` with the extension dropped."
  [srcdeps file]
  (->> (-> (srcdeps-relative srcdeps file)
           (str/split #"\.")
           first
           (str/split #"/"))
       (str/join ".")))

(defn java-class-dirs
  "Returns the set of top-level package roots (the first path segment under
  `srcdeps`) that contain bundled `.class` files. These are the roots jarjar
  relocates under the name+version prefix."
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

(defn first-src-path
  "Returns the first of `source-paths` relative to project `root` (the leading
  `root` prefix and its trailing separator are stripped)."
  [root source-paths]
  (apply str (drop (inc (count root)) (first source-paths))))

(defn source-dep?
  "True if `dependency` is marked for inlining, i.e. its metadata carries
  `:source-dep` or `:inline-dep`."
  [dependency]
  (let [{:keys [source-dep inline-dep]} (meta dependency)]
    (or source-dep inline-dep)))

(defn- create-rule
  "Builds a jarjar `Rule` relocating package `java-dir` (and everything under it)
  beneath `name-version`."
  [name-version java-dir]
  (let [rule (Rule.)]
    (. rule setPattern (str java-dir ".**"))
    (. rule setResult (str name-version "." java-dir ".@1"))
    rule))

(defn apply-jarjar!
  "Repackages the bundled Java `.class` files in `jar-file` in place. Relocates
  every package root found under `srcdeps` (see `java-class-dirs`) beneath the
  name+version prefix derived from `pname`/`pversion`, via jarjar."
  [pname pversion srcdeps jar-file]
  (let [java-dirs (java-class-dirs srcdeps)
        name-version (clean-name-version pname pversion)
        rules (map (partial create-rule name-version) java-dirs)
        processor (JjMainProcessor. rules false)
        jar-file (io/file jar-file)]
    (log/info (format "prefixing %s in %s with %s" java-dirs jar-file name-version))
    (StandaloneJarProcessor/run jar-file jar-file processor)))

(defn file->extension
  "Returns the Clojure source extension of `file` (\".clj\", \".cljc\" or
  \".cljs\"), or nil if it has none. Doubles as an is-this-a-source-file?
  predicate."
  [file]
  (re-find #"\.clj[cs]?$" file))

(defn extension->platform
  "Returns the platform keyword for a single-platform extension: `:clj` for
  \".clj\", `:cljs` for \".cljs\". Returns nil for `.cljc` or anything else."
  [extension-of-moved]
  (some->> (#{".cljs" ".clj"} extension-of-moved)
           rest
           (apply str)
           keyword))

(defn platform-comp
  "Returns the complementary platform keyword (`:clj` <-> `:cljs`), or nil when
  `platform` is nil."
  [platform]
  (when platform
    (->>  #{platform}
          (s/difference #{:cljs :clj})
          first)))

(defn- cljfile->dir
  "Returns the directory portion of slash-separated `clj-file` (its final segment
  dropped)."
  [clj-file]
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
  "Returns the set of distinct directories holding `clj-files`, each prefixed with
  `prefix` (as `prefix/dir`)."
  [prefix clj-files]
  (->> (map cljfile->dir clj-files)
       (remove str/blank?)
       (map (fn [clj-dir] (str prefix "/" clj-dir)))
       set))

(defn determine-source-dirs
  "Returns the project source dirs to inline, per the
  `[:mranderson :included-source-paths]` option: `nil`/`:first` means just the
  first source path, `:source-paths` means all of them, or an explicit vector of
  paths is used as-is."
  [{:keys [source-paths]
    {:keys [included-source-paths]} :mranderson}]
  (case included-source-paths
    (nil :first) (take 1 source-paths)
    :source-paths source-paths
    (do
      (assert (vector? included-source-paths)
              (pr-str included-source-paths))
      included-source-paths)))
