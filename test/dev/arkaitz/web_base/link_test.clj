(ns dev.arkaitz.web-base.link-test
  "`wb/link` (since 0.24.0): an internal link that swaps `<main>` and pushes its URL. Every
  expected string was rendered once by hand and typed here, never built from the vars
  under test; hiccup writes attributes sorted, so a hiccup that orders or escapes them
  differently reds every literal at once, and says so here first."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.shell :as shell]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")
(def ^:private NAV "hx-push-url=\"true\" hx-select=\"main\" hx-swap=\"outerHTML show:top showTarget:body\" hx-target=\"main\"")

(deftest a-link-renders-the-exact-navigation-attributes-around-its-real-href
  (is (= "<a href=\"/a\">Home</a>" (render/html [:a {:href "/a"} "Home"]))
      "witness: hiccup renders and orders attributes as these literals assume")
  (is (= (str "<a href=\"/a\" hx-get=\"/a\" " NAV ">Home</a>") (render/html (wb/link "/a" "Home")))
      "the six attributes, hx-get the href")
  (is (= (str "<a href=\"/\" hx-get=\"/\" " NAV "></a>") (render/html (wb/link "/")))
      "the root is a path on this origin")
  (is (= (str "<a aria-current=\"page\" class=\"nav\" href=\"/a/b?c=1&amp;d=2\" hx-get=\"/a/b?c=1&amp;d=2\" "
              NAV " rel=\"next\"><span>A</span> b</a>")
         (render/html (wb/link "/a/b?c=1&d=2" {:class "nav" :rel "next" :aria-current "page"} [:span "A"] " b")))
      "a first map is the attrs, kept; the body after it is the children")
  (is (= (str "<a href=\"/a\" hx-get=\"/a\" " NAV "><span class=\"i\">x</span></a>")
         (render/html (wb/link "/a" [:span {:class "i"} "x"])))
      "a vector first child is body, its own attrs on its own element")
  (is (= (str "<a class=\"c\" href=\"/a\" hx-get=\"/a\" " NAV "></a>") (render/html (wb/link "/a" {:class "c"})))
      "a map alone is attrs, with an empty body"))

(deftest a-link-refuses-an-href-that-is-not-a-path-on-this-origin--without-echoing-it
  (is (vector? (wb/link "/a")) "witness: a local href is not refused")
  (let [refused ["//evil.example" "/\\evil.example" "javascript:alert(1)" "https://evil.example/"
                 "" nil "a/b" "/ x" "/\tx" "/a\n" "/\u0001x" "/a\u0000" 42]]
    (is (= 13 (count refused)) "witness: every case below runs")
    (doseq [href refused]
      (testing (pr-str href)
        (let [e (try (wb/link href "x") nil (catch ExceptionInfo e e))]
          (is (some? e) "refused with an ex-info")
          (is (= {:wb/link :href} (ex-data e)) "the data names the argument, not its value")
          (is (= "web-base: link's href must be a path on this origin" (ex-message e))
              "the message is fixed: the href is never echoed"))))))

(deftest a-callers-attributes-are-kept--but-one-that-would-change-where-the-link-goes-or-how-it-swaps-is-refused
  (is (= (str "<a aria-current=\"page\" class=\"k\" data-id=\"7\" href=\"/a\" hx-get=\"/a\" " NAV " rel=\"next\" title=\"t\">x</a>")
         (render/html (wb/link "/a" {:class "k" "title" "t" :rel "next" :data-id "7" :aria-current "page"} "x")))
      "every other attribute is kept, a string key and a data- one that is not htmx's included")
  (let [refused [:href "href" :HREF :hx-get :hx-target :hx-select :hx-swap :hx-push-url
                 :hx-action :hx-method :hx-config :hx-vals :hx-status:200 :hx-on:click "hx-trigger"
                 :Hx-Target :data-hx-get "data-hx-swap" :DATA-HX-ACTION]]
    (is (= 19 (count refused)) "witness: every case below runs")
    (doseq [k refused]
      (testing (pr-str k)
        (let [e (try (wb/link "/a" {k "/evil" :class "k"} "x") nil (catch ExceptionInfo e e))]
          (is (some? e) "refused with an ex-info")
          (is (= {:wb/link :attrs} (ex-data e)) "the data names the argument, not its value")
          (is (= "web-base: link's attrs may not give an href or an htmx attribute" (ex-message e))
              "the message is fixed: the value is never echoed"))))))

(deftest the-bundled-htmx-reads-the-links-attributes-as-the-link-means-them
  ;; Read from the minified htmx 4.0.0 once and typed here: an upgrade that changes how
  ;; it classifies the request or reads `show` reds by name, before any browser does.
  (let [bundle (slurp (io/resource "dev/arkaitz/web_base/public/htmx.min.js"))]
    (is (str/includes? bundle "e.target===document.body||e.select?\"full\":\"partial\"")
        "an element with hx-select asks for the whole page")
    (is (str/includes? bundle "if(\"top\"===e.show||\"bottom\"===e.show){let r=e.showTarget?this.#Be(e.showTarget):t;r?.scrollIntoView?.(\"top\"===e.show)}")
        "show is top or bottom, scrolled from showTarget")
    (is (str/includes? bundle "else if(\"body\"===r)t=document.body;") "and showTarget:body is the body")))

(deftest a-request-of-type-full--what-a-link-asks-for--is-answered-with-the-whole-page
  (let [app   (wb/handler {:routes  [["/p" {:wb/layouts [shell/page]
                                            :get (fn [_] {:status 200 :body [:p "x"] :wb/slots {:title "T"}})}]]
                           :session {:key KEY}})
        full  (app (-> (mock/request :get "/p") (mock/header "HX-Request" "true") (mock/header "HX-Request-Type" "full")))
        frag  (app (reduce-kv mock/header (mock/request :get "/p") htmx/fragment-headers))
        mains (fn [r] (count (re-seq #"<main" (:body r))))]
    (is (= 200 (:status full)) "witness: the route answered")
    (is (str/starts-with? (:body full) "<!DOCTYPE html>") "a document")
    (is (= 1 (mains full)) "with one <main> to select")
    (is (str/includes? (:body full) "<title>T</title>") "and the title htmx takes")
    (is (= [0 false] [(mains frag) (str/starts-with? (:body frag) "<!DOCTYPE")])
        "control: the same route as a fragment has neither, so the full answer is the header's doing")))
