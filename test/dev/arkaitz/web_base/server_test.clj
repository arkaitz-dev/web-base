(ns dev.arkaitz.web-base.server-test
  "One real Jetty, on port 0, through the assembled handler. Every request has
  a timeout and the server is stopped in a finally: a hang is never a result."
  (:require [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.session :as session]
            [ring.adapter.jetty :as jetty])
  (:import [java.net ConnectException URI]
           [java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server ServerConnector]))

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
            cookie (second (re-find #"^(ring-session=[^;]*);" (str (first (get-in login [:headers "set-cookie"])))))]
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
