(ns demo.seam-test
  "The demo is the acceptance test (SPEC §8): if it needs anything web-base
  does not provide, the seam is in the wrong place. These are the seam
  scenarios ring-mock can see; what only a browser can see — htmx swapping a
  500 into its target, the back button, CSP in the console — is the manual
  smoke recorded in CLAUDE.md."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.system]
            [dev.arkaitz.web-base :as wb]
            [integrant.core :as ig]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- app []
  (wb/handler (ig/init-key :demo/web-config {:store       (ig/init-key :demo/store {:todos [{:id 1 :title "uno" :done? false}]})
                                             :session-key KEY
                                             :secure?     false})))

(defn- cookie-of [response]
  (second (re-find #"^(ring-session=[^;]*);" (str (first (get-in response [:headers "Set-Cookie"]))))))

(defn- token-in [body]
  (second (re-find #"name=\"__anti-forgery-token\" type=\"hidden\" value=\"([^\"]+)\"" (str body))))

(defn- get* [app path & headers]
  (app (reduce (fn [r [k v]] (mock/header r k v)) (mock/request :get path) (partition 2 headers))))

(deftest the-home-page-is-a-whole-document-and-a-tab-swap-is-only-the-section
  (let [app  (app)
        home (get* app "/")]
    (is (= 200 (:status home)))
    (is (str/starts-with? (:body home) "<!DOCTYPE html>\n<html lang=\"es\">") "the shell, in the default language")
    (is (str/includes? (:body home) "<div id=\"app\"><nav class=\"tabs\">") "the tabs section inside it")
    (is (str/includes? (:body home) "hx-headers:inherited=") "the CSRF token for htmx")
    (is (some? (cookie-of home)) "the first visit mints the session that holds the CSRF token")
    (is (str/includes? (:body home) "hx-swap=\"outerHTML\" hx-target=\"#app\"")
        "the tab buttons replace #app whole: the section answers with the wrapper itself")
    (let [swap (get* app "/tabs/search" "HX-Request" "true")]
      (is (= 200 (:status swap)))
      (is (str/starts-with? (:body swap) "<div id=\"app\">") "the section layout only — :wb/height 1")
      (is (not (str/includes? (:body swap) "<html")) "never the shell inside a swap"))))

(deftest the-gate-speaks-htmx-and-the-demo-s-own-login-satisfies-it
  (let [app (app)]
    (let [r (get* app "/private")]
      (is (= [303 "/login"] [(:status r) (get-in r [:headers "Location"])]) "a navigation to the private page goes to the login"))
    (let [r (get* app "/private" "HX-Request" "true")]
      (is (= [200 "/login" nil] [(:status r) (get-in r [:headers "HX-Redirect"]) (get-in r [:headers "Location"])])
          "an htmx request gets HX-Redirect and never a Location"))
    (let [form   (get* app "/login")
          token  (token-in (:body form))
          cookie (cookie-of form)
          login  (app (-> (mock/request :post "/login" {"name" "ada" "__anti-forgery-token" token})
                          (mock/header "Cookie" cookie)))
          rotated (cookie-of login)]
      (is (some? token) "the login form carries the csrf field")
      (is (= [303 "/private"] [(:status login) (get-in login [:headers "Location"])]) "login redirects to the private page")
      (is (and (some? rotated) (not= rotated cookie)) "the session id was rotated on login")
      (let [private (get* app "/private" "Cookie" rotated)]
        (is (= 200 (:status private)))
        (is (str/includes? (:body private) "Eres ada") "the private page names the subject")))
    (let [form   (get* app "/login")
          login  (app (-> (mock/request :post "/login" {"name" "x" "__anti-forgery-token" (token-in (:body form))})
                          (mock/header "Cookie" (cookie-of form))))]
      (is (= 200 (:status login)) "a rejected name re-renders the form")
      (is (str/includes? (:body login) "dinos quién eres") "with malli's message in the page's language"))))

(deftest errors-are-a-page-on-a-navigation-and-a-fragment-inside-a-swap
  (let [app (app)]
    (let [r (get* app "/throw")]
      (is (= 500 (:status r)))
      (is (str/starts-with? (:body r) "<!DOCTYPE html>") "a whole page, through the demo's error layout")
      (is (str/includes? (:body r) "Algo se ha roto") "in the demo's words"))
    (let [r (get* app "/throw" "HX-Request" "true")]
      (is (= 500 (:status r)))
      (is (str/starts-with? (:body r) "<div class=\"wb-error\"") "a fragment, for htmx 4 to swap")
      (is (not (str/includes? (:body r) "<html"))))
    (let [r (get* app "/tabs/nope")]
      (is (= 400 (:status r)) "coercion refuses an unknown tab")
      (is (str/includes? (:body r) "No se ha entendido") "worded by the demo, not the base")
      (is (str/includes? (:body r) "should be either") "with malli's explanation as data the layout chose to show"))
    (is (= 404 (:status (get* app "/nope"))))))

(deftest the-language-follows-the-header-and-then-the-session
  (let [app (app)]
    (is (str/starts-with? (:body (get* app "/" "Accept-Language" "en")) "<!DOCTYPE html>\n<html lang=\"en\">") "Accept-Language")
    (is (str/includes? (:body (get* app "/" "Accept-Language" "en")) ">Tasks<") "translated tabs")
    (let [form   (get* app "/")
          token  (second (re-find #"hx-headers:inherited=\"\{&quot;X-CSRF-Token&quot;:&quot;([^&]+)&quot;\}\"" (:body form)))
          cookie (or (cookie-of form)
                     (cookie-of (get* app "/login")))
          _      (is (some? token))
          switch (app (-> (mock/request :post "/lang" {"locale" "en" "__anti-forgery-token" token})
                          (mock/header "Cookie" cookie)))
          after  (get* app "/" "Cookie" (or (cookie-of switch) cookie) "Accept-Language" "es")]
      (is (= 303 (:status switch)))
      (is (str/starts-with? (:body after) "<!DOCTYPE html>\n<html lang=\"en\">") "the session's choice beats the header"))))

(deftest todos-round-trip-through-htmx-with-the-csrf-header
  (let [app    (app)
        form   (get* app "/login")
        token  (token-in (:body form))
        cookie (cookie-of form)
        add    (app (-> (mock/request :post "/todos" {"title" "dos"})
                        (mock/header "Cookie" cookie) (mock/header "HX-Request" "true") (mock/header "X-CSRF-Token" token)))]
    (is (= 200 (:status add)))
    (is (str/starts-with? (:body add) "<div id=\"todos\">") "the fragment htmx swaps in")
    (is (and (str/includes? (:body add) "uno") (str/includes? (:body add) "dos")))
    (is (= 403 (:status (app (-> (mock/request :post "/todos" {"title" "tres"}) (mock/header "Cookie" cookie) (mock/header "HX-Request" "true")))))
        "without the token: the base's 403")))
