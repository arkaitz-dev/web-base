(ns dev.arkaitz.web-base.server-test
  "One real Jetty, on port 0, through the assembled handler. Every request has
  a timeout and the server is stopped in a finally: a hang is never a result."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.adapter.jetty :as jetty])
  (:import [clojure.lang ExceptionInfo]
           [java.io IOException]
           [java.net ConnectException URI]
           [java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers HttpTimeoutException]
           [java.time Duration]
           [org.eclipse.jetty.server Server ServerConnector]
           [org.eclipse.jetty.server.handler GracefulHandler]
           [org.eclipse.jetty.util.component LifeCycle]
           [org.eclipse.jetty.util.thread QueuedThreadPool]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private id-pattern #"[A-Za-z0-9_-]{16}")

(def ^:private app
  (wb/handler {:routes     [["/me"    {:get (fn [r] {:status 200 :body (pr-str (:session r))})}]
                            ["/login" {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user "ann"}))}]]
               :session    {:key KEY :cookie-attrs {:secure false}}
               :static     {:root "public"}
               :csrf       false}))

(def ^:private client
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- http [port method path & headers]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path)))
                    (.timeout (Duration/ofSeconds 5))
                    (.method method (HttpRequest$BodyPublishers/noBody)))]
    (doseq [[k v] (partition 2 headers)] (.header builder k v))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status  (.statusCode response)
       :body    (.body response)
       :headers (into {} (map (fn [[k v]] [k (vec v)])) (.map (.headers response)))})))

(deftest server-start-binds-a-real-port-round-trips-http-and-stop-refuses-connections
  ;; Under a future with a deadline: a start that joined would be a red here,
  ;; never a hang of the whole run.
  (let [handle (deref (future (server/start app {:port 0})) 10000 ::timeout)
        port   (:port handle)]
    (is (map? handle) "start returned a handle within the deadline")
    (try
      (is (= #{:server :port} (set (keys handle))))
      (is (instance? Server (:server handle)))
      (is (pos-int? port))
      (is (= port (.getLocalPort ^ServerConnector (first (.getConnectors ^Server (:server handle)))))
          "the port is the connector's, not the option's — port 0 proves it")
      (let [login  (http port "POST" "/login")
            ;; The client's response map is not a Ring request: only the header value is borrowed.
            cookie (get-in (testing/with-cookies {} login) [:headers "cookie"])]
        (is (= [200 "in"] [(:status login) (:body login)]))
        (is (some? cookie) "a session cookie came over the wire")
        (let [me (http port "GET" "/me" "Cookie" cookie)]
          (is (= [200 "{:user \"ann\"}"] [(:status me) (:body me)]) "the cookie round-trips through a real socket")
          (is (re-matches id-pattern (str (first (get-in me [:headers "x-request-id"])))) "X-Request-Id over the wire")))
      (let [asset (http port "GET" "/host.txt")]
        (is (= [200 "HOST-ASSET\n" ["nosniff"]] [(:status asset) (:body asset) (get-in asset [:headers "x-content-type-options"])])
            "a static asset with the security headers"))
      (is (= 404 (:status (http port "GET" "/nope"))))
      (finally
        (when (map? handle) (server/stop handle))))
    (is (= :refused (try (http port "GET" "/me") (catch ConnectException _ :refused)))
        "after stop the port refuses connections")))

(deftest start-never-joins--a-host-asking-for-join-still-gets-its-handle-back
  (let [seen   (atom nil)
        real   jetty/run-jetty
        handle (with-redefs [jetty/run-jetty (fn [handler options] (reset! seen options) (real handler options))]
                 (deref (future (server/start app {:port 0 :join? true})) 10000 ::timeout))]
    (try
      (is (map? handle) "start returned instead of joining")
      (is (false? (:join? @seen)) "run-jetty was told not to join")
      (finally
        (when (map? handle) (server/stop handle))))))

;; --- the stop window ---------------------------------------------------------------
;;
;; Measured on Jetty 12.1.8, and the reason for each shape below: a request cut while
;; parked is seen by the client as an IOException only on a POST — HttpClient retries an
;; idempotent GET against the port that has just closed; a new connection may still be
;; answered for some tens of milliseconds after stop begins, so refusal is polled for,
;; each attempt a fresh client and a fresh TCP connect; and stop's duration is no witness
;; of waiting, because stopping a platform thread pool under a parked handler takes
;; ~2.5 s with no window at all (virtual threads, the default since 0.9.0, stop in ms). The only derived bound on it is the upper one: less than the default
;; window, when a shorter one was asked for.

(defn- until
  "Polls `pred` every 10 ms; answers whether it held within `ms` — a hang guard."
  [ms pred]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 10) (recur))))))

(defn- parking-app
  "`/park` blocks until `release` is delivered, recording how it ended in `trace`; a
  stop whose window closes interrupts it."
  [entered release trace]
  (fn [{:keys [uri]}]
    (if (= uri "/park")
      (let [_       (deliver entered true)
            outcome (try (deref release) :released (catch InterruptedException _ :interrupted))]
        (swap! trace conj [:handler outcome])
        {:status 200 :body "done"})
      {:status 200 :body "fast"})))

(defn- post-park
  "The parked POST, as a value: `[status body]`, or the Throwable the client got."
  [port]
  (try (let [r (http port "POST" "/park")] [(:status r) (:body r)])
       (catch Throwable t t)))

(defn- fresh-get
  "One GET on a client of its own — a new TCP connection, never a kept-alive one."
  [port]
  (let [c (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1)
              (.connectTimeout (Duration/ofSeconds 2)) (.build))]
    (try (let [r (.send c (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/fast")))
                              (.timeout (Duration/ofSeconds 5)) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
           [(.statusCode r) (.body r)])
         (catch ConnectException _ :refused))))

(defn- answers-until-refused
  "What fresh connections got until one was refused, within a 5 s guard the pass
  criterion does not depend on: `[answers :refused]`, or `[answers :still-open]`."
  [port]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop [answers []]
      (let [a (fresh-get port)]
        (cond (= :refused a)                           [answers :refused]
              (> (System/currentTimeMillis) deadline)  [(conj answers a) :still-open]
              :else                                    (recur (conj answers a)))))))

(defn- cut? [x] (and (instance? IOException x) (not (instance? HttpTimeoutException x))))

(deftest a-request-in-flight-finishes-with-200-while-new-connections-are-refused-and-stop-waits-for-it
  (let [entered (promise) release (promise) trace (atom [])
        handle  (server/start (parking-app entered release trace) {:port 0})
        port    (:port handle)]
    (try
      (is (= [200 "fast"] (fresh-get port)) "precondition: the port answers before stop")
      (is (= 10000 (.getStopTimeout ^Server (:server handle))) "the default window, the base's declared policy, is in force")
      (lt/with-log
        (let [response (future (post-park port))
              _        (is (true? (deref entered 5000 ::timeout)) "precondition: a request is in flight")
              stopping (future (let [r (try (server/stop handle) (catch Throwable t t))] (swap! trace conj :stop-returned) r))
              [answers outcome] (answers-until-refused port)]
          (is (= :refused outcome) (str "new connections are refused while the request drains, got " (last answers)))
          ;; Jetty's graceful handler starts refusing a moment before the connector closes,
          ;; so a connection accepted in between is answered 503 (measured: 2 runs in 40).
          ;; Whatever answered did so whole — served before the drain began, or refused by it
          ;; — and never served again once it had refused.
          (is (re-matches #"(200 )*(503 )*" (apply str (map #(str (first %) " ") answers)))
              (str "before the connector closed, whole 200s and then only 503s: " (mapv first answers)))
          (is (every? #(or (= [200 "fast"] %) (= 503 (first %))) answers)
              (str "a 200 is the handler's own answer: " (mapv first answers)))
          ;; Not the witness of waiting on its own — a window of 0 is still pending here,
          ;; stopping the pool under a parked handler — the 200 and the trace below are.
          (is (= ::pending (deref stopping 0 ::pending)) "stop is still pending while the request is in flight")
          (deliver release true)
          (is (= [200 "done"] (deref response 10000 ::timeout))
              "the request parked when stop began finished with its 200 — a cut would be an IOException")
          (is (nil? (deref stopping 30000 ::timeout)) "stop returned nil once it had drained")
          (is (= [[:handler :released] :stop-returned] @trace) "and only after the handler finished")
          (is (= [] (lt/the-log)) "a window that sufficed warns of nothing")))
      (is (.isStopped ^Server (:server handle)) "the server is stopped")
      (finally
        (deliver release true)
        (server/stop handle)))))

(defn- outliving-the-window
  "A parked request and a stop with `window`, the handler never released until the end:
  what the client got, what stop returned, how long it took, the log, and the handle."
  [window]
  (let [entered (promise) release (promise) trace (atom [])
        handle  (server/start (parking-app entered release trace) {:port 0 :stop-timeout-ms window})
        port    (:port handle)]
    (try
      (let [in-force (.getStopTimeout ^Server (:server handle))
            response (future (post-park port))
            parked   (deref entered 5000 ::timeout)]
        (lt/with-log
          (let [t0       (System/currentTimeMillis)
                stopping (future (try (server/stop handle) (catch Throwable t t)))
                stopped  (deref stopping 30000 ::timeout)
                elapsed  (- (System/currentTimeMillis) t0)]
            {:in-force in-force :parked parked :stopped stopped :elapsed elapsed
             :response (deref response 10000 ::timeout)
             :log (mapv (juxt :level (comp str :logger-ns) :throwable :message) (lt/the-log))
             :server (:server handle) :port port :trace trace})))
      (finally
        (deliver release true)
        (server/stop handle)))))

(defn- raw-get
  "A GET written on `socket` by hand, so it goes over that connection and no other:
  the status line, or the name of what the read threw."
  [^java.net.Socket socket path]
  (let [w (java.io.PrintWriter. (.getOutputStream socket) true)
        r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream socket)))]
    (.print w (str "GET " path " HTTP/1.1\r\nHost: localhost\r\n\r\n"))
    (.flush w)
    (try (let [status (.readLine r)]
           (loop [] (let [l (.readLine r)] (when (and l (not= "" l)) (recur))))
           status)
         (catch Exception e (.getName (class e))))))

(deftest a-request-on-a-connection-already-open-while-draining-is-answered-503-not-served
  (let [entered (promise) release (promise) trace (atom [])
        handle  (server/start (parking-app entered release trace) {:port 0})
        port    (:port handle)]
    (with-open [kept (doto (java.net.Socket. "127.0.0.1" (int port)) (.setSoTimeout 10000))]
      (try
        (is (= "HTTP/1.1 200 OK" (raw-get kept "/fast")) "precondition: the kept-alive connection is served")
        (let [response (future (post-park port))
              _        (is (true? (deref entered 5000 ::timeout)) "precondition: a request is in flight")
              stopping (future (server/stop handle))]
          (is (= :refused (second (answers-until-refused port))) "precondition: the drain has begun")
          (is (= "HTTP/1.1 503 Service Unavailable" (raw-get kept "/fast"))
              "a request on a connection opened before stop is refused with a 503, not served while the server drains")
          (deliver release true)
          (is (= [200 "done"] (deref response 10000 ::timeout)) "while the one in flight still finishes")
          (is (nil? (deref stopping 30000 ::timeout))))
        (finally
          (deliver release true)
          (server/stop handle))))))

(deftest a-request-that-outlives-the-window-is-cut-stop-returns-nil-and-warns-once
  (let [{:keys [in-force parked stopped elapsed response log server port trace]} (outliving-the-window 1000)]
    (is (= 1000 in-force) "the window asked for is the one in force")
    (is (true? parked) "precondition: a request was in flight")
    (is (nil? stopped) (str "stop swallowed the window running out and returned nil, got " (pr-str stopped)))
    (is (< elapsed 10000) (str "stop took " elapsed " ms, less than the default window: the option was honoured"))
    (is (cut? response) (str "the request that outlived the window was cut, got " (pr-str response)))
    (is (= [[:warn "dev.arkaitz.web-base.server" nil
             "requests still running when the stop window closed were cut (:stop-timeout-ms)"]]
           log)
        "exactly one WARN says so")
    (is (not (.isRunning ^Server server)) "the server is down")
    (is (.isStopped ^LifeCycle (.getThreadPool ^Server server)) "its threads are gone")
    (is (until 5000 #(= [[:handler :interrupted]] @trace))
        (str "and the handler was interrupted, not left running after stop returned: " (pr-str @trace)))
    (is (= :refused (fresh-get port)) "and its port refuses")))

(deftest stop-timeout-ms-zero-does-not-wait-for-a-parked-request-and-warns-nothing
  ;; "At once" is not a number: stopping a platform pool under a parked handler takes ~2.5 s.
  ;; A missing setStopTimeout is also a window of 0, so this row cannot catch it; the
  ;; two tests above do.
  (let [{:keys [in-force parked stopped elapsed response log server port trace]} (outliving-the-window 0)]
    (is (= 0 in-force) "a window of 0 is in force")
    (is (true? parked) "precondition: a request was in flight")
    (is (nil? stopped) (str "stop returned nil, got " (pr-str stopped)))
    (is (< elapsed 10000) (str "stop took " elapsed " ms, less than the default window: 0 was not read as absent"))
    (is (cut? response) (str "the parked request was cut, got " (pr-str response)))
    (is (= [] log) "no window, so nothing ran out and nothing is logged")
    (is (.isStopped ^Server server) "the server is stopped")
    (is (until 5000 #(= [[:handler :interrupted]] @trace))
        (str "and the handler was interrupted, not left running after stop returned: " (pr-str @trace)))
    (is (= :refused (fresh-get port)) "and its port refuses")))

(deftest stop-timeout-ms-outside-0-to-max-int-is-refused-naming-the-key-before-anything-opens
  (let [calls (atom 0)]
    (with-redefs [jetty/run-jetty (fn [& _] (swap! calls inc) (throw (IllegalStateException. "never reached")))]
      (doseq [bad [-1 1.5 "1000" 2147483648]]
        (is (= {:config-key [:stop-timeout-ms] :value bad}
               (try (server/start app {:port 0 :stop-timeout-ms bad}) ::started
                    (catch ExceptionInfo e (ex-data e))))
            (str (pr-str bad) " is refused naming [:stop-timeout-ms]"))))
    (is (= 0 @calls) "refused before Jetty was reached"))
  (doseq [[given in-force] [[nil 10000] [2147483647 2147483647]]]
    (let [handle (try (server/start app {:port 0 :stop-timeout-ms given})
                      (catch ExceptionInfo e (ex-data e)))]
      (is (instance? Server (:server handle)) (str (pr-str given) " is accepted, got " (pr-str handle)))
      (when (instance? Server (:server handle))
        (try
          (is (= in-force (.getStopTimeout ^Server (:server handle)))
              (str (pr-str given) " puts " in-force " in force"))
          (finally
            (is (nil? (deref (future (server/stop handle)) 5000 ::timeout))
                "and with nothing in flight stop does not wait out the window")))))))

(deftest a-host-configurator-runs-once-after-the-bases-on-the-server-in-the-handle
  (let [seen   (atom [])
        handle (server/start app {:port 0 :stop-timeout-ms 4242
                                  :configurator (fn [s] (swap! seen conj {:handler (class (.getHandler ^Server s))
                                                                          :window  (.getStopTimeout ^Server s)
                                                                          :server  s}))})]
    (try
      (is (= 1 (count @seen)) "the host's configurator ran once")
      (is (= [GracefulHandler 4242] ((juxt :handler :window) (first @seen)))
          "after the base's: it saw the graceful handler and the window already in place")
      (is (identical? (:server (first @seen)) (:server handle)) "on the server the handle holds")
      (finally
        (server/stop handle)))))

(deftest a-stop-failure-that-is-not-the-window-running-out-reaches-the-caller
  (let [s (proxy [Server] [] (doStop [] (proxy-super doStop) (throw (IllegalStateException. "boom"))))]
    (.start s)
    (is (.isRunning s) "precondition: started, or stop returns without calling doStop")
    (is (thrown-with-msg? IllegalStateException #"^boom$" (server/stop {:server s}))
        "only the window running out is swallowed")
    (is (not (.isRunning s)))))

;; --- the threads a request runs on ------------------------------------------------

(defn- who-app [{:keys [uri]}]
  (if (= uri "/who")
    {:status 200 :body (pr-str [(.isVirtual (Thread/currentThread)) (.getName (Thread/currentThread))])}
    {:status 404 :body ""}))

(defn- who-answers
  "Sixteen concurrent GETs of /who: contention is where a platform thread could slip in."
  [port]
  (mapv #(read-string (:body (deref % 10000 {:body "[:timeout nil]"})))
        (doall (repeatedly 16 #(future (http port "GET" "/who"))))))

(defn- with-server [options f]
  (let [handle (server/start who-app (assoc options :port 0))]
    (try (f handle (.getThreadPool ^Server (:server handle)))
         (finally (server/stop handle)))))

(deftest the-handler-runs-on-a-virtual-thread-by-default--false-restores-platform-threads-and-a-host-pool-is-used-as-given
  (with-server {}
    (fn [{:keys [port]} pool]
      (let [answers (who-answers port)]
        (is (= {true 16} (frequencies (map first answers))) (str "by default every request ran on a virtual thread, got " (frequencies answers)))
        (is (some? (.getVirtualThreadsExecutor ^QueuedThreadPool pool)) "on a pool with a virtual-thread executor"))))
  (with-server {:virtual-threads? false}
    (fn [{:keys [port]} pool]
      (is (= {false 16} (frequencies (map first (who-answers port)))) ":virtual-threads? false runs on ring's platform threads")
      (is (nil? (.getVirtualThreadsExecutor ^QueuedThreadPool pool)) "on a pool without the executor")))
  (let [host (doto (QueuedThreadPool. 20) (.setName "host-qtp"))]
    (with-server {:thread-pool host}
      (fn [{:keys [port]} pool]
        (let [answers (who-answers port)]
          (is (identical? host pool) ":thread-pool is used as given: the server's pool is the host's object")
          (is (= {false 16} (frequencies (map first answers))) "on its platform threads")
          (is (every? #(str/starts-with? (second %) "host-qtp-") answers) (str "the host's, by name: " (mapv second answers)))))))
  (let [host (QueuedThreadPool. 20)]
    (with-server {:thread-pool host :virtual-threads? false}
      (fn [_ pool] (is (identical? host pool) "false beside a host pool is accepted, and the host's pool stands"))))
  ;; A host pool with a virtual executor of the host's: stop must leave that executor
  ;; alone, or a host sharing it — Jetty's JVM-wide default, say — loses it everywhere.
  (let [executor (org.eclipse.jetty.util.VirtualThreads/getNamedVirtualThreadsExecutor "host-vt-")
        host     (doto (QueuedThreadPool. 20) (.setVirtualThreadsExecutor executor))]
    (with-server {:thread-pool host} (fn [_ pool] (is (identical? host pool))))
    (is (not (.isShutdown ^java.util.concurrent.ExecutorService executor)) "stop left the host's executor running")
    (let [ran (promise)]
      (.execute ^java.util.concurrent.Executor executor #(deliver ran true))
      (is (true? (deref ran 5000 ::timeout)) "and it still runs a task"))))

(defn- park-more-than-threads
  "32 POSTs parked in the handler of a server with 8 threads, each on a connection of its
  own, and a probe on a new connection. Answers what the test observes."
  [options]
  (let [entered (atom 0)
        release (promise)
        trace   (atom [])
        app     (fn [{:keys [uri]}]
                  (if (= uri "/park")
                    (do (swap! entered inc) @release {:status 200 :body "done"})
                    {:status 200 :body "fast"}))
        handle  (server/start app (merge {:port 0 :max-threads 8 :min-threads 8 :acceptor-threads 1 :selector-threads 1} options))
        port    (:port handle)
        pool    (.getThreadPool ^Server (:server handle))
        conn    ^ServerConnector (first (.getConnectors ^Server (:server handle)))
        parked  (doall (for [_ (range 32)]
                         (future (let [c (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) (.build))]
                                   (try (let [r (.send c (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/park")))
                                                             (.timeout (Duration/ofSeconds 20))
                                                             (.POST (HttpRequest$BodyPublishers/noBody)) (.build))
                                                       (HttpResponse$BodyHandlers/ofString))]
                                          [(.statusCode r) (.body r)])
                                        (catch Throwable t t))))))]
    {:entered entered :release release :trace trace :handle handle :port port :pool pool :parked parked
     :capacity (- (.getMaxThreads ^QueuedThreadPool pool) (.getAcceptors conn) (.getSelectorCount (.getSelectorManager conn)))}))

(deftest with-more-requests-parked-than-threads-a-new-connection-is-answered-before-they-are-released--and-not-on-platform-threads
  ;; A request parked in the handler stands in for one waiting on a full database pool.
  ;; The pass criterion is ORDER: the probe answered while the 32 are still parked. The
  ;; 5 and 10 s derefs are hang guards; the control's 1000 ms is declared policy — the
  ;; virtual probe answers in about 3 ms (measured 2026-09-28), 300 times inside it.
  (let [{:keys [entered release trace handle port parked]} (park-more-than-threads {})]
    (try
      (is (until 5000 #(= 32 @entered))
          (str "witness: 32 requests parked at once on 8 threads — the pool's threads are not what they hold; entered=" @entered))
      (let [probe (future (let [r (fresh-get port)] (swap! trace conj :probe-answered) r))]
        (is (= [200 "fast"] (deref probe 5000 ::timeout)) "a new connection is answered while 32 requests are parked")
        (is (not (realized? release)) "witness: before anything was released"))
      (deliver release true)
      (swap! trace conj :released)
      (is (= [:probe-answered :released] @trace) "in that order")
      (is (every? #(= [200 "done"] %) (map #(deref % 10000 ::timeout) parked)) "and every parked request finished")
      (finally (deliver release true) (server/stop handle))))
  (let [{:keys [entered release trace handle port pool parked capacity]} (park-more-than-threads {:virtual-threads? false})]
    (try
      (is (= 6 capacity) "control: the platform pool keeps max − acceptors − selectors = 6 threads for handlers")
      (is (until 5000 #(= capacity @entered)) (str "control: " capacity " requests entered, entered=" @entered))
      (Thread/sleep 200)
      (is (= capacity @entered) "control: and no more — the rest wait for a thread, which is the freeze")
      (is (.isLowOnThreads ^QueuedThreadPool pool) "control: the pool says so")
      (let [probe (future (let [r (fresh-get port)] (swap! trace conj :probe-answered) r))]
        (is (= ::pending (deref probe 1000 ::pending))
            "control: on platform threads the same probe is NOT answered while the threads are held — the harness can tell")
        (swap! trace conj :released)
        (deliver release true)
        (is (= [200 "fast"] (deref probe 10000 ::timeout)) "control: it is answered once they are released")
        (is (= [:released :probe-answered] @trace) "control: only then"))
      (is (every? #(= [200 "done"] %) (map #(deref % 10000 ::timeout) parked)) "control: and every parked request finished")
      (is (= 32 @entered))
      (finally (deliver release true) (server/stop handle)))))

(deftest virtual-threads-outside-true-false-and-true-beside-a-host-pool-are-refused-naming-the-key-before-jetty-is-reached
  ;; A JVM without virtual threads is refused too; VirtualThreads/areSupported is static
  ;; and no test here can make this JVM lack them, so that branch is read, not run.
  (let [calls (atom 0)]
    (with-redefs [jetty/run-jetty (fn [& _] (swap! calls inc) (throw (IllegalStateException. "never reached")))]
      (doseq [bad ["true" 1 :true]]
        (is (= {:config-key [:virtual-threads?] :value bad}
               (try (server/start app {:port 0 :virtual-threads? bad}) ::started (catch ExceptionInfo e (ex-data e))))
            (str (pr-str bad) " is refused naming [:virtual-threads?]")))
      (let [e (try (server/start app {:port 0 :virtual-threads? true :thread-pool (QueuedThreadPool. 4)}) nil
                   (catch ExceptionInfo e e))]
        (is (= {:config-key [:virtual-threads?]} (ex-data e)) "true beside a host pool is refused, the pool kept out of the data")
        (is (re-find #":thread-pool" (str (ex-message e))) "saying why")))
    (is (= 0 @calls) "refused before Jetty was reached"))
  (let [handle (server/start app {:port 0 :virtual-threads? nil})]
    (try (is (instance? Server (:server handle)) "nil is the absent option") (finally (server/stop handle)))))

(deftest ring-jetty-adapters-pool-options-reach-the-virtual-pool
  (let [read-pool (fn [^QueuedThreadPool p]
                    ;; getQueue is protected: read through reflection, in the test only.
                    (let [q ^org.eclipse.jetty.util.BlockingArrayQueue
                          (.invoke (doto (.getDeclaredMethod QueuedThreadPool "getQueue" (make-array Class 0)) (.setAccessible true))
                                   p (object-array 0))]
                      [(.getMaxThreads p) (.getMinThreads p) (.getIdleTimeout p) (.isDaemon p)
                       (some? (.getVirtualThreadsExecutor p)) (.getCapacity q) (.getMaxCapacity q)]))]
    (with-server {:max-threads 12 :min-threads 9 :thread-idle-timeout 1234 :daemon? true :max-queued-requests 77}
      (fn [_ pool] (is (= [12 9 1234 true true 9 77] (read-pool pool))
                       "[max min idle daemon virtual queue-capacity queue-max] are the host's options")))
    (with-server {}
      (fn [_ pool] (is (= [50 8 60000 false true 8 Integer/MAX_VALUE] (read-pool pool))
                       "and ring-jetty-adapter's defaults without them")))))
