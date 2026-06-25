;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:no-doc true
      :author "Stuart Sierra, Benedek Fazekas"
      :doc    "Refactoring tool to move a Clojure namespace from one name/file to
  another, and update all references to that namespace in your other
  Clojure source files.

  WARNING: This code is ALPHA and subject to change. It also modifies
  and deletes your source files! Make sure you have a backup or
  version control.

  DISCLAIMER
  This is a heavily modified version of Stuart Sierra's original clojure.tools.namespace.move

"}
    mranderson.move
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [mranderson.util :as util]
            [mranderson.zloc :as zloc]
            [me.raynes.fs :as fs]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.reader :as reader])
  (:import (java.io File FileNotFoundException)))

(defn- update-file
  "Reads file as a string, calls f on the string plus any args, then
  writes out return value of f as the new contents of file. Does not
  modify file if the content is unchanged."
  [file f & args]
  (let [old (slurp file)
        new (str (apply f old args))]
    (when-not (= old new)
      (spit file new))))

(defn- sym->file ^File
  [path sym extension]
  (io/file path (str (util/sym->file-name sym) extension)))

(defn- update? [file extension-of-moved]
  (let [file-ext (util/file->extension file)
        all-extensions #{".cljc" ".cljs" ".clj"}]
    (or
     (and (= ".cljc" extension-of-moved)
          (all-extensions file-ext))
     (= file-ext extension-of-moved)
     (= file-ext ".cljc"))))

(defn- prefix-libspec [libspec]
  (let [prefix (str/join "." (butlast (str/split (name libspec) #"\.")))]
    (and prefix (symbol prefix))))

(defn- java-package [sym]
  (str/replace (name sym) "-" "_"))

(defn- load-param
  "Renders `sym` as the resource path used by `(load \"…\")`: dots become
  slashes and dashes underscores, e.g. `clojure.tools.deps.alpha` ->
  `clojure/tools/deps/alpha`."
  [sym]
  (-> (name sym)
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn- java-style-prefix?
  [old-sym node]
  (when-let [node-sexpr (z/sexpr node)]
    (str/starts-with? node-sexpr (java-package old-sym))))

(defn- libspec-prefix?
  [node node-sexpr old-sym]
  (let [old-sym-prefix-libspec (prefix-libspec old-sym)
        first-node?            (z/leftmost? node)
        parent-leftmost-node   (z/leftmost (z/up node))
        parent-leftmost-sexpr  (and parent-leftmost-node
                                    (not (zloc/uneval? parent-leftmost-node))
                                    (z/sexpr parent-leftmost-node))]
    (and first-node?
         (= :require parent-leftmost-sexpr)
         (= node-sexpr old-sym-prefix-libspec))))

(defn- contains-sym? [old-sym node]
  (when-let [node-sexpr (z/sexpr node)]
    (or
     (= node-sexpr old-sym)
     (libspec-prefix? node node-sexpr old-sym))))

(defn- ->new-node [old-node old-sym new-sym]
  (let [old-prefix (prefix-libspec old-sym)]
    (cond-> old-node

      :always
      (str/replace-first
       (name old-sym)
       (name new-sym))

      (= old-prefix old-node)
      (str/replace-first
       (name old-prefix)
       (name (prefix-libspec new-sym))))))

(defn- replace-in-node [old-sym new-sym old-node]
  (let [new-node (->new-node old-node old-sym new-sym)]
    (cond
      (symbol? old-node) (symbol new-node)

      :else new-node)))

(defn- ns-decl? [node]
  (when-not (zloc/uneval? node)
    (= 'ns (z/sexpr (z/down node)))))

(def ^:const ns-form-placeholder (str "ns_" "form_" "placeholder"))

(defn- split-ns-form-ns-body
  "Returns ns form as a rewrite-clj loc and the ns body as string with a place holder for the ns form."
  [content]
  (let [reader     (reader/string-reader content)
        first-form (parser/parse reader)]
    (loop [ns-form-maybe (and first-form (z/of-node first-form))]
      (if (ns-decl? ns-form-maybe)
        [ns-form-maybe
         (str/replace content (z/root-string ns-form-maybe) ns-form-placeholder)]
        (if-let [next-form (parser/parse reader)]
          (recur (z/of-node next-form))
          [nil content])))))

(defn- import? [node]
  (when-let [node-sexpr (z/sexpr node)]
    (= :import node-sexpr)))

(defn- ->new-import-node [old-sym new-sym old-node]
  (let [new-node (str/replace old-node (java-package old-sym) (java-package new-sym))]
    (cond
      (symbol? old-node) (symbol new-node)

      :else new-node)))

(defn- replace-in-import* [import-loc old-sym new-sym]
  (loop [loc import-loc]
    (if-let [found-node (some-> loc
                                (zloc/find-next-depth-first (partial java-style-prefix? old-sym))
                                (z/edit (partial ->new-import-node old-sym new-sym)))]
      (recur found-node)
      (z/root loc))))

(defn- replace-in-import [ns-loc old-sym new-sym]
  (if-let [import-loc (some-> (zloc/find-next-depth-first ns-loc import?)
                              (z/up))]
    (-> (z/replace import-loc (replace-in-import* (z/of-node (z/node import-loc)) old-sym new-sym))
        z/root
        z/of-node)
    ns-loc))

(defn ^:no-doc rename-ns
  ;; exposed only for unit testing, could move to impl ns
  "Return `ns-loc`, with zipper location unchanged, applying `new-ns-name` and `add-meta-kw`,
  iff current namespace name is `old-ns-name`, else return `ns-loc`.

  `ns-loc` is assumed to be positioned at the `(ns ...)` form (or nil).

  We don't look at or alter `ns` form's `attr-map?`.

  We make an effort to preserve existing ordering and syntax of metadata."
  [ns-loc old-ns-name new-ns-name add-meta-kw]
  (when ns-loc
    (let [ns-name-loc (some-> ns-loc zloc/down zloc/right)
          cur-has-meta? (= :meta (z/tag ns-name-loc))
          ns-loc (cond
                   (not= (z/sexpr ns-name-loc) old-ns-name)
                   ns-loc

                   add-meta-kw
                   (if cur-has-meta?
                     (cond-> (zloc/down ns-name-loc)
                       ;; convert existing ^:some-meta to ^{:some-meta true ...}
                       (-> ns-name-loc zloc/down z/node n/keyword-node?)
                       (z/edit (fn [kw] (n/map-node [kw (n/spaces 1) true])))

                       ;; append our new meta to existing ^{:some-meta true ...}
                       :always
                       (-> (z/assoc add-meta-kw true)
                           zloc/right
                           (z/replace new-ns-name)))
                     ;; no existing meta, add it in
                     (-> ns-name-loc
                         (z/replace (n/meta-node
                                      (n/map-node [add-meta-kw (n/spaces 1) true])
                                      new-ns-name))))

                   cur-has-meta?
                   (-> ns-name-loc
                       zloc/down ;; to current meta
                       zloc/right ;; to namespace name
                       (z/replace new-ns-name))

                   :else
                   (z/replace ns-name-loc new-ns-name))]
      ;; we could maybe not rebuild zipper? but for now, go with the flow
      (-> ns-loc
          z/root
          z/of-node))))

(defn- replace-in-ns-form [ns-loc old-sym new-sym watermark]
  (loop [loc (-> (rename-ns ns-loc old-sym new-sym watermark)
                 (replace-in-import old-sym new-sym))]
    (if-let [found-node (some-> (zloc/find-next-depth-first loc (partial contains-sym? old-sym))
                                (z/edit (partial replace-in-node old-sym new-sym)))]
      (recur found-node)
      (z/root-string loc))))

(defn- class-reference?
  "True if `match`, a fully-qualified dotted symbol (the reader splits on `/`, so
  these never contain a slash), refers to a Java class/record rather than a
  namespace or var. Clojure class names are CamelCase, so we treat a final
  segment that starts with an uppercase letter as a class. A trailing dot
  (constructor form, e.g. `foo.Bar.`) is ignored."
  [match]
  (let [last-seg (-> match
                     (str/replace #"\.$" "")
                     (str/split #"\.")
                     last)]
    (and (seq last-seg)
         (Character/isUpperCase ^char (first last-seg)))))

(defn- source-replacement
  "Decides what a single matched body token `match` becomes under the rename
  `old-sym` -> `new-sym`. Handles each shape in turn: a bare namespace ref, a
  package/underscore prefix, a `^`-prefixed type hint, a dotted namespace/class
  reference (dashes become underscores for a class, see #73), and a
  `(load \"...\")` slash/underscore resource path (#61). Returns `match`
  unchanged when nothing applies."
  [old-sym new-sym match]
  (let [old-ns-ref      (name old-sym)
        new-ns-ref      (name new-sym)
        old-ns-ref-dot  (str old-ns-ref ".")
        new-ns-ref-dot  (str new-ns-ref ".")
        old-pkg-prefix  (java-package old-sym)
        new-pkg-prefix  (java-package new-sym)
        old-type-prefix (str "^" (java-package old-sym))
        new-type-prefix (str "^" (java-package new-sym))
        old-load-param  (load-param old-sym)
        new-load-param  (load-param new-sym)]
    (cond

      (= match old-ns-ref)
      new-ns-ref

      (and (str/starts-with? match old-pkg-prefix)
           (str/includes? match "_"))
      (str/replace match old-pkg-prefix new-pkg-prefix)

      (str/starts-with? match old-type-prefix)
      (str/replace match old-type-prefix new-type-prefix)

      (str/starts-with? match old-ns-ref-dot)
      (let [replaced (str/replace match old-ns-ref-dot new-ns-ref-dot)]
        ;; A fully-qualified class/record reference compiles to a Java package,
        ;; so any dash introduced by the new prefix must become an underscore.
        ;; A namespace/var reference keeps the dashes. See #73.
        (if (class-reference? match)
          (java-package replaced)
          replaced))

      ;; a `(load "…")` resource path, in slash/underscore form. See #61.
      (str/starts-with? match old-load-param)
      (str/replace match old-load-param new-load-param)

      :else
      match)))

(def ^:private symbol-regex
  ;; LispReader.java uses #"[:]?([\D&&[^/]].*/)?([\D&&[^/]][^/]*)" but
  ;; that's too broad; we don't want a whole namespace-qualified symbol,
  ;; just each part individually.
  #"\"?[a-zA-Z0-9$%*+=?!<>^_-]['.a-zA-Z0-9$%*+=?!<>_-]*")

(defn- replace-in-source [source-sans-ns old-sym new-sym]
  ;; `symbol-regex` never matches a `(load "…")` resource path (it stops at `/`),
  ;; so try the slash/underscore form of the namespace first. See #61.
  (let [regex (re-pattern (str (java.util.regex.Pattern/quote (load-param old-sym))
                               "|"
                               (.pattern ^java.util.regex.Pattern symbol-regex)))]
    (str/replace source-sans-ns regex (partial source-replacement old-sym new-sym))))

(defn- after-platform-marker? [platform node]
  (= platform (z/sexpr (z/left node))))

(defn- find-and-replace-platform-specific-subforms
  "In a `.cljc` ns form, masks the subforms belonging to `platform` (the opposite
  platform from the one being moved) by replacing each with a `<platform>_require`
  placeholder symbol, so the rename only touches the relevant branch. Returns
  `[replaced-nodes ns-loc]`; pair with `restore-platform-specific-subforms`."
  [platform ns-loc]
  (loop [loc         ns-loc
         found-nodes []]
    (if-let [found-node (zloc/find-next-depth-first loc (partial after-platform-marker? platform))]
      (recur (z/replace found-node (symbol (str (name platform) "_require"))) (conj found-nodes found-node))
      [found-nodes (z/of-node (z/root loc))])))

(defn- restore-platform-specific-subforms
  "Inverse of `find-and-replace-platform-specific-subforms`: substitutes each
  `<platform>_require` placeholder in the rewritten `ns-form` string back with the
  original `replaced-nodes`."
  [platform replaced-nodes ns-form]
  (loop [form             ns-form
         [n & rest-nodes] replaced-nodes]
    (if-not n
      form
      (recur (str/replace-first form (str (name platform) "_require") (z/string n)) rest-nodes))))

(defn replace-ns-symbol
  "ALPHA: subject to change. Given Clojure source as a file, replaces
  all occurrences of the namespace name old-sym with new-sym and
  returns modified source as a string.

  Splits the source file, parses the ns macro if found to do all the necessary
  transformations. Works on the body of namespace as text as simpler transformations
  are needed. When done puts the ns form and body back together."
  [content old-sym new-sym watermark extension-of-moved file-ext]
  (let [[ns-loc source-sans-ns] (split-ns-form-ns-body content)
        opposite-platform       (util/platform-comp (util/extension->platform extension-of-moved))
        [replaced-nodes ns-loc] (or (and ns-loc
                                         (= ".cljc" file-ext)
                                         opposite-platform
                                         (find-and-replace-platform-specific-subforms opposite-platform ns-loc))
                                    [[] ns-loc])

        new-ns-form             (replace-in-ns-form ns-loc old-sym new-sym watermark)
        new-source-sans-ns      (replace-in-source source-sans-ns old-sym new-sym)
        new-ns-form             (if (seq replaced-nodes)
                                  (restore-platform-specific-subforms opposite-platform replaced-nodes new-ns-form)
                                  new-ns-form)]
    (or
     (and
      new-ns-form
      (str/replace new-source-sans-ns ns-form-placeholder new-ns-form))
     new-source-sans-ns)))

(defn- apply-ns-rename-to-form
  "Applies one rename to a single ns form (given as a string), with the same cljc
  platform handling as `replace-ns-symbol`, and returns the rewritten ns-form
  string. The ns form is small, so reparsing it per rename is cheap - the
  expensive whole-file split happens once in `replace-ns-symbols`."
  [ns-form-str {:keys [old-sym new-sym watermark extension]} file-ext]
  (let [ns-loc             (z/of-string ns-form-str)
        opposite-platform  (util/platform-comp (util/extension->platform extension))
        [replaced ns-loc]  (or (and (= ".cljc" file-ext)
                                    opposite-platform
                                    (find-and-replace-platform-specific-subforms opposite-platform ns-loc))
                               [[] ns-loc])
        new-form           (replace-in-ns-form ns-loc old-sym new-sym watermark)]
    (if (seq replaced)
      (restore-platform-specific-subforms opposite-platform replaced new-form)
      new-form)))

(defn- ns-first-segments
  "The first path segment of a namespace, in both its namespace (dash) and
  package/path (underscore) spellings. Every token `source-replacement` could
  rewrite for a rename - a namespace ref, a fully-qualified class, a type hint or
  a `load` path - starts with one of these, so they're enough to index by."
  [old-sym]
  (let [seg (first (str/split (name old-sym) #"\."))]
    (cond-> #{seg}
      (str/includes? seg "-") (conj (str/replace seg "-" "_")))))

(defn- token-first-segment
  "The first segment of a matched token, ignoring a leading quote or type-hint
  caret, for lookup against `ns-first-segments`."
  [match]
  (-> match
      (str/replace #"^[\"^]+" "")
      (str/split #"[./]")
      first))

(defn- source-replacement-multi
  "Applies the first of `match`'s candidate renames that rewrites it. `by-segment`
  maps a first segment to the renames whose namespace starts with it; candidates
  are pre-sorted longest-namespace-first so a more specific prefix wins. Tokens
  whose first segment matches no rename (the vast majority) are returned as-is
  without touching any rename.

  A token that is a repackaged Java class (`java-classes`, the fully-qualified
  `.class` names) is left untouched even when its package is a moved namespace:
  it must keep pointing at the class, which jarjar relocates to a different
  prefix, so the java-import pass prefixes it instead. Deftype classes have no
  `.class` and so move with their namespace as usual. See #97."
  [by-segment java-classes match]
  (let [candidates (get by-segment (token-first-segment match))]
    (if (or (empty? candidates)
            ;; strip a leading quote/caret and a trailing dot (constructor form
            ;; `Class.`) so the bare class name is matched
            (contains? java-classes (-> match (str/replace #"^[\"^]+" "") (str/replace #"\.$" ""))))
      match
      (reduce (fn [_ {:keys [old-sym new-sym]}]
                (let [replaced (source-replacement old-sym new-sym match)]
                  (if (= replaced match) match (reduced replaced))))
              match
              candidates))))

(defn- replace-in-source-multi
  "Rewrites the ns body once for a whole batch of `renames`: a single regex scan
  whose replacement function dispatches by first segment to whichever rename
  matches each token, instead of one full scan per rename. References to
  repackaged Java classes (`java-classes`) are left for the java-import pass."
  [source-sans-ns renames java-classes]
  (if (empty? renames)
    source-sans-ns
    (let [by-segment (reduce (fn [m rename]
                               (reduce #(update %1 %2 (fnil conj []) rename)
                                       m (ns-first-segments (:old-sym rename))))
                             {} renames)
          regex      (re-pattern (str (->> renames
                                           (map #(java.util.regex.Pattern/quote (load-param (:old-sym %))))
                                           (str/join "|"))
                                      "|"
                                      (.pattern ^java.util.regex.Pattern symbol-regex)))]
      (str/replace source-sans-ns regex (partial source-replacement-multi by-segment java-classes)))))

(defn replace-ns-symbols
  "Applies a batch of `renames` to one file's `content` in a single pass: the ns
  form is parsed once and the body scanned once, rather than once per rename.
  `renames` is a seq of maps {:old-sym :new-sym :watermark :extension}. The
  result is equivalent to folding `replace-ns-symbol` over the renames, but
  O(file) instead of O(file x renames). `java-classes` (optional) are
  fully-qualified repackaged Java class names left untouched in the body."
  ([content renames file-ext]
   (replace-ns-symbols content renames file-ext #{}))
  ([content renames file-ext java-classes]
   (if (empty? renames)
     content
     ;; longest namespace first so e.g. `a.b.c` is preferred over `a.b` when both
     ;; could prefix-match the same token
     (let [renames       (sort-by #(- (count (name (:old-sym %)))) renames)
           [ns-loc body] (split-ns-form-ns-body content)
           new-ns-form   (when ns-loc
                           (reduce (fn [s rename] (apply-ns-rename-to-form s rename file-ext))
                                   (z/root-string ns-loc)
                                   renames))
           new-body      (replace-in-source-multi body renames java-classes)]
       (or (and new-ns-form (str/replace new-body ns-form-placeholder new-ns-form))
           new-body)))))

(defn move-ns-file
  "ALPHA: subject to change. Moves the .clj or .cljc source file (found relative
  to source-path) for the namespace named old-sym to a file for a
  namespace named new-sym.

  WARNING: This function moves and deletes your source files! Make
  sure you have a backup or version control."
  [old-sym new-sym extension source-path]
  (if-let [old-file (sym->file source-path old-sym extension)]
    (let [new-file (sym->file source-path new-sym extension)]
      (.mkdirs (.getParentFile new-file))
      (io/copy old-file new-file)
      (.delete old-file)
      (loop [dir (.getParentFile old-file)]
        (when-let [files (.listFiles dir)]
          (when (empty? files)
            (.delete dir)
            (recur (.getParentFile dir))))))
    (throw (FileNotFoundException. (format "file for %s not found in %s" old-sym source-path)))))

(def ^:private pmap-runner
  "Removing parallelism might alleviate https://github.com/benedekfazekas/mranderson/issues/56"
  (if (System/getProperty "mranderson.internal.no-parallelism")
    map
    pmap))

(defn- clojure-source-files-all
  "All Clojure source files (.clj/.cljc/.cljs) under `dirs`, normalized. Unlike
  `clojure-source-files` this doesn't filter by a single moved namespace's
  extension - the per-rename `update?` check is applied later, per file."
  [dirs]
  (->> dirs
       (map io/file)
       (filter #(.exists ^File %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (boolean (util/file->extension (str file))))))
       (mapv fs/normalized)))

(defn move-ns-files!
  "Moves the source file(s) for `old-sym` to `new-sym` WITHOUT rewriting any
  references (also moves a `.cljc` companion when moving a `.clj`/`.cljs`).

  WARNING: moves and deletes source files."
  [old-sym new-sym source-path extension]
  (move-ns-file old-sym new-sym extension source-path)
  ;; move cljc file with the platform specific file if exists
  (when (and (#{".clj" ".cljs"} extension) (.exists (sym->file source-path old-sym ".cljc")))
    (move-ns-file old-sym new-sym ".cljc" source-path)))

(defn- file-rename-counts
  "Counts, per rename, how many references in `content` the batch rewrite changes,
  for run reporting. Mirrors `source-replacement-multi`'s dispatch (a repackaged
  Java class is skipped; the longest-namespace prefix wins), so the tally matches
  what the actual rewrite does. Returns `{old-sym ref-count}` for renames that
  changed at least one reference."
  [content renames java-classes]
  (let [renames    (sort-by #(- (count (name (:old-sym %)))) renames)
        by-segment (reduce (fn [m rename]
                             (reduce #(update %1 %2 (fnil conj []) rename)
                                     m (ns-first-segments (:old-sym rename))))
                           {} renames)
        regex      (re-pattern (str (->> renames
                                         (map #(java.util.regex.Pattern/quote (load-param (:old-sym %))))
                                         (str/join "|"))
                                    "|"
                                    (.pattern ^java.util.regex.Pattern symbol-regex)))]
    (reduce (fn [counts match]
              (let [candidates (get by-segment (token-first-segment match))]
                (if (or (empty? candidates)
                        (contains? java-classes (-> match (str/replace #"^[\"^]+" "") (str/replace #"\.$" ""))))
                  counts
                  (if-let [matched (some (fn [{:keys [old-sym new-sym] :as rename}]
                                           (when (not= match (source-replacement old-sym new-sym match))
                                             rename))
                                         candidates)]
                    (update counts (:old-sym matched) (fnil inc 0))
                    counts))))
            {}
            (re-seq regex content))))

(defn- rewrite-file!
  "Applies the `applicable` renames to `file` in place (write skipped when nothing
  changes). Returns nil when unchanged, otherwise a report map `{:file ...
  :renames [{:old-sym :new-sym :refs n} ...]}` of the references rewritten."
  [^File file applicable file-ext java-classes]
  (let [old (slurp file)
        new (str (replace-ns-symbols old applicable file-ext java-classes))]
    (when (not= old new)
      (spit file new)
      (let [by-old (into {} (map (juxt :old-sym identity)) applicable)]
        {:file    file
         :renames (->> (file-rename-counts old applicable java-classes)
                       (map (fn [[old-sym refs]]
                              {:old-sym old-sym :new-sym (:new-sym (by-old old-sym)) :refs refs}))
                       (sort-by (comp str :old-sym))
                       vec)}))))

(defn replace-ns-symbols-in-source-files
  "Applies a batch of namespace renames to every Clojure source file under
  `dirs`, reading and writing each file at most once (in parallel, per file).

  `renames` is a seq of maps with `:old-sym`, `:new-sym`, `:extension` (the moved
  namespace's file extension) and `:watermark`. A rename is applied to a given
  file only when `update?` holds for that file and the rename's extension - the
  same per-rename behaviour as applying the renames one at a time, but without
  re-scanning the directories and re-reading every file once per rename.

  `java-classes` (optional) are fully-qualified repackaged Java class names that
  the rewrite must leave untouched (they are prefixed by the java-import pass).

  Returns a seq of per-file report maps for the files that changed (see
  `rewrite-file!`), used by the run report (#43)."
  ([renames dirs]
   (replace-ns-symbols-in-source-files renames dirs #{}))
  ([renames dirs java-classes]
   (when (seq renames)
     (let [files (clojure-source-files-all dirs)]
       (util/assert-no-duplicate-files files)
       (->> files
            (pmap-runner
             (fn [file]
               (let [file-ext   (util/file->extension (str file))
                     ;; only the renames whose moved-namespace extension applies to
                     ;; this file (the per-rename behaviour of `update?`)
                     applicable (filterv #(update? (str file) (:extension %)) renames)]
                 (when (seq applicable)
                   (rewrite-file! file applicable file-ext java-classes)))))
            (doall)
            (remove nil?))))))

(defn replace-ns-symbol-in-source-files
  "Replaces all occurrences of `old-sym` with `new-sym` in every Clojure source
  file under `dirs`. `extension` is the moved namespace's file extension (used to
  decide which files a rename applies to) and `watermark` the metadata key
  stamped on the renamed ns. A single-rename convenience wrapper over
  `replace-ns-symbols-in-source-files`."
  [old-sym new-sym extension dirs watermark]
  (replace-ns-symbols-in-source-files
   [{:old-sym old-sym :new-sym new-sym :extension extension :watermark watermark}]
   dirs))

(defn move-ns
  "ALPHA: subject to change. Moves the source file (of the given `extension`,
  relative to `source-path`) for namespace `old-sym` to `new-sym`, then replaces
  all references to the old name with the new name across every Clojure source
  file under `dirs`. The single-rename combination of `move-ns-files!` and
  `replace-ns-symbol-in-source-files`.

  WARNING: This function modifies and deletes your source files! Make
  sure you have a backup or version control."
  [old-sym new-sym source-path extension dirs watermark]
  (move-ns-files! old-sym new-sym source-path extension)
  (replace-ns-symbol-in-source-files old-sym new-sym extension dirs watermark))
