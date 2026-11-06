(ns dev.arkaitz.web-base.guard-test
  "`:wb/guard` through the assembled handler (since 0.22.0): a predicate asked after the
  gate and after coercion, whose refusal is the 404 a stranger gets whoever they are; how
  guards compose; what `testing/route-checks` reads; and what is refused at construction.
  A guard's 404 is byte-identical to the 404 of a path no route matches, so every refusal
  here is asserted beside an admitted request on the same path and the handlers' ledger."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.gate :as gate]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as wt]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]
           [java.util UUID]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private LOGIN "/entrar-aqui")

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page [status body]
  (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
       "<title>" status "</title></head><body>" body "</body></html>"))

(def ^:private login-route [LOGIN {:get (fn [_] {:status 200 :body "login page"})}])

(defn- config [& {:as more}]
  (merge {:session    {:key KEY}
          :subject-fn #(get-in % [:session :user])
          :login-path LOGIN
          :csrf       false}
         more))

(defn- guarded-app
  "A handler over `(tree h spy)` beside a login page and a login per user, CSRF off so a
  POST reaches the router: `[app ran asked in-as]` — the handlers' ledger, the guards'
  ledger of `[tag request]`, and `(in-as user request)` carrying that user's session.
  `(h tag)` answers `tag` and the subject; `(spy tag pred)` records each call."
  [tree]
  (let [ran   (atom [])
        asked (atom [])
        h     (fn [tag] (fn [r] (swap! ran conj tag) {:status 200 :body (str tag " " (pr-str (:wb/subject r)))}))
        spy   (fn [tag pred] (fn [r] (swap! asked conj [tag r]) (pred r)))
        app   (wb/handler (config :routes (into [login-route
                                                 ["/as/:user" {:post (fn [r] (session/rotate {:status 200 :body "in"}
                                                                                             {:user (get-in r [:path-params :user])}))}]]
                                                (tree h spy))))
        sessions (memoize (fn [user] (app (mock/request :post (str "/as/" user)))))]
    [app ran asked (fn [user request] (wt/with-cookies request (sessions user)))]))

(defn- seen [response]
  [(:status response) (:body response) (get-in response [:headers "Location"]) (get-in response [:headers "HX-Redirect"])])

(deftest a-guard-that-says-no-is-the-404-page-for-a-stranger-signed-in-or-not--yes-runs-the-handler--and-the-gate-is-asked-first
  (let [[app ran asked in-as]
        (guarded-app (fn [h spy]
                       [["/mine/:who" {:wb/guard (spy :own (fn [r] (= (get-in r [:path-params :who]) (:wb/subject r))))
                                       :get      (h :mine)}]
                        ["" {:wb/gate wb/subject-present?}
                         ["/doc" {:wb/guard (spy :doc (fn [r] (= "ann" (:wb/subject r)))) :get (h :doc) :post (h :doc-post)}]]]))
        GET      #(mock/request :get %)
        htmx     #(mock/header % "HX-Request" "true")]
    (is (= {:status 200 :body ":mine \"ann\""} (dissoc (app (in-as "ann" (GET "/mine/ann"))) :headers))
        "witness: the route exists at this path and the guard admits its owner")
    (is (= [404 (page 404 (frag 404)) nil nil] (seen (app (in-as "bob" (GET "/mine/ann")))))
        "signed in and refused: the 404 page, never a 403")
    (is (= [404 (page 404 (frag 404)) nil nil] (seen (app (GET "/mine/ann"))))
        "anonymous and refused on an ungated route: the 404 page, never a sign-in")
    (is (= [404 (frag 404) nil nil] (seen (app (htmx (GET "/mine/ann"))))) "an htmx swap gets the 404 fragment")
    (is (= [404 (page 404 (frag 404)) nil nil] (seen (app (in-as "bob" (GET "/doc")))))
        "gated and guarded, signed in, refused by the guard: a 404, not the gate's 403")
    (is (= [404 (frag 404) nil nil] (seen (app (htmx (in-as "bob" (GET "/doc")))))))
    (is (= [404 "404" nil nil] (seen (app (mock/header (in-as "bob" (GET "/doc")) "Accept" "text/plain")))))
    (is (= [303 "" (str LOGIN "?next=%2Fdoc") nil] (seen (app (GET "/doc"))))
        "anonymous at a gated and guarded route: the gate's refusal — it is asked before the guard")
    (is (= {:status 200 :body ":doc \"ann\""} (dissoc (app (in-as "ann" (GET "/doc"))) :headers)))
    (is (= {:status 200 :body ":doc-post \"ann\""} (dissoc (app (in-as "ann" (mock/request :post "/doc"))) :headers)))
    (is (= [:mine :doc :doc-post] @ran) "no refused request ran its handler")
    (is (= [:own :own :own :own :doc :doc :doc :doc :doc] (mapv first @asked))
        "the guard was asked once per request that passed the gate, and never for the anonymous /doc")
    (is (= [{:request-method :get :uri "/doc"} "bob"]
           (let [[_ r] (nth @asked 4)] [(select-keys r [:request-method :uri]) (:wb/subject r)]))
        "the guard is asked with the request itself, its subject on it")))

(deftest a-guard-refuses-on-any-falsy-answer-and-admits-on-any-truthy-one--a-throw-is-a-500--and-its-404-is-the-unmatched-paths
  (let [[app ran asked in-as]
        (guarded-app (fn [h spy]
                       [["/lookup/:k" {:wb/guard (spy :lookup (fn [r] (get {"kw" :admitted "map" {:any 1}} (get-in r [:path-params :k]))))
                                       :get      (h :lookup)}]
                        ["/throws" {:wb/guard (spy :throws (fn [_] (throw (RuntimeException. "GUARDBOOM")))) :get (h :throws)}]]))
        GET        #(mock/request :get %)
        no-id      #(update % :headers dissoc "X-Request-Id")]
    (is (= [200 200] [(:status (app (GET "/lookup/kw"))) (:status (app (GET "/lookup/map")))])
        "a keyword and a map are truthy: admitted, not only true")
    (is (= [404 (page 404 (frag 404)) nil nil] (seen (app (GET "/lookup/none")))) "nil is falsy: refused, not only false")
    (is (= (no-id (app (in-as "ann" (GET "/nowhere")))) (no-id (app (in-as "ann" (GET "/lookup/none")))))
        "the guard's 404 is the 404 of a path no route matches, headers and all: nothing tells the two apart")
    (lt/with-log
      (let [r (app (GET "/throws"))]
        (is (= [500 (page 500 (frag 500))] ((juxt :status :body) r))
            "a guard that throws is a 500, never a refusal and never an admission")
        (is (= [[:error (str "unhandled exception {:request-id " (get-in r [:headers "X-Request-Id"]) ", :uri /throws}") "GUARDBOOM"]]
               (mapv (juxt :level :message (comp ex-message :throwable))
                     (filter #(= 'dev.arkaitz.web-base.error (ns-name (:logger-ns %))) (lt/the-log))))
            "and its throw is logged")))
    (is (= [:lookup :lookup :lookup :lookup :throws] (mapv first @asked)) "witness: every request to a guarded path reached its guard")
    (is (= [:lookup :lookup] @ran) "only the truthy answers ran the handler")))

(deftest a-guard-sees-the-parameters-coerced--it-runs-after-coercion--so-a-malformed-id-is-coercions-400
  (let [owned  (UUID/randomUUID)
        other  (UUID/randomUUID)
        saw    (atom ::never)
        cfg    (config :coercion malli-coercion/coercion
                       :routes [login-route
                                ["/courses/:id" {:parameters {:path [:map [:id :uuid]]}
                                                 :wb/guard   (fn [r] (let [id (get-in r [:parameters :path :id])]
                                                                       (reset! saw id)
                                                                       (= owned id)))
                                                 :get        (fn [_] {:status 200 :body "course"})}]])
        app    (wb/handler cfg)
        chain  (mapv :name (get-in (r/match-by-path (wb/router cfg) (str "/courses/" owned)) [:result :get :data :middleware]))]
    (is (= [:dev.arkaitz.web-base.error/exception :dev.arkaitz.web-base.gate/gate :dev.arkaitz.web-base.render/render
            :reitit.ring.coercion/coerce-request :dev.arkaitz.web-base.gate/guard]
           chain)
        "the route's middleware as declared, outermost first: the guard is last, after coercion")
    (is (= [200 "course"] ((juxt :status :body) (app (mock/request :get (str "/courses/" owned))))) "witness: the owner's course answers")
    (is (= [owned true] [@saw (instance? UUID @saw)]) "the guard saw the coerced UUID, not the path's string")
    (is (= [404 (page 404 (frag 404)) other] [(:status (app (mock/request :get (str "/courses/" other))))
                                             (:body (app (mock/request :get (str "/courses/" other)))) @saw])
        "another course: asked with its id, and refused with the 404 page")
    (reset! saw ::never)
    (is (= [400 ::never] [(:status (app (mock/request :get "/courses/not-a-uuid"))) @saw])
        "a malformed id is coercion's 400, and the guard is never asked")))

(deftest guards-compose-parent-then-route-then-method--a-refusal-stops-the-chain--a-child-never-widens--nil-keeps-the-parent
  (let [[app ran asked in-as]
        (guarded-app (fn [h spy]
                       [["" {:wb/guard (spy :top (fn [r] (not= "bob" (:wb/subject r))))}
                         ["/m" {:wb/guard (spy :mid (fn [r] (not= "cid" (:wb/subject r))))}
                          ["/leaf" {:wb/guard (spy :leaf (constantly true)) :get (h :leaf)}]]
                         ["/doc" {:wb/guard (spy :route (fn [r] (not= "cid" (:wb/subject r))))
                                  :get      {:wb/guard (spy :by-get (constantly true)) :handler (h :read)}
                                  :post     {:wb/guard (spy :by-post (fn [r] (= "ann" (:wb/subject r)))) :handler (h :write)}}]
                         ["/nil" {:wb/guard nil :get (h :nil)}]]]))
        ask (fn [user method path]
              (reset! asked [])
              [(:status (app (in-as user (mock/request method path)))) (mapv first @asked)])]
    (is (= [404 [:top]] (ask "bob" :get "/m/leaf")) "refused at the root: nobody below is asked")
    (is (= [404 [:top :mid]] (ask "cid" :get "/m/leaf")) "refused by the child, the parent asked first")
    (is (= [200 [:top :mid :leaf]] (ask "ann" :get "/m/leaf")) "admitted by all three, in order")
    (is (= [404 [:top :route]] (ask "cid" :get "/doc")) "a method guard that admits all cannot widen its route's")
    (is (= [200 [:top :route :by-get]] (ask "ann" :get "/doc")))
    (is (= [404 [:top]] (ask "bob" :post "/doc")))
    (is (= [404 [:top :route :by-post]] (ask "dan" :post "/doc")) "the method's own guard narrows the route's")
    (is (= [200 [:top :route :by-post]] (ask "ann" :post "/doc")))
    (is (= [404 [:top]] (ask "bob" :get "/nil")) "a child's nil keeps the parent's guard")
    (is (= [200 [:top]] (ask "ann" :get "/nil")))
    (is (= [:leaf :read :write :nil] @ran) "only admitted handlers ran"))
  (let [[top mid leaf by-get c p] (repeatedly 6 #(fn [_] true))
        router   (wt/router [["" {:wb/guard top} ["/m" {:wb/guard mid} ["/leaf" {:wb/guard leaf :get {:wb/guard by-get :handler identity}}]]]
                             ["/lone" {:wb/guard c :get identity}]
                             ["" {:wb/guard p} ["/nil" {:wb/guard nil :get identity}]]])
        match    #(r/match-by-path router %)
        composed (get-in (match "/m/leaf") [:result :get :data :wb/guard])]
    (is (= [top mid leaf by-get] (:wb/guards (meta composed))) "the composition lists its parts, flattened, outermost first")
    (is (nil? (:wb/gates (meta composed))) "under the guard's own key, never the gate's")
    (is (not-any? #(identical? composed %) [top mid leaf by-get]) "and is none of them")
    (is (= [top mid leaf] (:wb/guards (meta (get-in (match "/m/leaf") [:data :wb/guard])))) "a method's guard does not rise to its route")
    (is (= [[top mid leaf by-get] [] [c]] [(gate/parts :wb/guard composed) (gate/parts :wb/guard nil) (gate/parts :wb/guard c)]))
    (is (= [true true true] [(identical? c (get-in (match "/lone") [:data :wb/guard])) (nil? (:wb/guards (meta c)))
                             (identical? p (get-in (match "/nil") [:data :wb/guard]))])
        "a lone guard is the host's own function, and a nil child keeps the parent's")))

(deftest route-checks-lists-every-declared-endpoint-with-its-gates-and-guards--a-guard-dropped-from-one-method-shows-there-alone
  (let [[top pg rg mg lone] (repeatedly 5 #(fn [_] true))
        tree     (fn [get-data] [[LOGIN {:get identity}]
                                 ["" {:wb/gate top :wb/guard pg}
                                  ["/doc" {:wb/guard rg :get get-data :post identity}]
                                  ["/any" {:handler identity}]
                                  ["/plain" {:get identity}]]
                                 ["/lone" {:wb/guard lone :get identity}]])
        full     (tree {:wb/guard mg :handler identity})
        dropped  (tree {:handler identity})
        router   (wt/router full)
        checks   (wt/route-checks router)
        any-rows (into {} (for [m [:connect :delete :get :head :options :patch :post :put :trace]]
                            [["/any" m] {:gates [top] :guards [pg]}]))]
    (is (= [pg rg mg] (:wb/guards (meta (get-in (r/match-by-path router "/doc") [:result :get :data :wb/guard]))))
        "witness: the endpoint itself composes the method's guard after the route's")
    (is (some? (get-in (r/match-by-path router "/plain") [:result :options])) "witness: reitit did add its own OPTIONS to /plain")
    (is (= (merge {[LOGIN :get]     {:gates [] :guards []}
                   ["/doc" :get]    {:gates [top] :guards [pg rg mg]}
                   ["/doc" :post]   {:gates [top] :guards [pg rg]}
                   ["/plain" :get]  {:gates [top] :guards [pg]}
                   ["/lone" :get]   {:gates [] :guards [lone]}}
                  any-rows)
           checks)
        "every declared endpoint, every method of a route-level handler, and not reitit's own OPTIONS")
    (is (instance? clojure.lang.PersistentTreeMap checks) "sorted, so two runs print alike")
    (is (= checks (wt/route-checks (wb/router {:routes full :login-path LOGIN}))) "wb/router reads the same")
    (is (= [[["/doc" :get] {:gates [top] :guards [pg rg]}]]
           (filter (fn [[k v]] (not= v (get checks k))) (wt/route-checks (wt/router dropped))))
        "a guard dropped from one method changes that endpoint's row and no other")
    (is (= {:gates [top] :guards [mg]} (get (wt/route-checks (ring/router full)) ["/doc" :get]))
        "control: reitit's own router replaces — the reason route-checks wants the base's")))

(deftest a-guard-that-is-not-callable--under-a-method-of-a-route-with-children--or-replaced-away-is-refused-when-the-handler-is-built
  (let [g       (fn [_] true)
        ok      (fn [_] {:status 200 :body "ok"})
        attempt (fn [tree] (try (wb/handler (config :routes (into [login-route] tree))) ::built
                                (catch ExceptionInfo e [(ex-message e) (select-keys (ex-data e) [:config-key :path :value])])))
        refused (fn [path] [(str "web-base: route " path " declares :wb/guard under a method and has routes under it,"
                                 " which could drop it — put the guard on the route's own data")
                            {:config-key [:routes] :path path}])]
    (doseq [bad [false "yes" 42]
            [shape tree] {"a leaf"                 [["/x" {:wb/guard bad :get ok}]]
                          "under a callable parent" [["" {:wb/guard g} ["/x" {:wb/guard bad :get ok}]]]
                          "over a callable child"   [["" {:wb/guard bad} ["/x" {:wb/guard g :get ok}]]]}]
      (is (= ["web-base: a route declares :wb/guard that is not callable" {:config-key [:wb/guard] :value bad}] (attempt tree))
          (str (pr-str bad) ", " shape ": refused")))
    (is (= ::built (attempt [["/x" {:wb/guard :wb/subject :get ok}]])) "control: a keyword is callable")
    (doseq [[shape tree] {"a child's method guard"     [["/a" {:get {:wb/guard g}} ["/x" {:get {:handler ok :wb/guard g}}]]]
                          "a child's route guard"      [["/a" {:get {:wb/guard g}} ["/x" {:wb/guard g :get ok}]]]
                          "a child declaring nothing"  [["/a" {:get {:wb/guard g}} ["/x" {:get ok}]]]
                          "a child with a map handler" [["/a" {:get {:wb/guard g}} ["/x" {:get {:handler ok}}]]]
                          "deeper, under a group"      [["" {:wb/guard g} ["/a" {:post {:wb/guard g}} ["/x" {:get ok}]]]]
                          "children as a list"         [["/a" {:get {:wb/guard g :handler ok}} (list ["/x" {:get ok}])]]
                          "children made by for"       [["/a" {:get {:wb/guard g}} (for [p ["/x"]] [p {:get ok}])]]}]
      (is (= (refused "/a") (attempt tree)) (str shape ": refused, naming the route")))
    (is (= [(str "web-base: route /a declares :wb/gate under a method and has routes under it,"
                 " which could widen it — put the gate on the route's own data")
            {:config-key [:routes] :path "/a"}]
           (attempt [["/a" {:get {:wb/gate g :handler ok} :post {:wb/guard g}} ["/x" {:get ok}]]]))
        "both under methods: the gate is named first")
    (is (= ["web-base: route /x declares :wb/guard but its middleware no longer has the guard — a :middleware ^:replace drops the base's own"
            {:config-key [:routes] :path "/x"}]
           (attempt [["" {:wb/guard g} ["/x" {:middleware ^:replace [] :get ok}]]])))
    (is (= ["web-base: route /x declares :wb/guard but its middleware no longer has the guard — a :middleware ^:replace drops the base's own"
            {:config-key [:routes] :path "/x"}]
           (attempt [["" {:wb/guard g} ["/x" {:middleware ^:replace [(gate/middleware {:login-path LOGIN})] :get ok}]]]))
        "a replacement that keeps the gate's middleware and drops the guard's is refused all the same")
    (is (= ["web-base: route /x declares :wb/guard but its middleware no longer has the guard — a :middleware ^:replace drops the base's own"
            {:config-key [:routes] :path "/x"}]
           (attempt [["" {:wb/guard g} ["/x" {:get ok :post {:middleware ^:replace [] :handler ok}}]]]))
        "a replacement under one method other than GET is refused too")
    (is (= ::built (attempt [["" {:wb/guard g} ["/x" {:middleware ^:replace [gate/guard-middleware] :get ok}]]]))
        "control: a replacement that keeps the guard's middleware builds")
    (is (= ::built (attempt [["/a" {:get {:wb/guard g :handler ok}}]])) "control: a method guard on a leaf builds")
    (is (= ::built (attempt [["/a" {:wb/guard g} ["/x" {:get ok}]]])) "control: the same guard on the route's own data builds")
    (is (= ::built (attempt [["" {:wb/guard g} ["/x" {:middleware [(fn [h] h)] :get ok}]]]))
        "control: a route's own middleware, added, keeps the guard")
    (testing "the middleware is named, and vanishes where no guard is"
      (is (= [:dev.arkaitz.web-base.gate/guard nil nil true]
             [(:name gate/guard-middleware) ((:compile gate/guard-middleware) {} nil)
              ((:compile gate/guard-middleware) {:wb/guard nil} nil)
              (fn? ((:compile gate/guard-middleware) {:wb/guard g} nil))])))))
