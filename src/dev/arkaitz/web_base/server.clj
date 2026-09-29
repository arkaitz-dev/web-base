(ns dev.arkaitz.web-base.server
  "Jetty behind two functions. Jetty lives entirely inside the base (SPEC
  §10): the host sees a handle map, never the server class. The bound port is
  in the handle because with `:port 0` there is no other way to learn it
  without Jetty's own API."
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent ExecutorService TimeoutException]
           [org.eclipse.jetty.server HttpConnectionFactory Server ServerConnector]
           [org.eclipse.jetty.server.handler GracefulHandler]
           [org.eclipse.jetty.util BlockingArrayQueue VirtualThreads]
           [org.eclipse.jetty.util.thread QueuedThreadPool]))

(def default-stop-timeout-ms
  "How long `stop` lets requests already in flight finish. Jetty's own default is 0,
  which cuts them, so this is the base's policy (decided with the user 2026-09-27):
  well inside the grace period Kubernetes (30 s) and systemd (90 s) give a process
  before killing it, with room left for the rest of a system's halt."
  10000)

(def ^:private default-options
  "What the base puts under the host's options. An idle connection is closed after 30 s —
  Jetty's own default, which ring-jetty-adapter raises to 200 s, so eight thousand
  clients trickling a body held 367 MB for over three minutes (measured) — and no
  response names the server's version."
  {:max-idle-time 30000 :send-server-version? false})

(def ^:private min-request-data-rate
  "Bytes a second below which Jetty gives up on a request body: Apache httpd's own
  `mod_reqtimeout` MinRate. An idle timeout alone cannot end a client that sends one
  byte just inside it; this does. Averaged from the first byte, so a legitimate upload's
  pauses pass. A host that must take slower clients lowers it in its `:configurator`."
  500)

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
    (doseq [connector (.getConnectors server)
            factory   (.getConnectionFactories connector)
            :when     (instance? HttpConnectionFactory factory)]
      (.setMinRequestDataRate (.getHttpConfiguration ^HttpConnectionFactory factory) min-request-data-rate))
    (when host-configurator (host-configurator server))))

(defn- virtual-thread-pool
  "ring-jetty-adapter's own pool, built from the same options and defaults
  (`create-threadpool` in ring.adapter.jetty 1.15.5, which is private), with every
  request run on a virtual thread of its own. Why, measured 2026-09-28 on Jetty 12.1.8,
  HikariCP 7.1.0 and PostgreSQL 18.6: with platform threads a request waiting on a full
  connection pool holds its thread, so once as many connections are busy as there are
  threads — 50 by default — a new connection is not even accepted until the load drops,
  and a health probe from a balancer times out on an instance that is merely busy (4 to
  27 s at 48 to 200 connections). On virtual threads the waiting request parks, the
  probe answers in milliseconds at 1000 connections, and an overload arrives as the
  pool's own timeout — a logged 500, a 503 from a health check — instead of silence."
  [options executor]
  (let [min-threads         (options :min-threads 8)
        max-threads         (options :max-threads 50)
        queue-max-capacity  (-> (options :max-queued-requests Integer/MAX_VALUE) (max 8))
        queue-capacity      (-> min-threads (max 8) (min queue-max-capacity))
        pool                (QueuedThreadPool. (int max-threads)
                                               (int min-threads)
                                               (int (options :thread-idle-timeout 60000))
                                               (BlockingArrayQueue. (int queue-capacity)
                                                                    (int queue-capacity)
                                                                    (int queue-max-capacity)))]
    (when (:daemon? options false)
      (.setDaemon pool true))
    (doto pool
      (.setVirtualThreadsExecutor executor))))

(defn- thread-pool
  "The pool the server runs on: the host's `:thread-pool` when it gives one, virtual
  threads unless `:virtual-threads?` is false, and ring-jetty-adapter's platform
  threads otherwise."
  [{:keys [thread-pool virtual-threads?] :as options}]
  (when-not (contains? #{nil true false} virtual-threads?)
    (throw (ex-info "web-base: server option :virtual-threads? must be true or false"
                    {:config-key [:virtual-threads?] :value virtual-threads?})))
  (when (and thread-pool (true? virtual-threads?))
    (throw (ex-info "web-base: server options :thread-pool and :virtual-threads? true contradict each other; the host's pool decides its threads"
                    {:config-key [:virtual-threads?]})))
  (cond
    thread-pool               thread-pool
    (false? virtual-threads?) nil
    ;; Jetty looks the executor up by reflection and answers nil where there is none,
    ;; so this namespace loads on a JVM without virtual threads and says so here; a
    ;; direct call to the JDK 21 method would fail to compile there instead.
    ;; An executor of this server's own, never Jetty's JVM-wide default: `stop` shuts it
    ;; down, which interrupts a request that outlived the window as the platform pool's
    ;; stop does. The shared default cannot be shut down, and a handler parked on it
    ;; ran on after stop returned, after the system it belonged to had halted (measured).
    :else (if-let [executor (VirtualThreads/getNamedVirtualThreadsExecutor "wb-request-")]
            (virtual-thread-pool options executor)
            (throw (ex-info "web-base: this JVM has no virtual threads; run on JDK 21 or later, or set :virtual-threads? false"
                            {:config-key [:virtual-threads?]})))))

(def ^:private executor-attribute
  "Where the server keeps the executor `start` built for it, for `stop` to shut down."
  "dev.arkaitz.web-base.server/executor")

(defn start
  "Starts Jetty on `handler` and returns `{:server s :port n}`. Options are
  ring-jetty-adapter's, minus `:join?`, which is always false: a joining
  start blocks the caller forever, which is a hang, not a server. Plus
  `:stop-timeout-ms`, how long `stop` lets requests in flight finish while it refuses
  new ones — `default-stop-timeout-ms` when absent or nil, 0 to cut them; and
  `:virtual-threads?`, true unless false: each request on a virtual thread of its own,
  see `virtual-thread-pool` for why and what it costs. A host's own `:thread-pool` is
  used as given. A host's own `:configurator` still runs, after the base's."
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
    (let [pool   (thread-pool options)
          server (jetty/run-jetty handler (cond-> (-> (merge default-options (into {} (remove (comp nil? val)) options))
                                                      (dissoc :stop-timeout-ms :virtual-threads?)
                                                      (assoc :join? false
                                                             :configurator (draining window configurator)))
                                            pool (assoc :thread-pool pool)))
          port   (.getLocalPort ^ServerConnector (first (.getConnectors ^Server server)))]
      (when (and pool (not (:thread-pool options)))
        (.setAttribute ^Server server executor-attribute (.getVirtualThreadsExecutor ^QueuedThreadPool pool)))
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
      (log/warn "requests still running when the stop window closed were cut (:stop-timeout-ms)"))
    (finally
      ;; The window has closed: a request still running on a virtual thread is
      ;; interrupted here, as the platform pool's stop interrupts its own threads.
      (when-let [executor (.getAttribute ^Server server executor-attribute)]
        (.shutdownNow ^ExecutorService executor))))
  nil)
