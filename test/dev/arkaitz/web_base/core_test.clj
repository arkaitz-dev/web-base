(ns dev.arkaitz.web-base.core-test
  "The assembled handler, end to end: the middleware order is the product and
  these are its observable consequences. Literals are the same shapes the
  unit tests pin, copied, never required from those namespaces."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page
  ([status body] (page status body nil))
  ([status body lang]
   (str "<!DOCTYPE html>\n<html" (when lang (str " lang=\"" lang "\"")) ">"
        "<head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
        "<title>" status "</title></head><body>" body "</body></html>")))

(def ^:private SEC {"X-Content-Type-Options" "nosniff"
                    "X-Frame-Options"        "DENY"
                    "Referrer-Policy"        "strict-origin-when-cross-origin"})
(def ^:private ERR-HTML {"Vary" "HX-Request, HX-Request-Type, Accept" "Cache-Control" "no-store" "Content-Type" "text/html; charset=utf-8"})
(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private LOGIN "/entrar-aqui")
(def ^:private id-pattern #"[A-Za-z0-9_-]{16}")

(def ^:private seen (atom nil))

(def ^:private routes
  [["/me"     {:get (fn [r] {:status 200 :body (pr-str (:session r))})}]
   ["/login"  {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user "ann"}))}]
   ["/priv"   {:wb/gate wb/subject-present? :get (fn [r] {:status 200 :body (str "hi " (pr-str (:wb/subject r)))})}]
   ["/token"  {:get (fn [r] {:status 200 :body (security/csrf-token r)})}]
   ["/post"   {:post (fn [_] {:status 200 :body "posted"})}]
   ["/see"    {:get (fn [r] (reset! seen r)
                      {:status 200 :body (str (:wb/locale r) "|" (when-let [tr (:wb/tr r)] (tr [:hi])))})}]
   ["/boom"   {:wb/gate (fn [_] (throw (RuntimeException. "PREDBOOM"))) :get (fn [_] {:status 200 :body "never"})}]
   ["/scheme" {:get (fn [r] {:status 200 :body (str (:scheme r) "|" (:remote-addr r))})}]])

(defn- config [& {:as more}]
  (merge {:routes     routes
          :session    {:key KEY}
          :subject-fn #(get-in % [:session :user])
          :login-path LOGIN
          :static     {:root "public"}}
         more))

(defn- id-of [response] (get-in response [:headers "X-Request-Id"]))

(defn- error-headers [response]
  (merge SEC ERR-HTML {"X-Request-Id" (id-of response)}))

(deftest handler-requires-routes-and-session-naming-the-missing-key
  (let [attempt (fn [cfg] (try (wb/handler cfg) ::built
                               (catch ExceptionInfo e [(ex-message e) (dissoc (ex-data e) :reitit.exception/cause)])))]
    (is (= ["web-base config needs :routes" {:config-key [:routes]}] (attempt {:session {:key KEY}})))
    (is (= ["web-base config needs :routes" {:config-key [:routes]}] (attempt {:routes nil :session {:key KEY}})) "nil counts as missing")
    (is (= ["web-base config needs :session" {:config-key [:session]}] (attempt {:routes []})))
    (is (fn? (wb/handler {:routes [] :session {:key KEY} :whatever-unknown 1})) "unknown keys are the host's business")
    (is (= ["a route declares :wb/gate but no :login-path is configured" {:config-key [:login-path]}]
           (attempt {:routes [["/p" {:wb/gate wb/subject-present? :get identity}]] :session {:key KEY}}))
        "a collaborator's construction-time check is reached")))

(deftest assembled-login-sets-session-and-gate-admits-the-cookie-refuses-without-it
  (let [app   (wb/handler (config :csrf false))
        login (app (mock/request :post "/login"))]
    (is (= {:status 200 :body "in"} (dissoc login :headers)))
    (is (seq (testing/cookies login)) "login issued a session cookie")
    (let [r (app (testing/with-cookies (mock/request :get "/priv") login))]
      (is (= {:status 200 :body "hi \"ann\""} (dissoc r :headers)) "gated GET with the cookie: the subject came from the session")
      (is (re-matches id-pattern (str (id-of r)))))
    (let [r (app (mock/request :get "/priv"))]
      (is (= [303 LOGIN "no-store" "HX-Request, HX-Request-Type" ""]
             [(:status r) (get-in r [:headers "Location"]) (get-in r [:headers "Cache-Control"]) (get-in r [:headers "Vary"]) (:body r)])
          "navigation refusal"))
    (let [r (app (mock/header (mock/request :get "/priv") "HX-Request" "true"))]
      (is (= [200 LOGIN nil ""] [(:status r) (get-in r [:headers "HX-Redirect"]) (get-in r [:headers "Location"]) (:body r)])
          "partial refusal: HX-Redirect and no Location")))
  (is (= 303 (:status ((wb/handler (config :csrf false :subject-fn nil)) (mock/request :get "/priv"))))
      ":subject-fn nil — an absent setting — means no subject, not a crash"))

(deftest assembled-csrf-refuses-unsafe-without-token-and-admits-header-or-form-field
  (let [app    (wb/handler (config))
        t      (app (mock/request :get "/token"))
        token  (:body t)
        with   #(testing/with-cookies % t)]
    (is (re-matches #"[A-Za-z0-9+/]{80}" (str token)) "ring-anti-forgery 1.4.0's token shape: 60 random bytes, base64 unpadded")
    (let [r (app (mock/request :post "/post"))]
      (is (re-matches id-pattern (str (id-of r))) "the refusal carries a well-formed request id")
      (is (= {:status 403 :headers (error-headers r) :body (page 403 (frag 403))} r) "no token, navigation → the base's 403 page"))
    (let [r (app (mock/header (mock/request :post "/post") "HX-Request" "true"))]
      (is (= {:status 403 :headers (error-headers r) :body (frag 403)} r) "no token, partial → fragment"))
    (let [r (app (mock/header (mock/request :post "/post") "Accept" "text/plain"))]
      (is (= [403 "text/plain; charset=utf-8" "403"] [(:status r) (get-in r [:headers "Content-Type"]) (:body r)]) "no token, text"))
    (is (= {:status 200 :body "posted"} (dissoc (app (with (mock/header (mock/request :post "/post") "X-CSRF-Token" token))) :headers))
        "header token passes")
    (is (= {:status 200 :body "posted"} (dissoc (app (with (mock/body (mock/request :post "/post") {"__anti-forgery-token" token}))) :headers))
        "form-field token passes: params runs before csrf")
    (is (= 403 (:status (app (with (mock/header (mock/request :post "/post") "X-CSRF-Token" (str (if (= \A (first token)) "B" "A") (subs token 1)))))))
        "a tampered token is refused")
    (is (= {:status 200 :body "posted"} (dissoc ((wb/handler (config :csrf false)) (mock/request :post "/post")) :headers))
        ":csrf false disables the check")
    (is (= 403 (:status ((wb/handler (config :csrf nil)) (mock/request :post "/post"))))
        ":csrf nil — a setting absent from the host's config map — keeps the check on")))

(deftest x-request-id-and-security-headers-on-every-response-routed-static-404-error
  (let [app (wb/handler (config :csrf false))
        ids (atom [])]
    (doseq [[label request] [["routed"  (mock/request :get "/me")]
                             ["static"  (mock/request :get "/host.txt")]
                             ["404"     (mock/request :get "/nope")]
                             ["refusal" (mock/request :get "/priv")]]]
      (testing label
        (let [{:keys [headers]} (app request)]
          (is (re-matches id-pattern (str (get headers "X-Request-Id"))) "X-Request-Id")
          (swap! ids conj (get headers "X-Request-Id"))
          (is (= SEC (select-keys headers (keys SEC))) "security headers"))))
    (is (= 4 (count (set @ids))) "distinct ids")
    (is (= 50 (count (set (repeatedly 50 #(id-of (app (mock/request :get "/nope"))))))) "fifty 404s, fifty ids")))

(deftest csp-nonce-in-the-header-equals-the-nonce-the-handler-saw-and-changes-per-request
  (let [app (wb/handler (config :csrf false
                                :security {:csp "script-src 'nonce-{nonce}'"
                                           :hsts {:max-age 31536000 :include-subdomains? true}
                                           :frame-options "SAMEORIGIN"}))
        r1  (app (mock/request :get "/see")) n1 (:wb/nonce @seen)
        r2  (app (mock/request :get "/see")) n2 (:wb/nonce @seen)]
    (is (re-matches #"[A-Za-z0-9+/]{22}==" (str n1)))
    (is (= (str "script-src 'nonce-" n1 "'") (get-in r1 [:headers "Content-Security-Policy"])) "the header carries the handler's nonce")
    (is (not= n1 n2) "a fresh nonce per request")
    (is (= (str "script-src 'nonce-" n2 "'") (get-in r2 [:headers "Content-Security-Policy"])))
    (is (= "max-age=31536000; includeSubDomains" (get-in r1 [:headers "Strict-Transport-Security"])))
    (is (= "SAMEORIGIN" (get-in r1 [:headers "X-Frame-Options"]))))
  (let [r ((wb/handler (config :csrf false :security {:frame-options false})) (mock/request :get "/nope"))]
    (is (= [nil "nosniff"] [(get-in r [:headers "X-Frame-Options"]) (get-in r [:headers "X-Content-Type-Options"])])
        "frame-options false omits the header, on a 404 too")))

(deftest i18n-puts-locale-and-tr-on-the-request-and-the-default-error-page-lang-follows
  (let [app (wb/handler (config :csrf false :i18n {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en}))]
    (is (= ":es|Hola" (:body (app (mock/header (mock/request :get "/see") "Accept-Language" "es-ES,es;q=0.9")))))
    (is (= ":en|Hello" (:body (app (mock/request :get "/see")))))
    (is (= (page 404 (frag 404) "es") (:body (app (mock/header (mock/request :get "/nope") "Accept-Language" "es"))))
        "the default handler's 404 renders in the negotiated language")
    (let [r (app (mock/header (mock/request :post "/post") "Accept-Language" "es"))]
      (is (= (page 403 (frag 403) "es") (:body ((wb/handler (config :i18n {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en}))
                                                (mock/header (mock/request :post "/post") "Accept-Language" "es"))))
          "a CSRF refusal renders in the negotiated language: csrf sits inside i18n")
      (is (= 200 (:status r)))))
  (let [app (wb/handler (config :csrf false))]
    (app (mock/request :get "/see"))
    (is (= {} (select-keys @seen [:wb/locale :wb/tr])) "without :i18n neither key is on the request")
    (is (= (page 404 (frag 404)) (:body (app (mock/request :get "/nope")))) "and the error page has no lang")))

(deftest static-serving-host-assets-and-the-base-wb-mount-takes-precedence-over-a-host-file
  ;; CSRF on: a plain GET through the session mints a cookie (the token), so
  ;; the absence of Set-Cookie on an asset is measured against a stack that
  ;; demonstrably sets one.
  (let [app (wb/handler (config))]
    (is (some? (get-in (app (mock/request :get "/me")) [:headers "Set-Cookie"])) "control: a routed GET does mint a session cookie")
    (let [r (app (mock/request :get "/host.txt"))]
      (is (= [200 "HOST-ASSET\n" "text/plain"] [(:status r) (slurp (:body r)) (get-in r [:headers "Content-Type"])]) "the host's asset")
      (is (= SEC (select-keys (:headers r) (keys SEC))) "with the security headers")
      (is (nil? (get-in r [:headers "Set-Cookie"])) "and no session cookie: assets answer before the session"))
    (let [r (app (mock/request :get "/wb/wb.css"))]
      (is (= [200 "text/css"] [(:status r) (get-in r [:headers "Content-Type"])]))
      (is (nil? (get-in r [:headers "Set-Cookie"])) "no session cookie on the base's assets either")
      (is (clojure.string/starts-with? (slurp (:body r)) "/* web-base structural stylesheet")
          "the base's own stylesheet, not the host's public/wb/wb.css shadow"))
    (is (= (page 404 (frag 404)) (:body (app (mock/request :get "/wb/missing.txt")))) "a miss under /wb/ falls through to the 404")
    (is (= (page 404 (frag 404)) (:body ((wb/handler (config :csrf false :static nil)) (mock/request :get "/host.txt"))))
        "no :static, no host assets"))
  ;; The cookie half above is blind to assets sitting inside the session
  ;; layer (Ring writes no cookie for a response without :session); a store
  ;; that counts reads is not.
  (let [reads   (atom 0)
        store   (reify ring.middleware.session.store/SessionStore
                  (read-session [_ _] (swap! reads inc) {:user "ann"})
                  (write-session [_ k _] (or k "fresh"))
                  (delete-session [_ _] nil))
        app     (wb/handler (config :session {:store store}))
        cookie  "ring-session=old"]
    (app (mock/header (mock/request :get "/me") "Cookie" cookie))
    (is (= 1 @reads) "control: a routed GET reads the store")
    (app (mock/header (mock/request :get "/host.txt") "Cookie" cookie))
    (app (mock/header (mock/request :get "/wb/wb.css") "Cookie" cookie))
    (is (= 1 @reads) "assets never read the store: they answer before the session")))

(deftest proxy-headers-honoured-only-when-opt-in-and-ignored-otherwise
  (let [forwarded (-> (mock/request :get "/scheme") (mock/header "X-Forwarded-Proto" "HTTPS") (mock/header "X-Forwarded-For" "10.0.0.1, 10.0.0.2"))
        on        (wb/handler (config :csrf false :security {:proxy? true}))
        off       (wb/handler (config :csrf false))]
    (is (= ":http|127.0.0.1" (:body (off (mock/request :get "/scheme")))) "baseline: ring-mock's own scheme and address")
    (is (= ":https|10.0.0.1" (:body (on forwarded))) "opt-in: first hop, lowercased proto")
    (is (= ":http|127.0.0.1" (:body (off forwarded))) "without opt-in the headers are ignored")
    (is (= ":http|127.0.0.1" (:body (on (mock/request :get "/scheme")))) "opt-in without headers: unchanged")))

(deftest order-proofs-gate-predicate-throw-is-500-and-csrf-refusal-carries-outer-headers
  (let [app (wb/handler (config))]
    (lt/with-log
      (let [r (app (mock/request :get "/boom"))]
        (is (= {:status 500 :body (page 500 (frag 500))} (select-keys r [:status :body])) "a throwing predicate becomes a 500 page: error sits outside gate")
        (is (= [['dev.arkaitz.web-base.error :error "PREDBOOM" (str "unhandled exception {:request-id " (id-of r) ", :uri /boom}")]
                ['dev.arkaitz.web-base.log :info nil "GET /boom 500 <n>ms"]]
               (mapv (juxt (comp ns-name :logger-ns) :level (comp ex-message :throwable)
                           #(clojure.string/replace (:message %) #"\d+ms$" "<n>ms"))
                     (lt/the-log)))
            "logged once at error with the request id, then the access line")))
    (let [r (app (mock/request :post "/post"))]
      (is (= [403 true SEC]
             [(:status r) (boolean (re-matches id-pattern (str (id-of r)))) (select-keys (:headers r) (keys SEC))])
          "a CSRF refusal carries the outer stack's id and headers"))))

