(ns dev.arkaitz.web-base.integrant-test
  "A real Jetty through Integrant. Every HTTP call has a timeout and halt!
  runs in a finally: a hang is never a result."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig])
  (:import [clojure.lang ExceptionInfo]
           [java.net ConnectException ServerSocket URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server]))

(def ^:private client
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) (.connectTimeout (Duration/ofSeconds 2)) (.build)))

(defn- http [port path]
  (let [request  (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path))) (.timeout (Duration/ofSeconds 5)) (.GET) (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)
     :headers (into {} (map (fn [[k v]] [k (first v)])) (.map (.headers response)))}))

(defn- config [port]
  {::wb/handler {:routes [["/" {:get (fn [_] {:status 200 :body [:p "hi"]})}]] :session {:key "AAAAAAAAAAAAAAAAAAAAAA=="}}
   ::wb/server  {:handler (ig/ref ::wb/handler) :port port}})

(deftest init-starts-jetty-on-the-bound-port-and-halt-stops-it
  (let [sys (deref (future (ig/init (config 0))) 10000 ::timeout)]
    (is (not= ::timeout sys) "init returned: the server does not join")
    (when (map? sys)
      (let [handle (::wb/server sys) port (:port handle)]
        (try
          (is (= #{::wb/handler ::wb/server} (set (keys sys))))
          (is (fn? (::wb/handler sys)))
          (is (= #{:server :port} (set (keys handle))))
          (is (instance? Server (:server handle)))
          (is (pos-int? port))
          (let [r (http port "/")]
            (is (= [200 "<!DOCTYPE html>\n<p>hi</p>" "text/html;charset=utf-8"] [(:status r) (:body r) (get-in r [:headers "content-type"])])
                "Jetty normalises the content-type; the body is the page")
            (is (re-matches #"[A-Za-z0-9_-]{16}" (str (get-in r [:headers "x-request-id"])))))
          (let [r (http port "/wb/wb.css")]
            (is (= [200 "text/css"] [(:status r) (get-in r [:headers "content-type"])]))
            (is (str/starts-with? (:body r) "/* web-base structural stylesheet")))
          (finally
            (ig/halt! sys)))
        (is (= :refused (try (http port "/") (catch ConnectException _ :refused))) "after halt! the port refuses connections")
        (is (.isStopped ^Server (:server handle)))))))

(deftest init-key-server-honours-the-port-option
  ;; The port is free when the socket closes and taken by Jetty a moment
  ;; later; another process grabbing it in between would red this without a
  ;; defect, which is the price of proving the requested port is honoured.
  (let [free (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        sys  (deref (future (ig/init (config free))) 10000 ::timeout)]
    (is (map? sys) "init returned within the deadline")
    (when (map? sys)
      (try
        (is (= free (:port (::wb/server sys))) "the requested port is the bound one")
        (is (= [200 "<!DOCTYPE html>\n<p>hi</p>"] (let [r (http free "/")] [(:status r) (:body r)])))
        (finally
          (ig/halt! sys))))))

(deftest server-without-a-port-is-refused-rather-than-taking-80
  (is (= ["server options need a non-negative integer :port (0 for an ephemeral one)" {:config-key [:port] :value nil}]
         (try (wb/start identity {}) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))))

(deftest read-string-reads-ig-ref-and-wb-env-in-the-same-edn
  (let [path (System/getenv "PATH")]
    (is (string? path) "precondition: PATH is set")
    (let [m (wbi/read-string "{:a #ig/ref :x/b :h #wb/env \"PATH\"}")]
      (is (= {:a (ig/ref :x/b) :h path} m))
      (is (ig/ref? (:a m))))
    (is (= ["environment variable WB_SURELY_UNSET_123 is not set" {:env "WB_SURELY_UNSET_123"}]
           (try (wbi/read-string "#wb/env \"WB_SURELY_UNSET_123\"") (catch ExceptionInfo e [(ex-message e) (ex-data e)]))))
    (is (thrown-with-msg? RuntimeException #"No reader function for tag wb/env" (ig/read-string "#wb/env \"PATH\""))
        "control: integrant alone does not know the tag — the merge is what adds it")))
