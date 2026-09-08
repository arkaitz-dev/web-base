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
            [dev.arkaitz.web-base.testing :as testing]
            [integrant.core :as ig]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- app []
  (wb/handler (ig/init-key :demo/web-config {:store       (ig/init-key :demo/store {:todos [{:id 1 :title "uno" :done? false}]})
                                             :session-key KEY
                                             :secure?     false})))

(defn- get* [app path & headers]
  (app (reduce (fn [r [k v]] (mock/header r k v)) (mock/request :get path) (partition 2 headers))))

(deftest the-home-page-is-a-whole-document-and-a-tab-swap-is-only-the-section
  (let [app  (app)
        home (get* app "/")]
    (is (= 200 (:status home)))
    (is (str/starts-with? (:body home) "<!DOCTYPE html>\n<html lang=\"es\">") "the shell, in the default language")
    (is (str/includes? (:body home) "<div id=\"app\"><nav class=\"tabs\">") "the tabs section inside it")
    (is (str/includes? (:body home) "hx-headers:inherited=") "the CSRF token for htmx")
    (is (seq (testing/cookies home)) "the first visit mints the session that holds the CSRF token")
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
    (let [form    (get* app "/login")
          token   (testing/csrf-token form)
          login   (app (-> (mock/request :post "/login" {"name" "ada" "__anti-forgery-token" token})
                           (testing/with-cookies form)))
          rotated (testing/cookies login)]
      (is (some? token) "the login form carries the csrf field")
      (is (= [303 "/private"] [(:status login) (get-in login [:headers "Location"])]) "login redirects to the private page")
      ;; That the id rotated is proved below over a store with ids: under the
      ;; cookie store every write differs anyway (random IV).
      (is (seq rotated) "login wrote the session cookie")
      (let [private (app (testing/with-cookies (mock/request :get "/private") login))]
        (is (= 200 (:status private)))
        (is (str/includes? (:body private) "Eres ada") "the private page names the subject")))
    (let [form   (get* app "/login")
          login  (app (-> (mock/request :post "/login" {"name" "x" "__anti-forgery-token" (testing/csrf-token form)})
                          (testing/with-cookies form)))]
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
          token  (testing/csrf-token form)
          _      (is (some? token) "the home page carries the token in the shell's <body>")
          _      (is (seq (testing/cookies form)) "and minted the session")
          switch (app (-> (mock/request :post "/lang" {"locale" "en" "__anti-forgery-token" token})
                          (testing/with-cookies form)))
          ;; The switch rewrote the session: its cookie, or the page's if it set none.
          after  (app (-> (mock/request :get "/") (mock/header "Accept-Language" "es")
                          (testing/with-cookies form) (testing/with-cookies switch)))]
      (is (= 303 (:status switch)))
      (is (str/starts-with? (:body after) "<!DOCTYPE html>\n<html lang=\"en\">") "the session's choice beats the header"))))

(deftest todos-round-trip-through-htmx-with-the-csrf-header
  (let [app    (app)
        form   (get* app "/login")
        token  (testing/csrf-token form)
        add    (app (-> (mock/request :post "/todos" {"title" "dos"})
                        (testing/with-cookies form) testing/fragment (mock/header "X-CSRF-Token" token)))]
    (is (= 200 (:status add)))
    (is (str/starts-with? (:body add) "<div id=\"todos\">") "the fragment htmx swaps in")
    (is (and (str/includes? (:body add) "uno") (str/includes? (:body add) "dos")))
    (is (= 403 (:status (app (-> (mock/request :post "/todos" {"title" "tres"}) (testing/with-cookies form) testing/fragment))))
        "without the token: the base's 403")))

(deftest boom-is-the-page-on-a-history-restore-and-the-500-fragment-on-a-swap
  ;; htmx sends HX-Request on a history restore too, with HX-Request-Type
  ;; "full": the route classifies with the base, never with the raw header.
  (let [app     (app)
        restore (get* app "/boom" "HX-Request" "true" "HX-Request-Type" "full")
        swap    (get* app "/boom" "HX-Request" "true")]
    (is (= 200 (:status restore)) "history restore of /boom: the boom page, not the 500 page")
    (is (str/starts-with? (:body restore) "<!DOCTYPE html>\n<html lang=\"es\">") "a whole document")
    (is (str/includes? (:body restore) "<title>Error deliberado · demo de web-base</title>")
        "the boom page's own <title>: the nav names it on every page, the 500 page included")
    (is (str/includes? (:body restore) "<div id=\"boom-target\">") "with the button's target")
    (is (not (str/includes? (:body restore) "wb-error")) "and no error fragment")
    (is (= [500 "<div class=\"wb-error\" data-status=\"500\"><strong class=\"wb-error-status\">500</strong></div>"]
           [(:status swap) (:body swap)])
        "a swap of /boom: exactly the base's 500 fragment")))

(deftest login-rotates-the-session-id--proved-over-a-store-with-ids
  ;; The demo's cookie store cannot witness `session/rotate`: every write
  ;; differs anyway, so "the cookie changed" would be green with the call
  ;; gone. A memory store has ids, and only rotation changes them.
  (let [sessions (atom {})
        app      (wb/handler (assoc (ig/init-key :demo/web-config {:store       (ig/init-key :demo/store {:todos []})
                                                                    :session-key KEY
                                                                    :secure?     false})
                                    :session {:store (memory/memory-store sessions)}))
        form     (get* app "/login")
        before   (get (testing/cookies form) "ring-session")
        login    (app (-> (mock/request :post "/login" {"name" "ada" "__anti-forgery-token" (testing/csrf-token form)})
                          (testing/with-cookies form)))
        after    (get (testing/cookies login) "ring-session")]
    (is (string? before) "the form minted a session: it holds the CSRF token")
    (is (= 303 (:status login)) "login succeeded")
    (is (string? after) "login wrote a session cookie")
    (is (not= before after) "with a different id: the demo called session/rotate")
    (is (= "ada" (get-in @sessions [after :subject])) "the new session holds the subject")
    (is (nil? (get @sessions before)) "and the old id is gone from the store")))
