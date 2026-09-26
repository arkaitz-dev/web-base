(ns dev.arkaitz.web-base
  "Public entry point of web-base: the handler that wires the base together and
  the server lifecycle. A library the host calls; it never calls the host back
  except through the functions the host hands it (SPEC §3).

  The wiring convention IS the product, so here it is, outermost first:

    request-id → security headers → proxy (opt-in)
    → [/wb/ assets → sessionless routes → host static → ] session → params → i18n → csrf → subject
    → ring-handler
        router, per matched route: error → gate → render → coercion → handler
        default handler: 404 / 405 / nil-handler 500

  Static assets answer before the session: a stylesheet fetch must not mint a
  session cookie, and a cookie on an asset defeats shared caches. They still
  carry the request id and the security headers. So do the host's `:sessionless`
  routes — a health probe, a webhook — which read no session and write none, whatever
  cookie arrives. Session sits outside subject
  (the subject function reads the session) and outside csrf (the token lives
  in the session); params sits outside csrf because the token may arrive as a
  form field; i18n sits outside csrf so a refused request's error page speaks
  the negotiated language. Error sits outside gate and render inside the
  router: a predicate or a layout may throw, and a refusal is an error datum.
  The default handler runs outside router middleware, so the request id, the
  session and the security headers reach it from the outer stack while errors
  render on their own."
  (:require [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.gate :as gate]
            [dev.arkaitz.web-base.i18n :as i18n]
            [dev.arkaitz.web-base.log :as log]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.session :as session]
            [clojure.string :as str]
            [clojure.tools.logging :as tools-log]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.util.codec :as codec]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.params :as params]))

(def subject-present?
  "The stock gate predicate."
  gate/subject-present?)

(defn rerender
  "The page at `path` rendered again for the request a handler is answering, with
  `form` — whatever the host's view reads, typically `{:values … :errors …}` — under
  `:wb/form`, and status 422 when the page renders as an ordinary 200.

  For a classic form that failed validation: the POST handler validates, and on
  failure answers `(rerender request \"/things\" {:values v :errors e})`, so the
  person gets the page they were on with what they typed and why it was refused —
  without a redirect that loses both, and without the POST handler rebuilding the
  page it does not own. The page's own `:get` handler runs, compiled as the router
  compiled it: its gate, coercion and layouts apply as for any GET of that path. The
  request keeps its session, subject, locale and CSRF token; its form, body and
  parameters are dropped, and `path`'s own path and query parameters take their
  place. Views read `(:wb/form request)`, whose shape is the host's.

  A response the page answers with a redirect, an `HX-Redirect` or any status other
  than 200 is returned as it is. An htmx form that swaps only itself wants
  `response/unprocessable` with its own fragment instead, since the page's GET would
  render the whole page's content into the form's target.

  Throws when `path` has no `:get` route, and when the request already carries
  `:wb/form` — a page that re-rendered into itself would never stop. Not a validation
  helper: the base still knows no schema (SPEC §7).

  **`path` is the host's own route, never input from the request.** Whatever GET it
  names runs as a side effect of this POST, as the caller — a logout, a link's
  redemption — so a path taken from a form field would let whoever submits it choose
  which."
  [request path form]
  (when (contains? request :wb/form)
    (throw (ex-info (str "web-base rerender: " path " was reached from a rerender already")
                    {:path path})))
  (let [[uri qs] (str/split (str path) #"\?" 2)
        match    (some-> (::r/router request) (r/match-by-path uri))
        handler  (get-in match [:result :get :handler])]
    (when-not handler
      (throw (ex-info (str "web-base rerender: no GET route for " path) {:path path})))
    (let [query    (if qs (codec/form-decode qs "UTF-8") {})
          query    (if (map? query) query {})
          response (handler (-> request
                                (dissoc :form-params :multipart-params :body :body-params :parameters
                                        :query-string :path-info)
                                (assoc :request-method :get
                                       :uri uri
                                       :query-params query
                                       :params query
                                       :path-params (:path-params match)
                                       ::r/match match
                                       :wb/form form)
                                (cond-> qs (assoc :query-string qs))))]
      (if (and (= 200 (:status response))
               (not (contains? (:headers response) "HX-Redirect")))
        (assoc response :status 422)
        response))))

(defn- require-key! [config k]
  (when (nil? (get config k))
    (throw (ex-info (str "web-base config needs " k) {:config-key [k]}))))

(def ^:private base-assets
  (ring/create-resource-handler {:path "/wb/" :root "dev/arkaitz/web_base/public"}))

(defn- sessionless-handler
  "The host's `:sessionless` routes, by exact path and any method, each inside the
  base's error middleware so a throw renders the base's 500 rather than reaching the
  server. A nil answer is a 500 too, logged: falling through to the next handler would
  hand the request to the session this mount exists to avoid."
  [routes render-error]
  (when (seq routes)
    (let [wrap     (:wrap (error/middleware render-error))
          handlers (update-vals routes wrap)]
      (fn [request]
        (when-let [h (get handlers (:uri request))]
          (or (h request)
              (do (tools-log/error "sessionless handler returned nil" {:request-id (:wb/request-id request)
                                                                :uri        (:uri request)})
                  (render-error {:status 500} request))))))))

(defn- validate-sessionless! [routes]
  (when (some? routes)
    (when-not (and (map? routes)
                   (every? #(and (string? %) (str/starts-with? % "/") (not (str/starts-with? % "/wb/"))) (keys routes))
                   (every? #(or (fn? %) (var? %)) (vals routes)))
      (throw (ex-info (str "web-base config :sessionless must be a map of path to handler, each path"
                           " starting with / and none under /wb/, which is the base's")
                      {:config-key [:sessionless]})))))

(defn- refuse-shadowing!
  "A sessionless path the router also matches would serve that route with no gate, no
  subject, no session and no CSRF — silently, and a gated page would be open. Refused at
  construction, naming the path."
  [router routes]
  (when-let [taken (first (sort (filter #(r/match-by-path router %) (keys routes))))]
    (throw (ex-info (str "web-base config :sessionless path " taken " is also one of :routes;"
                         " it would be served without the route's gate, session or CSRF")
                    {:config-key [:sessionless taken]}))))

(defn- with-assets
  "Assets first — the base's `/wb/` before the host's, so a host file cannot
  shadow the base's own — then the host's sessionless routes, then `app` for
  everything else. Only the assets answer conditional GETs with a 304: the
  resource handlers emit `Last-Modified` and nothing else honoured it, so every
  page load re-sent htmx whole."
  [static sessionless app]
  (apply ring/routes
         (remove nil? [(not-modified/wrap-not-modified base-assets)
                       sessionless
                       (when static
                         (not-modified/wrap-not-modified
                          (ring/create-resource-handler (merge {:path "/"} static))))
                       app])))

(defn handler
  "Builds the Ring handler from the host's config:

    :routes       reitit route data; per route `:wb/layouts` and `:wb/gate`
    :session      `{:key base64-or-bytes}` or `{:store s}` (required)
    :subject-fn   request → subject or nil (default: always nil)
    :login-path   where a refusal without a subject goes (required iff a route has :wb/gate)
    :coercion     a reitit coercion, passed through (optional)
    :static       create-resource-handler options for the host's assets (optional)
    :error-layout slot function for error pages (optional)
    :i18n         `{:dict … :default-locale … :locale-fn …}` (optional)
    :security     `{:frame-options … :csp … :hsts … :proxy? …}` (optional)
    :csrf         false to disable the anti-forgery token (on for anything else, nil included)
    :sessionless  `{\"/health\" handler}` — exact paths answered before the session, CSRF,
                  i18n and subject, with the request id and security headers only; a
                  handler is a function or a var, and a path one of :routes also
                  matches is refused (optional)

  Unknown keys are the host's own business. Every failure of a required or
  malformed value is raised here, at construction."
  [{:keys [routes coercion subject-fn login-path static error-layout i18n security csrf sessionless]
    :as   config}]
  (require-key! config :routes)
  (require-key! config :session)
  (validate-sessionless! sessionless)
  ;; Explicit nils — a config map assembled from an absent setting — must not
  ;; switch protection off or leave a function unbound.
  (let [subject-fn   (or subject-fn (constantly nil))
        csrf?        (not (false? csrf))
        render-error (error/renderer {:error-layout error-layout})
        router       (ring/router routes
                                  {:data (cond-> {:middleware [(error/middleware render-error)
                                                               (gate/middleware {:login-path   login-path
                                                                                 :render-error render-error})
                                                               render/middleware
                                                               coercion/coerce-request-middleware]}
                                           coercion (assoc :coercion coercion))})]
    (refuse-shadowing! router sessionless)
    (-> (ring/ring-handler router (error/default-handler render-error))
        (gate/wrap-subject subject-fn)
        (cond-> csrf? (security/wrap-csrf render-error))
        (cond-> i18n (i18n/wrap i18n))
        params/wrap-params
        (session/wrap (:session config))
        (->> (with-assets static (sessionless-handler sessionless render-error)))
        (cond-> (:proxy? security) security/wrap-proxy)
        (security/wrap-headers security)
        log/wrap-request-id)))

(def start
  "`(start handler {:port n})` → `{:server s :port n}`."
  server/start)

(def stop
  "Stops the handle returned by `start`."
  server/stop)
