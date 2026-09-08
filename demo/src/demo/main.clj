(ns demo.main
  "Entry point: `clojure -M:demo [port]`, or `java -jar` on the uberjar built
  by `clojure -T:build demo-uber`. The session key comes from WB_SESSION_KEY,
  or from `env.local.edn` in the directory the process starts in when that
  variable is unset — the base refuses to invent one either way."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [demo.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig]))

(def env-file
  "Where the demo looks for the variables `config.edn` needs, so a development
  machine does not export them on every start. Out of the repository by
  `.gitignore`'s `*.local.edn`; absent, the environment is the only source."
  "env.local.edn")

(defn read-config
  ([] (read-config env-file))
  ([env-file]
   (wbi/read-string (config/env-file-readers env-file)
                    (slurp (io/resource "config.edn")))))

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
        (println (str "Invalid port: " port " (expected 1-65535)"))
        (System/exit 1))
      (let [system (ig/init config)]
        (println (str "Demo listening on http://localhost:" (get-in system [:dev.arkaitz.web-base/server :port])))
        ;; Jetty does not join, so the main thread would exit and take the
        ;; JVM down with it.
        (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
        @(promise)))))
