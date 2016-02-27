;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Refactoring tool to move a Clojure namespace from one name/file to
  another, and update all references to that namespace in your other
  Clojure source files.

  WARNING: This code is ALPHA and subject to change. It also modifies
  and deletes your source files! Make sure you have a backup or
  version control.

  DISCLAIMER
  This is patched version of Stuart Siearra's original to handle cljc files

"}
  mranderson.move
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [mranderson.util :as util])
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

(defn- sym->file-name
  [sym]
  (-> (name sym)
      (str/replace "-" "_")
      (str/replace "." File/separator)))

(defn- sym->file
  [path sym extension]
  (io/file path (str (sym->file-name sym) extension)))

(defn- update? [file extension-of-moved]
  (let [file-ext (util/file->extension file)
        all-extensions #{".cljc" ".cljs" ".clj"}]
    (or
     (and (= ".cljc" extension-of-moved)
          (all-extensions file-ext))
     (= file-ext extension-of-moved)
     (= file-ext ".cljc"))))

(defn- clojure-source-files [dirs extension]
  (->> dirs
       (map io/file)
       (filter #(.exists ^File %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (update? (str file) extension))))
       (map #(.getCanonicalFile ^File %))))

(def ^:private symbol-regex
  ;; LispReader.java uses #"[:]?([\D&&[^/]].*/)?([\D&&[^/]][^/]*)" but
  ;; that's too broad; we don't want a whole namespace-qualified symbol,
  ;; just each part individually.
  #"[a-zA-Z0-9$%*+=?!<>_-]['.a-zA-Z0-9$%*+=?!<>_-]*")

(defn replace-ns-symbol
  "ALPHA: subject to change. Given Clojure source as a string, replaces
  all occurrences of the namespace name old-sym with new-sym and
  returns modified source as a string."
  [source old-sym new-sym]
  (let [old-name (name old-sym)
        new-name (name new-sym)]
    ;; A lossless parser would be better, but this is adequate
    (str/replace source symbol-regex
                 (fn [match]
                   (if (= match old-name)
                     new-name
                     match)))))

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
        (when (empty? (.listFiles dir))
          (.delete dir)
          (recur (.getParentFile dir)))))
    (throw (FileNotFoundException. (format "file for %s not found in %s" old-sym source-path)))))

(defn move-ns
  "ALPHA: subject to change. Moves the .clj or .cljc source file (found relative
  to source-path) for the namespace named old-sym to new-sym and
  replace all occurrences of the old name with the new name in all
  Clojure source files found in dirs.

  This is a purely textual transformation. It does not work on
  namespaces require'd or use'd from a prefix list.

  WARNING: This function modifies and deletes your source files! Make
  sure you have a backup or version control."
  [old-sym new-sym source-path extension dirs]
  (move-ns-file old-sym new-sym extension source-path)
  (doseq [file (clojure-source-files dirs extension)]
    (update-file file replace-ns-symbol old-sym new-sym)))
