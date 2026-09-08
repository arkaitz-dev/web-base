(ns demo.main
  "Entry point: `clojure -M:demo [port]`. Needs WB_SESSION_KEY in the
  environment — the base refuses to invent one."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [demo.system]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig]))

(defn read-config []
  (wbi/read-string (slurp (io/resource "config.edn"))))

(defn- with-port
  "The config with the port replaced, or nil when the argument is not a
  usable port number."
  [config port]
  (when-let [n (parse-long port)]
    (when (< 0 n 65536)
      (assoc-in config [:dev.arkaitz.web-base/server :port] n))))

(defn -main [& [port]]
  (let [config (if port (with-port (read-config) port) (read-config))]
    (if-not config
      (binding [*out* *err*]
        (println (str "Invalid port: " port " (usage: clojure -M:demo [port])"))
        (System/exit 1))
      (let [system (ig/init config)]
        (println (str "Demo listening on http://localhost:" (get-in system [:dev.arkaitz.web-base/server :port])))
        ;; Jetty does not join, so the main thread would exit and take the
        ;; JVM down with it.
        (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
        @(promise)))))
