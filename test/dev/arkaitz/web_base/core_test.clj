(ns dev.arkaitz.web-base.core-test
  "The assembled handler, end to end: the middleware order is the product and
  these are its observable consequences. Literals are the same shapes the
  unit tests pin, copied, never required from those namespaces."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.gate :as gate]
            [dev.arkaitz.web-base.testing :as testing]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page
  ([status body] (page status body nil))
  ([status body lang]
   (str "<!DOCTYPE html>\n<html" (when lang (str " lang=\"" lang "\"")) ">"
        "<head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
        "<title>" status "</title></head><body>" body "</body></html>")))

(def ^:private SEC {"X-Content-Type-Options" "nosniff"
                    "X-Frame-Options"        "DENY"
                    "Referrer-Policy"        "strict-origin-when-cross-origin"})
(def ^:private ERR-HTML {"Vary" "HX-Request, HX-Request-Type, Accept" "Cache-Control" "no-store" "Content-Type" "text/html; charset=utf-8"})
(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private LOGIN "/entrar-aqui")
(def ^:private id-pattern #"[A-Za-z0-9_-]{16}")

(def ^:private seen (atom nil))

(def ^:private routes
  [["/me"     {:get (fn [r] {:status 200 :body (pr-str (:session r))})}]
   ;; The page a gated refusal leads to: since 0.13.0 the base refuses a :login-path no
   ;; route answers GET at.
   [LOGIN     {:get (fn [_] {:status 200 :body "login page"})}]
   ["/login"  {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user "ann"}))}]
   ["/priv"   {:wb/gate wb/subject-present? :get (fn [r] {:status 200 :body (str "hi " (pr-str (:wb/subject r)))})}]
   ["/token"  {:get (fn [r] {:status 200 :body (security/csrf-token r)})}]
   ["/post"   {:post (fn [_] {:status 200 :body "posted"})}]
   ["/see"    {:get (fn [r] (reset! seen r)
                      {:status 200 :body (str (:wb/locale r) "|" (when-let [tr (:wb/tr r)] (tr [:hi])))})}]
   ["/boom"   {:wb/gate (fn [_] (throw (RuntimeException. "PREDBOOM"))) :get (fn [_] {:status 200 :body "never"})}]
   ["/scheme" {:get (fn [r] {:status 200 :body (str (:scheme r) "|" (:remote-addr r))})}]])

(defn- config [& {:as more}]
  (merge {:routes     routes
          :session    {:key KEY}
          :subject-fn #(get-in % [:session :user])
          :login-path LOGIN
          :static     {:root "public"}}
         more))

(defn- id-of [response] (get-in response [:headers "X-Request-Id"]))

(defn- error-headers [response]
  (merge SEC ERR-HTML {"X-Request-Id" (id-of response)}))

(deftest handler-requires-routes-and-session-naming-the-missing-key
  (let [attempt (fn [cfg] (try (wb/handler cfg) ::built
                               (catch ExceptionInfo e [(ex-message e) (dissoc (ex-data e) :reitit.exception/cause)])))]
    (is (= ["web-base: config needs :routes" {:config-key [:routes]}] (attempt {:session {:key KEY}})))
    (is (= ["web-base: config needs :routes" {:config-key [:routes]}] (attempt {:routes nil :session {:key KEY}})) "nil counts as missing")
    (is (= ["web-base: config needs :session" {:config-key [:session]}] (attempt {:routes []})))
    (is (fn? (wb/handler {:routes [] :session {:key KEY} :whatever-unknown 1})) "unknown keys are the host's business")
    (is (= ["web-base: a route declares :wb/gate but no :login-path is configured" {:config-key [:login-path]}]
           (attempt {:routes [["/p" {:wb/gate wb/subject-present? :get identity}]] :session {:key KEY}}))
        "a collaborator's construction-time check is reached")))

(def ^:private every-owned-key
  "Every key the base reads inside each map it owns, each with a value it accepts."
  {:session  {:key KEY :cookie-attrs {:secure false} :cookie-name "sid" :renew {:every-ms 1 :absolute-ms 2}}
   :security {:frame-options "SAMEORIGIN" :csp "default-src 'self'"
              :hsts {:max-age 1 :include-subdomains? true} :proxy-hops 1}
   :i18n     {:dict {:en {}} :default-locale :en :locale-fn (constantly nil)}
   :static   {:root "public" :path "/" :parameter :path :loader (clojure.lang.RT/baseLoader)
              :index-files ["index.html"] :index-redirect? false :canonicalize-uris? true
              :not-found-handler (constantly nil) :mime-types {} :allow-symlinks? false
              :paths (constantly nil)}})

(deftest an-unknown-key-inside-a-map-the-base-owns-is-refused-naming-its-path
  (let [attempt (fn [cfg] (try (wb/handler cfg) ::built
                               (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        base    (merge {:routes []} every-owned-key)]
    (is (= ::built (attempt base)) "control: every key the base reads is accepted, in every map it owns")
    (is (= ::built (attempt (assoc base :whatever-unknown 1))) "the top level stays the host's")
    (doseq [[path allowed] [[[:session :kye] "[:cookie-attrs :cookie-name :key :renew :store]"]
                            [[:session :renew :evry-ms] "[:absolute-ms :every-ms]"]
                            [[:security :hts] "[:csp :frame-options :hsts :proxy-hops]"]
                            [[:security :hsts :max-aeg] "[:include-subdomains? :max-age]"]
                            [[:i18n :default-lcoale] "[:default-locale :dict :locale-fn :locales]"]
                            [[:static :roto] (str "[:allow-symlinks? :canonicalize-uris? :index-files :index-redirect?"
                                                  " :loader :mime-types :not-found-handler :parameter :path :paths :root]")]]]
      (is (= [(str "web-base: unknown key [" (pr-str (peek path)) "] in " (pr-str (pop path)) " — it takes " allowed)
              {:config-key path}]
             (attempt (assoc-in base path 1)))
          (str "refused: " (pr-str path))))
    (is (= ["web-base: unknown keys [:a :b] in [:security] — it takes [:csp :frame-options :hsts :proxy-hops]"
            {:config-key [:security :a]}]
           (attempt (update base :security assoc :b 1 :a 2)))
        "several are all named, and the first sorted is the one in the data")))

(deftest assembled-login-sets-session-and-gate-admits-the-cookie-refuses-without-it
  (let [app   (wb/handler (config :csrf false))
        login (app (mock/request :post "/login"))]
    (is (= {:status 200 :body "in"} (dissoc login :headers)))
    (is (seq (testing/cookies login)) "login issued a session cookie")
    (let [r (app (testing/with-cookies (mock/request :get "/priv") login))]
      (is (= {:status 200 :body "hi \"ann\""} (dissoc r :headers)) "gated GET with the cookie: the subject came from the session")
      (is (re-matches id-pattern (str (id-of r)))))
    (let [r (app (mock/request :get "/priv"))]
      (is (= [303 (str LOGIN "?next=%2Fpriv") "no-store" "HX-Request, HX-Request-Type" ""]
             [(:status r) (get-in r [:headers "Location"]) (get-in r [:headers "Cache-Control"]) (get-in r [:headers "Vary"]) (:body r)])
          "navigation refusal, carrying the page as next"))
    (let [r (app (mock/header (mock/request :get "/priv") "HX-Request" "true"))]
      (is (= [200 LOGIN nil ""] [(:status r) (get-in r [:headers "HX-Redirect"]) (get-in r [:headers "Location"]) (:body r)])
          "partial refusal: HX-Redirect and no Location")))
  (is (= 303 (:status ((wb/handler (config :csrf false :subject-fn nil)) (mock/request :get "/priv"))))
      ":subject-fn nil — an absent setting — means no subject, not a crash"))

(deftest a-gate-on-a-parent-route-refuses-every-child-and-none-of-its-siblings-outside
  ;; The children carry no :wb/gate of their own: that is the condition under test. CSRF
  ;; is off because a tokenless POST would be CSRF's 403 and never reach the gate.
  (let [ran     (atom [])
        h       (fn [tag] (fn [r] (swap! ran conj [tag (:path-params r)])
                            {:status 200 :body (str tag " " (pr-str (:wb/subject r)))}))
        app     (wb/handler (config :csrf false
                                    :routes [["/login" {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user "ann"}))}]
                                             [LOGIN {:get (h :login)}]
                                             ["/pub" {:get (h :pub)}]
                                             ["" {:wb/gate wb/subject-present?}
                                              ["/a" {:get (h :a)}]
                                              ["/b/:id" {:post (h :b)}]
                                              ["/deep" ["/c" {:get (h :c)}]]
                                              ["/own" {:wb/gate (fn [_] false) :get (h :own)}]
                                              ["/open" {:wb/gate (constantly true) :get (h :open)}]
                                              ["/nil" {:wb/gate nil :get (h :nil)}]]]))
        login   (app (mock/request :post "/login"))
        in      #(testing/with-cookies % login)
        refusal (fn [r] [(:status r) (get-in r [:headers "Location"]) (get-in r [:headers "Cache-Control"])
                         (get-in r [:headers "Vary"]) (:body r)])
        denied  [303 LOGIN "no-store" "HX-Request, HX-Request-Type" ""]
        back    (fn [path] [303 (str LOGIN "?next=" (java.net.URLEncoder/encode ^String path "UTF-8")) "no-store" "HX-Request, HX-Request-Type" ""])]
    (is (seq (testing/cookies login)) "precondition: the login issued a session cookie")
    (is (= (back "/a") (refusal (app (mock/request :get "/a")))) "anonymous GET of a child: the parent's gate sends it to the login")
    (let [r (app (mock/header (mock/request :get "/a") "HX-Request" "true"))]
      (is (= [200 LOGIN nil "no-store" "HX-Request, HX-Request-Type" ""]
             [(:status r) (get-in r [:headers "HX-Redirect"]) (get-in r [:headers "Location"])
              (get-in r [:headers "Cache-Control"]) (get-in r [:headers "Vary"]) (:body r)])
          "anonymous htmx swap of a child: HX-Redirect, no Location, uncached"))
    (is (= denied (refusal (app (mock/request :post "/b/7")))) "anonymous POST to a child with a path param: refused")
    (is (= (back "/deep/c") (refusal (app (mock/request :get "/deep/c")))) "a grandchild two levels down: refused")
    (is (= (back "/own") (refusal (app (mock/request :get "/own")))) "a child with a gate of its own refuses the anonymous too")
    ;; Composition rather than replacement is only visible through a child gate that
    ;; admits what the parent's refuses: /open (since 0.16.0).
    (is (= (back "/open") (refusal (app (mock/request :get "/open"))))
        "a child whose own gate admits everyone still refuses the anonymous: it composes with the parent's, never replaces it")
    (is (= (back "/nil") (refusal (app (mock/request :get "/nil"))))
        "a child's nil gate does not open it: nil leaves the parent's gate in place")
    (is (= {:status 200 :body ":pub nil"} (dissoc (app (mock/request :get "/pub")) :headers))
        "a sibling outside the group admits the anonymous visitor")
    (is (= [[:pub {}]] @ran) "no gated child's handler ran for an anonymous request — /open included")
    (is (= {:status 200 :body ":a \"ann\""} (dissoc (app (in (mock/request :get "/a"))) :headers))
        "signed in, the child's handler ran with the subject")
    (let [r (app (in (mock/header (mock/request :get "/a") "HX-Request" "true")))]
      (is (= [200 ":a \"ann\"" nil] [(:status r) (:body r) (get-in r [:headers "HX-Redirect"])])
          "signed in, an htmx swap of the child passes: no HX-Redirect"))
    (is (= {:status 200 :body ":b \"ann\""} (dissoc (app (in (mock/request :post "/b/7"))) :headers)) "signed-in POST reaches the child")
    (is (= {:status 200 :body ":c \"ann\""} (dissoc (app (in (mock/request :get "/deep/c"))) :headers)) "and the grandchild")
    (is (= {:status 200 :body ":open \"ann\""} (dissoc (app (in (mock/request :get "/open"))) :headers))
        "signed in, both gates admit: /open's handler ran with the subject")
    (let [r (app (in (mock/request :get "/own")))]
      (is (= [403 (page 403 (frag 403))] [(:status r) (:body r)])
          "signed in and still refused by the child's own gate: a 403, since there is a subject — the child narrows the parent"))
    (is (= [[:pub {}] [:a {}] [:a {}] [:b {:id "7"}] [:c {}] [:open {}]] @ran)
        "the children ran only for the subject, /open once and only signed in")))

;; --- gates compose (since 0.16.0) -------------------------------------------

(defn- gated-app
  "A handler over `tree` beside a login page and two logins, ann's and bob's; CSRF off so
  a POST reaches the gate. Answers `[app ran in-as]`: the handlers' ledger, and
  `(in-as user request)` carrying that user's session."
  [tree]
  (let [ran (atom [])
        app (wb/handler (config :csrf false
                                :routes (into [[LOGIN {:get (fn [_] {:status 200 :body "login page"})}]
                                               ["/as/:user" {:post (fn [r] (session/rotate {:status 200 :body "in"}
                                                                                           {:user (get-in r [:path-params :user])}))}]]
                                              (tree (fn [tag] (fn [r] (swap! ran conj tag)
                                                                {:status 200 :body (str tag " " (pr-str (:wb/subject r)))}))))))
        sessions (memoize (fn [user] (app (mock/request :post (str "/as/" user)))))]
    [app ran (fn [user request] (testing/with-cookies request (sessions user)))]))

(defn- back [path] [303 (str LOGIN "?next=" (java.net.URLEncoder/encode ^String path "UTF-8"))])
(defn- outcome [response] [(:status response) (get-in response [:headers "Location"])])
(def ^:private forbidden [403 nil])

(deftest a-child-gate-narrows-its-parent--refused-by-the-child-is-a-403--admitted-by-both-a-200
  (let [[app ran in-as] (gated-app (fn [h] [["" {:wb/gate wb/subject-present?}
                                             ["/admins" {:wb/gate (fn [r] (= "root" (:wb/subject r))) :get (h :admins)}]
                                             ["/anyone" {:wb/gate (constantly true) :get (h :anyone)}]]]))]
    (is (= (back "/admins") (outcome (app (mock/request :get "/admins")))) "anonymous: the parent refuses first")
    (is (= (back "/anyone") (outcome (app (mock/request :get "/anyone")))) "anonymous, under a child that admits all: still the parent's refusal")
    (is (= forbidden (outcome (app (in-as "ann" (mock/request :get "/admins"))))) "signed in, the child refuses: a 403, there being a subject")
    (is (= {:status 200 :body ":anyone \"ann\""} (dissoc (app (in-as "ann" (mock/request :get "/anyone"))) :headers))
        "signed in and admitted by both")
    (is (= {:status 200 :body ":admins \"root\""} (dissoc (app (in-as "root" (mock/request :get "/admins"))) :headers))
        "the one the child admits gets in")
    (is (= [:anyone :admins] @ran) "only admitted handlers ran")))

(deftest a-method-level-gate-composes-with-the-routes-and-the-parents
  (let [[app ran in-as] (gated-app (fn [h] [["" {:wb/gate wb/subject-present?}
                                             ["/doc" {:wb/gate (fn [r] (not= "bob" (:wb/subject r)))
                                                      :get  {:wb/gate (constantly true) :handler (h :read)}
                                                      :post {:wb/gate (fn [_] false) :handler (h :write)}}]]]))]
    (is (= (back "/doc") (outcome (app (mock/request :get "/doc"))))
        "anonymous GET under a method gate that admits all: the parent's refusal")
    (is (= forbidden (outcome (app (in-as "bob" (mock/request :get "/doc"))))) "bob is refused by the route's gate, under the method's")
    (is (= {:status 200 :body ":read \"ann\""} (dissoc (app (in-as "ann" (mock/request :get "/doc"))) :headers)) "ann reads")
    (is (= forbidden (outcome (app (in-as "ann" (mock/request :post "/doc"))))) "and the POST's own gate narrows what the GET's does not")
    (is (= [:read] @ran) "only the admitted read ran"))
  (let [top   (fn [_] true) route (fn [_] true) by-get (fn [_] true)
        router (testing/router [["" {:wb/gate top} ["/doc" {:wb/gate route :get {:wb/gate by-get :handler identity}}]]])
        match  (r/match-by-path router "/doc")]
    (is (= [true true true] (mapv identical? [top route by-get] (:wb/gates (meta (get-in match [:result :get :data :wb/gate])))))
        "the GET endpoint's gate is parent, route and method, in that order")
    (is (= [true true] (mapv identical? [top route] (:wb/gates (meta (get-in match [:data :wb/gate])))))
        "the route's own data, parent and route: a method's gate does not rise to the route")))

(deftest a-chain-asks-the-parent-first--a-refusal-stops-it
  (let [asked (atom [])
        spy   (fn [tag pred] (fn [r] (swap! asked conj tag) (pred r)))
        top   (spy :top wb/subject-present?)
        mid   (spy :mid (fn [r] (not= "bob" (:wb/subject r))))
        leaf  (spy :leaf (constantly true))
        [app _ in-as] (gated-app (fn [h] [["" {:wb/gate top}
                                           ["/m" {:wb/gate mid} ["/leaf" {:wb/gate leaf :get (h :leaf)}]]
                                           ;; Relies on its parent: under a child asked first it would throw.
                                           ["/strict" {:wb/gate (fn [r] (.startsWith ^String (:wb/subject r) "a")) :get (h :strict)}]]]))
        ask   (fn [request] (reset! asked []) [(outcome (app request)) @asked])]
    (is (= [(back "/m/leaf") [:top]] (ask (mock/request :get "/m/leaf"))) "anonymous: the parent alone is asked")
    (is (= [forbidden [:top :mid]] (ask (in-as "bob" (mock/request :get "/m/leaf")))) "bob: refused in the middle, the leaf never asked")
    (is (= [[200 nil] [:top :mid :leaf]] (ask (in-as "ann" (mock/request :get "/m/leaf")))) "ann: every gate, root to leaf")
    (is (= (back "/strict") (outcome (app (mock/request :get "/strict"))))
        "a child may rely on its parent's precondition: the anonymous never reaches it"))
  (let [top (fn [_] true) mid (fn [_] true) leaf (fn [_] true)
        router (testing/router [["" {:wb/gate top} ["/m" {:wb/gate mid} ["/leaf" {:wb/gate leaf :get identity}]]]])
        composed (get-in (r/match-by-path router "/m/leaf") [:data :wb/gate])]
    (is (= [true true true] (mapv identical? [top mid leaf] (:wb/gates (meta composed))))
        "the composed gate lists its parts flattened, root first")
    (is (not-any? #(identical? composed %) [top mid leaf]) "and is none of them")))

(deftest a-gate-that-is-not-callable-is-refused-at-construction--under-a-gate-or-over-one
  (let [attempt (fn [tree] (try (wb/handler (config :routes (into [[LOGIN {:get (fn [_] {:status 200 :body ""})}]] tree)))
                                ::built
                                ;; reitit adds its own cause to the data; the base's keys are these.
                                (catch ExceptionInfo e [(ex-message e) (select-keys (ex-data e) [:config-key :value])])))
        refused (fn [bad] ["web-base: a route declares :wb/gate that is not callable" {:config-key [:wb/gate] :value bad}])]
    (doseq [bad [false "yes" 42]]
      (is (= (refused bad) (attempt [["" {:wb/gate wb/subject-present?} ["/x" {:wb/gate bad :get identity}]]]))
          (str (pr-str bad) " under a callable parent: refused, never composed into a function"))
      (is (= (refused bad) (attempt [["" {:wb/gate bad} ["/x" {:wb/gate wb/subject-present? :get identity}]]]))
          (str (pr-str bad) " over a callable child: refused, never quietly replaced by the child's")))
    (is (= ::built (attempt [["" {:wb/gate wb/subject-present?} ["/x" {:wb/gate (constantly true) :get identity}]]]))
        "control: two callable gates build")))

;; Found by the Phase-3 panel on 0.16.0: reitit merges a parent's method data into its
;; children's endpoints, where a child's method data or plain handler replaces it.
(deftest a-gate-under-a-method-of-a-route-with-children-is-refused--it-could-be-widened
  (let [admin?  (fn [r] (= "root" (:wb/subject r)))
        ok      (fn [_] {:status 200 :body "ok"})
        attempt (fn [tree] (try (wb/handler (config :routes (into [[LOGIN {:get (fn [_] {:status 200 :body ""})}]] tree)))
                                ::built
                                (catch ExceptionInfo e [(ex-message e) (select-keys (ex-data e) [:config-key :path])])))
        refused (fn [path] [(str "web-base: route " path " declares :wb/gate under a method and has routes under it,"
                                 " which could widen it — put the gate on the route's own data")
                            {:config-key [:routes] :path path}])]
    (doseq [[shape tree] {"a child's method gate"        [["/a" {:get {:wb/gate admin?}} ["/x" {:get {:handler ok :wb/gate wb/subject-present?}}]]]
                          "a child's route gate"         [["/a" {:get {:wb/gate admin?}} ["/x" {:wb/gate wb/subject-present? :get ok}]]]
                          "a child declaring nothing"    [["/a" {:get {:wb/gate admin?}} ["/x" {:get ok}]]]
                          "a child with a map handler"   [["/a" {:get {:wb/gate admin?}} ["/x" {:get {:handler ok}}]]]
                          "deeper, under a group"        [["" {:wb/gate wb/subject-present?} ["/a" {:post {:wb/gate admin?}} ["/x" {:get ok}]]]]
                          "children as a list"           [["/a" {:get {:wb/gate admin? :handler ok}} (list ["/x" {:get ok}])]]
                          "children made by for"         [["/a" {:get {:wb/gate admin?}} (for [p ["/x"]] [p {:get ok}])]]}]
      (is (= (refused "/a") (attempt tree)) (str shape ": refused, naming the route")))
    (is (= ::built (attempt [["/a" {:get {:wb/gate admin? :handler ok}}]])) "control: a method gate on a leaf builds")
    (is (= ::built (attempt [["/a" {:wb/gate admin?} ["/x" {:get ok}]]])) "control: the same gate on the route's own data builds")))

(deftest a-gate-whose-middleware-was-replaced-away-is-refused
  (let [ok      (fn [_] {:status 200 :body "ok"})
        attempt (fn [tree] (try (wb/handler (config :routes (into [[LOGIN {:get (fn [_] {:status 200 :body ""})}]] tree)))
                                ::built
                                (catch ExceptionInfo e [(ex-message e) (select-keys (ex-data e) [:config-key :path])])))]
    (is (= ["web-base: route /x declares :wb/gate but its middleware no longer has the gate — a :middleware ^:replace drops the base's own"
            {:config-key [:routes] :path "/x"}]
           (attempt [["" {:wb/gate wb/subject-present?} ["/x" {:middleware ^:replace [] :get ok}]]]))
        "a ^:replace under a gated group: refused, never served ungated")
    (is (= ::built (attempt [["" {:wb/gate wb/subject-present?} ["/x" {:middleware [(fn [h] h)] :get ok}]]]))
        "control: middleware of the route's own, added, builds")))

(deftest testing-router-compiles-as-the-handler-does
  (let [p (fn [_] true) c (fn [_] true)
        gate-of (fn [router] (get-in (r/match-by-path router "/x") [:data :wb/gate]))
        tree [["" {:wb/gate p} ["/x" {:wb/gate c :get identity}]]]]
    (is (= [true true] (mapv identical? [p c] (:wb/gates (meta (gate-of (testing/router tree))))))
        "testing/router composes as the base does")
    (is (identical? c (gate-of (ring/router tree))) "control: reitit's own router replaces — a test reading it reads a semantics the handler no longer has")))

(deftest a-nil-is-no-gate--and-a-lone-gate-is-the-hosts-own-function
  (let [p (fn [_] true) c (fn [_] true)
        router (testing/router [["" {:wb/gate p} ["/nil" {:wb/gate nil :get identity}]]
                             ["/lone" {:wb/gate c :get identity}]
                             ["" {} ["/under-ungated" {:wb/gate c :get identity}]]])
        gate-at #(get-in (r/match-by-path router %) [:data :wb/gate])]
    (is (identical? p (gate-at "/nil")) "a child's nil keeps the parent's own predicate, not a composition with a stand-in")
    (is (and (identical? c (gate-at "/lone")) (identical? c (gate-at "/under-ungated")))
        "with no gate above, the host's predicate is what reitit holds")
    (is (nil? (:wb/gates (meta (gate-at "/lone")))) "unwrapped")))

(deftest assembled-csrf-refuses-unsafe-without-token-and-admits-header-or-form-field
  (let [app    (wb/handler (config))
        t      (app (mock/request :get "/token"))
        token  (:body t)
        with   #(testing/with-cookies % t)]
    (is (re-matches #"[A-Za-z0-9+/]{80}" (str token)) "ring-anti-forgery 1.4.0's token shape: 60 random bytes, base64 unpadded")
    (let [r (app (mock/request :post "/post"))]
      (is (re-matches id-pattern (str (id-of r))) "the refusal carries a well-formed request id")
      (is (= {:status 403 :headers (error-headers r) :body (page 403 (frag 403))} r) "no token, navigation → the base's 403 page"))
    (let [r (app (mock/header (mock/request :post "/post") "HX-Request" "true"))]
      (is (= {:status 403 :headers (error-headers r) :body (frag 403)} r) "no token, partial → fragment"))
    (let [r (app (mock/header (mock/request :post "/post") "Accept" "text/plain"))]
      (is (= [403 "text/plain; charset=utf-8" "403"] [(:status r) (get-in r [:headers "Content-Type"]) (:body r)]) "no token, text"))
    (is (= {:status 200 :body "posted"} (dissoc (app (with (mock/header (mock/request :post "/post") "X-CSRF-Token" token))) :headers))
        "header token passes")
    (is (= {:status 200 :body "posted"} (dissoc (app (with (mock/body (mock/request :post "/post") {"__anti-forgery-token" token}))) :headers))
        "form-field token passes: params runs before csrf")
    (is (= 403 (:status (app (with (mock/header (mock/request :post "/post") "X-CSRF-Token" (str (if (= \A (first token)) "B" "A") (subs token 1)))))))
        "a tampered token is refused")
    (is (= {:status 200 :body "posted"} (dissoc ((wb/handler (config :csrf false)) (mock/request :post "/post")) :headers))
        ":csrf false disables the check")
    (is (= 403 (:status ((wb/handler (config :csrf nil)) (mock/request :post "/post"))))
        ":csrf nil — a setting absent from the host's config map — keeps the check on")))

(deftest x-request-id-and-security-headers-on-every-response-routed-static-404-error
  (let [app (wb/handler (config :csrf false))
        ids (atom [])]
    (doseq [[label request] [["routed"  (mock/request :get "/me")]
                             ["static"  (mock/request :get "/host.txt")]
                             ["404"     (mock/request :get "/nope")]
                             ["refusal" (mock/request :get "/priv")]]]
      (testing label
        (let [{:keys [headers]} (app request)]
          (is (re-matches id-pattern (str (get headers "X-Request-Id"))) "X-Request-Id")
          (swap! ids conj (get headers "X-Request-Id"))
          (is (= SEC (select-keys headers (keys SEC))) "security headers"))))
    (is (= 4 (count (set @ids))) "distinct ids")
    (is (= 50 (count (set (repeatedly 50 #(id-of (app (mock/request :get "/nope"))))))) "fifty 404s, fifty ids")))

(deftest csp-nonce-in-the-header-equals-the-nonce-the-handler-saw-and-changes-per-request
  (let [app (wb/handler (config :csrf false
                                :security {:csp "script-src 'nonce-{nonce}'"
                                           :hsts {:max-age 31536000 :include-subdomains? true}
                                           :frame-options "SAMEORIGIN"}))
        r1  (app (mock/request :get "/see")) n1 (:wb/nonce @seen)
        r2  (app (mock/request :get "/see")) n2 (:wb/nonce @seen)]
    (is (re-matches #"[A-Za-z0-9+/]{22}==" (str n1)))
    (is (= (str "script-src 'nonce-" n1 "'") (get-in r1 [:headers "Content-Security-Policy"])) "the header carries the handler's nonce")
    (is (not= n1 n2) "a fresh nonce per request")
    (is (= (str "script-src 'nonce-" n2 "'") (get-in r2 [:headers "Content-Security-Policy"])))
    (is (= "max-age=31536000; includeSubDomains" (get-in r1 [:headers "Strict-Transport-Security"])))
    (is (= "SAMEORIGIN" (get-in r1 [:headers "X-Frame-Options"]))))
  (let [r ((wb/handler (config :csrf false :security {:frame-options false})) (mock/request :get "/nope"))]
    (is (= [nil "nosniff"] [(get-in r [:headers "X-Frame-Options"]) (get-in r [:headers "X-Content-Type-Options"])])
        "frame-options false omits the header, on a 404 too")))

(deftest i18n-puts-locale-and-tr-on-the-request-and-the-default-error-page-lang-follows
  (let [app (wb/handler (config :csrf false :i18n {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en :locales [:en :es]}))]
    (is (= ":es|Hola" (:body (app (mock/header (mock/request :get "/see") "Accept-Language" "es-ES,es;q=0.9")))))
    (is (= ":en|Hello" (:body (app (mock/request :get "/see")))))
    (is (= (page 404 (frag 404) "es") (:body (app (mock/header (mock/request :get "/nope") "Accept-Language" "es"))))
        "the default handler's 404 renders in the negotiated language")
    (let [r (app (mock/header (mock/request :post "/post") "Accept-Language" "es"))]
      (is (= (page 403 (frag 403) "es") (:body ((wb/handler (config :i18n {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en :locales [:en :es]}))
                                                (mock/header (mock/request :post "/post") "Accept-Language" "es"))))
          "a CSRF refusal renders in the negotiated language: csrf sits inside i18n")
      (is (= 200 (:status r)))))
  (let [app (wb/handler (config :csrf false))]
    (app (mock/request :get "/see"))
    (is (= {} (select-keys @seen [:wb/locale :wb/tr])) "without :i18n neither key is on the request")
    (is (= (page 404 (frag 404)) (:body (app (mock/request :get "/nope")))) "and the error page has no lang")))

(deftest static-serving-host-assets-and-the-base-wb-mount-takes-precedence-over-a-host-file
  ;; CSRF on: a GET that renders the token mints a session cookie, so the
  ;; absence of Set-Cookie on an asset is measured against a stack that
  ;; demonstrably sets one.
  (let [app (wb/handler (config))]
    (is (some? (get-in (app (mock/request :get "/token")) [:headers "Set-Cookie"])) "control: a routed GET that renders the token does mint a session cookie")
    (let [r (app (mock/request :get "/host.txt"))]
      (is (= [200 "HOST-ASSET\n" "text/plain"] [(:status r) (slurp (:body r)) (get-in r [:headers "Content-Type"])]) "the host's asset")
      (is (= SEC (select-keys (:headers r) (keys SEC))) "with the security headers")
      (is (nil? (get-in r [:headers "Set-Cookie"])) "and no session cookie: assets answer before the session"))
    (let [r (app (mock/request :get "/wb/wb.css"))]
      (is (= [200 "text/css"] [(:status r) (get-in r [:headers "Content-Type"])]))
      (is (nil? (get-in r [:headers "Set-Cookie"])) "no session cookie on the base's assets either")
      (is (clojure.string/starts-with? (slurp (:body r)) "/* web-base structural stylesheet")
          "the base's own stylesheet, not the host's public/wb/wb.css shadow"))
    (is (= (page 404 (frag 404)) (:body (app (mock/request :get "/wb/missing.txt")))) "a miss under /wb/ falls through to the 404")
    (is (= (page 404 (frag 404)) (:body ((wb/handler (config :csrf false :static nil)) (mock/request :get "/host.txt"))))
        "no :static, no host assets"))
  ;; The cookie half above is blind to assets sitting inside the session
  ;; layer (Ring writes no cookie for a response without :session); a store
  ;; that counts reads is not.
  (let [reads   (atom 0)
        store   (reify ring.middleware.session.store/SessionStore
                  (read-session [_ _] (swap! reads inc) {:user "ann"})
                  (write-session [_ k _] (or k "fresh"))
                  (delete-session [_ _] nil))
        app     (wb/handler (config :session {:store store}))
        cookie  "ring-session=old"]
    (app (mock/header (mock/request :get "/me") "Cookie" cookie))
    (is (= 1 @reads) "control: a routed GET reads the store")
    (app (mock/header (mock/request :get "/host.txt") "Cookie" cookie))
    (app (mock/header (mock/request :get "/wb/wb.css") "Cookie" cookie))
    (is (= 1 @reads) "assets never read the store: they answer before the session")))

(deftest proxy-headers-honoured-only-when-opt-in-and-counted-from-the-right
  ;; What appending proxies write: the client's own entry first, each proxy's after it.
  (let [forwarded (-> (mock/request :get "/scheme") (mock/header "X-Forwarded-Proto" "HTTPS")
                      (mock/header "X-Forwarded-For" "6.6.6.6, 10.0.0.1, 10.0.0.2"))
        one       (wb/handler (config :csrf false :security {:proxy-hops 1}))
        two       (wb/handler (config :csrf false :security {:proxy-hops 2}))
        off       (wb/handler (config :csrf false))]
    (is (= ":http|127.0.0.1" (:body (off (mock/request :get "/scheme")))) "baseline: ring-mock's own scheme and address")
    (is (= ":https|10.0.0.2" (:body (one forwarded))) "one hop: the last entry, what the one proxy saw — never the client-written first")
    (is (= ":https|10.0.0.1" (:body (two forwarded))) "two hops: the second from the right")
    (is (= ":http|127.0.0.1" (:body (off forwarded))) "without opt-in the headers are ignored")
    (is (= ":http|127.0.0.1" (:body (one (mock/request :get "/scheme")))) "opt-in without headers: unchanged")))

(deftest proxy?-is-refused-naming-proxy-hops--and-a-hop-count-must-be-positive
  (let [attempt (fn [security] (try (wb/handler (config :security security)) ::built
                                    (catch ExceptionInfo e [(ex-message e) (ex-data e)])))]
    (is (= [(str "web-base: :security :proxy? took the first X-Forwarded-For entry, which the client writes;"
                 " say :proxy-hops 1 behind one proxy that appends (nginx, a cloud load balancer), 2 behind a"
                 " CDN and a load balancer")
            {:config-key [:security :proxy?]}]
           (attempt {:proxy? true}))
        ":proxy? is refused with what replaced it: its meaning changed, so the host re-decides")
    (doseq [bad [0 -1 1.5 "1" true]]
      (is (= ["web-base: :security :proxy-hops must be a positive number of proxies"
              {:config-key [:security :proxy-hops] :value bad}]
             (attempt {:proxy-hops bad}))
          (str (pr-str bad) " is refused")))
    (is (= ::built (attempt {:proxy-hops nil})) "control: nil is the absent option")))

(deftest order-proofs-gate-predicate-throw-is-500-and-csrf-refusal-carries-outer-headers
  (let [app (wb/handler (config))]
    (lt/with-log
      (let [r (app (mock/request :get "/boom"))]
        (is (= {:status 500 :body (page 500 (frag 500))} (select-keys r [:status :body])) "a throwing predicate becomes a 500 page: error sits outside gate")
        (is (= [['dev.arkaitz.web-base.error :error "PREDBOOM" (str "unhandled exception {:request-id " (id-of r) ", :uri /boom}")]
                ['dev.arkaitz.web-base.log :info nil "GET /boom 500 <n>ms"]]
               (mapv (juxt (comp ns-name :logger-ns) :level (comp ex-message :throwable)
                           #(clojure.string/replace (:message %) #"\d+ms$" "<n>ms"))
                     (lt/the-log)))
            "logged once at error with the request id, then the access line")))
    (let [r (app (mock/request :post "/post"))]
      (is (= [403 true SEC]
             [(:status r) (boolean (re-matches id-pattern (str (id-of r)))) (select-keys (:headers r) (keys SEC))])
          "a CSRF refusal carries the outer stack's id and headers"))))

(deftest conditional-get-is-answered-with-304-by-both-asset-handlers-only--never-by-the-app
  (let [STAMP "Wed, 01 Jan 2020 00:00:00 GMT"
        app   (wb/handler (config :csrf false
                                  :routes (conj routes ["/stamped" {:get (fn [_] {:status 200 :headers {"Last-Modified" STAMP} :body [:p "x"]})}])))
        lm-re #"[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT"]
    (doseq [[path type] [["/wb/wb.css" "text/css"] ["/host.txt" "text/plain"]]]
      (let [first* (app (mock/request :get path))
            lm     (get-in first* [:headers "Last-Modified"])
            again  (app (mock/header (mock/request :get path) "If-Modified-Since" lm))
            older  (app (mock/header (mock/request :get path) "If-Modified-Since" STAMP))]
        (is (and (= 200 (:status first*)) (some? (:body first*)) (some? (get-in first* [:headers "Content-Length"])) (re-matches lm-re (str lm)))
            (str "GET " path " answers 200 with a body, Content-Length and Last-Modified: " (pr-str (:headers first*))))
        (is (= [304 nil type nil] [(:status again) (:body again) (get-in again [:headers "Content-Type"]) (get-in again [:headers "Content-Length"])])
            (str path " with the same If-Modified-Since: 304, no body, no Content-Length"))
        (is (= SEC (select-keys (:headers again) (keys SEC))) "the outer security headers survive the 304")
        (is (and (= 200 (:status older)) (some? (:body older)))
            (str path " with an earlier If-Modified-Since: 200 — the date is compared, not the header's presence"))))
    (let [r     (app (mock/header (mock/request :get "/stamped") "If-Modified-Since" STAMP))
          plain (app (mock/request :get "/stamped"))]
      (is (= [200 "<!DOCTYPE html>\n<p>x</p>" STAMP] [(:status r) (:body r) (get-in r [:headers "Last-Modified"])])
          "a routed response with Last-Modified is never 304'd: the app sits outside wrap-not-modified")
      (is (= (dissoc r :headers) (dissoc plain :headers)) "the same response as without the conditional header"))))

(deftest an-anonymous-request-that-renders-no-token-writes-no-session-through-the-whole-stack
  (let [writes (atom 0)
        store  (reify ring.middleware.session.store/SessionStore
                 (read-session [_ _] nil)
                 (write-session [_ k _] (swap! writes inc) (or k "fresh"))
                 (delete-session [_ _] nil))
        app    (wb/handler (config :session {:store store}))
        cookie #(get-in % [:headers "Set-Cookie"])]
    (let [r (app (mock/request :get "/token"))]
      (is (= [1 true] [@writes (some? (cookie r))]) "control: a page that renders the token writes one session"))
    (reset! writes 0)
    (doseq [[label path] [["a route that never reads the token" "/me"]
                          ["the gate's redirect to the login" "/priv"]
                          ["the default 404" "/nope"]]]
      (let [r (app (mock/request :get path))]
        (is (= [0 nil] [@writes (cookie r)]) (str label ": no session written and no cookie set (" (:status r) ")"))))))

(deftest a-sessionless-route-reads-no-session-and-writes-none-whatever-cookie-arrives
  (let [reads   (atom 0)
        writes  (atom 0)
        store   (reify ring.middleware.session.store/SessionStore
                  (read-session [_ _] (swap! reads inc) {:user "ann"})
                  (write-session [_ k _] (swap! writes inc) (or k "fresh"))
                  (delete-session [_ _] nil))
        health  (fn [r] {:status 200 :headers {"Content-Type" "text/plain"}
                         :body (pr-str [(contains? r :session) (:wb/subject r) (:anti-forgery-token r)])})
        app     (wb/handler (config :session {:store store} :sessionless {"/health" health}))
        cookie  "ring-session=old"]
    (app (mock/header (mock/request :get "/me") "Cookie" cookie))
    (is (= 1 @reads) "control: a routed request with that cookie reads the store")
    (reset! reads 0)
    (let [r (app (mock/header (mock/request :get "/health") "Cookie" cookie))]
      (is (= [200 "[false nil nil]"] [(:status r) (:body r)])
          "the handler saw no session, no subject and no token: it ran outside all three")
      (is (= [0 0 nil] [@reads @writes (get-in r [:headers "Set-Cookie"])])
          "and the store was neither read nor written, and no cookie was set")
      (is (= SEC (select-keys (:headers r) (keys SEC))) "with the security headers")
      (is (re-matches id-pattern (str (id-of r))) "and the request id"))
    (is (= 200 (:status (app (mock/request :post "/health")))) "any method, and no CSRF token asked for")))

(deftest a-sessionless-handler-that-throws-or-answers-nil-is-the-bases-500-never-the-apps
  (let [writes (atom 0)
        store  (reify ring.middleware.session.store/SessionStore
                 (read-session [_ _] nil)
                 (write-session [_ k _] (swap! writes inc) (or k "fresh"))
                 (delete-session [_ _] nil))
        app    (wb/handler (config :session {:store store}
                                   :sessionless {"/throws" (fn [_] (throw (ex-info "secret detail" {})))
                                                 "/void"   (fn [_] nil)}))]
    (doseq [path ["/throws" "/void"]]
      (let [r (app (mock/header (mock/request :get path) "Accept" "text/plain"))]
        (is (= [500 "500"] [(:status r) (:body r)]) (str path ": the base's 500, with nothing of the throw in it"))
        (is (= 0 @writes) (str path ": and it never fell through to the app or its session"))))
    (lt/with-log
      (app (mock/request :get "/void"))
      (is (some #(and (= :error (:level %))
                      (clojure.string/starts-with? (str (:message %)) "sessionless handler returned nil {:request-id "))
                (lt/the-log))
          (str "a nil answer is logged at error, as the docstring says: " (mapv :message (lt/the-log)))))))

(deftest a-sessionless-path-the-router-also-matches-is-refused-at-construction
  (is (= ["web-base: config :sessionless path /priv is also one of :routes; it would be served without the route's gate, session or CSRF"
          {:config-key [:sessionless "/priv"]}]
         (try (wb/handler (config :sessionless {"/health" identity "/priv" identity})) nil
              (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
      "a gated page cannot be opened by naming it here")
  (is (fn? (wb/handler (config :sessionless {"/health" #'identity})))
      "control: a path no route matches is taken, and a var is a handler too")
  (is (= [(str "web-base: config :sessionless path /pr/ is also one of :routes; it would be served without the"
               " route's gate, session or CSRF")
          {:config-key [:sessionless "/pr/"]}]
         (try (wb/handler (config :routes [["/pr/secret" {:wb/gate wb/subject-present? :get (fn [_] {:status 200 :body "s"})}]]
                                  :sessionless {"/pr/" identity})) nil
              (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
      "a prefix that covers a route is refused too, or the gated page under it would be open"))

(deftest a-sessionless-prefix-that-covers-a-templated-route-is-refused-at-construction--a-sibling-and-a-same-name-leaf-are-not
  (let [h       (fn [_] {:status 200 :body "s"})
        attempt (fn [template prefix]
                  (try (wb/handler (config :routes [[LOGIN {:get h}] [template {:wb/gate wb/subject-present? :get h}]]
                                           :sessionless {prefix identity}))
                       ::built
                       (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        refusal (fn [prefix] [(str "web-base: config :sessionless path " prefix " is also one of :routes; it would be served"
                                   " without the route's gate, session or CSRF")
                              {:config-key [:sessionless prefix]}])]
    (doseq [[prefix template] [["/acme/" "/:org/secret"]
                               ["/acme/" "/{org}/secret"]
                               ["/files/x/" "/files/*rest"]
                               ["/files/x/" "/files/{*rest}"]
                               ["/a/b/" "/a/:x/c"]
                               ["/acme/" "/acme/x"]
                               ["/q/secret/" "/{a/b}/secret/x"]
                               ["/acme-1/" "/acme-{id}/secret"]
                               ["/acme-1/" "/acme-:id/secret"]
                               ;; Two groups: a greedy collapse would take `{a}/secret/{b}` as one.
                               ["/q/secret/z/" "/{a}/secret/{b}/x"]
                               ;; A brace in the prefix is a literal character, never a parameter.
                               ["/{a/b}/secret/" "/:x/b}/secret/z"]]]
      (is (= (refusal prefix) (attempt template prefix)) (str prefix " over " template ": refused")))
    (doseq [[prefix template] [["/acme/" "/acme"] ["/acme/" "/other/:x"] ["/a/b/" "/a/:x"] ["/x/abc/" "/x/{user/id}"]]]
      (is (= ::built (attempt template prefix)) (str prefix " beside " template ": accepted, no path answers to both")))))

(deftest sessionless-config-is-refused-at-construction-when-malformed
  (doseq [[label bad] [["not a map" [["/health" identity]]]
                       ["a path without a leading slash" {"health" identity}]
                       ["a path under the base's /wb/" {"/wb/health" identity}]
                       ["a handler that is not a function" {"/health" "ok"}]
                       ["the root, which as a prefix would take every page" {"/" identity}]]]
    (is (= [(str "web-base: config :sessionless must be a map of path to handler, each path starting with / and none"
                 " under /wb/, which is the base's, nor / itself, which would take every page away from the session")
            {:config-key [:sessionless]}]
           (try (wb/handler (config :sessionless bad)) nil
                (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
        label))
  (is (fn? (wb/handler (config :sessionless nil))) "control: nil is the absent option"))

(defn- store-that-throws
  "A session store whose `method` throws with a secret in the message — what a pool
  that is down looks like from the session layer."
  [method]
  (reify ring.middleware.session.store/SessionStore
    (read-session [_ _] (if (= :read method) (throw (java.sql.SQLException. "pool closed: jdbc:SECRET-URL")) {:user "ann"}))
    (write-session [_ k _] (if (= :write method) (throw (java.sql.SQLException. "pool closed: jdbc:SECRET-URL")) (or k "fresh")))
    (delete-session [_ _] nil)))

(deftest a-session-store-that-throws-is-the-bases-500-and-never-the-adapters
  (let [app    (wb/handler (config :session {:store (store-that-throws :read)}))
        cookie #(mock/header % "Cookie" "ring-session=old")]
    (lt/with-log
      (let [r (app (cookie (mock/request :get "/me")))]
        (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)]) "a navigation gets the base's 500 page")
        (is (= (error-headers r) (select-keys (:headers r) (keys (error-headers r))))
            "with the outer layers' headers: request id, security, no-store")
        (is (= 1 (count (filter #(= :error (:level %)) (lt/the-log)))) "logged once, as an unhandled exception")))
    (is (= [500 (frag 500)] ((juxt :status :body) (app (-> (mock/request :get "/me") cookie (mock/header "HX-Request" "true")))))
        "an htmx swap gets the fragment")
    (let [r (app (-> (mock/request :get "/me") cookie (mock/header "Accept" "text/plain")))]
      (is (= [500 "500"] [(:status r) (:body r)]) "and text gets text")
      (is (not (clojure.string/includes? (pr-str r) "SECRET")) "nothing of the exception reaches the response"))
    (is (= 200 (:status (app (mock/request :get "/wb/wb.css")))) "control: assets never touch the session and still answer")))

(deftest a-session-that-cannot-be-written-and-a-subject-fn-that-throws-are-the-bases-500-too
  (let [writing (wb/handler (config :session {:store (store-that-throws :write)}))
        r       (writing (-> (mock/request :get "/token") (mock/header "Accept" "text/plain")))]
    (is (= [500 "500"] [(:status r) (:body r)]) "a write that fails on the way out"))
  (let [subject (wb/handler (config :subject-fn (fn [_] (throw (ex-info "SECRET subject failure" {})))))
        r       (subject (-> (mock/request :get "/me") (mock/header "Accept" "text/plain")))]
    (is (= [500 "500"] [(:status r) (:body r)]) "a subject function that throws")
    (is (not (clojure.string/includes? (str (:body r)) "SECRET"))))
  (is (= 200 (:status ((wb/handler (config)) (mock/request :get "/me")))) "control: a healthy stack answers as before"))

(deftest the-outer-boundary-renders-the-bases-own-page-never-the-hosts-layout
  ;; The host's error layout is written for a request that went through session, i18n
  ;; and subject; outside them it would throw — here, translating its title — and a
  ;; layout that throws there has nothing left to catch it.
  (let [translating (fn [{:keys [content request]}] [:main [:h1 ((:wb/tr request) [:hi])] content])
        throwing    (fn [_] (throw (IllegalStateException. "layout bug")))]
    (doseq [[label layout] [["a layout that translates" translating] ["a layout that always throws" throwing]]]
      (let [app (wb/handler (config :session {:store (store-that-throws :read)}
                                    :i18n {:dict {:en {:hi "Hello"}} :default-locale :en}
                                    :error-layout layout))
            r   (try (app (-> (mock/request :get "/me") (mock/header "Cookie" "ring-session=old")))
                     (catch Throwable t {:escaped (.getName (class t))}))]
        (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)])
            (str label ": the base's own 500 page, not an exception at the adapter: " (pr-str (:escaped r))))))
    (let [app (wb/handler (config :error-layout throwing
                                  :sessionless {"/sl" (fn [_] (throw (ex-info "sessionless failure" {})))}))
          r   (try (app (mock/request :get "/sl")) (catch Throwable t {:escaped (.getName (class t))}))]
      (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)])
          (str "a sessionless route that throws gets the same page: " (pr-str (:escaped r)))))))

;; --- :wb/log-path: a path that carries a secret stays out of every log line ----------

(defn- log-lines
  "Every entry captured, as text, the throwable's message and data included: what a
  secret would have to be absent from."
  []
  (pr-str (mapv (juxt :logger-ns :level :message #(some-> % :throwable ex-message) #(some-> % :throwable ex-data))
                (lt/the-log))))

(defn- access-lines []
  (->> (lt/the-log)
       (filter #(= 'dev.arkaitz.web-base.log (ns-name (:logger-ns %))))
       (mapv #(clojure.string/replace (:message %) #"\d+ms$" "<n>ms"))))

(def ^:private marked-routes
  [[LOGIN {:get (fn [_] {:status 200 :body "login page"})}]
   ["/login/redeem/:token" {:wb/log-path :template
                            :get (fn [r] (if (= "gone" (-> r :path-params :token)) {:status 404 :body ""} {:status 200 :body ""}))}]
   ["/things/:id" {:get (fn [_] {:status 200 :body ""})}]
   ["/priv/:token" {:wb/log-path :template :wb/gate wb/subject-present? :get (fn [_] {:status 200 :body ""})}]
   ["/admin" {:wb/log-path :template}
    ["/:secret" {:get (fn [_] {:status 200 :body ""})}]]
   ["/void/:token" {:wb/log-path :template :get (fn [_] nil)}]
   ["/confirm/:token/now" {:wb/log-path :template :get (fn [_] {:status 200 :body ""})}]])

(deftest access-line-shows-the-template-only-for-a-route-marked-log-path-template--uri-elsewhere--never-the-query
  (let [app (wb/handler (config :routes marked-routes))]
    (lt/with-log
      (doseq [[method uri] [[:get "/login/redeem/SECRET-TOKEN?next=%2Fx"] [:get "/login/redeem/gone"]
                            [:post "/login/redeem/SECRET-TOKEN"] [:get "/things/42?q=1"]
                            [:get "/priv/SECRET-TOKEN"] [:get "/admin/SECRET-TOKEN"] [:get "/void/SECRET-TOKEN"]
                            ;; The token mid-path and a query after it: matched with the query,
                            ;; the literal last segment would not match and the path would leak.
                            [:get "/confirm/SECRET-TOKEN/now?a=1"]]]
        (app (mock/request method uri)))
      (is (= ["GET /login/redeem/:token 200 <n>ms" "GET /login/redeem/:token 404 <n>ms"
              "POST /login/redeem/:token 403 <n>ms" "GET /things/42 200 <n>ms"
              "GET /priv/:token 303 <n>ms" "GET /admin/:secret 200 <n>ms" "GET /void/:token 500 <n>ms"
              "GET /confirm/:token/now 200 <n>ms"]
             (access-lines))
          "a marked route logs its template whatever answered — handler, 404, the CSRF refusal, gate, nil — and an unmarked one its path, never the query")
      (is (clojure.string/includes? (log-lines) "handler returned nil {:request-id")
          "witness: the nil handler's error line was captured, so the sweep below reads it")
      (is (not (clojure.string/includes? (log-lines) "SECRET-TOKEN"))
          (str "the token reaches no line, the error's datum included: " (log-lines))))
    (lt/with-log
      ((wb/handler (config :routes [["/open" {:wb/log-path :template}
                                     ["/:secret" {:wb/log-path nil :get (fn [_] {:status 200 :body ""})}]]]))
       (mock/request :get "/open/SECRET-TOKEN"))
      (is (= ["GET /open/:secret 200 <n>ms"] (access-lines))
          "a child's nil does not unmark it: reitit merges a nil as no value, so a parent's mark cannot be dropped by accident — a canary for reitit's merge, which the README promises"))
    (lt/with-log
      (app (mock/request :get "/nowhere/SECRET-TOKEN"))
      (is (= ["GET /nowhere/SECRET-TOKEN 404 <n>ms"] (access-lines))
          "control: a path no route matches has no template, and logs as it came"))))

(deftest a-session-store-that-throws-on-a-marked-route-logs-a-500-with-the-template-and-the-token-reaches-no-log-line
  (let [broken  (wb/handler (config :routes marked-routes :session {:store (store-that-throws :read)}))
        healthy (wb/handler (config :routes marked-routes))
        cookie  #(mock/header % "Cookie" "ring-session=old")]
    (lt/with-log
      (let [r (broken (cookie (mock/request :get "/login/redeem/SECRET-TOKEN")))]
        (is (= 500 (:status r)) "witness: the store threw before the router ran")
        (is (= 1 (count (filter #(= :error (:level %)) (lt/the-log)))) "witness: the boundary logged it, once")
        (is (= ["GET /login/redeem/:token 500 <n>ms"] (access-lines)) "the access line shows the template")
        (is (not (clojure.string/includes? (log-lines) "SECRET-TOKEN"))
            (str "and the token reaches no line, the unhandled exception's datum included: " (log-lines)))))
    (lt/with-log
      (broken (cookie (mock/request :get "/things/SECRET-TOKEN")))
      (is (clojure.string/includes? (pr-str (mapv :message (filter #(= :error (:level %)) (lt/the-log)))) "SECRET-TOKEN")
          "control: an unmarked route under the same failure logs its path in the error's datum, so the sweep above can see one there"))
    (lt/with-log
      (is (= 200 (:status (healthy (mock/request :get "/login/redeem/SECRET-TOKEN")))) "control: a healthy store answers")
      (is (= ["GET /login/redeem/:token 200 <n>ms"] (access-lines))))))

(deftest an-unknown-log-path-value-or-one-set-under-a-method-is-refused-when-the-handler-is-built--naming-the-route
  (let [h       (fn [_] {:status 200 :body ""})
        attempt (fn [routes] (try (wb/handler (config :routes routes)) ::built
                                  (catch ExceptionInfo e [(ex-message e) (ex-data e)])))]
    (is (= ["web-base: route /a/:t has :wb/log-path :uri; the only value is :template"
            {:config-key [:routes "/a/:t" :wb/log-path]}]
           (attempt [["/a/:t" {:wb/log-path :uri :get h}]])))
    (is (= ["web-base: route /a/:t has :wb/log-path \"template\"; the only value is :template"
            {:config-key [:routes "/a/:t" :wb/log-path]}]
           (attempt [["/a/:t" {:wb/log-path "template" :get h}]])))
    (is (= ["web-base: route /a/:t sets :wb/log-path under :get; put it on the route's own data, where it covers every method"
            {:config-key [:routes "/a/:t" :get :wb/log-path]}]
           (attempt [["/a/:t" {:get {:wb/log-path :template :handler h}}]]))
        "a method's data is not what a match reads, so the key there would hide nothing")
    (is (= ["web-base: route /a/:t sets :wb/log-path under :delete; put it on the route's own data, where it covers every method"
            {:config-key [:routes "/a/:t" :delete :wb/log-path]}]
           (attempt [["/a/:t" {:get h :delete {:wb/log-path :template :handler h}}]]))
        "under any method, not only a GET")
    (is (= ["web-base: route /g/:t has :wb/log-path :uri; the only value is :template"
            {:config-key [:routes "/g/:t" :wb/log-path]}]
           (attempt [["/g" {:wb/log-path :uri} ["/:t" {:get h}]]]))
        "a value set on a parent reaches its children, and is refused there")
    (is (= ["web-base: route /b/:t has :wb/log-path :uri; the only value is :template"
            {:config-key [:routes "/b/:t" :wb/log-path]}]
           (attempt [["/a/:t" {:wb/log-path :template :get h}] ["/b/:t" {:wb/log-path :uri :get h}]]))
        "every route is checked, not only the first")
    (is (= ::built (attempt [["/a/:t" {:wb/log-path nil :get h}]])) "control: an explicit nil is the absent option")
    (is (= ::built (attempt [["/a/:t" {:wb/log-path :template :get h}]])) "control: :template builds")))

(deftest through-the-handler-a-signed-in-page-is-not-cached-and-an-anonymous-one-without-a-form-is
  (let [app       (wb/handler (config))
        cache-of  (fn [response] (get-in response [:headers "Cache-Control"]))
        anonymous (app (mock/request :get "/me"))]
    (is (= [200 nil] [(:status anonymous) (cache-of anonymous)])
        "an anonymous page that read no token and wrote no session is left cacheable")
    (let [b      (testing/visit (testing/browser app) :get "/token")
          _      (is (= "no-store" (cache-of (:response b))) "a page that read the CSRF token is somebody's own")
          b      (testing/visit (assoc b :token (:body (:response b))) :post "/login")
          signed (:response b)
          b      (testing/visit b :get "/me")]
      (is (= [200 "no-store"] [(:status signed) (cache-of signed)]) "the login that rotated the session")
      (is (= "{:user \"ann\"}" (:body (:response b))) "witness: the browser is signed in")
      (is (= "no-store" (cache-of (:response b))) "and a signed-in page is not cached"))))

(deftest a-sessionless-prefix-answers-its-subtree-before-the-session--an-exact-path-and-a-longer-prefix-win
  (let [seen (atom [])
        tag  (fn [t] (fn [r] (swap! seen conj [t (:uri r) (contains? r :session)]) {:status 200 :body t}))
        app  (wb/handler (config :sessionless {"/api/"     (tag "api")
                                               "/api/v2/"  (tag "v2")
                                               "/api/ping" (tag "ping")}))
        body (fn [path] (:body (app (mock/header (mock/request :post path) "Cookie" "ring-session=x"))))]
    (is (= ["api" "api" "v2" "ping" "api"]
           (mapv body ["/api/" "/api/orgs/7/notes" "/api/v2/x" "/api/ping" "/api/pingpong"]))
        "the subtree goes to its prefix, the longest prefix and an exact path win, and a longer path is not the exact one")
    (is (every? (fn [[_ _ session?]] (not session?)) @seen)
        "none of them saw a session, whatever cookie came: they answer before it")
    (is (= 403 (:status (app (mock/request :post "/post")))) "control: a router POST is still refused by CSRF")
    (is (= 404 (:status (app (mock/request :get "/apix")))) "a path that only shares letters with the prefix is the router's 404")))

;; --- a body the client did not send --------------------------------------------------

(defn- throwing-body
  "A body whose every read throws `boom`, noting in `attempted` that one was asked for."
  [boom attempted]
  (proxy [java.io.InputStream] []
    (read ([] (reset! attempted true) (throw boom))
          ([_] (reset! attempted true) (throw boom))
          ([_ _ _] (reset! attempted true) (throw boom)))
    (skip [_] (reset! attempted true) (throw boom))))

(deftest a-body-the-client-did-not-send-is-its-jetty-status-and-one-info-line--only-when-read-through-the-limit--else-a-logged-500
  (let [rid       (atom nil)
        ran       (atom false)
        to-throw  (atom nil)
        err-ns    (the-ns 'dev.arkaitz.web-base.error)
        note!     (fn [r] (reset! rid (:wb/request-id r)) (reset! ran true))
        app       (wb/handler (config :csrf false :max-body-bytes 16
                                      :routes [["/slurp" {:post (fn [r] (note! r) {:status 200 :body (str (count (slurp (:body r))))})}]
                                               ["/throw" {:post (fn [r] (note! r) (throw @to-throw))}]
                                               ["/wrap" {:post (fn [r] (note! r)
                                                                 (try (slurp (:body r))
                                                                      (catch ExceptionInfo e (throw (ex-info "host" {:my 1} e)))))}]
                                               ["/own" {:post (fn [r] (note! r)
                                                                (try (slurp (:body r))
                                                                     (catch ExceptionInfo e
                                                                       (throw (ex-info "host" {:status 422 :type :dev.arkaitz.web-base.error/error} e)))))}]]))
        run       (fn [path body]
                    (reset! rid nil) (reset! ran false)
                    (lt/with-log [(app (-> (mock/request :post path) (assoc :body body) (dissoc :content-length)
                                           (update :headers dissoc "content-length")))
                                  (vec (lt/the-log))]))
        of-error  (fn [log] (filterv #(= err-ns (:logger-ns %)) log))
        errors    (fn [log] (filterv #(= :error (:level %)) log))
        info-line (fn [class status] (lt/->LogEntry err-ns :info nil (str "request body not received: " class " → " status
                                                                           " {:request-id " @rid ", :uri /slurp}")))
        eof       "org.eclipse.jetty.io.EofException"
        bad       "org.eclipse.jetty.http.BadMessageException"]
    (doseq [[label boom status class]
            [["an EofException, bare" (org.eclipse.jetty.io.EofException. "mid") 400 eof]
             ["a 408 inside an IOException, as Jetty's stream throws it"
              (java.io.IOException. "read" (org.eclipse.jetty.http.BadMessageException. 408 "slow")) 408 bad]
             ["a 408 thrown bare, a runtime exception" (org.eclipse.jetty.http.BadMessageException. 408 "slow") 408 bad]
             ["a 431 two causes down"
              (java.io.IOException. (RuntimeException. "x" (org.eclipse.jetty.http.BadMessageException. 431 "big"))) 431 bad]
             ["a code outside 400-599" (java.io.IOException. (org.eclipse.jetty.http.BadMessageException. 200 "odd")) 400 bad]]]
      (let [attempted (atom false)
            [r log]   (run "/slurp" (throwing-body boom attempted))]
        (is (true? @attempted) (str label ": witness: the handler's read reached the body"))
        (is (re-matches id-pattern (str @rid)) (str label ": witness: under a request id the line can name"))
        (is (= [status (page status (frag status))] [(:status r) (:body r)]) (str label ": its own status"))
        (is (= [(info-line class status)] (of-error log)) (str label ": exactly one INFO line naming it"))
        (is (= [] (errors log)) (str label ": and no ERROR anywhere"))))
    (let [boom    (org.eclipse.jetty.io.EofException. "mid")
          _       (reset! to-throw boom)
          [r log] (run "/throw" (java.io.ByteArrayInputStream. (.getBytes "a")))]
      (is (true? @ran) "witness: the handler ran and threw it itself")
      (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)])
          "the same Jetty exception from anything but the body — an upstream client's — is a 500")
      (is (= [(lt/->LogEntry err-ns :error boom (str "unhandled exception {:request-id " @rid ", :uri /throw}"))] (of-error log))
          "logged at ERROR with the throwable, and no INFO line"))
    (let [attempted (atom false)
          boom      (java.io.EOFException. "plain")
          [r log]   (run "/slurp" (throwing-body boom attempted))]
      (is (true? @attempted) "witness: the read reached the body")
      (is (= [500 (page 500 (frag 500))] [(:status r) (:body r)])
          "a failure of the body Jetty does not call the client's — a plain EOFException — is a 500")
      (is (= [(lt/->LogEntry err-ns :error boom (str "unhandled exception {:request-id " @rid ", :uri /slurp}"))] (of-error log))
          "logged at ERROR with that very throwable"))
    (let [[r log] (run "/wrap" (java.io.ByteArrayInputStream. (.getBytes "a=0123456789abcde")))]
      (is (true? @ran) "witness: the host's reader ran")
      (is (= [413 (page 413 (frag 413))] [(:status r) (:body r)])
          "a host that wraps the base's 413 in an ex-info of its own still answers 413")
      (is (= [[] []] [(of-error log) (errors log)]) "and nothing is logged as an error"))
    (let [[r _] (run "/own" (java.io.ByteArrayInputStream. (.getBytes "a=0123456789abcde")))]
      (is (true? @ran) "witness: the host's reader ran")
      (is (= [422 (page 422 (frag 422))] [(:status r) (:body r)])
          "a host's own error datum, with the base's 413 as its cause, is the host's answer"))))

(deftest a-client-that-breaks-off-an-upload-is-its-status-and-one-info-line-naming-the-route-by-its-template
  (let [err-ns (the-ns 'dev.arkaitz.web-base.error)
        rid    (atom nil)
        app    (wb/handler (config :routes [["/t/:token" {:wb/log-path :template
                                                           :wb/multipart {:max-file-size 1000}
                                                           :post (fn [r] (reset! rid :handler-ran) {:status 200 :body "no"})}]]))
        body   (throwing-body (org.eclipse.jetty.io.EofException. "gone") (atom false))
        [r log] (lt/with-log
                  [(app (-> (mock/request :post "/t/SECRET-TOKEN")
                            (assoc :body body) (dissoc :content-length) (update :headers dissoc "content-length")
                            (assoc-in [:headers "content-type"] "multipart/form-data; boundary=B")))
                   (vec (lt/the-log))])
        lines  (filterv #(= err-ns (:logger-ns %)) log)]
    (is (nil? @rid) "witness: the parse failed before any handler ran")
    (is (= [400 (page 400 (frag 400))] [(:status r) (:body r)])
        "an upload the client broke off mid-parse keeps Jetty's status, and is not called too large")
    (is (= [[:info nil]] (mapv (juxt :level :throwable) lines)) "one INFO line, no throwable")
    (is (re-matches #"request body not received: org\.eclipse\.jetty\.io\.EofException → 400 \{:request-id [A-Za-z0-9_-]{16}, :uri /t/:token\}"
                    (str (:message (first lines))))
        (str "naming the route by its template: " (:message (first lines))))
    (is (not-any? #(clojure.string/includes? (str (:message %)) "SECRET-TOKEN") log) "and the secret in the path nowhere")))

;; --- the request body limit ---------------------------------------------------------

(defn- limited-app []
  (wb/handler (config :csrf false :max-body-bytes 16
                      :routes [["/form" {:post (fn [r] {:status 200 :body (pr-str (:form-params r))})}]
                               ["/raw" {:post (fn [r] {:status 200 :body (str (count (slurp (:body r))))})}]]
                      :sessionless {"/probe" (fn [r] {:status 200 :body (str (count (slurp (:body r))))})})))

(defn- post-body
  "A POST of `body` whose declared length is `declared` — :absent for none."
  [path ^String body declared]
  (let [r (-> (mock/request :post path) (mock/body body) (mock/content-type "application/x-www-form-urlencoded"))]
    (if (= :absent declared)
      (-> r (dissoc :content-length) (update :headers dissoc "content-length"))
      (-> r (assoc :content-length declared) (assoc-in [:headers "content-length"] (str declared))))))

(deftest the-limit-is-exact-through-the-assembled-handler--declared-lying-and-undeclared-bodies-alike
  (let [app      (limited-app)
        at-limit "a=0123456789abcd"
        over     "a=0123456789abcde"]
    (is (= [16 17] [(count at-limit) (count over)]) "witness: the two bodies straddle the limit")
    (doseq [path ["/form" "/raw" "/probe"]
            [how declared-of] [["declared" count] ["lying" (constantly 4)] ["undeclared" (constantly :absent)]]]
      (let [ok (app (post-body path at-limit (declared-of at-limit)))
            no (app (post-body path over (declared-of over)))]
        (is (= 200 (:status ok)) (str path ", " how ": exactly the limit is served"))
        (is (= [413 (page 413 (frag 413))] [(:status no) (:body no)])
            (str path ", " how ": one byte over is the base's 413"))
        (is (= (error-headers no) (select-keys (:headers no) (keys (error-headers no))))
            (str path ", " how ": with the request id and the security headers"))))
    (is (= "{\"a\" \"0123456789abcd\"}" (:body (app (post-body "/form" at-limit 16))))
        "the form that passed was read through the limited stream, field and all")
    (is (= 413 (:status (app (-> (mock/request :post "/raw") (assoc :content-length 17))))) "a declared length alone, as the key")
    (is (= 413 (:status (app (-> (mock/request :post "/raw") (mock/header "Content-Length" "17"))))) "or as the header")
    (is (= "nil" (:body ((wb/handler (config :routes [["/g" {:get (fn [r] {:status 200 :body (pr-str (:body r))})}]]))
                         (mock/request :get "/g"))))
        "control: a request without a body gets none")))

(deftest a-declared-length-over-the-limit-is-refused-without-touching-the-body
  (let [touched (atom [])
        body    (proxy [java.io.InputStream] []
                  (read ([] (swap! touched conj :read) -1)
                        ([_] (swap! touched conj :read) -1)
                        ([_ _ _] (swap! touched conj :read) -1))
                  (skip [_] (swap! touched conj :skip) 0)
                  (available [] (swap! touched conj :available) 0))
        r       ((limited-app) (-> (mock/request :post "/raw") (assoc :body body :content-length 17)))]
    (is (= 413 (:status r)) "witness: refused")
    (is (= [] @touched) "and nothing asked the body for a byte")))

(deftest a-huge-undeclared-body-is-refused-after-reading-about-the-limit-and-never-held-whole
  (let [pulled  (atom 0)
        largest (atom 0)
        total   800000
        called  (atom false)
        body    (proxy [java.io.InputStream] []
                  (read ([] (if (< @pulled total) (do (swap! pulled inc) (int \x)) -1))
                        ([^bytes b] (.read ^java.io.InputStream this b 0 (alength b)))
                        ([^bytes b off len]
                         (swap! largest max len)
                         (if (< @pulled total)
                           (let [n (min len (- total @pulled))]
                             (java.util.Arrays/fill b (int off) (int (+ off n)) (byte (int \x)))
                             (swap! pulled + n)
                             n)
                           -1))))
        app     (wb/handler (config :csrf false :routes [["/form" {:post (fn [r] (reset! called true) {:status 200 :body "no"})}]]))
        r       (deref (future (app (-> (mock/request :post "/form") (mock/content-type "application/x-www-form-urlencoded")
                                        (assoc :body body) (dissoc :content-length))))
                       10000 ::timeout)]
    (is (= 413 (:status r)) (str "an undeclared 800 000-byte form is refused under the default limit: " (pr-str (:status r))))
    (is (< 200000 @pulled (+ 200000 @largest 1))
        (str "after pulling just past 200 000 bytes and no more than one read beyond: pulled " @pulled ", largest read " @largest))
    (is (false? @called) "the handler never ran")))

(deftest max-body-bytes-is-refused-naming-the-key-and-nil-leaves-200000-in-force
  (doseq [bad [0 -1 1.5 "16" :sixteen true]]
    (is (= ["web-base: :max-body-bytes must be a positive number of bytes" {:config-key [:max-body-bytes] :value bad}]
           (try (wb/handler (config :max-body-bytes bad)) nil (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str (pr-str bad) " is refused")))
  (doseq [app [(wb/handler (config :csrf false)) (wb/handler (config :csrf false :max-body-bytes nil))]]
    (is (= [200 413] (mapv #(:status (app (-> (mock/request :post "/post") (assoc :content-length %)))) [200000 200001]))
        "200 000 bytes pass and 200 001 do not, with the key absent or nil")))

;; --- multipart, per route ---------------------------------------------------------------

(defn- upload-app [spec]
  (wb/handler (config :routes [["/form" {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
                                         :get (fn [_] {:status 200 :body [:p "form"]})}]
                               ["/upload" {:wb/multipart spec
                                           :post (fn [r] (let [{:keys [filename size tempfile]} (get-in r [:multipart-params "photo"])]
                                                           {:status 200 :body (pr-str [filename size (some-> tempfile .exists)
                                                                                       (get-in r [:multipart-params "caption"])])}))}]
                               ["/plain" {:post (fn [_] {:status 200 :body "plain"})}]])))

(defn- upload [app path files]
  (let [b (testing/visit (testing/browser app) :get "/form")]
    (:response (testing/visit b :post path {"caption" "hi"} {:files files}))))

(defn- photo [n] {"photo" {:filename "a.png" :content-type "image/png" :bytes (byte-array n (byte 7))}})

(deftest a-route-that-declares-multipart-takes-a-no-js-upload-with-its-csrf-field--an-undeclared-one-parses-nothing
  (let [app (upload-app {:max-file-size 1000})]
    (is (= [200 "[\"a.png\" 1000 true \"hi\"]"] ((juxt :status :body) (upload app "/upload" (photo 1000))))
        "the file, the field beside it, and the CSRF field in the body all reached the route")
    (is (= 403 (:status (upload app "/plain" (photo 10))))
        "a route that did not declare it never parses the body, so its token is unseen: CSRF refuses, nothing is written")))

(deftest an-upload-past-its-routes-limits-is-a-413
  (let [two-files (merge (photo 10) {"other" {:filename "b" :bytes (byte-array 1)}})]
    (is (= 413 (:status (upload (upload-app {:max-file-size 1000}) "/upload" (photo 1001)))) "a file one byte over :max-file-size")
    ;; Ring counts every part: here the caption, the CSRF field and the two files, four.
    (is (= 200 (:status (upload (upload-app {:max-file-size 1000 :max-file-count 4}) "/upload" two-files)))
        "control: four parts under a count of four pass")
    (is (= 413 (:status (upload (upload-app {:max-file-size 1000 :max-file-count 3}) "/upload" two-files)))
        "four parts under a count of three are refused")
    (is (= 413 (:status (upload (upload-app {:max-file-size 5000 :max-body-bytes 3000}) "/upload" (photo 4000))))
        "a body past the route's :max-body-bytes, whatever the file size allows")
    (is (= 200 (:status (upload (upload-app {:max-file-size 300000}) "/upload" (photo 300000))))
        "without :max-body-bytes, the route takes its file size beyond the base's 200 000 bytes")
    (is (= 413 (:status ((upload-app {:max-file-size 1000})
                         (-> (mock/request :post "/upload") (mock/body (apply str "a=" (repeat 200500 "x")))
                             (mock/content-type "application/x-www-form-urlencoded")))))
        "a body that is not multipart keeps the base's limit on a multipart route: 200 500 bytes are refused, not let through to CSRF")))

(defn- spying-on-uploads
  "Runs `f` with every request's store watched: answers `[result requests]`, one entry
  per multipart request parsed, each `{:files [File …] :existed [bool …] :prior [[len …] …]}`
  — the Files production minted, whether each existed when minted, and the lengths of
  those before it at that moment."
  [f]
  (let [original @#'wb/request-store
        requests (atom [])]
    (with-redefs-fn {#'wb/request-store
                     (fn [created]
                       (let [entry (atom {:files [] :existed [] :prior []})]
                         (swap! requests conj entry)
                         (add-watch created ::spy
                                    (fn [_ _ old new]
                                      (let [^java.io.File file (peek new)]
                                        (swap! entry #(-> % (update :files conj file) (update :existed conj (.exists file))
                                                          (update :prior conj (mapv (fn [^java.io.File o] (.length o)) old)))))))
                         (original created)))}
      (fn [] [(f) (mapv deref @requests)]))))

(deftest every-upload-temp-file-exists-while-the-request-runs-and-is-gone-when-it-ends--after-a-200--a-csrf-403--and-a-413-mid-parse
  (let [seen-file (atom nil)
        app       (wb/handler (config :routes [["/form" {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
                                                         :get (fn [_] {:status 200 :body [:p "form"]})}]
                                               ["/upload" {:wb/multipart {:max-file-size 1000}
                                                           :post (fn [r] (let [{:keys [tempfile]} (get-in r [:multipart-params "photo"])]
                                                                           (reset! seen-file tempfile)
                                                                           {:status 200 :body (str (.exists ^java.io.File tempfile))}))}]
                                               ["/plain" {:post (fn [_] {:status 200 :body "plain"})}]]))
        gone?     (fn [{:keys [files]}] (mapv #(.exists ^java.io.File %) files))]
    (let [[r [req]] (spying-on-uploads #(upload app "/upload" (photo 1000)))]
      (is (= [200 "true"] [(:status r) (:body r)]) "200: the handler saw its file on disk")
      (is (= [[@seen-file] [true]] [(:files req) (:existed req)]) "200: one file was written, the one the handler saw")
      (is (= [false] (gone? req)) (str "200: none survives the response: " (mapv str (:files req)))))
    (let [[r [req]] (spying-on-uploads #(:response (testing/visit (assoc (testing/browser app) :token "bogus") :post "/upload"
                                                                   {"caption" "hi"} {:files (photo 10)})))]
      (is (= 403 (:status r)) "403: an anonymous POST with no valid token is refused")
      (is (= [1 [true]] [(count (:files req)) (:existed req)]) "403: its upload was written while it was judged")
      (is (= [false] (gone? req)) "403: and is gone once the refusal is sent"))
    (let [[r [req]] (spying-on-uploads #(upload app "/upload" (array-map "a" {:filename "a" :bytes (byte-array 10)}
                                                                         "b" {:filename "b" :bytes (byte-array 1001)})))]
      (is (= 413 (:status r)) "413: the second file is past :max-file-size")
      (is (= [2 [true true] [[] [10]]] [(count (:files req)) (:existed req) (:prior req)])
          "413: the first was written whole before the second was begun")
      (is (= [false false] (gone? req)) "413: and both are gone, the one whose copy threw included"))
    (let [[r requests] (spying-on-uploads #(upload app "/plain" (photo 10)))]
      (is (= [403 []] [(:status r) requests]) "control: a route that did not declare it builds no store at all"))))

(defn- multipart-request
  "A POST to `path` whose multipart body is exactly `n` bytes, its one file padded to
  make it so, sent with `content-type`: `[request file-size]`."
  [path n content-type]
  (let [body-of #(@#'testing/multipart-body [] {"photo" {:filename "a" :bytes (byte-array %)}})
        size    (- n (alength ^bytes (body-of 0)))
        bytes   ^bytes (body-of size)]
    [(-> (mock/request :post path)
         (assoc :body (java.io.ByteArrayInputStream. bytes) :content-length (alength bytes))
         (assoc-in [:headers "content-length"] (str (alength bytes)))
         (assoc-in [:headers "content-type"] content-type))
     size]))

(deftest only-an-exact-multipart-form-data-type-gets-the-routes-larger-limit--a-lookalike-keeps-the-bases-and-parses-nothing
  (let [ran      (atom false)
        app      (wb/handler (config :csrf false
                                     :routes [["/upload" {:wb/multipart {:max-file-size 300000}
                                                          :post (fn [r] (reset! ran true)
                                                                  {:status 200 :body (pr-str (get-in r [:multipart-params "photo" :size]))})}]]))
        boundary @#'testing/boundary
        send     (fn [type] (reset! ran false)
                   (let [[request size] (multipart-request "/upload" 200001 (str type "; boundary=" boundary))]
                     [(app request) size]))]
    (let [[r size] (send "multipart/form-data")]
      (is (= [200 (str size)] [(:status r) (:body r)]) "multipart/form-data: a 200 001-byte body is taken whole, its file parsed"))
    (doseq [type ["multipart/form-dataX" "multipart/form-data-x" "Multipart/Form-Data"]]
      (let [[r _] (send type)]
        (is (= [413 (page 413 (frag 413)) false] [(:status r) (:body r) @ran])
            (str type ": the base's limit, as Ring would not parse it either"))))))

(deftest a-declared-multipart-routes-default-limit-is-its-file-size-plus-the-bases-limit-exactly
  (let [ran     (atom false)
        app-of  (fn [spec] (wb/handler (config :max-body-bytes 16
                                               :routes [["/form" {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
                                                                  :get (fn [_] {:status 200 :body [:p "form"]})}]
                                                        ["/upload" {:wb/multipart spec
                                                                    :post (fn [r] (reset! ran true) {:status 200 :body (str (:content-length r))})}]])))
        send    (fn [spec] (reset! ran false) (let [r (upload (app-of spec) "/upload" (photo 1000))] [(:status r) (:body r) @ran]))
        [_ b _] (send {:max-file-size 100000})
        B       (parse-long b)]
    (is (< 1000 B 2000) (str "witness: the body of a 1000-byte photo, its caption and token measures " B))
    (is (= [200 (str B) true] (send {:max-file-size (- B 16)})) "file size + 16 is exactly the body: served")
    (is (= [413 (page 413 (frag 413)) false] (send {:max-file-size (- B 17)})) "one byte less is refused")
    (is (= [413 (page 413 (frag 413)) false] (send {:max-file-size 100000 :max-body-bytes (- B 1)}))
        "an explicit :max-body-bytes wins over the sum")
    (is (= [200 (str B) true] (send {:max-file-size 100000 :max-body-bytes B})) "and at the body's size it is served")))

(deftest an-upload-refused-mid-parse-is-the-hosts-error-page-in-the-negotiated-language
  ;; The parse runs inside i18n and outside the router, and renders its refusal through
  ;; the host's own error layout — not the outer boundary's bare page, which is for what
  ;; failed before a language or a session existed.
  (let [layout (fn [{:keys [content request]}] [:main [:h1 ((:wb/tr request) [:too-big])] content])
        app    (wb/handler (config :i18n {:dict {:en {:too-big "Too big"} :eu {:too-big "Handiegia"}} :default-locale :en :locales [:en :eu]}
                                   :error-layout layout
                                   :routes [["/form" {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
                                                      :get (fn [_] {:status 200 :body [:p "form"]})}]
                                            ["/upload" {:wb/multipart {:max-file-size 1000}
                                                        :post (fn [_] {:status 200 :body "no"})}]]))
        b      (testing/visit (testing/browser app) :get "/form" nil {:headers {"accept-language" "eu"}})
        r      (:response (testing/visit b :post "/upload" {"caption" "hi"} {:files (photo 1001) :headers {"accept-language" "eu"}}))]
    (is (= 413 (:status r)) "witness: the file is past :max-file-size, found as it was parsed")
    (is (clojure.string/includes? (str (:body r)) "<h1>Handiegia</h1>")
        (str "the host's error layout, in the language the request asked for: " (:body r)))))

(deftest wb-multipart-is-checked-when-the-handler-is-built
  (let [attempt (fn [data] (try (wb/handler (config :routes [["/u" data]])) ::built
                                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        h       (fn [_] {:status 200 :body ""})]
    (doseq [bad [{} {:max-file-size 0} {:max-file-size 10 :store identity} {:max-file-size 10 :max-file-count -1} true]]
      (is (= [(str "web-base: route /u has :wb/multipart " (pr-str bad) "; it takes [:max-body-bytes :max-file-count"
                   " :max-file-size], positive numbers, :max-file-size required")
              {:config-key [:routes "/u" :wb/multipart]}]
             (attempt {:wb/multipart bad :post h}))
          (str (pr-str bad) " is refused")))
    (is (= ["web-base: route /u sets :wb/multipart under :post; put it on the route's own data"
            {:config-key [:routes "/u" :post :wb/multipart]}]
           (attempt {:post {:wb/multipart {:max-file-size 10} :handler h}})))
    (is (= ::built (attempt {:wb/multipart {:max-file-size 10} :post h})) "control")))

(deftest a-health-probe-says-ok-or-unavailable-and-nothing-else--a-throw-is-a-logged-503
  (let [probe (fn [ready?] ((wb/handler (config :sessionless {"/health" (response/health ready?)})) (mock/request :get "/health")))
        H     {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"}]
    (is (= [200 "ok"] ((juxt :status :body) (probe (constantly true)))) "ready: 200 ok")
    (is (= H (select-keys (:headers (probe (constantly true))) (keys H))) "plain text, uncached")
    (is (= [503 "unavailable"] ((juxt :status :body) (probe (constantly false)))) "not ready: 503")
    (lt/with-log
      (is (= [503 "unavailable"] ((juxt :status :body) (probe #(throw (RuntimeException. "pool closed"))))) "a throw is a 503")
      (is (= [[:warn "health check failed" "pool closed"]]
             (mapv (juxt :level :message (comp ex-message :throwable))
                   (filter #(= 'dev.arkaitz.web-base.response (ns-name (:logger-ns %))) (lt/the-log))))
          "and is logged once with its cause"))))

(deftest a-gated-router-refuses-a-login-path-that-is-not-a-page-it-serves
  (let [ok      (fn [_] {:status 200 :body "x"})
        gated   ["/priv" {:wb/gate wb/subject-present? :get ok}]
        attempt (fn [cfg] (try (wb/handler (merge {:session {:key KEY}} cfg)) ::built
                               (catch ExceptionInfo e [(ex-message e) (dissoc (ex-data e) :reitit.exception/cause)])))
        nowhere (fn [p] [(str "web-base: :login-path " p " is not a page of this router — no route answers GET there,"
                              " so every gated refusal would lead nowhere")
                         {:config-key [:login-path]}])]
    (is (= (nowhere "/in") (attempt {:routes [gated] :login-path "/in"}))
        "no route there: every refusal would redirect to a 404")
    (is (= (nowhere "/in") (attempt {:routes [gated ["/in" {:post ok}]] :login-path "/in"}))
        "a route that answers only POST there is no page to land on")
    (is (= [(str "web-base: :login-path /in is itself gated, so a refusal would redirect to a page that refuses too")
            {:config-key [:login-path]}]
           (attempt {:routes [gated ["/in" {:wb/gate wb/subject-present? :get ok}]] :login-path "/in"}))
        "a gated login page would loop")
    (is (= ::built (attempt {:routes [gated ["/in" {:get ok}]] :login-path "/in?from=gate"}))
        "control: the page is matched without the login path's query")
    (is (= (nowhere "/in") (attempt {:routes [["/priv" {:get {:wb/gate wb/subject-present? :handler ok}}]] :login-path "/in"}))
        "a gate on a method, not the route, counts too")
    (is (= [(str "web-base: :login-path /in is itself gated, so a refusal would redirect to a page that refuses too")
            {:config-key [:login-path]}]
           (attempt {:routes [gated ["/in" {:get {:wb/gate wb/subject-present? :handler ok}}]] :login-path "/in"}))
        "and a login page gated on its GET would loop as surely")
    (is (= ::built (attempt {:routes [gated ["/in" {:get ok}]] :login-path "/in#form"}))
        "control: and without its fragment")
    (is (= ::built (attempt {:routes [gated] :login-path "https://sso.example/login"}))
        "a login page on another origin, a single sign-on's, is not this router's to check")
    (is (= ::built (attempt {:routes [gated] :login-path "//sso.example/login"}))
        "nor one written protocol-relative, which a browser also takes to another origin")
    (is (= ::built (attempt {:routes [gated] :login-path "/\\sso.example/login"}))
        "nor one whose backslash a browser reads as the second slash")
    (doseq [other ["HTTPS://sso.example/login" " //sso.example/login" "\u0001//sso.example/login" "\\\\sso.example/login"
                   "\\/sso.example/login" "/\t/sso.example/login"]]
      (is (= ::built (attempt {:routes [gated] :login-path other}))
          (str (pr-str other) " is another origin to a browser, as it reads an address")))
    (is (= (nowhere "in") (attempt {:routes [gated ["/in" {:get ok}]] :login-path "in"}))
        "a relative login path is this origin's, and resolves against the refused page: checked, and refused")
    (is (= ::built (attempt {:routes [["/open" {:get ok}]] :login-path "/nowhere"}))
        "control: with no gate anywhere the login path leads nowhere and nothing is refused")
    (is (= (nowhere "/entrar")
           (attempt {:routes     [gated]
                     :login-path "/entrar"
                     :plugins    [{:wb.plugin/name :auth :routes [["/login" {:get ok}]] :login-path "/login"}]}))
        "the case that asked for it: a host :login-path wins over a plugin's while the plugin's page stays at /login")))
