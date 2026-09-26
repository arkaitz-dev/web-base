(ns demo.main
  "Entry point: `clojure -M:demo [port]`, or `java -jar` on the uberjar built
  by `clojure -T:build demo-uber`. The session key comes from WB_SESSION_KEY,
  or from `env.local.edn` in the directory the process starts in when that
  variable is unset — the base refuses to invent one either way."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [demo.system]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]))

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

(defn -main [& args]
  (wbi/run! {:config    "config.edn"
             :env-file  env-file
             :port-path [:dev.arkaitz.web-base/server :port]
             :banner    #(str "Demo listening on http://localhost:" (get-in % [:dev.arkaitz.web-base/server :port]))}
            args))
