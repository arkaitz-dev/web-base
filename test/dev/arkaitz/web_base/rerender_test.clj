(ns dev.arkaitz.web-base.rerender-test
  "A form's round trip: `rerender` renders a route's own GET again for the request a
  POST is answering, and `response/unprocessable` is the 422 of a handler that renders
  its own fragment. Through the assembled handler with sessions and CSRF on; literals
  copied, never required from the views that produce them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as testing]
            [reitit.core :as r]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private LOGIN "/entrar-aqui")

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page [status body]
  (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
       "<title>" status "</title></head><body>" body "</body></html>"))

(defn- layout [{:keys [content]}] [:html [:body [:div#layout content]]])
(defn- section [{:keys [content title]}] [:section [:h1 title] content])

(defn- app
  "A host with a form page and every shape the tests need. `got` holds the request the
  form page's GET handler saw, `runs` counts its runs and the looping page's."
  [got runs]
  (wb/handler
   {:session    {:key KEY}
    :subject-fn #(get-in % [:session :user])
    :login-path LOGIN
    :routes
    [["/" {:get (fn [_] {:status 200 :body "home"})}]
     ["/login" {:post (fn [r] (session/rotate (response/see-other "/") {:user (get-in r [:form-params "user"])}))}]
     ["/things/:id" {:wb/layouts [layout]
                     :get (fn [r]
                            (reset! got r)
                            (swap! runs inc)
                            (response/ok [:div#things-page
                                          [:form (security/csrf-field r)
                                           [:input {:name "name" :value (get-in r [:wb/form :values :name])}]
                                           [:p.err (get-in r [:wb/form :errors :name])]
                                           [:i (get-in r [:params "q"]) "/" (get-in r [:path-params :id])]]]))}]
     ;; No path parameters here on purpose: the target's must come from the target.
     ["/save" {:post (fn [r] (let [n (get-in r [:form-params "name"])]
                               (if (= "fine" n)
                                 (response/see-other "/")
                                 (wb/rerender r "/things/7?q=z" {:values {:name n} :errors {:name "bad"}}))))}]
     ;; What a POST route's own middleware leaves on the request — coerced parameters, a
     ;; multipart form, a parsed body — planted by hand, so no coercion library is needed.
     ["/save-dirty" {:middleware [(fn [h] (fn [r] (h (assoc r :parameters {:form {:x 1}}
                                                              :multipart-params {"f" "x"}
                                                              :body-params {:y 1}
                                                              :path-info "/save-dirty"))))]
                     ;; What the POST itself was given travels in the form, as the witness
                     ;; that the planting happened at all.
                     :post (fn [r] (wb/rerender r "/things/7" {:planted (select-keys r [:parameters :multipart-params
                                                                                          :body-params :path-info :query-string])}))}]
     ["/admin" {:wb/gate (fn [r] (= "root" (:wb/subject r)))
                :get (fn [_] (swap! runs inc) (response/ok [:p "admin page"]))}]
     ["/save-admin" {:post (fn [r] (wb/rerender r "/admin" {}))}]
     ["/gone" {:get (fn [_] (response/see-other "/"))}]
     ["/hx" {:get (fn [_] (htmx/redirect "/"))}]
     ["/missing" {:get (fn [_] (error/throw! {:status 404}))}]
     ["/via" {:post (fn [r] (wb/rerender r (get-in r [:form-params "to"]) {}))}]
     ["/loop" {:get (fn [r] (swap! runs inc) (wb/rerender r "/loop" (get-in r [:params "form"])))}]
     ["/kick" {:post (fn [r] (wb/rerender r "/loop" (when (= "some" (get-in r [:form-params "form"])) {})))}]
     ["/only-post" {:post (fn [_] {:status 200 :body "only"})}]
     ["/frag" {:post (fn [_] (response/unprocessable [:form#f "bad"]))}]
     ["/frag2" {:wb/layouts [layout section]
                :post (fn [_] (response/unprocessable [:form#f "bad"] {:slots {:title "Nope"} :height 1}))}]]}))

(defn- fixture []
  (let [got (atom nil) runs (atom 0)]
    {:got got :runs runs :app (app got runs)}))

;; `/` answers a plain string, so it carries no token: a token comes from a page that
;; renders one. The form page is that page; reaching it costs one run, subtracted below.
(defn- ready [app] (testing/visit (testing/browser app) :get "/things/1"))

(deftest rerender-runs-the-targets-own-get-with-the-form-and-only-the-targets-params
  (let [{:keys [got runs app]} (fixture)
        b     (ready app)
        token (:token b)
        _     (reset! runs 0)
        b     (testing/visit b :post "/save" {"name" "<b>x" "extra" "1"})
        r     (:response b)]
    (is (string? token) "witness: a page gave the browser a token")
    (is (= 422 (:status r)) "the refused form answers 422")
    (is (= (str "<!DOCTYPE html>\n<html><body><div id=\"layout\"><div id=\"things-page\"><form>"
                "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"" token "\">"
                "<input name=\"name\" value=\"&lt;b&gt;x\"><p class=\"err\">bad</p><i>z/7</i></form></div></div></body></html>")
           (:body r))
        "the whole page, drawn by the page's own GET handler through its layout, with the typed value escaped")
    (is (= 1 @runs) "the page's GET handler ran exactly once")
    (let [seen @got]
      (is (= [:get "/things/7" "q=z" {"q" "z"} {"q" "z"} {:id "7"}]
             [(:request-method seen) (:uri seen) (:query-string seen) (:query-params seen) (:params seen) (:path-params seen)])
          "the page saw a GET of the target, with the target's own query and path parameters only")
      (is (= {:values {:name "<b>x"} :errors {:name "bad"}} (:wb/form seen)) "and the form it was handed")
      (is (not (contains? seen :form-params)) "the POST's form did not follow it")
      (is (not (contains? seen :body)) "nor its body")
      (is (= "/things/:id" (get-in seen [::r/match :template])) "the match is the target's")
      (is (= token (:anti-forgery-token seen)) "and the request kept its session's token"))
    (let [b (testing/visit b :post "/save" {"name" "fine"})]
      (is (= [200 "home"] [(:status (:response b)) (:body (:response b))])
          "the token on the 422 page is a working one: the corrected form goes through"))))

(deftest nothing-the-post-was-given-reaches-the-page
  (let [{:keys [got app]} (fixture)
        r    (:response (testing/visit (ready app) :post "/save-dirty?x=1" {"name" "n"}))
        seen @got]
    (is (= 422 (:status r)) "witness: the page was rendered again")
    (is (= {:parameters {:form {:x 1}} :multipart-params {"f" "x"} :body-params {:y 1}
            :path-info "/save-dirty" :query-string "x=1"}
           (:planted (:wb/form seen)))
        "witness: the POST really carried every one of them, so their absence below is the rerender's doing")
    (is (= "/things/7" (:uri seen)) "and it is the target the page saw")
    (is (= {} (select-keys seen [:parameters :multipart-params :body-params :query-string :path-info :form-params :body]))
        "none of the POST's parameters, bodies, query string or path reaches a page asked for with none of its own")
    (is (= [{} {}] [(:query-params seen) (:params seen)]) "and its parameters are the target's: none")))

(deftest rerender-answers-the-targets-gate-refusal-not-422
  (testing "control: the one the gate lets in gets the page, 422"
    (let [{:keys [runs app]} (fixture)
          b (-> (ready app) (testing/visit :post "/login" {"user" "root"}) (testing/visit :get "/things/1"))
          _ (reset! runs 0)
          r (:response (testing/visit b :post "/save-admin"))]
      (is (= 422 (:status r)))
      (is (str/includes? (:body r) "admin page"))
      (is (= 1 @runs))))
  (testing "somebody else signed in gets the gate's 403, and the page never ran"
    (let [{:keys [runs app]} (fixture)
          b (-> (ready app) (testing/visit :post "/login" {"user" "ann"}) (testing/visit :get "/things/1"))
          _ (reset! runs 0)
          r (:response (testing/visit b :post "/save-admin"))]
      (is (= [403 (page 403 (frag 403))] [(:status r) (:body r)]))
      (is (= 0 @runs))))
  (testing "nobody signed in gets the gate's own redirect to the login, unchanged"
    (let [{:keys [app]} (fixture)
          b (ready app)
          r (app (-> (mock/request :post "/save-admin")
                     (mock/body {"__anti-forgery-token" (:token b)})
                     (assoc-in [:headers "cookie"] (str/join "; " (map (fn [[k v]] (str k "=" v)) (:jar b))))))]
      (is (= [303 LOGIN "no-store" ""]
             [(:status r) (get-in r [:headers "Location"]) (get-in r [:headers "Cache-Control"]) (:body r)])))))

(deftest rerender-passes-through-what-is-not-a-plain-200
  (let [{:keys [app]} (fixture)
        b    (ready app)
        post (fn [to] (app (-> (mock/request :post "/via")
                               (mock/body {"__anti-forgery-token" (:token b) "to" to})
                               (assoc-in [:headers "cookie"] (str/join "; " (map (fn [[k v]] (str k "=" v)) (:jar b)))))))]
    (let [r (post "/gone")]
      (is (= [303 "/" ""] [(:status r) (get-in r [:headers "Location"]) (:body r)]) "a redirect stays a redirect"))
    (let [r (post "/hx")]
      (is (= [200 "/" ""] [(:status r) (get-in r [:headers "HX-Redirect"]) (:body r)]) "an HX-Redirect stays a 200"))
    (let [r (post "/missing")]
      (is (= [404 (page 404 (frag 404))] [(:status r) (:body r)]) "the page's own error stays its error"))))

(deftest rerender-with-no-get-route-is-the-posts-500-naming-the-path
  (doseq [to ["/only-post" "/nope"]]
    (lt/with-log
      (let [{:keys [app]} (fixture)
            r (:response (testing/visit (ready app) :post "/via" {"to" to}))]
        (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)]) (str to ": the POST's error page"))
        (is (= [(str "web-base rerender: no GET route for " to) {:path to}]
               (some (fn [{:keys [throwable]}] (when throwable [(ex-message throwable) (ex-data throwable)]))
                     (lt/the-log)))
            (str to ": and the log names the path"))))))

(deftest a-get-that-rerenders-itself-stops-at-one-run
  (doseq [[label form] [["an empty form" "some"] ["no form at all" nil]]]
    (lt/with-log
      (let [{:keys [runs app]} (fixture)
            b (ready app)
            _ (reset! runs 0)
            r (:response (testing/visit b :post "/kick" (when form {"form" form})))
            t (some :throwable (lt/the-log))]
        (is (= 500 (:status r)) (str label ": an error, not a hang"))
        (is (= 1 @runs) (str label ": the looping page ran once"))
        (is (= [clojure.lang.ExceptionInfo "web-base rerender: /loop was reached from a rerender already" {:path "/loop"}]
               [(class t) (ex-message t) (ex-data t)])
            (str label ": stopped by the guard, not by the stack"))))))

(deftest htmx-unprocessable-is-a-422-fragment-and-a-fragment-rerender-is-the-pages-content
  (let [{:keys [app]} (fixture)
        b (ready app)]
    (let [r (:response (testing/visit b :post "/frag" {} {:htmx? true}))]
      (is (= [422 "<form id=\"f\">bad</form>"] [(:status r) (:body r)]) "the fragment, alone, 422"))
    (let [r (:response (testing/visit b :post "/frag2" {} {:htmx? true}))]
      (is (= [422 "<section><h1>Nope</h1><form id=\"f\">bad</form></section>"] [(:status r) (:body r)])
          "with its slots and its height"))
    (let [r (:response (testing/visit b :post "/save" {"name" ""} {:htmx? true}))]
      (is (= 422 (:status r)))
      (is (str/starts-with? (:body r) "<div id=\"things-page\">")
          "a rerender under htmx renders the page's whole content — why a form that swaps only itself wants unprocessable")
      (is (not (str/includes? (:body r) "layout")) "without the layout"))))
