(ns dev.arkaitz.web-base.testing-test
  "The readers a host's suite relies on, pinned against literal wire shapes
  and against the real middleware that writes them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.render :as render]
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
