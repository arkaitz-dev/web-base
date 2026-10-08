(ns dev.arkaitz.web-base.gate-test
  "The login path is deliberately one no default would guess."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.gate :as gate]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private login "/entrar-aqui")
(def ^:private render-error (error/renderer {}))
(def ^:private gate-opts {:login-path login :render-error render-error})

(def ^:private VARY "HX-Request, HX-Request-Type, Accept")
(def ^:private HTML {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/html; charset=utf-8"})
(def ^:private TEXT {"Vary" VARY "Cache-Control" "no-store" "Content-Type" "text/plain; charset=utf-8"})
(def ^:private REFUSAL {"Vary" "HX-Request, HX-Request-Type" "Cache-Control" "no-store"})
(def ^:private FRAG-403 "<div class=\"wb-error\" data-status=\"403\"><strong class=\"wb-error-status\">403</strong></div>")
(def ^:private PAGE-403 (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                             "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
                             "<title>403</title></head><body>" FRAG-403 "</body></html>"))

(defn- hi [request]
  {:status 200 :body (str "hi " (pr-str (:wb/subject request)))})

(def ^:private subject-fn #(get-in % [:headers "x-subject"]))

(defn- router
  ([] (router gate-opts))
  ([opts]
   (ring/router [["/pub"   {:get hi}]
                 ["/priv"  {:wb/gate gate/subject-present? :get hi :post hi}]
                 ["/open"  {:wb/gate (constantly true) :get hi}]
                 ["/never" {:wb/gate (fn [_] false) :get hi}]]
                {:data {:middleware [(gate/middleware opts)]}})))

(defn- app-of [router]
  (gate/wrap-subject (ring/ring-handler router (error/default-handler render-error)) subject-fn))

(def ^:private app (app-of (router)))

(defn- req [method path & headers]
  (reduce (fn [request [k v]] (mock/header request k v)) (mock/request method path) (partition 2 headers)))

(defn- get* [path & headers]
  (apply req :get path headers))

(deftest wrap-subject-puts-the-subject-fn-result-on-every-request--public-routes-included
  (let [calls (atom [])
        spy   (fn [request] (swap! calls conj request) :s)]
    (is (= {:uri "/x" :wb/subject :s :headers {"Cache-Control" "no-store"}} ((gate/wrap-subject identity spy) {:uri "/x"}))
        "the result lands under :wb/subject — and, echoed as the response here, a response with a subject is not cached")
    (is (= [{:uri "/x"}] @calls) "subject-fn is called once with the request as received"))
  (let [out ((gate/wrap-subject identity (constantly nil)) {:uri "/"})]
    (is (= [true nil false] [(contains? out :wb/subject) (:wb/subject out) (contains? out :headers)])
        "nil is still put on the request, and an anonymous response is left cacheable"))
  (is (= {:uri "/" :wb/subject :s :headers {"Cache-Control" "no-store"}}
         ((gate/wrap-subject identity (constantly :s)) {:uri "/" :wb/subject :stale}))
      "a stale value is replaced")
  (is (= {:status 200 :body "hi nil"} (app (get* "/pub"))) "public route, anonymous: the handler sees nil")
  (is (= {:status 200 :headers {"Cache-Control" "no-store"} :body "hi \"ana\""} (app (get* "/pub" "X-Subject" "ana")))
      "public route handler sees :wb/subject, and its answer to somebody is not cached")
  (is (= {:status 200 :headers {"Cache-Control" "no-store"} :body "hi \"ana\""} (app (get* "/priv" "X-Subject" "ana")))
      "gated route handler sees it too"))

(deftest subject-present?-is-presence-not-the-key-nor-truthiness
  (is (= [false false true true true true true]
         (mapv gate/subject-present?
               [{} {:wb/subject nil} {:wb/subject "ana"} {:wb/subject {}} {:wb/subject 0} {:wb/subject ""} {:wb/subject false}]))
      "presence means a non-nil value: not the key, and false is a subject too"))

(deftest gate-honours-the-predicate--true-runs-the-handler-untouched--the-predicate-sees-the-whole-request
  (let [by-pred    (atom nil)
        by-handler (atom nil)
        spy        (fn [request] (reset! by-pred request) (some? (:wb/subject request)))
        recorder   (fn [request] (reset! by-handler request) (hi request))
        router     (ring/router [["/spy" {:wb/gate spy :get recorder}]] {:data {:middleware [(gate/middleware gate-opts)]}})
        app        (app-of router)
        original   (get* "/spy" "X-Subject" "ana")]
    (is (= {:status 200 :headers {"Cache-Control" "no-store"} :body "hi \"ana\""} (app original))
        "predicate true → handler response returned, only marked uncacheable for its subject")
    (is (= @by-handler @by-pred) "the predicate sees exactly the request the handler sees")
    (is (= original (select-keys @by-pred (keys original))) "which carries everything the client sent")
    (is (= "ana" (:wb/subject @by-pred)) "and the subject"))
  (is (= {:status 200 :body "hi nil"} (app (get* "/open")))
      "predicate true without a subject still runs the handler — the base does not second-guess the predicate")
  ;; A refusal is what speaks htmx; a pass must not. Without this row a gate
  ;; that refused every fragment would be green — and an htmx swap of a gated
  ;; route is what the README teaches.
  (is (= {:status 200 :headers {"Cache-Control" "no-store"} :body "hi \"ana\""} (app (get* "/priv" "X-Subject" "ana" "HX-Request" "true")))
      "an htmx swap with the subject present passes: no HX-Redirect, no fragment")
  (is (= 403 (:status (app (get* "/never" "X-Subject" "ana")))) "predicate false with a subject → 403"))

(deftest refusal-without-a-subject--303-on-a-navigation--hx-redirect-and-never-location-on-a-partial
  (let [back     {:status 303 :headers (assoc REFUSAL "Location" (str login "?next=%2Fpriv")) :body ""}
        redirect {:status 303 :headers (assoc REFUSAL "Location" login) :body ""}
        hx       {:status 200 :headers (assoc REFUSAL "HX-Redirect" login) :body ""}]
    (is (= back (app (get* "/priv")))
        "navigation refusal is exactly 303 + Location carrying the page as next, uncached, varying on htmx")
    (is (= hx (app (get* "/priv" "HX-Request" "true"))) "htmx partial refusal is exactly 200 + HX-Redirect, no Location, no next")
    (is (= back (app (get* "/priv" "HX-Request" "true" "HX-Request-Type" "full"))) "history restore is a navigation → 303 with next")
    (is (= back (app (get* "/priv" "Accept" "text/plain"))) "Accept plays no part in a refusal")
    (is (= back (app (get* "/priv" "Accept" "application/json"))) "an API client is refused the same way: no 401 (SPEC §9.2, the base cannot name a scheme)")
    (is (= {:status 303 :headers (assoc REFUSAL "Location" (str login "?next=%2Fpriv%3Fq%3Da%26b%3D1")) :body ""}
           (app (assoc (get* "/priv") :query-string "q=a&b=1")))
        "the query string travels too, encoded")
    (is (= redirect (app (req :post "/priv"))) "a refused POST is a 303 without next: there is no page to return to, only a form")
    (is (= hx (app (req :post "/priv" "HX-Request" "true"))) "a refused htmx POST gets HX-Redirect")
    (is (= hx (app {:request-method :get :uri "/priv" :headers {"hx-request" "true"}})) "hand-built lowercase header")))

(deftest refusal-with-a-subject-is-a-403-datum-through-render-error--fragment-on-a-partial--page-on-a-navigation
  (is (= {:status 403 :headers HTML :body PAGE-403} (app (get* "/never" "X-Subject" "ana"))) "refusal with a subject → 403 page (never a redirect)")
  (is (= {:status 403 :headers HTML :body FRAG-403} (app (get* "/never" "X-Subject" "ana" "HX-Request" "true"))) "→ 403 fragment on a partial")
  (is (= {:status 403 :headers TEXT :body "403"} (app (get* "/never" "X-Subject" "ana" "Accept" "text/plain"))) "→ 403 text")
  (let [seen     (promise)
        spy      (fn [datum request] (deliver seen [datum request]) {:status 999 :body "spy"})
        app      (app-of (router {:login-path login :render-error spy}))
        original (get* "/never" "X-Subject" "ana")
        out      (app original)
        [datum request] (deref seen 0 [::never ::never])]
    (is (= {:status 999 :headers {"Cache-Control" "no-store"} :body "spy"} out)
        "the gate returns render-error's response, uncacheable like every answer to a subject")
    (is (= {:status 403} datum) "render-error receives exactly {:status 403}")
    (is (= original (select-keys request (keys original))) "and the whole request the client sent")
    (is (= "ana" (:wb/subject request)) "subject included")))

(deftest middleware-is-named-and-vanishes-from-routes-without-wb-gate
  (let [mw (gate/middleware gate-opts)]
    (is (= :dev.arkaitz.web-base.gate/gate (:name mw)))
    (is (nil? ((:compile mw) {} nil)) "compile returns nil for a route without :wb/gate")
    (is (nil? ((:compile mw) {:wb/gate nil} nil)) "and for a nil gate")
    (is (fn? ((:compile mw) {:wb/gate (constantly true)} nil)) "a wrapper for a gated route"))
  (let [match #(r/match-by-path (router) %)
        chain #(mapv :name (get-in (match %) [:result :get :middleware]))]
    (is (fn? (get-in (match "/pub") [:result :get :handler])) "/pub is a routed GET")
    (is (= [] (chain "/pub")) "and reitit's compiled chain for it holds no gate")
    (is (= [:dev.arkaitz.web-base.gate/gate] (chain "/priv")) "while /priv holds it")))

(deftest a-gated-route-with-a-bad-gate-or-no-login-path-fails-at-router-construction-naming-the-config-key
  (let [build (fn [routes opts]
                (try (ring/router routes {:data {:middleware [(gate/middleware opts)]}})
                     ::constructed
                     (catch ExceptionInfo e [(ex-message e) (dissoc (ex-data e) :reitit.exception/cause)])))]
    (is (= ["web-base: a route declares :wb/gate but no :login-path is configured" {:config-key [:login-path]}]
           (build [["/priv" {:wb/gate gate/subject-present? :get hi}]] {:render-error render-error}))
        "a gated route with no :login-path throws at router construction")
    (is (= ["web-base: a route declares :wb/gate but no :login-path is configured" {:config-key [:login-path]}]
           (build [["/priv" {:wb/gate gate/subject-present? :get hi}]] {:login-path "" :render-error render-error}))
        "a blank :login-path counts as missing")
    (doseq [bad [false "yes" 42]]
      (is (= ["web-base: a route declares :wb/gate that is not callable" {:config-key [:wb/gate] :value bad}]
             (build [["/priv" {:wb/gate bad :get hi}]] gate-opts))
          (str ":wb/gate " (pr-str bad) " is refused rather than opening the route")))
    (is (= ::constructed (build [["/priv" {:wb/gate :wb/subject :get hi}]] gate-opts))
        "a keyword is callable: a valid predicate looking the key up")
    (is (= ::constructed (build [["/pub" {:get hi}]] {:render-error render-error}))
        "ungated routes need no :login-path")
    (is (= ::constructed (build [["/priv" {:wb/gate gate/subject-present? :get hi}]] gate-opts))
        "control: the same route constructs with the key")))

(deftest a-throwing-predicate-propagates--the-gate-never-turns-a-throw-into-a-refusal
  (let [app (app-of (ring/router [["/boom" {:wb/gate (fn [_] (throw (RuntimeException. "PREDBOOM"))) :get hi}]]
                                 {:data {:middleware [(gate/middleware gate-opts)]}}))]
    (doseq [[label request] [["navigation" (get* "/boom")] ["partial" (get* "/boom" "HX-Request" "true")]]]
      (testing label
        (is (= "PREDBOOM" (try (app request) ::no-throw (catch RuntimeException e (ex-message e))))
            "the predicate's exception escapes untouched")))))

(deftest a-response-to-somebody-is-not-cached--unless-the-handler-says-how--and-an-anonymous-one-is-left-alone
  (let [wrap (fn [response subject] ((gate/wrap-subject (constantly response) (constantly subject)) {:uri "/"}))]
    (is (= {"Cache-Control" "no-store"} (:headers (wrap {:status 200 :body ""} "ana")))
        "a subject's page gets no-store")
    (is (= {"cache-control" "private, max-age=60"} (:headers (wrap {:status 200 :headers {"cache-control" "private, max-age=60"} :body ""} "ana")))
        "a handler that said how, in any case of the name, is not overruled and gets no second header")
    (is (= {"Cache-Control" "no-store"} (:headers (wrap {:status 303 :headers {} :body "" :session {:flash "saved"}} nil)))
        "an anonymous response that writes the session is somebody's too")
    (is (= {:status 200 :body ""} (wrap {:status 200 :body ""} nil))
        "control: an anonymous response that writes nothing is untouched, so a public page stays cacheable")
    (is (nil? (wrap nil "ana")) "a nil response stays nil")))

(deftest redirect-for-sends-a-navigation-with-a-303-and-a-swap-with-hx-redirect--both-uncached
  (is (= {:status 303 :headers (assoc REFUSAL "Location" "/orgs") :body ""} (gate/redirect-for (get* "/x") "/orgs"))
      "a navigation")
  (is (= {:status 200 :headers (assoc REFUSAL "HX-Redirect" "/orgs") :body ""} (gate/redirect-for (get* "/x" "HX-Request" "true") "/orgs"))
      "an htmx swap: HX-Redirect and no Location htmx would follow and swap in"))

(deftest a-login-path-with-a-query-gets-next-after-an-ampersand
  (let [app (app-of (router {:login-path "/entrar?lang=eu" :render-error (fn [d _] {:status (:status d)})}))]
    (is (= "/entrar?lang=eu&next=%2Fpriv" (get-in (app (get* "/priv")) [:headers "Location"])))))

(deftest next-is-carried-at-2048-encoded-characters-and-dropped-past-it--measured-encoded-not-raw--and-a-head-carries-it-like-a-get
  (let [app      (app-of (ring/router [["/priv" {:wb/gate gate/subject-present? :get hi :head hi}]]
                                      {:data {:middleware [(gate/middleware gate-opts)]}}))
        refused  (fn [method qs] (let [r (app (cond-> (mock/request method "/priv") qs (assoc :query-string qs)))]
                                   [(:status r) (select-keys (:headers r) (keys REFUSAL)) (get-in r [:headers "Location"])]))
        carried  (fn [encoded-qs] [303 REFUSAL (str login "?next=%2Fpriv%3F" encoded-qs)])
        dropped  [303 REFUSAL login]
        a        #(apply str (repeat % "a"))]
    (is (= 2048 (count (str "%2Fpriv%3F" (a 2038)))) "witness: 2038 letters encode to exactly 2048 with the page")
    (is (= (carried (a 2038)) (refused :get (a 2038))) "encoded 2048: carried")
    (is (= dropped (refused :get (a 2039))) "encoded 2049: the login page alone")
    (is (= 2047 (count (str "%2Fpriv%3F" (apply str (repeat 679 "%26")))))
        "witness: 679 ampersands encode to 2047, and one more to 2050")
    (is (= (carried (apply str (repeat 679 "%26"))) (refused :get (apply str (repeat 679 "&")))) "encoded 2047: carried")
    (is (< (count (str "/priv?" (apply str (repeat 680 "&")))) 2048) "witness: 680 ampersands are far under 2048 raw")
    (is (= dropped (refused :get (apply str (repeat 680 "&")))) "and 2050 encoded: dropped, the bound is on what is sent")
    (is (= [303 REFUSAL (str login "?next=%2Fpriv")] (refused :head nil)) "a HEAD carries next like a GET")
    (is (= dropped (refused :head (a 2039))) "and is bounded like one")))

(deftest compose-lists-its-parts-in-order-and-flattened--and-is-none-of-them
  (let [a (fn [_] true) b (fn [_] true) c (fn [_] false)
        ab (gate/compose a b) abc (gate/compose ab c)]
    (is (= [true true true] (mapv identical? [a b c] (:wb/gates (meta abc)))) "flattened, parent first")
    (is (= [true true true] (mapv identical? [c a b] (:wb/gates (meta (gate/compose c ab))))) "in the order composed")
    (is (not-any? #(identical? abc %) [a b c]) "a new function")
    (is (= [true false] [(boolean (ab {})) (boolean (abc {}))]) "admitting what both admit, and nothing either refuses")
    (is (false? ((gate/compose (fn [_] false) (fn [_] (throw (ex-info "child ran" {})))) {}))
        "a refusing parent means the child is never called")
    (is (nil? (:wb/gates (meta a))) "a lone predicate carries nothing")))
