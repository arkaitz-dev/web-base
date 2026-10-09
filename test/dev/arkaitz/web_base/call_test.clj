(ns dev.arkaitz.web-base.call-test
  "`testing/call` — one request as a program sends it — `visit {:multipart? true}` and
  `testing/header`. The request `call` builds is observed by a handler that echoes it,
  and through the assembled stack where the claim is the stack's: no session and no
  token, the body limit where it reads. Every expected value was measured."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.testing :as wt])
  (:import [clojure.lang ExceptionInfo]
           [java.util Locale]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- bytes-of [body] (when body (vec (.readAllBytes ^java.io.InputStream body))))

(defn- echo
  "What a handler received, as data."
  [r]
  {:status 200
   :body   (pr-str {:keys         (sort (keys r))
                    :method       (:request-method r)
                    :uri          (:uri r)
                    :query-string (:query-string r)
                    :headers      (:headers r)
                    :content-type (:content-type r)
                    :length       (:content-length r)
                    :remote-addr  (:remote-addr r)
                    :bytes        (bytes-of (:body r))})})

(defn- seen [& args] (edn/read-string (:body (apply wt/call echo args))))

(deftest call-builds-the-raw-request-a-program-sends
  (let [bare (seen :get "/api/x")]
    (is (= {:keys [:headers :protocol :remote-addr :request-method :scheme :server-name :server-port :uri]
            :headers {"host" "localhost"} :remote-addr "127.0.0.1" :query-string nil :bytes nil}
           (select-keys bare [:keys :headers :remote-addr :query-string :bytes]))
        "a GET: no cookie, no token, no body — nothing a browser adds"))
  (is (= ["/api/x" "q=%ZZ&a=1&a=2"] ((juxt :uri :query-string) (seen :get "/api/x?q=%ZZ&a=1&a=2")))
      "the query string as written, a malformed escape included")
  (is (= ["/api/y" "z=1"] ((juxt :uri :query-string) (seen :get "http://example.test/api/y?z=1")))
      "an absolute URL's path and query")
  (let [s "{\"a\":\"ñ€\"}"
        r (seen :post "/api/x" {:body s})]
    (is (= [13 10] [(count (.getBytes s "UTF-8")) (count s)]) "witness: the body's UTF-8 bytes outnumber its characters")
    (is (= [:body :content-length :content-type :headers :protocol :remote-addr :request-method :scheme :server-name :server-port :uri]
           (:keys r))
        "a POST carries its body and nothing a browser adds — no params, no cookie, no token")
    (is (= {:bytes [123 34 97 34 58 34 -61 -79 -30 -126 -84 34 125] :content-type "application/json" :length 13
            :headers {"host" "localhost" "content-type" "application/json" "content-length" "13"}}
           (select-keys r [:bytes :content-type :length :headers]))
        "a string body is its UTF-8 bytes, typed JSON by default, its length declared twice"))
  (is (= [[1 2 -1] 3] ((juxt :bytes :length) (seen :post "/api/x" {:body (byte-array [1 2 255])}))) "bytes as given")
  (is (= ["application/x-www-form-urlencoded" {"host" "localhost" "content-type" "application/x-www-form-urlencoded" "content-length" "3"}]
         ((juxt :content-type :headers) (seen :post "/api/x" {:body "a=1" :content-type "application/x-www-form-urlencoded"})))
      "a type of the test's own")
  (let [r (seen :post "/api/x" {:body "abc" :chunked? true})]
    (is (= [[97 98 99] nil {"host" "localhost" "content-type" "application/json"} false]
           [(:bytes r) (:length r) (:headers r) (boolean (some #{:content-length} (:keys r)))])
        "chunked: the body, and no length — neither the key nor the header"))
  (is (= {"host" "localhost" "authorization" "Bearer t" "x-req-id" "7"}
         (:headers (seen :get "/api/x" {:headers {"Authorization" "Bearer t" :X-Req-Id "7"}})))
      "headers lower-cased")
  (let [default (Locale/getDefault)]
    (try (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
         (is (= "x-clıent-ıd" (.toLowerCase "X-CLIENT-ID")) "witness: under tr_TR the default lower-case is dotless")
         (is (contains? (:headers (seen :get "/api/x" {:headers {"X-CLIENT-ID" "1"}})) "x-client-id")
             "and call lower-cases by the root locale")
         (finally (Locale/setDefault default))))
  (is (= "10.0.0.9" (:remote-addr (seen :get "/api/x" {:remote-addr "10.0.0.9"}))) "the source address"))

(defn- refusal [f] (try (f) :sent (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(deftest call-refuses-a-request-it-would-not-send-as-asked
  (doseq [[label opts message data]
          [["a host of the test's" {:headers {"Host" "evil"}}
            "web-base: call writes the host header itself — pass :body, :content-type or :chunked? instead" {:path "/api/x" :header "host"}]
           ["a content type as a header" {:body "x" :headers {"Content-Type" "text/plain"}}
            "web-base: call writes the content-type header itself — pass :body, :content-type or :chunked? instead" {:path "/api/x" :header "content-type"}]
           ["a length beside chunked" {:body "x" :chunked? true :headers {"content-length" "999"}}
            "web-base: call writes the content-length header itself — pass :body, :content-type or :chunked? instead" {:path "/api/x" :header "content-length"}]
           ["one header twice" {:headers {"Accept" "a" "accept" "b"}}
            "web-base: call was given the accept header twice, in different cases" {:path "/api/x" :header "accept"}]
           ["a type with no body" {:content-type "text/plain"}
            "web-base: call was given :content-type or :chunked? with no :body to send" {:path "/api/x"}]
           ["chunked with no body" {:chunked? true}
            "web-base: call was given :content-type or :chunked? with no :body to send" {:path "/api/x"}]
           ["a map for a body" {:body {:a 1}}
            "web-base: call sends a body that is a string or bytes, and was given a clojure.lang.PersistentArrayMap — encode it first" {:path "/api/x"}]]]
    (is (= [message data] (refusal #(wt/call echo :post "/api/x" opts))) (str label ": refused"))))

(defn- app [api & {:as more}]
  (wb/handler (merge {:session     {:key KEY}
                      :routes      [["/form" {:get  (fn [r] {:status 200 :body [:form (security/csrf-field r)]})
                                              :post (fn [_] {:status 200 :body "posted"})}]]
                      :sessionless {"/api/" api}}
                     more)))

(deftest call-reaches-a-sessionless-api-with-no-session-and-no-token--and-a-csrf-page-refuses-it
  (let [got (atom nil)
        h   (app (fn [r] (reset! got [(:uri r) (:query-string r) (contains? r :session) (:anti-forgery-token r) (slurp (:body r))])
                   {:status 200 :headers {"Content-Type" "application/json"} :body "{\"ok\":true}"}))
        r   (wt/call h :post "/api/things?x=%ZZ" {:body "{\"a\":1}" :headers {"authorization" "Bearer x"}})]
    (is (= [200 "{\"ok\":true}" nil] [(:status r) (:body r) (wt/header r "Set-Cookie")])
        "the API answered, and no session was written")
    (is (= ["/api/things" "x=%ZZ" false nil "{\"a\":1}"] @got) "with no session and no token on the request, the body intact")
    (let [refused (wt/call h :post "/form" {:body "x=1" :content-type "application/x-www-form-urlencoded"})
          b       (wt/visit (wt/browser h) :get "/form")]
      (is (= 403 (:status refused)) "a CSRF-protected POST through call is refused: call sends no token")
      (is (= [200 "posted"] ((juxt :status :body) (:response (wt/visit b :post "/form" {"x" "1"}))))
          "control: the same POST from a browser that holds the token is taken"))))

(deftest calls-body-meets-the-limit-where-the-limit-reads-it
  (let [entered (atom [])
        api     (fn [r]
                  (swap! entered conj [:entered (:content-length r)])
                  (try {:status 200 :body (slurp (:body r))}
                       (catch Exception e
                         (swap! entered conj [:threw (security/body-failure e)])
                         (throw e))))
        h       (app api :max-body-bytes 10)
        sent    (fn [opts] (reset! entered []) (let [r (wt/call h :post "/api/x" opts)] [(:status r) @entered]))]
    (is (= [413 []] (sent {:body "12345678901"})) "a declared length past the limit never enters the handler")
    (is (= [413 [[:entered nil] [:threw {:status 413}]]] (sent {:body "12345678901" :chunked? true}))
        "chunked, it enters with no length, and the read throws the body's 413")
    (is (= [[200 [[:entered 10]]] [200 [[:entered nil]]]] [(sent {:body "1234567890"}) (sent {:body "1234567890" :chunked? true})])
        "the limit itself is read whole both ways"))
  (let [naive (app (fn [r] (try (slurp (:body r)) {:status 200 :body ""} (catch Exception _ {:status 400 :body ""})))
                   :max-body-bytes 10)]
    (is (= [413 400] [(:status (wt/call naive :post "/api/x" {:body "12345678901"}))
                      (:status (wt/call naive :post "/api/x" {:body "12345678901" :chunked? true}))])
        "the README's trap, pinned: a parser's try around the read turns the chunked 413 into its 400")))

(defn- mp-app []
  (wb/handler {:session {:key KEY}
               :routes  [["/up" {:wb/multipart {:max-file-size 100}
                                 :get  (fn [r] {:status 200 :body [:form (security/csrf-field r)]})
                                 :post (fn [r] {:status 200
                                                :body (pr-str {:mp   (some-> (:multipart-params r) (dissoc "__anti-forgery-token"))
                                                               :form (some-> (:form-params r) (dissoc "__anti-forgery-token"))
                                                               :ct   (:content-type r)
                                                               :p    (wb/param r "name")})})}]
                         ["/plain" {:get  (fn [r] {:status 200 :body [:form (security/csrf-field r)]})
                                    :post (fn [_] {:status 200 :body "plain"})}]]}))

(deftest multipart?-sends-a-fileless-form-as-multipart
  (let [h    (mp-app)
        post (fn [path opts] (let [b (wt/visit (wt/browser h) :get path)
                                   r (:response (wt/visit b :post path {"name" "ada"} opts))]
                               [(:status r) (when (= 200 (:status r)) (edn/read-string (:body r)))]))]
    (is (= [200 {:mp nil :form {"name" "ada"} :ct "application/x-www-form-urlencoded; charset=UTF-8" :p "ada"}] (post "/up" {}))
        "without the flag the form is urlencoded: :params holds it, :multipart-params is nil")
    (is (= [200 {:mp {"name" "ada"} :form {} :ct "multipart/form-data; boundary=wb-test-boundary-7MA4YWxkTrZu0gW" :p "ada"}]
           (post "/up" {:multipart? true}))
        "with it the form is multipart: :multipart-params holds every field, the token parsed as a part")
    (is (= [403 nil] (post "/plain" {:multipart? true}))
        "to a route that declares no :wb/multipart nothing parses it, and its token is unseen")
    (is (= "web-base: {:files …} or {:multipart? true} goes in the body of a POST, PUT or PATCH, and a GET to /up has none"
           (try (wt/visit (wt/visit (wt/browser h) :get "/up") :get "/up" {"name" "ada"} {:multipart? true}) :sent
                (catch ExceptionInfo e (ex-message e))))
        "a method with no body refuses the flag rather than ignore it")))

(deftest header-reads-one-value-whatever-the-case
  (is (= ["a=1" "text/plain" nil nil nil "l1" nil]
         [(wt/header {:headers {"Set-Cookie" ["a=1" "b=2"]}} "set-cookie")
          (wt/header {:headers {"content-type" "text/plain"}} "Content-Type")
          (wt/header {:headers {"x" "1"}} "y")
          (wt/header {:status 200} "x")
          (wt/header nil "x")
          (wt/header {:headers {"Set-Cookie" (map identity ["l1" "l2"])}} "SET-COOKIE")
          (wt/header {:headers {"x" []}} "x")])
      "the first of several, the string when one, nil when absent")
  (let [default (Locale/getDefault)]
    (try (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
         (is (= "v" (wt/header {:headers {"X-CLIENT-ID" "v"}} "x-client-id")) "a Turkish default locale changes nothing")
         (finally (Locale/setDefault default)))))
