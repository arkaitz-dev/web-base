(ns dev.arkaitz.web-base.subject-order-test
  "The subject is computed just inside the session (since 0.17.0), so a `:locale-fn` and
  the page of a refused request see who is asking, the subject function runs once per
  request on every path, and it reads the session and nothing computed after it. Every
  expectation here was measured against both orders; each test reds under the old one,
  where the subject was innermost, and the rows that do not discriminate are labelled."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as wt]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private LOGIN "/entrar-aqui")

(def ^:private calls (atom 0))

(defn- subject-fn [r] (swap! calls inc) (get-in r [:session :user]))

(defn- who-layout [{:keys [content request]}] [:main [:p.who (str (:id (:wb/subject request)))] content])

(def ^:private routes
  [["/page"   {:wb/layouts [shell/page] :get (fn [r] {:status 200 :body [:p ((:wb/tr r) [:hi])]})}]
   ["/login"  {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user {:id "ann" :lang :eu}}))}]
   ["/form"   {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
               :get (fn [_] {:status 200 :body [:p "form"]})}]
   ["/post"   {:post (fn [_] {:status 200 :body "posted"})}]
   ["/upload" {:wb/multipart {:max-file-size 1000} :post (fn [_] {:status 200 :body "up"})}]
   ["/priv"   {:wb/gate wb/subject-present? :get (fn [_] {:status 200 :body "priv"})}]
   ["/never"  {:wb/gate (fn [_] false) :get (fn [_] {:status 200 :body "never"})}]
   ["/plain"  {:get (fn [_] {:status 200 :body "plain"})}]
   [LOGIN     {:get (fn [_] {:status 200 :body "login"})}]])

(defn- config [& {:as m}]
  (merge {:routes      routes :session {:key KEY} :subject-fn subject-fn :login-path LOGIN
          :sessionless {"/health" (fn [_] {:status 200 :body "ok"})}
          :i18n        {:dict {:en {:hi "Hello"} :eu {:hi "Kaixo"}} :default-locale :en :locales [:en :eu]
                        :locale-fn (fn [r] (some-> (:wb/subject r) :lang vector))}}
         m))

(defn- photo [n] {"photo" {:filename "a.png" :content-type "image/png" :bytes (byte-array n (byte 7))}})

(defn- signed-in
  "A browser signed in as ann, whose language is eu, holding the rotated session's token."
  [app]
  (-> (wt/browser app) (wt/visit :get "/form") (wt/visit :post "/login") (wt/visit :get "/form")))

(defn- one
  "One request, no redirect followed."
  [b method path & [params opts]]
  (:response (wt/visit b method path params (assoc opts :follow? false))))

(defn- html-tag [r] (re-find #"<html[^>]*>" (str (:body r))))
(defn- word [r] (second (re-find #"<main class=\"wb-main\"><p>([^<]*)</p>" (str (:body r)))))
(defn- who [r] (second (re-find #"<p class=\"who\">([^<]*)</p>" (str (:body r)))))

(deftest a-locale-fn-reading-the-subject-decides-the-page-language
  (let [app  (wb/handler (config))
        b    (signed-in app)
        anon (wt/browser app)]
    (is (= [200 true] [(:status (:response b)) (some? (:token b))]) "witness: the sign-in walk ended on a page with a token (the rows below show it was ann's)")
    (is (= ["<html lang=\"en\">" "Hello"] ((juxt html-tag word) (one anon :get "/page")))
        "control: an anonymous visitor gets the default")
    (is (= "<html lang=\"eu\">" (html-tag (one anon :get "/page" nil {:headers {"accept-language" "eu"}})))
        "control: eu is reachable without a subject, so the next row can only be the locale-fn's")
    (is (= ["<html lang=\"eu\">" "Kaixo"] ((juxt html-tag word) (one b :get "/page")))
        "ann's language, which only the subject knows, decides her page: the locale-fn saw the subject")
    (is (= [404 "<html lang=\"eu\">"] ((juxt :status html-tag) (one b :get "/nope")))
        "and the base's own 404 page")))

(deftest a-refused-requests-error-page-sees-the-subject
  (let [app  (wb/handler (config :error-layout who-layout))
        b    (signed-in app)
        anon (-> (wt/browser app) (wt/visit :get "/form"))]
    (is (= [403 ""] ((juxt :status who) (one (assoc anon :token "bogus") :post "/post")))
        "control: without a subject the layout prints nothing")
    (is (= [403 "ann"] ((juxt :status who) (one (assoc b :token "bogus") :post "/post")))
        "a refused CSRF token's page knows who asked")
    (is (= [413 "ann"] ((juxt :status who) (one b :post "/upload" {"c" "x"} {:files (photo 1001)})))
        "and so does an upload refused mid-parse")
    (testing "unchanged by the reorder"
      (is (= [404 "ann"] ((juxt :status who) (one b :get "/nope"))) "the 404 already saw it"))))

(deftest the-subject-function-is-called-exactly-once-per-request-on-every-path
  (let [app     (wb/handler (config))
        b       (signed-in app)
        anon    (wt/browser app)
        counted (fn [f] (reset! calls 0) (let [r (f)] [(:status r) @calls]))]
    (is (= {"page signed in"      [200 1]
            "page anonymous"      [200 1]
            "404"                 [404 1]
            "csrf 403"            [403 1]
            "multipart 413"       [413 1]
            "gate refuses"        [403 1]
            "gate sends to login" [303 1]
            "body limit 413"      [413 0]
            "sessionless"         [200 0]}
           {"page signed in"      (counted #(one b :get "/page"))
            "page anonymous"      (counted #(one anon :get "/page"))
            "404"                 (counted #(one b :get "/nope"))
            "csrf 403"            (counted #(one (assoc b :token "bogus") :post "/post"))
            "multipart 413"       (counted #(one b :post "/upload" {"c" "x"} {:files (photo 1001)}))
            "gate refuses"        (counted #(one b :get "/never"))
            "gate sends to login" (counted #(one anon :get "/priv"))
            "body limit 413"      (counted #(app (-> (mock/request :post "/post") (assoc :content-length 300000))))
            "sessionless"         (counted #(app (mock/request :get "/health")))})
        "[status, calls]: once wherever the session runs, never before it")
    (testing "unchanged by the reorder: the subject was outside the router either way"
    (let [throwing (wb/handler (config :subject-fn (fn [r] (swap! calls inc) (throw (ex-info "SECRET" {})))))
          r        (do (reset! calls 0) (throwing (-> (mock/request :get "/page") (mock/header "accept" "text/plain"))))]
      (is (= [500 1 "500"] [(:status r) @calls (str (:body r))])
          "a subject function that throws is the outer boundary's bare 500, called once, its message kept out")))))

(deftest the-subject-function-reads-the-session-and-nothing-computed-after-it
  (let [seen (atom nil)
        app  (wb/handler (config :subject-fn (fn [r] (reset! seen (set (keys r))) (get-in r [:session :user]))))
        b    (signed-in app)]
    (one b :get "/page?q=1")
    (is (= {:session true :wb/request-id true :wb/nonce true}
           (select-keys (zipmap @seen (repeat true))
                        [:session :params :query-params :wb/tr :wb/locale :anti-forgery-token :wb/request-id :wb/nonce]))
        "the subject function sees the session, and no parameter, translation or token computed after it"))
  (let [app (wb/handler (config :subject-fn (fn [r] (get-in r [:params "who"])) :csrf false
                                :routes [["/me" {:get (fn [r] {:status 200 :body (pr-str (:wb/subject r))})}]]))]
    (is (= "nil" (:body (app (mock/request :get "/me?who=ann"))))
        "so a subject read from a parameter is no subject: it is the session's to say"))
  (testing "unchanged by the reorder"
    (let [app  (wb/handler (config))
          b    (signed-in app)
          anon (wt/browser app)]
      ;; A plain route, not a page with a form: one that renders the CSRF field writes
      ;; the session, and is uncached for that reason alone, whoever asks.
      (is (= ["no-store" nil] [(get-in (one b :get "/plain") [:headers "Cache-Control"])
                               (get-in (one anon :get "/plain") [:headers "Cache-Control"])])
          "a response for a subject is uncached; an anonymous one is not marked")
      (is (str/starts-with? (str (get-in (one anon :get "/priv") [:headers "Location"])) LOGIN)
          "the gate still sends the anonymous to sign in"))))
