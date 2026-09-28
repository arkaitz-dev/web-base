(ns dev.arkaitz.web-base.server-test
  "One real Jetty, on port 0, through the assembled handler. Every request has
  a timeout and the server is stopped in a finally: a hang is never a result."
  (:require [clojure.test :refer [deftest is]]
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
           [org.eclipse.jetty.util.component LifeCycle]))

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
;; of waiting, because stopping the thread pool under a parked handler takes ~2.5 s with
;; no window at all. The only derived bound on it is the upper one: less than the default
;; window, when a shorter one was asked for.

(defn- parking-app
  "`/park` blocks until `release` is delivered, recording how it ended in `trace`; the
  thread pool stopping under it interrupts it."
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
             :server (:server handle) :port port})))
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
  (let [{:keys [in-force parked stopped elapsed response log server port]} (outliving-the-window 1000)]
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
    (is (= :refused (fresh-get port)) "and its port refuses")))

(deftest stop-timeout-ms-zero-does-not-wait-for-a-parked-request-and-warns-nothing
  ;; "At once" is not a number: stopping the pool under a parked handler takes ~2.5 s.
  ;; A missing setStopTimeout is also a window of 0, so this row cannot catch it; the
  ;; two tests above do.
  (let [{:keys [in-force parked stopped elapsed response log server port]} (outliving-the-window 0)]
    (is (= 0 in-force) "a window of 0 is in force")
    (is (true? parked) "precondition: a request was in flight")
    (is (nil? stopped) (str "stop returned nil, got " (pr-str stopped)))
    (is (< elapsed 10000) (str "stop took " elapsed " ms, less than the default window: 0 was not read as absent"))
    (is (cut? response) (str "the parked request was cut, got " (pr-str response)))
    (is (= [] log) "no window, so nothing ran out and nothing is logged")
    (is (.isStopped ^Server server) "the server is stopped")
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
