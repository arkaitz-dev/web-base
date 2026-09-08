(ns dev.arkaitz.web-base.render-test
  "Every expected HTML string is a literal observed once by hand, never computed
  with the renderer under test: a fixpoint oracle cannot see a broken renderer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.render :as render]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

;; Distinguishable layouts: a swapped nesting produces a different string.
(defn- outer  [{:keys [content]}]       [:html [:body [:div#outer content]]])
(defn- inner  [{:keys [content]}]       [:section#inner content])
(defn- titled [{:keys [content title]}] [:article#titled [:h1 title] content])

(def ^:private stack [outer inner])

(def ^:private page-req    (mock/request :get "/"))
(def ^:private partial-req (mock/header page-req "HX-Request" "true"))
(def ^:private restore-req (mock/header partial-req "HX-Request-Type" "full"))
(def ^:private hiccup-ok   {:status 200 :body [:p "hi"]})

(def ^:private DOC   "<!DOCTYPE html>\n")
(def ^:private FULL  "<html><body><div id=\"outer\"><section id=\"inner\"><p>hi</p></section></div></body></html>")
(def ^:private OUTER "<html><body><div id=\"outer\"><p>hi</p></div></body></html>")
(def ^:private INNER "<section id=\"inner\"><p>hi</p></section>")
(def ^:private BARE  "<p>hi</p>")
(def ^:private HDRS  {"Vary" "HX-Request, HX-Request-Type"
                      "Content-Type" "text/html; charset=utf-8"})

(defn- body [request response stack]
  (:body (render/response request response stack)))

(deftest fold-order-nests-innermost-first
  (is (= (str DOC FULL) (body page-req hiccup-ok stack))
      "outer wraps inner wraps body")
  (is (= (str DOC "<html><body><div id=\"outer\"><section id=\"inner\"><p>a</p><p>b</p></section></div></body></html>")
         (body page-req {:status 200 :body (list [:p "a"] [:p "b"])} stack))
      "a seq body renders as siblings inside the same nesting"))

(deftest page-request-gets-whole-stack-and-doctype--partial-gets-nothing
  (is (= (str DOC FULL) (body page-req hiccup-ok stack))
      "page request → whole stack + doctype")
  (is (= BARE (body partial-req hiccup-ok stack))
      "htmx partial (ring-mock) → zero layouts, no doctype")
  (is (= BARE (body {:headers {"hx-request" "true"}} hiccup-ok stack))
      "htmx partial (hand-built lowercase header, as Ring delivers it) → zero layouts"))

(deftest history-restore-hx-request-type-full-is-a-page
  (let [expected {:status 200 :body (str DOC FULL) :headers HDRS}]
    (is (= expected (render/response restore-req hiccup-ok stack))
        "hx-request-type: full → whole stack (history restore), ring-mock")
    (is (= expected (render/response {:headers {"hx-request" "true" "hx-request-type" "full"}}
                                     hiccup-ok stack))
        "hx-request-type: full → whole stack, hand-built headers")
    (is (= BARE (body {:headers {"hx-request" "true" "hx-request-type" "partial"}} hiccup-ok stack))
        "hx-request-type: partial → the value is read, not the header's presence")))

(deftest wb-height-is-obeyed-on-partial-and-on-page--doctype-only-at-full-height
  (is (= INNER (body partial-req (assoc hiccup-ok :wb/height 1) stack))
      ":wb/height 1 on a partial → inner only")
  (is (= INNER (body page-req (assoc hiccup-ok :wb/height 1) stack))
      ":wb/height 1 on a page → inner only, no doctype")
  (is (= (str DOC FULL) (body page-req (assoc hiccup-ok :wb/height 2) stack))
      ":wb/height 2 (explicit full height) on a page → doctype")
  (is (= BARE (body page-req (assoc hiccup-ok :wb/height 0) stack))
      ":wb/height 0 on a page → bare, no doctype")
  (is (= FULL (body partial-req (assoc hiccup-ok :wb/height 2) stack))
      ":wb/height 2 on a partial → whole stack, no doctype"))

(deftest non-hiccup-bodies-pass-through-untouched-except-base-keys-stripped
  (let [raw "<b>raw</b> & <i>x</i>"]
    (doseq [req [page-req partial-req]]
      (is (= {:status 200 :body raw}
             (render/response req {:status 200 :body raw :wb/height 1 :wb/slots {:title "T"}} stack))
          "string body with < passes through unescaped, base keys stripped, no headers added")
      (is (= {:status 204 :body nil}
             (render/response req {:status 204 :body nil :wb/height 1} stack))
          "nil body passes through")))
  (let [in  (io/input-stream (.getBytes "<x>"))
        out (render/response page-req {:status 200 :body in} stack)]
    (is (identical? in (:body out)) "InputStream body is the same object")
    (is (= {:status 200} (dissoc out :body)) "InputStream response otherwise untouched"))
  (let [f   (io/file "deps.edn")
        out (render/response page-req {:status 200 :body f} stack)]
    (is (identical? f (:body out)) "File body is the same object")
    (is (= {:status 200} (dissoc out :body)) "File response otherwise untouched")))

(deftest content-type-set-only-when-absent--case-insensitive
  (let [headers (fn [response] (:headers (render/response page-req response [])))]
    (is (= HDRS (headers {:status 200 :body [:p "hi"]}))
        "no :headers → Content-Type set")
    (is (= HDRS (headers {:status 200 :headers {} :body [:p "hi"]}))
        "empty :headers → Content-Type set")
    (is (= {"content-type" "text/plain" "Vary" "HX-Request, HX-Request-Type"}
           (headers {:status 200 :headers {"content-type" "text/plain"} :body [:p "hi"]}))
        "existing lowercase content-type is kept, none added")
    (is (= {"CONTENT-TYPE" "application/xhtml+xml" "Vary" "HX-Request, HX-Request-Type"}
           (headers {:status 200 :headers {"CONTENT-TYPE" "application/xhtml+xml"} :body [:p "hi"]}))
        "existing content-type under another case is kept, none added")))

(deftest vary-on-every-rendered-response
  (doseq [[label req] [["page" page-req] ["partial" partial-req] ["restore" restore-req]]
          [height-label response] [["default height" hiccup-ok]
                                   ["height 1" (assoc hiccup-ok :wb/height 1)]]]
    (is (= "HX-Request, HX-Request-Type"
           (get-in (render/response req response stack) [:headers "Vary"]))
        (str "Vary on a " label " rendered at " height-label)))
  (is (= {"X-Handler" "kept"
          "Vary" "HX-Request, HX-Request-Type"
          "Content-Type" "text/html; charset=utf-8"}
         (:headers (render/response page-req {:status 200 :headers {"X-Handler" "kept"} :body [:p "hi"]} stack)))
      "the handler's own headers survive alongside Vary and Content-Type"))

(deftest slots-reach-the-layout-and-base-keys-are-stripped
  (let [response {:status 200 :body [:p "hi"] :wb/slots {:title "Hello <T>"} :wb/height 1}
        out      (render/response page-req response [titled])]
    (is (= (str DOC "<article id=\"titled\"><h1>Hello &lt;T&gt;</h1><p>hi</p></article>") (:body out))
        "slot :title reaches the layout")
    (is (= {} (select-keys out [:wb/height :wb/slots]))
        ":wb/height and :wb/slots stripped from the response")
    (is (= #{:status :body :headers} (set (keys out)))
        "no other key leaks"))
  (let [seen (promise)
        spy  (fn [m] (deliver seen m) [:i (:content m)])
        _    (render/response page-req {:status 200 :body [:p "hi"] :wb/slots {:title "T"}} [spy])
        m    (deref seen 0 ::never)]
    (is (not= ::never m) "the layout was invoked")
    (when (map? m)
      (is (= page-req (:request m)) ":request is the request map given to response")
      (is (= [:p "hi"] (:content m)) ":content is the handler's hiccup")
      (is (= "T" (:title m)) "the slot is visible")
      (is (= #{:content :request :title} (set (keys m))) "exactly the slots plus content and request"))))

(deftest height-outside-the-stack-throws-ex-info
  (let [attempt (fn [request height stack]
                  (try (render/response request (assoc hiccup-ok :wb/height height) stack)
                       (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        msg     "response :wb/height is not within the route's layout stack"]
    (is (= [msg {:wb/height 3 :layouts 2}] (attempt page-req 3 stack)) ":wb/height 3 beyond a 2-layout stack throws")
    (is (= [msg {:wb/height -1 :layouts 2}] (attempt page-req -1 stack)) "negative height throws")
    (is (= [msg {:wb/height 1.5 :layouts 2}] (attempt page-req 1.5 stack)) "fractional height throws")
    (is (= [msg {:wb/height "1" :layouts 2}] (attempt page-req "1" stack)) "string height throws")
    (is (= [msg {:wb/height 1 :layouts 0}] (attempt page-req 1 [])) "height 1 on an empty stack throws")
    (is (= (str DOC FULL) (:body (attempt page-req 2 stack))) "height equal to the stack is accepted on a page")
    (is (= FULL (:body (attempt partial-req 2 stack))) "height equal to the stack is accepted on a partial")))

(deftest reitit-middleware-concatenates-nested-layouts-and-renders-bare-without-them
  (let [hi     (fn [_] {:status 200 :body [:p "hi"]})
        routes [["/p" {:wb/layouts [outer]}
                 ["/c"    {:wb/layouts [inner] :get hi}]
                 ["/only" {:get hi}]
                 ["/x"    {:wb/layouts ^:replace [titled]
                           :get (fn [_] {:status 200 :body [:p "hi"] :wb/slots {:title "T"}})}]]
                ["/bare" {:get hi}]]
        app    (ring/ring-handler (ring/router routes {:data {:middleware [render/middleware]}}))]
    (is (= {:status 200 :body (str DOC FULL) :headers HDRS}
           (app (mock/request :get "/p/c")))
        "/p/c renders parent outer around child inner")
    (is (= {:status 200 :body (str DOC OUTER) :headers HDRS}
           (app (mock/request :get "/p/only")))
        "/p/only inherits the parent's layout alone")
    (is (= {:status 200 :body (str DOC "<article id=\"titled\"><h1>T</h1><p>hi</p></article>") :headers HDRS}
           (app (mock/request :get "/p/x")))
        "/p/x with ^:replace drops the parent's layout and receives its slots")
    (is (= {:status 200 :body (str DOC BARE) :headers HDRS}
           (app (mock/request :get "/bare")))
        "/bare with no layouts anywhere renders bare with doctype")
    (is (= {:status 200 :body BARE :headers HDRS}
           (app (mock/header (mock/request :get "/p/c") "HX-Request" "true")))
        "/p/c as an htmx partial → zero layouts through the router")))

(deftest htmx-partial-request-truth-table-and-redirect-shape
  (doseq [[label request expected]
          [["no headers key"                       {}                                                            false]
           ["empty headers"                        {:headers {}}                                                 false]
           ["hx-request true (hand-built)"         {:headers {"hx-request" "true"}}                              true]
           ["hx-request true + type partial"       {:headers {"hx-request" "true" "hx-request-type" "partial"}}  true]
           ["hx-request true + type full → page"   {:headers {"hx-request" "true" "hx-request-type" "full"}}     false]
           ["hx-request false"                     {:headers {"hx-request" "false"}}                             false]
           ["type partial without hx-request"      {:headers {"hx-request-type" "partial"}}                      false]
           ["ring-mock HX-Request true"            (mock/header (mock/request :get "/") "HX-Request" "true")     true]
           ["ring-mock HX-Request + full"          (-> (mock/request :get "/")
                                                       (mock/header "HX-Request" "true")
                                                       (mock/header "HX-Request-Type" "full"))                   false]]]
    (testing label
      (is (= expected (htmx/partial-request? request)))))
  (is (= {:status 200 :headers {"HX-Redirect" "/login"} :body ""} (htmx/redirect "/login"))
      "redirect is a 200 with HX-Redirect only — no Location, no 3xx"))
