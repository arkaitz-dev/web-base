(ns web-base-test.run-halting
  "A host that starts, says so, and is halted by SIGTERM — run by integrant_test in a
  subprocess. The marker file's path comes from the environment."
  (:require [dev.arkaitz.web-base.integrant :as wbi]
            [web-base-test.run-keys]))

(defn -main [& args]
  (wbi/run! {:config "run-halting-config.edn" :banner (constantly "up")} args))
