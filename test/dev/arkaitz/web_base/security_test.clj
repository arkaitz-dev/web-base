(ns dev.arkaitz.web-base.security-test
  "Header maps are compared whole against literals. `DEFAULTS` is written out,
  never taken from `security/default-headers`: a wrong default would agree
  with itself."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.params :as params]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]
           [java.util Base64]))

(def ^:private DEFAULTS {"X-Content-Type-Options" "nosniff"
                         "X-Frame-Options"        "DENY"
                         "Referrer-Policy"        "strict-origin-when-cross-origin"})
(def ^:private VARY "HX-Request, HX-Request-Type, Accept")
(def ^:private HTML {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/html; charset=utf-8"})
(def ^:private TEXT {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/plain; charset=utf-8"})
(def ^:private FRAG-403 "<div class=\"wb-error\" data-status=\"403\"><strong class=\"wb-error-status\">403</strong></div>")
(def ^:private PAGE-403 (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                             "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
                             "<title>403</title></head><body>" FRAG-403 "</body></html>"))
(def ^:private NONCE #"[A-Za-z0-9+/]{22}==")

(defn- ok [_] {:status 200 :body "x"})
(def ^:private req (mock/request :get "/"))

(defn- headers-with [config]
  (:headers ((security/wrap-headers ok config) req)))

(deftest wrap-headers-adds-the-three-defaults-to-every-response-that-lacks-them
  (is (= {:status 200 :body "x" :headers DEFAULTS} ((security/wrap-headers ok {}) req)) "no :headers key")
  (is (= {:status 204 :headers DEFAULTS} ((security/wrap-headers (fn [_] {:status 204}) {}) req)) "a 204 without body")
  (is (= {:status 200 :body "x" :headers (assoc DEFAULTS "Content-Type" "text/plain")}
         ((security/wrap-headers (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "x"}) {}) req))
      "the handler's other header survives"))

(deftest wrap-headers-keeps-every-header-the-handler-set--the-base-never-overwrites--case-insensitively
  (let [mine {"X-Content-Type-Options" "custom" "X-Frame-Options" "SAMEORIGIN" "Referrer-Policy" "no-referrer"
              "Strict-Transport-Security" "mine" "Content-Security-Policy" "mine"}]
    (is (= {:status 200 :headers mine :body "x"}
           ((security/wrap-headers (fn [_] {:status 200 :headers mine :body "x"}) {:csp "default-src 'self'" :hsts {:max-age 1}}) req))
        "handler wins for all five names"))
  (is (= {"x-frame-options" "SAMEORIGIN" "X-Content-Type-Options" "nosniff" "Referrer-Policy" "strict-origin-when-cross-origin"}
         (:headers ((security/wrap-headers (fn [_] {:status 200 :headers {"x-frame-options" "SAMEORIGIN"} :body "x"}) {}) req)))
      "a lowercase handler header counts as present: no second X-Frame-Options"))

(deftest frame-options-string-replaces-deny--false-omits-it--nil-keeps-deny--anything-else-refused
  (is (= (assoc DEFAULTS "X-Frame-Options" "SAMEORIGIN") (headers-with {:frame-options "SAMEORIGIN"})))
  (is (= (dissoc DEFAULTS "X-Frame-Options") (headers-with {:frame-options false})))
  (is (= DEFAULTS (headers-with {:frame-options nil})))
  (is (= DEFAULTS (headers-with {})))
  (doseq [bad [true :sameorigin 42]]
    (is (= ["security :frame-options must be a string, false to omit it, or absent"
            {:config-key [:security :frame-options] :value bad}]
           (try (security/wrap-headers ok {:frame-options bad}) ::constructed
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "refused at construction: " (pr-str bad)))))

(deftest csp-must-be-a-non-blank-string-when-given
  (doseq [bad [42 "" "   " :none]]
    (is (= ["security :csp must be a non-blank policy string, or absent" {:config-key [:security :csp] :value bad}]
           (try (security/wrap-headers ok {:csp bad}) ::constructed
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "refused at construction: " (pr-str bad))))
  (is (fn? (security/wrap-headers ok {:csp nil})) "nil means absent"))

(deftest hsts-is-emitted-exactly-as-configured-and-absent-without-config
  (is (= (assoc DEFAULTS "Strict-Transport-Security" "max-age=31536000") (headers-with {:hsts {:max-age 31536000}})))
  (is (= (assoc DEFAULTS "Strict-Transport-Security" "max-age=31536000; includeSubDomains")
         (headers-with {:hsts {:max-age 31536000 :include-subdomains? true}})))
  (is (= (assoc DEFAULTS "Strict-Transport-Security" "max-age=0") (headers-with {:hsts {:max-age 0 :include-subdomains? false}}))
      "max-age 0 is a value, not an absence")
  (is (= DEFAULTS (headers-with {})))
  (is (= DEFAULTS (headers-with {:hsts false}))))

(deftest hsts-with-a-bad-max-age-throws-ex-info-at-construction-naming-the-config-key
  (let [attempt (fn [hsts] (try (security/wrap-headers ok {:hsts hsts}) ::constructed
                                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))]
    (doseq [[hsts value] [[{} nil] [{:max-age -1} -1] [{:max-age "1"} "1"] [{:max-age 1.5} 1.5] [{:max-age nil} nil]]]
      (is (= ["security :hsts needs a non-negative integer :max-age" {:config-key [:security :hsts :max-age] :value value}]
             (attempt hsts))
          (str "refused: " (pr-str hsts))))
    (doseq [hsts [true 42]]
      (is (= ["security :hsts must be a map with :max-age" {:config-key [:security :hsts] :value hsts}] (attempt hsts))
          (str "a non-map names :hsts itself: " (pr-str hsts))))
    (is (fn? (security/wrap-headers ok {:hsts {:max-age 0}})))
    (is (fn? (security/wrap-headers ok {:hsts {:max-age 1}})))))

(deftest csp-carries-the-request-nonce-at-every-placeholder--and-the-same-nonce-is-on-the-request
  (let [seen (atom nil)
        spy  (fn [r] (reset! seen r) {:status 200 :body "x"})
        resp ((security/wrap-headers spy {:csp "script-src 'nonce-{nonce}' 'self'; x {nonce}"}) req)
        n    (:wb/nonce @seen)]
    (is (re-matches NONCE (str n)) "the request carries a real nonce")
    (is (= (assoc DEFAULTS "Content-Security-Policy" (str "script-src 'nonce-" n "' 'self'; x " n)) (:headers resp))
        "every placeholder carries the request's nonce")
    (is (not (str/includes? (get-in resp [:headers "Content-Security-Policy"]) "{nonce}")) "no placeholder left"))
  (is (= (assoc DEFAULTS "Content-Security-Policy" "default-src 'self'") (headers-with {:csp "default-src 'self'"}))
      "a policy without placeholder is sent verbatim"))

(deftest nonce-is-on-every-request-without-csp--fresh-per-request--sixteen-random-bytes-base64
  (let [seen   (atom nil)
        app    (security/wrap-headers (fn [r] (reset! seen r) {:status 200 :body "x"}) {})
        nonces (vec (repeatedly 32 (fn [] (app req) (:wb/nonce @seen))))
        bytes  (mapv #(vec (.decode (Base64/getDecoder) ^String %)) nonces)]
    (is (every? #(re-matches NONCE %) nonces) "standard base64 with padding")
    (is (every? #(= 16 (count %)) bytes) "16 bytes each")
    (is (= 32 (count (set nonces))) "thirty-two requests, thirty-two nonces")
    (is (every? (fn [position] (> (count (set (map #(nth % position) bytes))) 1)) (range 16))
        "every byte position varies")
    (app (assoc req :wb/nonce "STALE"))
    (is (re-matches NONCE (str (:wb/nonce @seen))) "a stale nonce on the request is replaced")
    (is (= DEFAULTS (:headers (app req))) "no CSP header appears just because a nonce exists")))

(deftest wrap-headers-passes-a-nil-response-through-as-nil--after-having-called-the-handler-with-a-nonce
  (doseq [config [{} {:csp "x{nonce}"}]]
    (let [seen (atom nil)
          out  ((security/wrap-headers (fn [r] (reset! seen r) nil) config) req)]
      (is (nil? out) (str "nil stays nil with " (pr-str config)))
      (is (re-matches NONCE (str (:wb/nonce @seen))) "and the handler was called with a nonce"))))

(deftest wrap-proxy-takes-the-first-forwarded-proto-lowercased--only-http-or-https--else-leaves-scheme-alone
  (let [proxy (security/wrap-proxy identity)]
    (doseq [[value scheme] [["HTTPS, http" :https] ["https" :https] [" https " :https]]]
      (let [original (mock/header req "X-Forwarded-Proto" value)]
        (is (= (assoc original :scheme scheme) (proxy original)) (str "proto " (pr-str value)))))
    (let [original (assoc (mock/header req "X-Forwarded-Proto" "http") :scheme :https)]
      (is (= (assoc original :scheme :http) (proxy original)) "http on an https request"))
    (doseq [value ["gopher" "" ",https"]]
      (let [original (mock/header req "X-Forwarded-Proto" value)]
        (is (= original (proxy original)) (str "left alone: " (pr-str value)))))))

(deftest wrap-proxy-takes-the-first-forwarded-for-trimmed--and-without-headers-the-request-is-untouched
  (let [proxy (security/wrap-proxy identity)]
    (doseq [[value addr] [["10.0.0.1, 10.0.0.2" "10.0.0.1"] ["  203.0.113.9  " "203.0.113.9"]]]
      (let [original (mock/header req "X-Forwarded-For" value)]
        (is (= (assoc original :remote-addr addr) (proxy original)) (str "for " (pr-str value)))))
    (doseq [value ["" " , 1.2.3.4"]]
      (let [original (mock/header req "X-Forwarded-For" value)]
        (is (= original (proxy original)) (str "left alone: " (pr-str value)))))
    (let [original (-> req (mock/header "X-Forwarded-Proto" "https") (mock/header "X-Forwarded-For" "203.0.113.9"))]
      (is (= (assoc original :scheme :https :remote-addr "203.0.113.9") (proxy original)) "both headers"))
    (is (= req (proxy req)) "no headers, no change")))

;; --- CSRF ------------------------------------------------------------------

(def ^:private render-error (error/renderer {}))

(defn- csrf-app
  "A spy handler behind csrf behind the session over a memory store the test
  can read."
  [sessions seen]
  (session/wrap (security/wrap-csrf (fn [r]
                                      ;; The dynamic var is bound only while the handler runs.
                                      (reset! seen (assoc r ::bound (force anti-forgery/*anti-forgery-token*)))
                                      {:status 200 :body "x"})
                                    render-error)
                {:store (memory/memory-store sessions)}))

(defn- sid-of [response]
  (second (re-find #"^ring-session=([^;]*);" (str (first (get-in response [:headers "Set-Cookie"]))))))

(defn- with-sid [request sid] (mock/header request "Cookie" (str "ring-session=" sid)))

(defn- established
  "`[app sessions sid token]` after one GET."
  []
  (let [sessions (atom {})
        seen     (atom nil)
        app      (csrf-app sessions seen)
        response (app req)]
    [app sessions (sid-of response) (security/csrf-token @seen) seen response]))

(deftest csrf-get-issues-a-token-on-the-request-and-stores-that-same-token-in-the-session
  (let [[app sessions sid t seen response] (established)]
    (is (= {:status 200 :body "x"} (dissoc response :headers)))
    (is (some? sid) "a session cookie was issued")
    (is (re-matches #"[A-Za-z0-9+/]{80}" (str t)) "ring-anti-forgery 1.4.0's token shape: 60 random bytes, base64 unpadded")
    (is (= {sid {:ring.middleware.anti-forgery/anti-forgery-token t}} @sessions) "the session holds the token and nothing else")
    (is (= t (:anti-forgery-token @seen) (::bound @seen)) "the accessor reads what the library bound")
    (is (= {:status 200 :body "x"} (app (with-sid req sid))) "a second GET with the cookie rewrites nothing")
    (is (= {sid {:ring.middleware.anti-forgery/anti-forgery-token t}} @sessions) "same token, same session")))

(deftest csrf-post-without-a-token-is-render-errors-403-datum--fragment-on-a-partial--page-on-a-navigation--text-otherwise
  (let [[app _ sid] (established)
        post (with-sid (mock/request :post "/") sid)]
    (is (= {:status 403 :headers HTML :body PAGE-403} (app post)) "navigation → 403 page")
    (is (= {:status 403 :headers HTML :body FRAG-403} (app (mock/header post "HX-Request" "true"))) "partial → 403 fragment")
    (is (= {:status 403 :headers TEXT :body "403"} (app (mock/header post "Accept" "text/plain"))) "text")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app (mock/request :post "/"))) "without any cookie: no session is created either")
    (let [seen     (promise)
          spy      (fn [datum request] (deliver seen [datum request]) {:status 999 :body "spy"})
          spied    (session/wrap (security/wrap-csrf ok spy) {:store (memory/memory-store)})
          original (mock/request :post "/")
          out      (spied original)
          [datum request] (deref seen 0 [::never ::never])]
      (is (= {:status 999 :body "spy"} out) "the refusal is render-error's response")
      (is (= {:status 403} datum) "with exactly {:status 403}")
      (is (= original (select-keys request (keys original))) "and the request as sent")
      (is (contains? request :session) "session middleware ran before"))))

(deftest csrf-post-passes-with-the-sessions-token--refused-without-the-cookie--tampered--or-with-another-sessions-token
  (let [[app sessions sid t] (established)
        [_ _ _ t2]           (established)
        post                 (with-sid (mock/request :post "/") sid)
        tampered             (str (if (= \A (first t)) "B" "A") (subs t 1))]
    (is (not= t t2) "precondition: two sessions, two tokens")
    (is (= {:status 200 :body "x"} (app (mock/header post "X-CSRF-Token" t))) "the session's token in the header passes")
    (is (= {sid {:ring.middleware.anti-forgery/anti-forgery-token t}} @sessions) "a passing POST rewrites nothing")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app (mock/header (mock/request :post "/") "X-CSRF-Token" t))) "token without its cookie")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app (mock/header post "X-CSRF-Token" tampered))) "one character off")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app (mock/header post "X-CSRF-Token" t2))) "another session's genuine token")
    (is (= {:status 200 :body "x"} (app (mock/header post "X-CSRF-Token" t))) "control: the refusals did not erase the stored token")))

(deftest csrf-form-field-passes-only-when-wrap-params-runs-before-csrf--and-the-field-is-the-one-csrf-field-renders
  (let [[_ sessions sid t] (established)
        store    (memory/memory-store sessions)
        field    (second (security/csrf-field {:anti-forgery-token t}))
        with     (session/wrap (params/wrap-params (security/wrap-csrf ok render-error)) {:store store})
        without  (session/wrap (security/wrap-csrf ok render-error) {:store store})
        posted   (fn [app body] (app (with-sid (mock/body (mock/request :post "/") body) sid)))]
    (is (= {:status 200 :body "x"} (posted with {(:name field) (:value field)})) "params before csrf: the field is read")
    (is (= {:status 403 :headers HTML :body PAGE-403} (posted without {(:name field) (:value field)})) "csrf before params: the field is invisible")
    (is (= {:status 403 :headers HTML :body PAGE-403}
           (with (with-sid (mock/request :post (str "/?" (:name field) "=" (java.net.URLEncoder/encode ^String t "UTF-8"))) sid)))
        "a token in the query string is not accepted")
    (is (= {:status 403 :headers HTML :body PAGE-403} (posted with {"anti-forgery-token" t})) "a wrongly named field")))

(deftest csrf-safe-methods-need-no-token--every-other-method-does
  (let [[app _ sid t] (established)]
    (doseq [method [:get :head :options]]
      (is (= {:status 200 :body "x"} (app (with-sid (mock/request method "/") sid))) (str (name method) " without token")))
    (doseq [method [:post :put :delete :patch]]
      (is (= {:status 403 :headers HTML :body PAGE-403} (app (with-sid (mock/request method "/") sid))) (str (name method) " without token"))
      (is (= {:status 200 :body "x"} (app (mock/header (with-sid (mock/request method "/") sid) security/csrf-header t)))
          (str (name method) " with the token under the name csrf-header publishes")))))

(deftest csrf-field-and-csrf-header-are-the-literal-shapes-forms-and-the-shell-rely-on
  (is (= [:input {:type "hidden" :name "__anti-forgery-token" :value "T<&"}] (security/csrf-field {:anti-forgery-token "T<&"})))
  (is (= "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T&lt;&amp;\">"
         (render/html (security/csrf-field {:anti-forgery-token "T<&"})))
      "escaped once, by the renderer")
  (is (nil? (security/csrf-field {})) "no token, no field")
  (is (= "X-CSRF-Token" security/csrf-header)))

(deftest csrf-under-the-cookie-store--the-token-survives-sealing-and-a-post-with-cookie-and-token-passes
  (let [seen  (atom nil)
        app   (session/wrap (security/wrap-csrf (fn [r] (reset! seen r) {:status 200 :body "x"}) render-error)
                            {:key "AAECAwQFBgcICQoLDA0ODw=="})
        first* (app req)
        cookie (second (re-matches #"ring-session=([A-Za-z0-9%]+--[A-Za-z0-9%]+); Path=/; HttpOnly; SameSite=Lax; Secure"
                                   (str (first (get-in first* [:headers "Set-Cookie"])))))
        t      (security/csrf-token @seen)
        post   (mock/header (mock/request :post "/") "Cookie" (str "ring-session=" cookie))]
    (is (some? cookie) "the cookie store sealed a session")
    (is (= {:status 200 :body "x"} (app (mock/header post "X-CSRF-Token" t))) "cookie + token passes, session not re-sealed")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app post)) "cookie without token")
    (is (= {:status 403 :headers HTML :body PAGE-403} (app (mock/header (mock/request :post "/") "X-CSRF-Token" t))) "token without cookie")))
