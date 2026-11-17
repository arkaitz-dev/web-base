(ns dev.arkaitz.web-base.nav-focus-test
  "What `wb/link` needs from the shell (since 0.24.0): a `<main>` a script can focus, the
  base's `/wb/nav.js` that focuses it after a swap, served as the other base assets are,
  and no session for an anonymous page that only carries such links. What the script does
  in a browser no JVM test can see; what is pinned here is that it is served, loaded, and
  keyed to the very `hx-select` the link sends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.shell :as shell]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")

(def ^:private app
  (wb/handler {:routes  [["/" {:wb/layouts [shell/page]
                               :get (fn [_] {:status 200 :body [:p (wb/link "/x" "x")]})}]]
               :session {:key KEY}}))

(deftest the-shell-loads-nav-js-after-htmx-and-its-main-takes-a-scripts-focus-only
  (let [body (:body (app (mock/request :get "/")))]
    (is (re-find #"<script defer nonce=\"([A-Za-z0-9+/]{22}==)\" src=\"/wb/htmx.min.js\"></script><script defer nonce=\"\1\" src=\"/wb/nav.js\"></script>" body)
        "nav.js, right after htmx, under the same nonce")
    (is (str/includes? body "<main class=\"wb-main\" tabindex=\"-1\">") "a <main> focusable by a script, never by Tab")))

(deftest nav-js-is-served-uncached-and-moves-the-focus-only-after-a-links-swap
  (let [r (app (mock/request :get "/wb/nav.js"))]
    (is (= 200 (:status r)) "served from the base's assets")
    (is (= "no-cache" (get-in r [:headers "Cache-Control"])) "revalidated like the base's other assets")
    (is (str/includes? (slurp (:body r)) "ctx.select !== \"main\"")
        "keyed to the hx-select wb/link sends, so no other swap moves the focus")
    (is (str/includes? (str (wb/link "/x" "x")) ":hx-select \"main\"") "witness: which is the one the link sends")))

(deftest nav-js-names-the-chrome-it-marks-and-the-links-it-skips
  ;; Lexical, and only what substrings prove: which links are judged at all. What it does
  ;; with them — the mark on load, after a swap, Back and Forward; a host's own value, a
  ;; false mark, a fragment, a query, another origin — runs in a browser
  ;; (test/browser/nav-check.mjs, held by nav_checks_test).
  (let [js (slurp (:body (app (mock/request :get "/wb/nav.js"))))]
    (is (str/includes? js "\".wb-nav a[href], [data-wb-nav] a[href]\"") "the shell's nav and any nav a page marks as its chrome")
    (is (str/includes? js "a.closest(\"main\")") "a link inside the swapped <main> is skipped")))

(deftest an-anonymous-page-of-links-writes-no-session-and-carries-no-token
  (let [r (app (mock/request :get "/"))]
    (is (str/includes? (:body r) "hx-get=\"/x\"") "witness: the page carries a wb/link")
    (is (not (str/includes? (:body r) "hx-headers")) "no token on <body>: an anonymous session holds none")
    (is (nil? (get-in r [:headers "Set-Cookie"])) "and no session cookie is set")))
