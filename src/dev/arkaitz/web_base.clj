(ns dev.arkaitz.web-base
  "Public entry point of web-base: the handler that wires the base together and
  the server lifecycle. A library the host calls; it never calls the host back
  except through the functions the host hands it (SPEC §3).

  The wiring convention IS the product, so here it is, outermost first:

    request-id → security headers → proxy (opt-in)
    → [/wb/ assets → host static → ] session → params → i18n → csrf → subject
    → ring-handler
        router, per matched route: error → gate → render → coercion → handler
        default handler: 404 / 405 / nil-handler 500

  Static assets answer before the session: a stylesheet fetch must not mint a
  session cookie, and a cookie on an asset defeats shared caches. They still
  carry the request id and the security headers. Session sits outside subject
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
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.middleware.params :as params]))

(def subject-present?
  "The stock gate predicate."
  gate/subject-present?)

(defn- require-key! [config k]
  (when (nil? (get config k))
    (throw (ex-info (str "web-base config needs " k) {:config-key [k]}))))

(def ^:private base-assets
  (ring/create-resource-handler {:path "/wb/" :root "dev/arkaitz/web_base/public"}))

(defn- with-assets
  "Assets first — the base's `/wb/` before the host's, so a host file cannot
  shadow the base's own — then `app` for everything else."
  [static app]
  (apply ring/routes
         (remove nil? [base-assets
                       (when static (ring/create-resource-handler (merge {:path "/"} static)))
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

  Unknown keys are the host's own business. Every failure of a required or
  malformed value is raised here, at construction."
  [{:keys [routes coercion subject-fn login-path static error-layout i18n security csrf]
    :as   config}]
  (require-key! config :routes)
  (require-key! config :session)
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
    (-> (ring/ring-handler router (error/default-handler render-error))
        (gate/wrap-subject subject-fn)
        (cond-> csrf? (security/wrap-csrf render-error))
        (cond-> i18n (i18n/wrap i18n))
        params/wrap-params
        (session/wrap (:session config))
        (->> (with-assets static))
        (cond-> (:proxy? security) security/wrap-proxy)
        (security/wrap-headers security)
        log/wrap-request-id)))

(def start
  "`(start handler {:port n})` → `{:server s :port n}`."
  server/start)

(def stop
  "Stops the handle returned by `start`."
  server/stop)
