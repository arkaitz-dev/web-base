(ns dev.arkaitz.web-base.server
  "Jetty behind two functions. Jetty lives entirely inside the base (SPEC
  §10): the host sees a handle map, never the server class. The bound port is
  in the handle because with `:port 0` there is no other way to learn it
  without Jetty's own API."
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent TimeoutException]
           [org.eclipse.jetty.server Server ServerConnector]
           [org.eclipse.jetty.server.handler GracefulHandler]))

(def default-stop-timeout-ms
  "How long `stop` lets requests already in flight finish. Jetty's own default is 0,
  which cuts them, so this is the base's policy (decided with the user 2026-09-27):
  well inside the grace period Kubernetes (30 s) and systemd (90 s) give a process
  before killing it, with room left for the rest of a system's halt."
  10000)

(defn- draining
  "A configurator that puts a `GracefulHandler` around the handler the adapter
  installed and gives Jetty the window, then runs the host's own, if any. The window is
  what makes stop wait for a request in flight; the graceful handler is what answers a
  request arriving meanwhile on a connection already open with a 503 instead of starting
  work the window may then cut (both measured on Jetty 12.1.8)."
  [stop-timeout-ms host-configurator]
  (fn [^Server server]
    (.setHandler server (GracefulHandler. (.getHandler server)))
    (.setStopTimeout server (long stop-timeout-ms))
    (when host-configurator (host-configurator server))))

(defn start
  "Starts Jetty on `handler` and returns `{:server s :port n}`. Options are
  ring-jetty-adapter's, minus `:join?`, which is always false: a joining
  start blocks the caller forever, which is a hang, not a server. Plus
  `:stop-timeout-ms`, how long `stop` lets requests in flight finish while it refuses
  new ones — `default-stop-timeout-ms` when absent or nil, 0 to cut them. A host's own
  `:configurator` still runs, after the base's."
  [handler {:keys [port configurator stop-timeout-ms] :as options}]
  ;; Jetty would silently take port 80 without one.
  (when-not (nat-int? port)
    (throw (ex-info "web-base: server options need a non-negative integer :port (0 for an ephemeral one)"
                    {:config-key [:port] :value port})))
  ;; An explicit nil is a setting that was absent where the map was assembled, as the
  ;; handler's :csrf reads it: the policy, never a refusal.
  (let [window (if (nil? stop-timeout-ms) default-stop-timeout-ms stop-timeout-ms)]
    (when-not (and (nat-int? window) (<= window Integer/MAX_VALUE))
      (throw (ex-info "web-base: server option :stop-timeout-ms must be an integer from 0 to 2147483647 (milliseconds)"
                      {:config-key [:stop-timeout-ms] :value window})))
    (let [server (jetty/run-jetty handler (-> options
                                              (dissoc :stop-timeout-ms)
                                              (assoc :join? false
                                                     :configurator (draining window configurator))))
          port   (.getLocalPort ^ServerConnector (first (.getConnectors ^Server server)))]
      {:server server :port port})))

(defn stop
  "Stops the server behind a handle returned by `start`: new connections are refused as
  the connector closes — within tens of milliseconds, measured — and requests in flight
  get the handle's window to finish. Returns nil.

  Jetty answers a window that ran out by throwing a `TimeoutException` from its stop,
  with the connector already closed and its threads already gone (measured). That is
  the policy working, not a failure to stop, so it is logged and swallowed here: thrown,
  it would abort the halt of everything else a system holds."
  [{:keys [server]}]
  (try
    (.stop ^Server server)
    (catch TimeoutException _
      (log/warn "requests still running when the stop window closed were cut (:stop-timeout-ms)")))
  nil)
