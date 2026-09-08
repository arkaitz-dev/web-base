(ns dev.arkaitz.web-base.shell-test
  "Every expected document is a literal observed once by hand. Hiccup orders
  attributes alphabetically and renders `:defer true` as a bare `defer`."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.shell :as shell]
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

(deftest csrf-token-in-hx-headers-inherited-on-body-iff-present--and-all-three-together
  (is (= (str "<html>" SKEL)
         (html {:request {}}))
      "no token, no hx-headers")
  (is (= (str "<html>" HEAD-START ASSETS "</head><body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;tok/ABC+123=&quot;}\">"
              "<main class=\"wb-main\"></main></body></html>")
         (html {:request {:anti-forgery-token "tok/ABC+123="}}))
      "token → hx-headers:inherited on body with key X-CSRF-Token")
  (is (= (str "<html lang=\"es\">" HEAD-START "<link href=\"/wb/wb.css\" rel=\"stylesheet\"><script defer nonce=\"n1\" src=\"/wb/htmx.min.js\"></script></head>"
              "<body hx-headers:inherited=\"{&quot;X-CSRF-Token&quot;:&quot;tok&quot;}\"><main class=\"wb-main\"></main></body></html>")
         (html {:request {:anti-forgery-token "tok" :wb/nonce "n1" :wb/locale "es"}}))
      "lang, nonce and token compose"))

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

(defn- app-with-shell []
  (wb/handler {:routes   [["/" {:wb/layouts [shell/page]
                                :get  (fn [_] {:status 200 :body [:p "hi"]})
                                :post (fn [_] {:status 200 :body [:p "posted"]})}]]
               :session  {:key KEY}
               :security {:csp "script-src 'nonce-{nonce}'"}}))

(deftest nonce-in-the-script-equals-the-nonce-in-the-csp-header--end-to-end
  (let [r ((app-with-shell) (mock/request :get "/"))
        n (second (re-find #"^script-src 'nonce-([A-Za-z0-9+/]{22}==)'$" (str (get-in r [:headers "Content-Security-Policy"]))))]
    (is (string? n) (str "CSP header present and well formed: " (pr-str (get-in r [:headers "Content-Security-Policy"]))))
    (is (str/includes? (:body r) (str "<script defer nonce=\"" n "\" src=\"/wb/htmx.min.js\"></script>")) "the script carries the CSP's nonce")
    (is (= 1 (count (re-seq #"nonce=\"" (:body r)))) "exactly one nonce attribute in the page")
    (is (str/starts-with? (:body r) "<!DOCTYPE html>\n<html><head>"))))

(deftest token-embedded-in-hx-headers-is-the-one-wrap-csrf-accepts--round-trip
  (let [app    (app-with-shell)
        first* (app (mock/request :get "/"))
        token  (second (re-find #"<body hx-headers:inherited=\"\{&quot;X-CSRF-Token&quot;:&quot;([A-Za-z0-9+/=]+)&quot;\}\">" (:body first*)))
        cookie (second (re-find #"^(ring-session=[^;]*);" (str (first (get-in first* [:headers "Set-Cookie"])))))
        post   (fn [& headers] (let [r (reduce (fn [q [k v]] (mock/header q k v)) (mock/header (mock/request :post "/") "HX-Request" "true") (partition 2 headers))
                                     out (app r)] [(:status out) (:body out)]))
        frag   "<div class=\"wb-error\" data-status=\"403\"><strong class=\"wb-error-status\">403</strong></div>"]
    (is (and (string? token) (= 80 (count token))) "the shell embedded the session's token (ring-anti-forgery 1.4.0: 60 random bytes, base64 unpadded)")
    (is (some? cookie))
    (is (= [200 "<p>posted</p>"] (post "Cookie" cookie "X-CSRF-Token" token)) "the embedded token is accepted")
    (is (= [403 frag] (post "Cookie" cookie)) "without the header: refused")
    (is (= [403 frag] (post "Cookie" cookie "X-CSRF-Token" "nope")) "a wrong token: refused")
    (is (= [403 frag] (post "X-CSRF-Token" token)) "without the cookie: refused")))

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
