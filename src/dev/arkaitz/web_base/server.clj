(ns dev.arkaitz.web-base.server
  "Jetty behind two functions. Jetty lives entirely inside the base (SPEC
  §10): the host sees a handle map, never the server class. The bound port is
  in the handle because with `:port 0` there is no other way to learn it
  without Jetty's own API."
  (:require [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server Server ServerConnector]))

(defn start
  "Starts Jetty on `handler` and returns `{:server s :port n}`. Options are
  ring-jetty-adapter's, minus `:join?`, which is always false: a joining
  start blocks the caller forever, which is a hang, not a server."
  [handler {:keys [port] :as options}]
  ;; Jetty would silently take port 80 without one.
  (when-not (nat-int? port)
    (throw (ex-info "server options need a non-negative integer :port (0 for an ephemeral one)"
                    {:config-key [:port] :value port})))
  (let [server (jetty/run-jetty handler (assoc options :join? false))
        port   (.getLocalPort ^ServerConnector (first (.getConnectors ^Server server)))]
    {:server server :port port}))

(defn stop
  "Stops the server behind a handle returned by `start`."
  [{:keys [server]}]
  (.stop ^Server server))
