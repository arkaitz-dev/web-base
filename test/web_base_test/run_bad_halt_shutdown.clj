(ns web-base-test.run-bad-halt-shutdown
  "A host whose system cannot be halted, stopped by SIGTERM — run by integrant_test in a
  subprocess. Every tools.logging line goes to the file WB_TEST_LOG names, through the
  root of the logger factory, which is what a shutdown hook's thread reads."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [dev.arkaitz.web-base.integrant :as wbi]
            [web-base-test.run-keys]))

(defn- file-factory [path]
  (reify impl/LoggerFactory
    (name [_] "test-file")
    (get-logger [_ logger-ns]
      (reify impl/Logger
        (enabled? [_ _] true)
        (write! [_ level t message]
          (spit path (str (pr-str [(str logger-ns) level (some-> t ex-message) message]) "\n") :append true))))))

(defn -main [& args]
  (alter-var-root #'log/*logger-factory* (constantly (file-factory (System/getenv "WB_TEST_LOG"))))
  (wbi/run! {:config "run-bad-halt-shutdown-config.edn" :banner (constantly "up")} args))
