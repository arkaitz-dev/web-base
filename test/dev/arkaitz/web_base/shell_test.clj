(ns dev.arkaitz.web-base.shell-test
  "Every expected document is a literal observed once by hand. Hiccup orders
  attributes alphabetically and renders `:defer true` as a bare `defer`."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]
            [hiccup2.core :as h]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [java.security MessageDigest]))

(def ^:private HEAD-START "<head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">")
(def ^:private ASSETS "<link href=\"/wb/wb.css\" rel=\"stylesheet\"><script defer src=\"/wb/htmx.min.js\"></script>")
(def ^:private SKEL (str HEAD-START ASSETS "</head><body><main class=\"wb-main\"></main></body></html>"))

(defn- html [slots] (render/html (shell/page slots)))

(deftest empty-slot-map-emits-only-the-fixed-skeleton
  (is (= (str "<html>" SKEL) (html {})) "no slot → skeleton only: no empty container, no lang, no nonce, no hx-headers")
  (is (= ["/wb/wb.css" "/wb/htmx.min.js"] [shell/css-path shell/htmx-path])))

(deftest every-slot-lands-in-its-own-container-in-document-order--escaped
  (is (= (str "<html lang=\"eu\">" HEAD-START "<title>T &lt;x&gt;</title>" ASSETS "<meta content=\"b\" name=\"a\"></head>"
              "<body><header class=\"wb-header\"><h1>H</h1></header><nav class=\"wb-nav\"><a href=\"/\">n</a></nav>"
              "<div class=\"wb-identity\"><span>me</span></div><main class=\"wb-main\"><p>c</p></main>"
              "<footer class=\"wb-footer\"><small>f</small></footer></body></html>")
         (html {:lang "eu" :title "T <x>" :head [:meta {:name "a" :content "b"}]
                :header [:h1 "H"] :nav [:a {:href "/"} "n"] :identity [:span "me"] :content [:p "c"] :footer [:small "f"]}))
      "each slot in its own container, document order, :head last in <head>, title escaped"))

(deftest head-slot-accepts-a-seq-and-is-appended-last
  (is (= (str "<html>" HEAD-START ASSETS "<meta name=\"a\"><link href=\"/i\" rel=\"icon\"></head><body><main class=\"wb-main\"></main></body></html>")
         (html {:head (list [:meta {:name "a"}] [:link {:rel "icon" :href "/i"}])}))
      "a seq :head renders every element after the base script"))

(deftest lang-explicit-wins--locale-fills-when-absent--nothing-otherwise
  (doseq [[label slots expected] [["explicit wins"   {:lang "eu" :request {:wb/locale :es}} "<html lang=\"eu\">"]
                                  ["keyword locale"  {:request {:wb/locale :es}}            "<html lang=\"es\">"]
                                  ["string locale"   {:request {:wb/locale "es"}}           "<html lang=\"es\">"]
                                  ["no locale"       {:request {}}                          "<html>"]
                                  ["no request"      {}                                     "<html>"]]]
    (testing label
      (is (= (str expected SKEL) (html slots))))))

(deftest nonce-on-the-htmx-script-iff-the-request-carries-wb-nonce
  (is (= (str "<html>" HEAD-START "<link href=\"/wb/wb.css\" rel=\"stylesheet\"><script defer nonce=\"abc+/==\" src=\"/wb/htmx.min.js\"></script>"
              "</head><body><main class=\"wb-main\"></main></body></html>")
         (html {:request {:wb/nonce "abc+/=="}}))
      "nonce lands on the htmx script only"))

(def ^:private TOKEN "tok/ABC+123=")
(def ^:private BODY-TOKEN "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;tok/ABC+123=&quot;}\">")

(defn- token-request
  "A request carrying a token its session holds and its own fresh read flag, as
  `wrap-csrf` builds one for a signed-in visitor: a flag shared between cases would make
  every later `true` vacuous."
  []
  {:anti-forgery-token TOKEN ::security/csrf-used (volatile! false) ::security/csrf-stored true})

(defn- with-token [main] (str "<html>" HEAD-START ASSETS "</head>" BODY-TOKEN main "</body></html>"))

(deftest token-on-body-iff-the-rendered-body-uses-it--htmx-attribute-in-any-slot-or-a-form-that-read-it--and-the-read-itself
  (testing "a formless, hx-free page: the token is neither emitted nor read"
    (let [req (token-request)]
      (is (= (str "<html>" SKEL) (html {:request req})) "no hx, no form → no hx-headers on <body>")
      (is (false? @(::security/csrf-used req)) "formless, hx-free → no token read (csrf-used stays false)")))
  (testing "an attribute that merely contains hx- is not one htmx acts on"
    (let [req (token-request)]
      (is (= (str "<html>" HEAD-START ASSETS "</head><body><main class=\"wb-main\"><a class=\"hx-link\" href=\"/docs/hx-boost\" id=\"hx-doc\">docs</a></main></body></html>")
             (html {:request req :content [:a {:href "/docs/hx-boost" :id "hx-doc" :class "hx-link"} "docs"]}))
          "hx- inside a value is not an attribute → no token")
      (is (false? @(::security/csrf-used req)) "and no read")))
  (doseq [[label slots main]
          [["hx-post keyword attribute"
            {:content [:button {:hx-post "/x"} "go"]}
            "<main class=\"wb-main\"><button hx-post=\"/x\">go</button></main>"]
           ["data-hx- prefix"
            {:content [:a {:data-hx-get "/x"} "go"]}
            "<main class=\"wb-main\"><a data-hx-get=\"/x\">go</a></main>"]
           ["upper-case attribute, which an HTML parser lower-cases"
            {:content [:button {:HX-Post "/x"} "go"]}
            "<main class=\"wb-main\"><button HX-Post=\"/x\">go</button></main>"]
           ["lazy content, untouched before the call"
            {:content (for [x ["a" "b"]] [:button {:hx-post (str "/" x)} x])}
            "<main class=\"wb-main\"><button hx-post=\"/a\">a</button><button hx-post=\"/b\">b</button></main>"]
           ["a bare boolean attribute, as hiccup renders {:hx-boost true}"
            {:content [:div {:hx-boost true} "x"]}
            "<main class=\"wb-main\"><div hx-boost>x</div></main>"]
           ["raw HTML"
            {:content (h/raw "<button hx-post=\"/x\">go</button>")}
            "<main class=\"wb-main\"><button hx-post=\"/x\">go</button></main>"]
           ["hx only in :header"
            {:header [:a {:hx-get "/h"} "h"] :content [:p "c"]}
            "<header class=\"wb-header\"><a hx-get=\"/h\">h</a></header><main class=\"wb-main\"><p>c</p></main>"]
           ["hx only in :nav"
            {:nav [:a {:hx-get "/n"} "n"] :content [:p "c"]}
            "<nav class=\"wb-nav\"><a hx-get=\"/n\">n</a></nav><main class=\"wb-main\"><p>c</p></main>"]
           ["hx only in :identity"
            {:identity [:a {:hx-get "/i"} "i"] :content [:p "c"]}
            "<div class=\"wb-identity\"><a hx-get=\"/i\">i</a></div><main class=\"wb-main\"><p>c</p></main>"]
           ["hx only in :footer"
            {:footer [:a {:hx-get "/f"} "f"] :content [:p "c"]}
            "<main class=\"wb-main\"><p>c</p></main><footer class=\"wb-footer\"><a hx-get=\"/f\">f</a></footer>"]]]
    (testing label
      (let [req (token-request)]
        (is (= (with-token main) (html (assoc slots :request req))) (str label " → token on <body>"))
        (is (true? @(::security/csrf-used req)) (str label " → the token was read, so the session keeps it")))))
  (testing "a form in lazy content, no htmx: the field read the token while the body rendered"
    (let [req  (token-request)
          form (map (fn [_] [:form (security/csrf-field req)]) [:once])]
      (is (false? @(::security/csrf-used req)) "witness: nothing read the token before the shell ran")
      (is (= (with-token "<main class=\"wb-main\"><form><input name=\"__anti-forgery-token\" type=\"hidden\" value=\"tok/ABC+123=\"></form></main>")
             (html {:request req :content form}))
          "the body's token and the field's are one, and the decision came after the form rendered")
      (is (true? @(::security/csrf-used req)))))
  (testing "content that read the token itself, with no form and no htmx — a meta for a script"
    (let [req  (token-request)
          read (map (fn [_] [:span {:data-token (security/csrf-token req)}]) [:once])]
      (is (= (with-token "<main class=\"wb-main\"><span data-token=\"tok/ABC+123=\"></span></main>")
             (html {:request req :content read}))
          "a read by the content puts the token on <body> too: the session keeps it either way")))
  (testing "an anonymous htmx page carries no token: its session holds none (since 0.21.0)"
    (let [req (dissoc (token-request) ::security/csrf-stored)]
      (is (= (str "<html>" HEAD-START ASSETS "</head><body><main class=\"wb-main\"><button hx-post=\"/x\">go</button></main></body></html>")
             (html {:request req :content [:button {:hx-post "/x"} "go"]}))
          "no hx-headers on <body>, though the request carries the library's token")
      (is (false? @(::security/csrf-used req)) "and nothing was read")))
  (testing "an htmx page under `:csrf false` carries no token, because there is none"
    (is (= (str "<html>" HEAD-START ASSETS "</head><body><main class=\"wb-main\"><button hx-post=\"/x\">go</button></main></body></html>")
           (html {:request {} :content [:button {:hx-post "/x"} "go"]}))
        "no token on the request → no hx-headers, never an empty one"))
  (testing "lang, nonce and token compose"
    (is (= (str "<html lang=\"es\">" HEAD-START "<link href=\"/wb/wb.css\" rel=\"stylesheet\"><script defer nonce=\"n1\" src=\"/wb/htmx.min.js\"></script></head>"
                "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;tok&quot;}\"><main class=\"wb-main\"><button hx-post=\"/x\">go</button></main></body></html>")
           (html {:request {:anti-forgery-token "tok" :wb/nonce "n1" :wb/locale "es" ::security/csrf-used (volatile! false)
                            ::security/csrf-stored true}
                  :content [:button {:hx-post "/x"} "go"]})))))

(deftest works-as-the-outermost-route-layout-through-render-middleware
  (let [app (ring/ring-handler
             (ring/router [["/" {:wb/layouts [shell/page]
                                 :get (fn [_] {:status 200 :body [:p "hi"] :wb/slots {:title "Home"}})}]]
                          {:data {:middleware [render/middleware]}}))]
    (is (= {:status 200
            :headers {"Vary" "HX-Request, HX-Request-Type" "Content-Type" "text/html; charset=utf-8"}
            :body (str "<!DOCTYPE html>\n<html>" HEAD-START "<title>Home</title>" ASSETS "</head><body><main class=\"wb-main\"><p>hi</p></main></body></html>")}
           (app (mock/request :get "/")))
        "shell as route layout: doctype + title slot")
    (is (= "<p>hi</p>" (:body (app (mock/header (mock/request :get "/") "HX-Request" "true")))) "partial: bare")))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")

(defn- sign-in
  "The response to a sign-in to `app`: a POST the browser vouches for as same-origin,
  since a token is born only when the session rotates."
  [app]
  (let [login (app (mock/header (mock/request :post "/login") "Sec-Fetch-Site" "same-origin"))]
    (is (= 303 (:status login)) "witness: the sign-in passed CSRF and redirected")
    (is (seq (testing/cookies login)) "witness: and set the session cookie")
    login))

(defn- app-with-shell []
  (wb/handler {:routes   [["/" {:wb/layouts [shell/page]
                                :get  (fn [_] {:status 200 :body [:p "hi"]})}]
                          ["/hx" {:wb/layouts [shell/page]
                                  :get  (fn [_] {:status 200 :body [:button {:hx-post "/hx"} "go"]})
                                  :post (fn [_] {:status 200 :body [:p "posted"]})}]
                          ["/login" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/"} :body ""} {:user "ann"}))}]]
               :session  {:key KEY}
               :security {:csp "script-src 'nonce-{nonce}'"}}))

(deftest nonce-in-the-script-equals-the-nonce-in-the-csp-header--end-to-end
  (let [r ((app-with-shell) (mock/request :get "/"))
        n (second (re-find #"^script-src 'nonce-([A-Za-z0-9+/]{22}==)'$" (str (get-in r [:headers "Content-Security-Policy"]))))]
    (is (string? n) (str "CSP header present and well formed: " (pr-str (get-in r [:headers "Content-Security-Policy"]))))
    (is (str/includes? (:body r) (str "<script defer nonce=\"" n "\" src=\"/wb/htmx.min.js\"></script>")) "the script carries the CSP's nonce")
    (is (= 1 (count (re-seq #"nonce=\"" (:body r)))) "exactly one nonce attribute in the page")
    (is (str/starts-with? (:body r) "<!DOCTYPE html>\n<html><head>"))))

(deftest a-formless-hx-free-page-writes-no-session-and-is-not-marked-private--nor-does-an-anonymous-htmx-page--a-signed-in-one-is
  (let [app (app-with-shell)
        r   (app (mock/request :get "/"))
        a   (app (mock/request :get "/hx"))
        c   (app (testing/with-cookies (mock/request :get "/hx") (sign-in app)))]
    (is (= 200 (:status r)))
    (is (str/ends-with? (:body r) "</head><body><main class=\"wb-main\"><p>hi</p></main></body></html>")
        "witness: the page rendered through the shell, with a bare <body>")
    (is (= [] (testing/header-values (:headers r) "Set-Cookie"))
        (str "a formless, hx-free page set a session cookie: " (testing/header-values (:headers r) "Set-Cookie")))
    (is (nil? (testing/csrf-token r)) "and carries no token")
    (is (nil? (get-in r [:headers "Cache-Control"])) "nothing read the token, so the page is not marked private")
    (testing "an anonymous htmx page, by design since 0.21.0: its session holds no token to send"
      (is (str/ends-with? (:body a) "</head><body><main class=\"wb-main\"><button hx-post=\"/hx\">go</button></main></body></html>")
          "witness: the htmx page rendered, with a bare <body>")
      (is (= [] (testing/header-values (:headers a) "Set-Cookie"))
          (str "an anonymous htmx page set a session cookie: " (testing/header-values (:headers a) "Set-Cookie")))
      (is (nil? (testing/csrf-token a)) "and carries no token")
      (is (nil? (get-in a [:headers "Cache-Control"])) "nor is it marked private"))
    (testing "control: the same app, the htmx page of a signed-in visitor"
      (let [token (testing/csrf-token c)]
        (is (= "no-store" (get-in c [:headers "Cache-Control"])))
        (is (and (string? token) (re-matches #"[A-Za-z0-9+/]{80}" token)))
        (is (str/includes? (:body c) (str "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;" token
                                          "&quot;}\"><main class=\"wb-main\"><button hx-post=\"/hx\">go</button></main>")))))))

(deftest token-embedded-in-hx-headers-is-the-one-wrap-csrf-accepts--round-trip
  (let [app    (app-with-shell)
        login  (sign-in app)
        first* (app (testing/with-cookies (mock/request :get "/hx") login))
        token  (testing/csrf-token first*)
        post   (fn [base & headers] (let [r (reduce (fn [q [k v]] (mock/header q k v)) (mock/header base "HX-Request" "true") (partition 2 headers))
                                          out (app r)] [(:status out) (:body out)]))
        bare   (mock/request :post "/hx")
        jarred (testing/with-cookies bare login)
        frag   "<div class=\"wb-error\" data-status=\"403\"><strong class=\"wb-error-status\">403</strong></div>"]
    (is (str/includes? (:body first*) "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;") "the shell put the token on <body>")
    (is (and (string? token) (= 80 (count token))) "the shell embedded the session's token (ring-anti-forgery 1.4.0: 60 random bytes, base64 unpadded)")
    (is (= [200 "<p>posted</p>"] (post jarred "X-CSRF-Token" token)) "the embedded token is accepted")
    (is (= [403 frag] (post jarred)) "without the header: refused")
    (is (= [403 frag] (post jarred "X-CSRF-Token" "nope")) "a wrong token: refused")
    (is (= [403 frag] (post bare "X-CSRF-Token" token)) "without the cookie: refused")))

(deftest served-by-wb-handler-at-the-paths-the-shell-emits--with-right-content-types
  (let [app (wb/handler {:routes [] :session {:key KEY}})
        css (app (mock/request :get shell/css-path))
        js  (app (mock/request :get shell/htmx-path))]
    (is (= [200 "text/css"] [(:status css) (get-in css [:headers "Content-Type"])]))
    (is (= (slurp (io/resource "dev/arkaitz/web_base/public/wb.css")) (slurp (:body css))) "the shipped stylesheet")
    (is (= [200 "text/javascript"] [(:status js) (get-in js [:headers "Content-Type"])]))
    (is (= [404 "text/html; charset=utf-8"] (let [r (app (mock/request :get "/wb/nope.css"))] [(:status r) (get-in r [:headers "Content-Type"])])))
    (let [r (app (mock/request :get "/wb/../deps.edn"))]
      (is (= 404 (:status r)) "traversal refused")
      (is (not (str/includes? (str (:body r)) ":paths")) "and nothing leaked"))))

(deftest the-attributes-the-shell-emits-exist-in-the-vendored-htmx
  (let [bundle (slurp (io/resource "dev/arkaitz/web_base/public/htmx.min.js"))]
    (is (str/includes? bundle "hx-headers") "htmx 4 reads hx-headers")
    (is (str/includes? bundle ":inherited") "and inherits only through the :inherited modifier the shell uses")))

(deftest vendored-htmx-is-exactly-the-recorded-4-0-0-artifact
  (let [app   (wb/handler {:routes [] :session {:key KEY}})
        file  (:body (app (mock/request :get shell/htmx-path)))
        bytes (with-open [in (io/input-stream file)] (.readAllBytes in))
        hash  (str/join (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") bytes)))]
    (is (= 36716 (count bytes)) "byte length")
    (is (= "e484d9171a9db30a39c8f16e3d709d4137f3211c659f8e6125816635033d593f" hash)
        (str "htmx.min.js sha256 " hash " ≠ the recorded 4.0.0 hash"))))

(deftest every-wb-custom-property-used-in-wb-css-is-declared-on-root--and-none-is-dead
  (let [css        (slurp (io/resource "dev/arkaitz/web_base/public/wb.css"))
        root       (second (re-find #"(?s):root \{(.*?)\}" css))
        declared   (set (map second (re-seq #"--wb-([a-z-]+)\s*:" (str root))))
        referenced (set (map second (re-seq #"var\(--wb-([a-z-]+)\)" css)))
        seam       #{"accent" "bg" "border" "danger" "font" "gap" "measure" "muted" "text"}]
    (is (= seam declared) "the theming seam, declared on :root")
    (is (= seam referenced) "and every knob is read")
    (is (= #{} (set/difference referenced declared)) (str "used but undeclared: " (set/difference referenced declared)))
    (is (= #{} (set/difference declared referenced)) (str "declared but unused: " (set/difference declared referenced)))))
