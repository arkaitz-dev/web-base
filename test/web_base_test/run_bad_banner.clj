(ns web-base-test.run-bad-banner
  "A host whose banner throws once the system has started — run by integrant_test in a
  subprocess. The marker file's path comes from the environment."
  (:require [dev.arkaitz.web-base.integrant :as wbi]
            [web-base-test.run-keys]))

(defn -main [& args]
  (wbi/run! {:config "run-halting-config.edn"
             :banner (fn [_] (throw (ex-info "the banner could not format the port" {})))}
            args))
