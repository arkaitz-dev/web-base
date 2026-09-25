(ns dev.arkaitz.web-base.testing-test
  "The readers a host's suite relies on, pinned against literal wire shapes
  and against the real middleware that writes them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private TOKEN "tok/ABC+123=")

(defn- login-app []
  (wb/handler {:routes  [["/login" {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user "ann"}))}]
                         ["/me"    {:get (fn [r] {:status 200 :body (pr-str (:session r))})}]]
               :session {:key KEY}
               :csrf    false}))

(deftest cookies-reads-set-cookie-as-ring-writes-it--one-string-or-a-seq--any-header-case--value-up-to-the-first-semicolon
  (let [app        (session/wrap (fn [_] (session/rotate {:status 200 :body ""} {:user "ann"})) {:key KEY})
        r          (app (mock/request :post "/login"))
        set-cookie (get-in r [:headers "Set-Cookie"])
        raw        (str (first set-cookie))
        sealed     (second (re-matches #"ring-session=([A-Za-z0-9%]+--[A-Za-z0-9%]+); Path=/; HttpOnly; SameSite=Lax; Secure" raw))]
    (is (and (sequential? set-cookie) (not (vector? set-cookie))) "Ring writes Set-Cookie as a lazy seq, never a vector: the shape the reader must take")
    (is (string? sealed) (str "the cookie store wrote a sealed session: " raw))
    (is (= {"ring-session" sealed} (testing/cookies r)) "Ring's real Set-Cookie is read whole"))
  (is (= {"a" "1"} (testing/cookies {:headers {"Set-Cookie" "a=1; Path=/"}})) "a lone string is one cookie")
  (is (= {"a" "1" "b" "x=="} (testing/cookies {:headers {"set-cookie" ["a=1; Path=/" "b=x==; HttpOnly"]}}))
      "an HTTP client's lowercase header, two cookies, a padded value kept whole")
  (is (= {"ring-session" nil} (testing/cookies {:headers {"Set-Cookie" ["ring-session=; Max-Age=0; Path=/"]}}))
      "a deletion cookie — Max-Age 0 — is nil: to be forgotten")
  (is (= {"e" ""} (testing/cookies {:headers {"Set-Cookie" "e=; Path=/"}})) "an empty value without Max-Age is a kept cookie")
  (is (= {"a" "1"} (testing/cookies {:headers {"Set-Cookie" "a=1; Max-Age=3600; Path=/"}})) "a positive Max-Age keeps the cookie")
  (is (= {"a" nil} (testing/cookies {:headers {"Set-Cookie" "a=1; Max-Age=-1; Path=/"}})) "a negative Max-Age is a deletion too: zero or less")
  (is (= {"a" "1"} (testing/cookies {:headers {"Set-Cookie" "a=1; Max-Age=soon; Path=/"}}))
      "a Max-Age that is not a number is ignored, and the cookie kept (RFC 6265 §5.2.2)")
  (is (= {"a" "1"} (testing/cookies {:headers {"Set-Cookie" "a=1; Max-Age=; Path=/"}})) "an empty Max-Age likewise")
  (is (= [{} {}] [(testing/cookies {:headers {}}) (testing/cookies {})]) "no Set-Cookie: nothing"))

(deftest with-cookies-puts-every-cookie-of-the-response-on-the-request-as-a-browser-would--and-ring-reads-it-back
  (let [req (mock/request :get "/")]
    (is (= (assoc-in req [:headers "cookie"] "a=1; b=2")
           (testing/with-cookies req {:headers {"Set-Cookie" ["a=1; Path=/" "b=2; HttpOnly"]}}))
        "the cookie header is exactly n1=v1; n2=v2 and nothing else changed")
    (is (= req (testing/with-cookies req {:headers {"Set-Cookie" ["ring-session=; Max-Age=0"]}}))
        "a deletion of a cookie the request never had: untouched")
    (is (= req (testing/with-cookies req {:status 200})) "no Set-Cookie: request untouched")
    (is (= (assoc-in req [:headers "cookie"] "e=")
           (testing/with-cookies req {:headers {"Set-Cookie" ["e=; Path=/"]}}))
        "a cookie whose value is empty but which is not a deletion travels in the jar")
    (let [odd (assoc-in req [:headers "cookie"] "keep=me;a=old")]
      (is (= odd (testing/with-cookies odd {:status 200}))
          "an unchanged jar leaves the request's own header exactly as it was, canonical or not"))
    (let [jarred (assoc-in req [:headers "cookie"] "a=old; keep=me")]
      (is (= (assoc-in req [:headers "cookie"] "a=old; b=2; keep=me")
             (testing/with-cookies jarred {:headers {"Set-Cookie" ["b=2; Path=/"]}}))
          "a cookie the request already carried survives beside the new one")
      (is (= (assoc-in req [:headers "cookie"] "a=new; keep=me")
             (testing/with-cookies jarred {:headers {"Set-Cookie" ["a=new; Path=/"]}}))
          "a cookie set again replaces the old value, not appended")
      (is (= (assoc-in req [:headers "cookie"] "keep=me")
             (testing/with-cookies jarred {:headers {"Set-Cookie" ["a=; Max-Age=0"]}}))
          "a deletion forgets the cookie the request carried")
      (is (= req (testing/with-cookies (assoc-in req [:headers "cookie"] "a=1") {:headers {"Set-Cookie" ["a=; Max-Age=0"]}}))
          "deleting the only cookie removes the header")))
  (let [app   (login-app)
        login (app (mock/request :post "/login"))]
    (is (= "{}" (:body (app (mock/request :get "/me")))) "control: no cookie, an empty session")
    (is (= "{:user \"ann\"}" (:body (app (testing/with-cookies (mock/request :get "/me") login))))
        "the session login wrote is read back through with-cookies")))

(deftest csrf-token-reads-the-hidden-field-in-any-attribute-order-or-the-shells-hx-headers--whole-token--nil-otherwise
  (is (= TOKEN (testing/csrf-token {:body (str "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"" TOKEN "\">")}))
      "Hiccup's attribute order; base64 + / = kept")
  (is (= TOKEN (testing/csrf-token {:body (str "<input value=\"" TOKEN "\" type=\"hidden\" name=\"__anti-forgery-token\">")}))
      "attribute order does not matter")
  (is (= "T1" (testing/csrf-token {:body "<input name=\"other\" type=\"hidden\" value=\"NOPE\"><form><input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T1\"></form>"}))
      "a decoy hidden field before the token is skipped")
  (is (= TOKEN (testing/csrf-token {:body (str "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;" TOKEN "&quot;}\"><main></main></body>")}))
      "the shell's body attribute, as Hiccup escapes it")
  (is (= "FROMFIELD"
         (testing/csrf-token {:body "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;FROMBODY&quot;}\"><form><input name=\"__anti-forgery-token\" type=\"hidden\" value=\"FROMFIELD\"></form></body>"}))
      "both carriers present: the hidden field is read first")
  (is (= [nil nil nil nil]
         [(testing/csrf-token {:body "<p>hi</p>"})
          (testing/csrf-token {:body nil})
          (testing/csrf-token {:body [:input {:name "__anti-forgery-token" :value "x"}]})
          (testing/csrf-token {:body (io/input-stream (.getBytes "x"))})])
      "nil, not a throw, when the body is not a string or has no token")
  (let [app    (wb/handler {:routes  [["/" {:wb/layouts [shell/page]
                                            :get  (fn [_] {:status 200 :body [:p "hi"]})
                                            :post (fn [_] {:status 200 :body "posted"})}]]
                            :session {:key KEY}})
        r      (app (mock/request :get "/"))
        t      (testing/csrf-token r)
        oracle (second (re-find #"hx-headers:inherited=\"\{&quot;X-CSRF-Token&quot;:&quot;([^&]+)&quot;\}\"" (:body r)))]
    (is (and (string? oracle) (re-matches #"[A-Za-z0-9+/]{80}" oracle)) "the shell embedded a token (independent regex)")
    (is (= oracle t) "csrf-token reads the shell's real token whole")
    (is (= [200 "posted"]
           (let [out (app (-> (mock/request :post "/") (testing/with-cookies r) (assoc-in [:headers "x-csrf-token"] t)))]
             [(:status out) (:body out)]))
        "and wrap-csrf accepts it")))

(deftest fragment-adds-exactly-the-header-the-classifier-reads--and-nothing-else
  (let [req   (mock/request :get "/")
        outer (fn [{:keys [content]}] [:div#outer content])]
    (is (= (update req :headers assoc "hx-request" "true" "hx-request-type" "partial") (testing/fragment req))
        "fragment adds htmx 4's two swap headers and touches nothing else")
    (is (= [true false] [(htmx/partial-request? (testing/fragment req)) (htmx/partial-request? req)]) "the classifier accepts it, and not the original")
    (is (true? (htmx/partial-request? (testing/fragment (assoc-in req [:headers "hx-request-type"] "full"))))
        "a request marked as a whole document becomes a swap: fragment's headers win")
    (is (= {:headers {"hx-request" "true" "hx-request-type" "partial"}} (testing/fragment {})) "a request without :headers gets them")
    (is (= ["<p>x</p>" "<!DOCTYPE html>\n<div id=\"outer\"><p>x</p></div>"]
           [(:body (render/response (testing/fragment req) {:status 200 :body [:p "x"]} [outer]))
            (:body (render/response req {:status 200 :body [:p "x"]} [outer]))])
        "render answers the bare fragment for it and the whole page for the original")))

;; --- the browser --------------------------------------------------------------
;;
;; A real `wb/handler` with sessions and CSRF on, wrapped so that every request it
;; receives is logged as [method uri cookie-header] — the observation the browser's own
;; jar cannot fake, because it is what the server was actually sent.

(defn- browser-app []
  (let [log   (atom [])
        loops (atom 0)
        app   (wb/handler
               {:session {:key KEY}
                :routes  [["/form" {:wb/layouts [shell/page]
                                    :get (fn [r] (response/ok [:form (security/csrf-field r)]))}]
                          ["/login" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/me"} :body ""}
                                                                   {:user "ann"}))}]
                          ["/login-as" {:post (fn [r] (session/rotate {:status 303 :headers {"Location" "/me"} :body ""}
                                                                      {:user (get-in r [:params "who"])}))}]
                          ["/me" {:get (fn [r] {:status 200 :body (pr-str (dissoc (:session r) :ring.middleware.anti-forgery/anti-forgery-token))})}]
                          ["/echo" {:post (fn [r] {:status 200
                                                   :body   (pr-str {:form (:form-params r)
                                                                    :hdr  (get-in r [:headers "x-csrf-token"])
                                                                    :addr (:remote-addr r)})})}]
                          ["/frag" {:wb/layouts [shell/page] :post (fn [_] (response/ok [:p "posted"]))}]
                          ["/set-a" {:get (fn [r] {:status 200 :headers {"Set-Cookie" [(str "a=" (get-in r [:params "v"]) "; Path=/")]} :body "ok"})}]
                          ["/del-a" {:get (fn [_] {:status 200 :headers {"Set-Cookie" ["a=; Max-Age=0; Path=/"]} :body "ok"})}]
                          ["/" {:get (fn [_] {:status 200 :body "root"})}]
                          ["/r0" {:get (fn [_] {:status 301 :headers {"Location" "http://localhost/r1"} :body ""})}]
                          ["/r1" {:get (fn [_] {:status 303 :headers {"Location" "/r2"} :body ""})}]
                          ;; A lower-case header name, as some servers and proxies write it.
                          ["/r2" {:get (fn [_] {:status 302 :headers {"location" "/r3?x=1"} :body ""})}]
                          ["/to-root" {:get (fn [_] {:status 302 :headers {"Location" "http://localhost"} :body ""})}]
                          ["/r3" {:get (fn [r] {:status 200 :body (str "qs:" (:query-string r))})}]
                          ;; A loop that ends by itself after fifty turns, so a browser that lost
                          ;; its bound gets a 200 and reds the assertion instead of hanging.
                          ["/loop" {:get (fn [_] (if (< (swap! loops inc) 50)
                                                   {:status 302 :headers {"Location" "/loop"} :body ""}
                                                   {:status 200 :body "out"}))}]
                          ["/hx-go" {:get (fn [_] (htmx/redirect "/me"))}]]})]
    {:log log
     :app (fn [r] (swap! log conj [(:request-method r) (:uri r) (get-in r [:headers "cookie"])]) (app r))}))

(defn- set-cookie-of [response]
  (let [v (some (fn [[k v]] (when (= "set-cookie" (str/lower-case (str k))) v)) (:headers response))]
    (cond (nil? v) [] (string? v) [v] :else (vec v))))

(deftest the-jar-accumulates-across-a-page-that-sets-no-cookie--so-the-session-survives-it
  (let [{:keys [app log]} (browser-app)
        b1 (-> (testing/browser app) (testing/visit :get "/form") (testing/visit :post "/login"))
        _  (is (re-find #":user \"ann\"" (:body (:response b1))) "control: the login worked and landed on /me")
        b2 (testing/visit b1 :get "/me")
        b3 (testing/visit b2 :get "/me")]
    (is (= [] (set-cookie-of (:response b3)))
        "precondition: this /me set no cookie at all — the page the trap is about")
    (is (= (:jar b2) (:jar b3)) "and the jar is unchanged by it")
    (let [b4 (testing/visit b3 :get "/me")]
      (is (re-find #":user \"ann\"" (:body (:response b4)))
          (str "the request after a cookie-less page still carries the session: " (:body (:response b4))))
      (is (some? (nth (last @log) 2)) "it sent cookies at all")
      (is (= (nth (last (butlast @log)) 2) (nth (last @log) 2)) "and the same ones as the request before"))))

(deftest a-cookie-set-again-replaces-and-a-max-age-zero-deletion-forgets
  (let [{:keys [app log]} (browser-app)
        sent #(nth (last @log) 2)
        b    (-> (testing/browser app) (testing/visit :get "/form")
                 (testing/visit :get "/set-a?v=1") (testing/visit :get "/set-a?v=2") (testing/visit :get "/form"))]
    (is (str/includes? (sent) "a=2") "the second value was sent")
    (is (not (str/includes? (sent) "a=1")) "and the first was replaced, not kept beside it")
    (is (str/includes? (sent) "ring-session=") "with the session cookie still beside it")
    (let [b (-> b (testing/visit :get "/del-a") (testing/visit :get "/form"))]
      (is (not (str/includes? (sent) "a=")) "a Max-Age=0 deletion is forgotten")
      (is (str/includes? (sent) "ring-session=") "and only that cookie")
      (is (not (contains? (:jar b) "a")) "the jar has no entry for it"))))

(deftest a-post-with-no-token-throws-before-sending--and-a-wrong-token-is-the-403-it-replaces
  (let [{:keys [app log]} (browser-app)
        e (try (testing/visit (testing/browser app) :post "/echo" {:x "1"}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (re-find #"no CSRF token" (str (ex-message e))) "a POST with no token known throws, naming why")
    (is (= {:path "/echo"} (ex-data e)))
    (is (= [] @log) "before anything was sent"))
  (let [{:keys [app]} (browser-app)
        b (testing/visit (testing/browser app) :get "/form")]
    (is (= 403 (:status (:response (testing/visit (assoc b :token "wrong") :post "/echo" {:x "1"}))))
        "control: CSRF is on, so a wrong token is refused — the oracle below bites")
    (let [ok (:response (testing/visit b :post "/echo" {:x "1"}))]
      (is (= 200 (:status ok)) "the token of the page in front of it passes")
      (is (= {"x" "1"} (dissoc (:form (edn/read-string (:body ok))) "__anti-forgery-token"))))))

(deftest after-login-the-token-is-the-new-one--the-stale-one-is-refused
  (let [{:keys [app]} (browser-app)
        b  (testing/visit (testing/browser app) :get "/form")
        t1 (:token b)
        b  (testing/visit b :post "/login")]
    (is (string? t1) "witness: the first page carried a token")
    (is (= 403 (:status (:response (testing/visit (assoc b :token t1) :post "/echo" {:x "1"}))))
        "the pre-login token is refused once the session was rotated — why the docstring says GET first")
    (let [b  (testing/visit b :get "/form")
          t2 (:token b)]
      (is (and (string? t2) (not= t1 t2)) "the page after login carries a new token, and the browser keeps it")
      (is (= 200 (:status (:response (testing/visit b :post "/echo" {:x "1"})))) "which passes"))))

(deftest form-encoding-round-trips-non-ascii-and-the-separators
  (let [{:keys [app]} (browser-app)
        b  (testing/visit (testing/browser app) :get "/form")
        ok (:response (testing/visit b :post "/echo" {"q" "a&b=c" "a&b" "1 + 1" "ñ" "ünï cødé ✓"}))]
    (is (= {"q" "a&b=c" "a&b" "1 + 1" "ñ" "ünï cødé ✓"}
           (dissoc (:form (edn/read-string (:body ok))) "__anti-forgery-token"))
        "every key and value arrives as written")))

(deftest redirects-are-followed-as-get-through-the-jar--hx-redirect-is-left--the-loop-bound-throws
  (let [{:keys [app log]} (browser-app)
        b (testing/visit (testing/browser app) :get "/r0")]
    (is (= [[:get "/r0"] [:get "/r1"] [:get "/r2"] [:get "/r3"]] (mapv #(subvec % 0 2) @log))
        (str "a 301 to an absolute URL, a 303, and a 302 whose header is spelt in lower case —"
             " each followed as a GET of its path"))
    (is (= "qs:x=1" (:body (:response b))) "the query string of a Location arrives"))
  (let [{:keys [app log]} (browser-app)
        b (testing/visit (testing/browser app) :get "/to-root")]
    (is (= [[:get "/to-root"] [:get "/"]] (mapv #(subvec % 0 2) @log)) "an origin with no path is its root")
    (is (= "root" (:body (:response b)))))
  (let [{:keys [app log]} (browser-app)
        b (-> (testing/browser app) (testing/visit :get "/form") (testing/visit :post "/login"))]
    (is (= [[:post "/login"] [:get "/me"]] (mapv #(subvec % 0 2) (take-last 2 @log))) "the 303 of a POST becomes a GET")
    (is (re-find #":user \"ann\"" (:body (:response b))) "carrying the cookie the 303 set"))
  (let [{:keys [app log]} (browser-app)
        b (testing/visit (testing/browser app) :get "/hx-go")]
    (is (= 1 (count @log)) "an HX-Redirect is not followed")
    (is (= "/me" (get-in (:response b) [:headers "HX-Redirect"])) "and is left for the test to read"))
  (let [{:keys [app log]} (browser-app)
        e (try (testing/visit (testing/browser app) :get "/loop") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (re-find #"more than 10 redirects" (str (ex-message e))) "a redirect loop throws")
    (is (= 11 (count @log)) "after the first request and ten followed")))

(deftest htmx-sends-the-header-token-and-the-fragment-headers--remote-addr--and-the-old-browser-is-unchanged
  (let [{:keys [app]} (browser-app)
        b (testing/visit (testing/browser app) :get "/form")]
    (is (= "<p>posted</p>" (:body (:response (testing/visit b :post "/frag" {} {:htmx? true}))))
        "as htmx: the fragment, not the shell")
    (is (str/starts-with? (:body (:response (testing/visit b :post "/frag"))) "<!DOCTYPE html>")
        "control: the same POST without it is a whole page")
    (let [echoed (edn/read-string (:body (:response (testing/visit b :post "/echo" {:x "1"} {:htmx? true}))))]
      (is (= (:token b) (:hdr echoed)) "the token went as the header")
      (is (= {"x" "1"} (:form echoed)) "and not as a form field"))
    (is (= "127.0.0.1" (:addr (edn/read-string (:body (:response (testing/visit b :post "/echo" {}))))))
        "the default source")
    (is (= "10.0.0.7" (:addr (edn/read-string (:body (:response (testing/visit b :post "/echo" {} {:remote-addr "10.0.0.7"}))))))
        "and the one asked for"))
  (let [{:keys [app]} (browser-app)
        b1 (testing/visit (testing/browser app) :get "/form")]
    (let [ann (-> b1 (testing/visit :post "/login-as" {:who "ann"}))
          bob (-> b1 (testing/visit :post "/login-as" {:who "bob"}))]
      (is (re-find #":user \"ann\"" (:body (:response (testing/visit ann :get "/me")))) "two tabs from one value")
      (is (re-find #":user \"bob\"" (:body (:response (testing/visit bob :get "/me")))) "each with its own session"))))
