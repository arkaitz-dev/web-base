(ns dev.arkaitz.web-base.scripted-swap-test
  "A page with scripts of its own, asked for by an htmx swap of the whole document
  (since 0.24.0): htmx never runs the `<head>` it is answered with, so the page answers
  `HX-Redirect` to itself and the browser loads it as a document. Every literal was
  measured once through the stack and typed here. ring-mock normalises no path, so the
  off-origin cases prove `own-location`, not what an adapter would let through."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")
(def ^:private VARY "HX-Request, HX-Request-Type")

(defn- page [body & [slots]] (fn [_] {:status 200 :body body :wb/slots slots}))

(def ^:private app
  (wb/handler {:routes  [["/" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"] :get (page [:p "root"])}]
                         ["/created" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"]
                                      :get (fn [_] {:status 201 :body [:p "made"]})}]
                         ["/things" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"]
                                     :get  (fn [r] {:status 200 :body [:p (str "things" (some-> (:wb/form r) :error))]})
                                     :post (fn [r] (wb/rerender r "/things" {:error ": refused"}))}]
                         ["/slot" {:wb/layouts [shell/page] :get (page [:p "s"] {:scripts ["/js/a.js"]})}]
                         ["/route" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"] :get (page [:p "r"])
                                    :post (page [:p "posted"])}]
                         ["/plain" {:wb/layouts [shell/page] :get (page [:p "plain"])}]
                         ["/bare" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"] :get (fn [_] {:body [:p "bare"]})}]
                         ["/422" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"]
                                  :get (fn [_] {:status 422 :body [:p "bad"]})}]
                         ["/sess" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"]
                                   :get (fn [_] {:status 200 :body [:p "x"] :session {:seen 1}
                                                 :headers {"X-Mine" "1" "Set-Cookie" ["a=b; Path=/"]}})}]
                         ["/maybe" {:wb/layouts [shell/page]
                                    :get (fn [r] {:status 200 :body [:p "m"]
                                                  :wb/slots {:scripts (when (:query-string r) ["/js/a.js"])}})}]
                         ["/tall" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"]
                                   :get (fn [_] {:status 200 :body [:p "t"] :wb/height 1})}]
                         ["/who" {:get (fn [r] {:status 200 :body (pr-str (:session r))})}]
                         ["/item/:id" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"] :get (page [:p "i"])}]]
               :session {:key KEY}}))

(def ^:private catch-all
  (wb/handler {:routes  [["/*p" {:wb/layouts [shell/page] :wb/scripts ["/js/r.js"] :get (page [:p "catch"])}]]
               :session {:key KEY}}))

(defn- full [request] (-> request (mock/header "HX-Request" "true") (mock/header "HX-Request-Type" "full")))
(defn- seen [r] [(:status r) (:body r) (select-keys (:headers r) ["HX-Redirect" "Vary" "Content-Type"])])

(deftest a-link-to-a-page-with-scripts-of-its-own-is-answered-with-a-redirect-to-that-page
  (is (str/includes? (:body (app (mock/request :get "/slot"))) "src=\"/js/a.js\"") "witness: /slot loads a script of its slot")
  (is (str/includes? (:body (app (mock/request :get "/route"))) "src=\"/js/r.js\"") "witness: /route loads its route's")
  (is (= [200 "" {"HX-Redirect" "/slot?a=1&b=%2F" "Vary" VARY}] (seen (app (full (mock/request :get "/slot?a=1&b=%2F")))))
      "a slot's scripts: to its own path and query, the body empty")
  (is (= [200 "" {"HX-Redirect" "/route" "Vary" VARY}] (seen (app (full (mock/request :get "/route")))))
      "a route's scripts")
  (is (= "/item/a%20b?x=1" (get-in (app (full (mock/request :get "/item/a%20b?x=1"))) [:headers "HX-Redirect"]))
      "the path as it was asked for, its encoding kept")
  (is (= "/route" (get-in (app (-> (mock/request :get "/route") (mock/header "HX-History-Restore-Request" "true")
                                   (mock/header "HX-Request-Type" "full")))
                          [:headers "HX-Redirect"]))
      "Back, as htmx 4 asks for it: no HX-Request, no HX-Target")
  (is (= "/bare" (get-in (app (full (mock/request :get "/bare"))) [:headers "HX-Redirect"]))
      "a response without a status is Ring's 200")
  (is (= "/" (get-in (app (full (mock/request :get "/"))) [:headers "HX-Redirect"])) "the root page")
  (is (= ["/route" "/route"] (mapv #(get-in (app (mock/header (full (mock/request :get "/route")) "HX-Target" %)) [:headers "HX-Redirect"])
                                    ["main" "body"]))
      "a swap of the page's <main> or <body>, as htmx names them"))

(deftest what-the-handler-set-survives-the-redirect
  (is (= "{}" (:body (app (mock/request :get "/who")))) "witness: without its cookie, no session")
  (let [r (app (full (mock/request :get "/sess")))]
    (is (= "/sess" (get-in r [:headers "HX-Redirect"])) "witness: redirected")
    (is (= "1" (get-in r [:headers "X-Mine"])) "the handler's own header")
    (is (= "b" (get (testing/cookies r) "a")) "its cookie")
    (is (= "{:seen 1}" (:body (app (testing/with-cookies (mock/request :get "/who") r))))
        "and the session it wrote")))

(deftest only-a-whole-document-swap-of-a-scripted-page-by-get-is-redirected
  (is (= "/route" (get-in (app (full (mock/request :get "/route"))) [:headers "HX-Redirect"]))
      "witness: the same app does redirect, so each nil below is the case's doing")
  (let [cases [["a page without scripts" (full (mock/request :get "/plain")) "<p>plain</p>" true]
               ["a navigation" (mock/request :get "/route") "src=\"/js/r.js\"" true]
               ["an htmx fragment" (mock/header (mock/request :get "/route") "HX-Request" "true") "<p>r</p>" false]
               ["a fragment the handler renders through the shell" (mock/header (mock/request :get "/tall") "HX-Request" "true")
                "src=\"/js/r.js\"" false]
               ["the restore header without type full, which Vary could not tell from a navigation"
                (mock/header (mock/request :get "/route") "HX-History-Restore-Request" "true") "src=\"/js/r.js\"" true]
               ["type full without HX-Request" (mock/header (mock/request :get "/route") "HX-Request-Type" "full")
                "src=\"/js/r.js\"" true]
               ["a status other than 200" (full (mock/request :get "/422")) "<p>bad</p>" true]
               ["a POST" (-> (mock/request :post "/route") (mock/header "Sec-Fetch-Site" "same-origin") full)
                "<p>posted</p>" true]
               ["a form rerender shows again" (-> (mock/request :post "/things") (mock/header "Sec-Fetch-Site" "same-origin") full)
                "<p>things: refused</p>" true]
               ["a status other than 200 that is not an error" (full (mock/request :get "/created")) "<p>made</p>" true]
               ["a swap of another element that selects from the page"
                (mock/header (full (mock/request :get "/route")) "HX-Target" "div#results") "<p>r</p>" true]]]
    (doseq [[label request marker document?] cases]
      (testing label
        (let [r (app request)]
          (is (nil? (get-in r [:headers "HX-Redirect"])) "not redirected")
          (is (str/includes? (str (:body r)) marker) "answered with the page itself")
          (is (= document? (str/starts-with? (str (:body r)) "<!DOCTYPE html>")) "as a document, or as the fragment asked")))))
  (testing "the flag is the request's own, never the route's"
    (is (= "/route" (get-in (app (full (mock/request :get "/route"))) [:headers "HX-Redirect"])) "a swap first")
    (is (str/includes? (:body (app (mock/request :get "/route"))) "<p>r</p>") "then a navigation renders")
    (is (str/includes? (:body (app (full (mock/request :get "/plain")))) "<p>plain</p>") "and a page without scripts too")
    (is (= "/maybe?s=1" (get-in (app (full (mock/request :get "/maybe?s=1"))) [:headers "HX-Redirect"]))
        "a page whose scripts this request gives")
    (is (str/includes? (str (:body (app (full (mock/request :get "/maybe"))))) "<p>m</p>")
        "and the same route without them, swapped right after, renders")))

(deftest a-path-that-is-not-this-origins-is-never-a-redirect
  (is (= "/ok/x" (get-in (catch-all (full (mock/request :get "/ok/x"))) [:headers "HX-Redirect"]))
      "witness: the catch-all route redirects a path of this origin")
  (doseq [uri ["//evil.example/x" "/\\evil.example/x"]]
    (testing uri
      (let [r (catch-all (full (assoc (mock/request :get "/") :uri uri)))]
        (is (nil? (get-in r [:headers "HX-Redirect"])) "no Location another origin's")
        (is (= 200 (:status r)) "the page is answered")
        (is (str/includes? (:body r) "<p>catch</p>") "as itself")))))

(deftest the-location-is-the-matched-routes-path-never-the-uri
  ;; reitit matches on :uri, so through a router the two never differ: `response` is
  ;; called with a request built by hand, as the middleware would hand it.
  (let [layout  (fn [{:keys [content request]}] (render/mark-scripted! request) [:html [:body content]])
        request (fn [uri match] (cond-> {:request-method :get :uri uri :query-string "q=1"
                                         :headers {"hx-request" "true" "hx-request-type" "full"}
                                         ::render/scripted (volatile! false)}
                                  match (assoc :reitit.core/match {:path match})))
        answer  #(render/response % {:status 200 :body [:p "x"] :wb/slots {:title "T"} :wb/height 1} [layout])]
    (is (= {:status 200 :body "" :headers {"HX-Redirect" "/real?q=1" "Vary" VARY}}
           (answer (request "//evil.example/x" "/real")))
        "the match's path, with the query, and the keys only the base reads stripped")
    (is (nil? (get-in (answer (request "/real" "//evil.example/x")) [:headers "HX-Redirect"]))
        "a matched path not of this origin is no Location")
    (is (nil? (get-in (answer (request "/real" nil)) [:headers "HX-Redirect"])) "no match, no Location")
    (is (str/includes? (:body (render/response (dissoc (request "/real" "/real") ::render/scripted)
                                               {:status 200 :body [:p "x"]} [layout]))
                       "<p>x</p>")
        "a request without the flag renders: mark-scripted! marks nothing")))
