(ns user
  "REPL workflow for the demo: (go) starts the system, (reset) reloads changed
  namespaces and restarts it, (halt) stops it. Needs WB_SESSION_KEY in the
  environment."
  (:require [demo.main :as main]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]))

(ig-repl/set-prep! main/read-config)

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)

(defn store
  "The running store component, handy for poking at state from the REPL."
  []
  (:demo/store state/system))
