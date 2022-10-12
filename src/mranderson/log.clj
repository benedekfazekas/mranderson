(ns ^:no-doc mranderson.log
  "Here we replicate lein logging.
  This gives us what a lein plugin user would expect and is also reasonable for non-lein use.
  We don't use's lein's logging methods because we don't want to tie ourselves to lein just for logging.
  We also add in multi-threading support which lein logging does not do.")

;; lein-isms for controlling logging
(def ^:private log-info+? (not (System/getenv "LEIN_SILENT")))
(def ^:private log-debug? (System/getenv "DEBUG"))

;; a lock so (at least our) log line output do not get inter-mingled if we decide to log
;; from multi-threaded work
(def ^:private lock (Object.))

(defn info
  "Lein sends info to stdout, is silenced by LEIN_SILENT, so we do that too."
  [& args]
  (when log-info+?
    (locking lock
      (apply println args))))

(defn warn
  "Lein warns to stderr, is silenced by LEIN_SILENT, so we match that."
  [& args]
  (when log-info+?
    (locking lock
      (binding [*out* *err*]
        (apply println args)))))

(defn debug
  "Lein's debug is enabled by DEBUG, sent to stdout, and is unaffected by LEIN_SILENT,
  so we replicate that behaviour"
  [& args]
  (when log-debug?
    (locking lock
      (apply println args))))
