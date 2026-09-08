(ns dev.arkaitz.web-base.error-test
  "Expected strings are literals observed once by hand, never computed with the
  renderer under test. `page` and `frag` below only glue literals together."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.render :as render]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private page-req    (mock/request :get "/x"))
(def ^:private partial-req (mock/header page-req "HX-Request" "true"))
(def ^:private restore-req (mock/header partial-req "HX-Request-Type" "full"))
(def ^:private text-req    (mock/header page-req "Accept" "text/plain"))

(def ^:private VARY "HX-Request, HX-Request-Type, Accept")
(def ^:private HTML {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/html; charset=utf-8"})
(def ^:private TEXT {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/plain; charset=utf-8"})

(defn- frag
  ([status] (frag status nil nil))
  ([status title detail]
   (str "<div class=\"wb-error\" data-status=\"" status "\">"
        "<strong class=\"wb-error-status\">" status "</strong>"
        (when title (str "<span class=\"wb-error-title\">" title "</span>"))
        (when detail (str "<p class=\"wb-error-detail\">" detail "</p>"))
        "</div>")))

(defn- page
  ([status body] (page status body nil))
  ([status body lang]
   (str "<!DOCTYPE html>\n<html" (when lang (str " lang=\"" lang "\"")) ">"
        "<head><meta charset=\"utf-8\">"
        "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
        "<title>" status "</title></head><body>" body "</body></html>")))

(def ^:private render-error (error/renderer {}))

(defn- entry [level throwable message]
  (lt/->LogEntry (the-ns 'dev.arkaitz.web-base.error) level throwable message))

(deftest fragment-on-htmx-partial-carries-the-real-status-and-never-a-document
  (is (= {:status 403 :headers HTML :body (frag 403 "T" "no &lt;b&gt;")}
         (render-error {:status 403 :title "T" :detail "no <b>"} partial-req))
      "403 fragment on an htmx partial: bare div, real status, escaped detail")
  (is (= {:status 403 :headers HTML :body (frag 403 "&lt;i&gt;" nil)}
         (render-error {:status 403 :title "<i>"} partial-req))
      "the title is escaped too")
  (is (= {:status 404 :headers HTML :body (frag 404)}
         (render-error {:status 404} {:headers {"hx-request" "true" "hx-request-type" "partial"}}))
      "hand-built lowercase headers, as Ring delivers them")
  (is (= {:status 404 :headers HTML :body (frag 404)}
         (render-error {:status 404} (mock/header partial-req "Accept" "text/plain")))
      "htmx wins over an Accept that excludes HTML"))

(deftest page-on-navigation-and-on-history-restore-is-a-self-contained-document-with-doctype
  (is (= {:status 404 :headers HTML :body (page 404 (frag 404))}
         (render-error {:status 404} page-req))
      "page request → doctype + default page")
  (is (= {:status 500 :headers HTML :body (page 500 (frag 500))}
         (render-error {:status 500} restore-req))
      "hx-request-type full → page, not a fragment"))

(deftest default-page-lang-comes-from-wb-locale-and-is-absent-without-it
  (is (= (page 404 (frag 404) "es")
         (:body (render-error {:status 404} (assoc page-req :wb/locale "es"))))
      "lang from :wb/locale")
  (is (= (page 404 (frag 404))
         (:body (render-error {:status 404} page-req)))
      "no lang attribute without :wb/locale"))

(deftest plain-text-when-accept-names-neither-html-nor-wildcard
  (doseq [[datum body] [[{:status 403 :title "Forbidden" :detail "Because"} "403 Forbidden\nBecause"]
                        [{:status 403 :title "Forbidden"}                  "403 Forbidden"]
                        [{:status 403 :detail "Because"}                   "403\nBecause"]
                        [{:status 404}                                     "404"]
                        [{:status 403 :title "<script>"}                   "403 <script>"]]]
    (is (= {:status (:status datum) :headers TEXT :body body}
           (render-error datum text-req))
        (str "Accept: text/plain → text rendering " (pr-str datum))))
  (doseq [[accept expected] [["application/json"            TEXT]
                             ["application/json, */*;q=0.1" HTML]
                             ["text/html"                   HTML]
                             ["text/*"                      HTML]]]
    (is (= expected (:headers (render-error {:status 404} (mock/header page-req "Accept" accept))))
        (str "Accept: " accept))))

(deftest error-layout-receives-content-request-error-and-only-the-page-uses-it
  (let [seen    (promise)
        layout  (fn [m] (deliver seen m) [:html [:body [:main#custom (:content m)]]])
        datum   {:status 418 :title "Tea" :detail "leaf"}
        request (assoc page-req :wb/tr ::host-context)
        out     ((error/renderer {:error-layout layout}) datum request)
        m       (deref seen 0 ::never)]
    (is (= {:status 418 :headers HTML
            :body (str "<!DOCTYPE html>\n<html><body><main id=\"custom\">" (frag 418 "Tea" "leaf") "</main></body></html>")}
           out)
        "error-layout output used for the page")
    (is (not= ::never m) "the layout was invoked")
    (is (= #{:content :request :error} (set (keys m))) "layout receives exactly content/request/error")
    (is (= request (:request m)) ":request is the request given, host context included")
    (is (= datum (:error m)) ":error is the datum given, detail included")
    (is (= [:div.wb-error {:data-status 418}
            [:strong.wb-error-status 418]
            [:span.wb-error-title "Tea"]
            [:p.wb-error-detail "leaf"]]
           (:content m))
        ":content is the fragment as hiccup, not a string"))
  (let [never  (fn [_] (throw (ex-info "must not be called" {})))
        render (error/renderer {:error-layout never})
        datum  {:status 418 :title "Tea"}]
    (is (= {:status 418 :headers HTML :body (frag 418 "Tea" nil)} (render datum partial-req))
        "error-layout is not consulted for a fragment")
    (is (= {:status 418 :headers TEXT :body "418 Tea"} (render datum text-req))
        "error-layout is not consulted for text")))

(deftest throw!-datum-round-trips-through-the-middleware-with-title-and-detail
  (let [datum {:status 403 :title "T" :detail "D"}]
    (is (= ["web-base error 403" (assoc datum :type :dev.arkaitz.web-base.error/error)]
           (try (error/throw! datum)
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        "throw! carries the datum and :type")
    (is (= :dev.arkaitz.web-base.error/exception (:name (error/middleware render-error)))
        "middleware is a named reitit middleware map")
    (let [spy     (fn [datum _request] datum)
          handler ((:wrap (error/middleware spy)) (fn [_] (error/throw! datum)))]
      (is (= datum (handler page-req)) "middleware hands the datum back without :type"))
    (let [handler ((:wrap (error/middleware render-error)) (fn [_] (error/throw! datum)))]
      (is (= {:status 403 :headers HTML :body (page 403 (frag 403 "T" "D"))} (handler page-req))
          "thrown 403 renders as a 403 page")
      (is (= {:status 403 :headers HTML :body (frag 403 "T" "D")} (handler partial-req))
          "thrown 403 renders as a 403 fragment"))))

(deftest a-datum-without-an-integer-status-fails-loudly
  (doseq [datum [{:title "T"} {:status "404"} {:status 404.0}]]
    (is (= ["error datum has no integer :status" {:datum datum}]
           (try (error/throw! datum)
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "throw! refuses " (pr-str datum)))
    (is (= ["error datum has no integer :status" {:datum datum}]
           (try (render-error datum page-req)
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "renderer refuses " (pr-str datum)))))

(defn- app-with [middleware routes]
  (ring/ring-handler
   (ring/router routes {:data {:coercion   malli-coercion/coercion
                               :middleware middleware}})
   (error/default-handler render-error)))

(def ^:private routes
  [["/items/:id" {:parameters {:path [:map [:id :int]]}
                  :get (fn [{{{:keys [id]} :path} :parameters}] {:status 200 :body [:p (pr-str id)]})}]
   ["/nil"       {:get (fn [_] nil)}]
   ["/any-nil"   {:handler (fn [_] nil)}]
   ["/only-post" {:post (fn [_] {:status 200 :body "ok"})}]
   ["/get-post"  {:get (fn [_] {:status 200 :body "ok"}) :post (fn [_] {:status 200 :body "ok"})}]])

(def ^:private app
  (app-with [(error/middleware render-error) render/middleware coercion/coerce-request-middleware] routes))

(deftest coercion-failure-is-400-with-structured-data-only-and-nothing-from-the-request
  (let [request (mock/header (mock/request :get "/items/SENTINELVALUE") "Cookie" "ring-session=SECRETCOOKIE")]
    (lt/with-log
      (is (= {:status 400 :headers HTML :body (page 400 (frag 400))} (app request))
          "coercion failure → bare 400 page: the base words nothing")
      (is (= {:status 400 :headers HTML :body (frag 400)}
             (app (mock/header request "HX-Request" "true")))
          "coercion failure → bare 400 fragment on a partial")
      (is (= {:status 400 :headers TEXT :body "400"}
             (app (mock/header request "Accept" "text/plain")))
          "coercion failure → bare 400 text")
      (is (= [] (lt/the-log)) "a coercion failure is the client's contract, not an incident: nothing logged"))
    (let [seen   (promise)
          layout (fn [m] (deliver seen (:error m)) [:html [:body (:content m)]])
          spied  (ring/ring-handler
                  (ring/router routes {:data {:coercion   malli-coercion/coercion
                                              :middleware [(error/middleware (error/renderer {:error-layout layout}))
                                                           render/middleware
                                                           coercion/coerce-request-middleware]}}))]
      (spied request)
      (is (= {:status 400 :wb/coercion {:id ["should be an integer"]}} (deref seen 0 ::never))
          "the host's layout receives the humanized explanation as data under :wb/coercion"))
    (let [raw (app-with [coercion/coerce-request-middleware] routes)
          s   (str (try (raw request) ::no-throw (catch ExceptionInfo e (pr-str (ex-data e)))))]
      (is (= [true true] [(boolean (re-find #"SECRETCOOKIE" s)) (boolean (re-find #"SENTINELVALUE" s))])
          "control: the raw coercion ex-data does carry the cookie and the value — the guard is live"))
    (let [spy     (fn [datum _request] datum)
          handler ((:wrap (error/middleware spy))
                   (fn [_] (throw (ex-info "x" {:type :reitit.coercion/request-coercion :cookie "SECRETCOOKIE"}))))]
      (lt/with-log
        (is (= {:status 400} (handler page-req))
            "when the explanation cannot be encoded the datum is a bare 400, never the raw ex-data")
        (is (= [[:warn "coercion explanation could not be encoded"]]
               (mapv (juxt :level :message) (lt/the-log)))
            "and the degradation is logged at warn")
        (is (instance? Throwable (:throwable (first (lt/the-log)))) "with the cause attached")))
    (let [spy      (fn [datum _request] datum)
          exploded (Error. "ENCODEBOOM")
          coercion (reify reitit.coercion/Coercion
                     (-get-name [_] (throw exploded)))
          handler  ((:wrap (error/middleware spy))
                    (fn [_] (throw (ex-info "x" {:type :reitit.coercion/request-coercion :coercion coercion}))))]
      (lt/with-log
        (is (= {:status 400} (handler page-req))
            "an Error while encoding stays inside the middleware: still a bare 400")
        (is (= [(entry :warn exploded "coercion explanation could not be encoded")] (lt/the-log))
            "and is logged with the Error itself")))
    (is (= {:status 200
            :headers {"Vary" "HX-Request, HX-Request-Type" "Content-Type" "text/html; charset=utf-8"}
            :body "<!DOCTYPE html>\n<p>7</p>"}
           (app (mock/request :get "/items/7")))
        "control: a valid id passes coercion as an integer, not a string")))

(deftest unexpected-throwable-is-a-bare-500-and-logged-at-error-with-the-throwable-itself
  (let [handler-for (fn [boom] ((:wrap (error/middleware render-error)) (fn [_] (throw boom))))
        boom        (RuntimeException. "SECRETMESSAGE")
        request     (assoc (mock/request :get "/x?token=SECRETQS") :wb/request-id "rid-1")]
    (lt/with-log
      (is (= {:status 500 :headers HTML :body (page 500 (frag 500))} ((handler-for boom) request))
          "unexpected throwable → bare 500 page, message withheld")
      (is (= [(entry :error boom "unhandled exception {:request-id rid-1, :uri /x}")] (lt/the-log))
          "logged exactly once at :error with the throwable itself, the request id, the path without its query string"))
    (lt/with-log
      (is (= {:status 500 :headers HTML :body (frag 500)} ((handler-for boom) partial-req))
          "unexpected throwable → bare 500 fragment on a partial"))
    (let [err (Error. "ERRMSG")]
      (lt/with-log
        (is (= {:status 500 :headers HTML :body (page 500 (frag 500))} ((handler-for err) page-req))
            "a java.lang.Error is caught and rendered too")
        (is (= [(entry :error err "unhandled exception {:request-id nil, :uri /x}")] (lt/the-log))
            "the Error is logged with itself")))
    (let [foreign (ex-info "other" {:type :something/else :status 402})]
      (lt/with-log
        (is (= {:status 500 :headers HTML :body (page 500 (frag 500))} ((handler-for foreign) page-req))
            "an ex-info with a foreign :type is a 500, its :status is not trusted")
        (is (= [(entry :error foreign "unhandled exception {:request-id nil, :uri /x}")] (lt/the-log))
            "and it is logged once")))))

(deftest default-handler-404-405-and-500-for-a-nil-handler-render-outside-router-middleware
  ;; OPTIONS appears in Allow because reitit adds an OPTIONS endpoint to every
  ;; route by default; a reitit change there would show up here.
  (doseq [[label path method status headers] [["unmatched path"            "/nope"      :get  404 HTML]
                                              ["route without the method" "/only-post" :get  405 (assoc HTML "Allow" "OPTIONS, POST")]
                                              ["HEAD on a POST-only route" "/only-post" :head 405 (assoc HTML "Allow" "OPTIONS, POST")]
                                              ["Allow lists every method, sorted" "/get-post" :put 405 (assoc HTML "Allow" "GET, OPTIONS, POST")]]]
    (testing label
      (is (= {:status status :headers headers :body (page status (frag status))}
             (app (mock/request method path)))
          "page request → whole page")
      (is (= {:status status :headers headers :body (frag status)}
             (app (mock/header (mock/request method path) "HX-Request" "true")))
          "partial request → fragment")))
  (doseq [path ["/nil" "/any-nil"]]
    (testing (str "handler returned nil at " path)
      (lt/with-log
        (is (= {:status 500 :headers HTML :body (page 500 (frag 500))}
               (app (mock/request :get path)))
            "a nil answer is a programming error → 500 page")
        (is (= [(entry :error nil (str "handler returned nil {:request-id nil, :uri " path "}"))] (lt/the-log))
            "and it is logged"))))
  (is (= {:status 404 :headers TEXT :body "404"}
         (app (mock/header (mock/request :get "/nope") "Accept" "text/plain")))
      "unmatched path with Accept: text/plain → text")
  (is (= {:status 404 :headers HTML :body (page 404 (frag 404))}
         ((error/default-handler render-error) page-req))
      "called directly without a match → 404 page"))

(def ^:private layout-boom (RuntimeException. "LAYOUTBOOM"))

(deftest a-layout-that-throws-inside-render-middleware-becomes-an-error-page--order-proof
  (let [routes    [["/throwing-layout" {:wb/layouts [(fn [_] (throw layout-boom))]
                                        :get (fn [_] {:status 200 :body [:p "hi"]})}]]
        app-right (app-with [(error/middleware render-error) render/middleware] routes)
        app-wrong (app-with [render/middleware (error/middleware render-error)] routes)
        request   (mock/request :get "/throwing-layout")]
    (lt/with-log
      (is (= {:status 500 :headers HTML :body (page 500 (frag 500))} (app-right request))
          "layout throw with error outside render → 500 page")
      (is (= [(entry :error layout-boom "unhandled exception {:request-id nil, :uri /throwing-layout}")] (lt/the-log))
          "the layout's exception is logged once"))
    (is (= {:status 200 :headers {"Vary" "HX-Request, HX-Request-Type" "Content-Type" "text/html; charset=utf-8"} :body "<p>hi</p>"}
           (app-right (mock/header request "HX-Request" "true")))
        "on a partial the layout is not applied, so nothing throws")
    (is (= "LAYOUTBOOM"
           (try (app-wrong request) ::no-throw (catch RuntimeException e (ex-message e))))
        "control: with render outside error the throw escapes — the order is what protects")))
