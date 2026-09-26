(ns web-base-test.run-failing
  "A host whose system fails to start, run by integrant_test in a subprocess."
  (:require [dev.arkaitz.web-base.integrant :as wbi]
            [web-base-test.run-keys]))

(defn -main [& args]
  (wbi/run! {:config "run-failing-config.edn" :banner (constantly "never printed")} args))
