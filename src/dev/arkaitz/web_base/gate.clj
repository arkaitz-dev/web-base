(ns dev.arkaitz.web-base.gate
  "The gate on private pages (SPEC §5, §9). The base knows that a request may
  carry a subject and nothing about what a subject is: the host hands it a
  function from request to subject-or-nil, and per route a predicate over the
  request. What the base owns is the translation of a refusal into the right
  outcome for the kind of request — a `303` for a navigation, so a refused
  POST is never re-posted to the login page; an `HX-Redirect` for an htmx swap
  so the login page never lands inside a `div`; and a 403 error datum when
  there is a subject and the predicate still says no. No `401`: a proper one
  needs `WWW-Authenticate`, and only whoever authenticates knows the scheme."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.security :as security]
            [meta-merge.core :as mm]))

(defn subject-present?
  "The stock predicate: there is a subject. Presence is all the base ever
  checks; it never looks inside, so `false` is a subject like any other."
  [request]
  (some? (:wb/subject request)))

(defn wrap-subject
  "Puts `(subject-fn request)` on every request as `:wb/subject`, gated route
  or not — a public page's layout also paints the identity corner.

  Eager on purpose (decided 2026-09-27 by a five-lens panel, measured): with
  auth-base the call is one indexed read of the revocation generation per signed-in
  request, 9.7 µs on SQLite, after the session read the store already made; it is
  wasted only on responses that paint no identity, and it is where revocation takes
  effect. A lazy value was rejected — a `delay` is truthy, so `subject-present?` would
  admit anyone — and so were a per-route opt-out (the default 404 has no route) and a
  cached generation (revocation within N seconds instead of at the next request). A
  route that needs no subject at all belongs in `:sessionless`.

  A response to a request with a subject, or one that writes the session, gets
  `Cache-Control: no-store` unless the handler said otherwise: it is somebody's own."
  [handler subject-fn]
  (fn [request]
    (let [subject  (subject-fn request)
          response (handler (assoc request :wb/subject subject))]
      (cond-> response
        (or (some? subject) (contains? response :session)) security/private))))

(def refusal-headers
  "A refusal depends on the session and on the kind of request: never cached,
  and varying on the htmx headers like every response whose shape does. Public for
  `testing/gate-refusal?`, which recognises a refusal by them."
  {"Vary" htmx/vary "Cache-Control" "no-store"})

(defn redirect-for
  "`request` sent to `path` the way the base sends a refused request to its login page:
  a 303 for a navigation, an `HX-Redirect` for an htmx swap (a 303 there would be
  followed and swapped into the target), uncached and varying on the htmx headers. For
  a detour of the host's own — no organisation chosen yet — as route middleware after
  the base's gate, which has already checked that somebody is signed in."
  [request path]
  (if (htmx/partial-request? request)
    (update (htmx/redirect path) :headers merge refusal-headers)
    {:status  303
     :headers (assoc refusal-headers "Location" path)
     :body    ""}))

(def ^:private max-next
  "The longest `next` the refusal carries, encoded: past it the refusal goes to the
  login page alone. The page's URL, encoded, roughly triples, and Jetty's 8 KB response
  header buffer answered a 6000-character query with a 500 (measured); 2048 keeps the
  whole `Location` far inside it, and is longer than any page worth coming back to."
  2048)

(defn- with-next
  "`login-path` carrying, as `next`, the page a refused navigation asked for, so the
  sign-in can return there: only a GET or HEAD that is not a swap has such a page, and
  only one whose address encodes within `max-next`. The path is the request's own,
  local by construction; whoever reads `next` back must still check it, since anybody
  can type one."
  [login-path request]
  (let [next (when (and (#{:get :head} (:request-method request)) (not (htmx/partial-request? request)))
               (java.net.URLEncoder/encode (str (:uri request) (some->> (:query-string request) (str "?"))) "UTF-8"))]
    (if (and next (<= (count next) max-next))
      (str login-path (if (str/includes? login-path "?") "&" "?") "next=" next)
      login-path)))

(defn- refuse [request {:keys [login-path render-error]}]
  (if (subject-present? request)
    (render-error {:status 403} request)
    (redirect-for request (with-next login-path request))))

(defn- parts [gate] (or (:wb/gates (meta gate)) [gate]))

(defn compose
  "A gate admitting what both `parent` and `child` admit, the parent asked first. It
  carries every predicate it is made of under `:wb/gates` in its metadata, flattened,
  so a test can name the gates a route is guarded by: a composed gate is identical to
  none of them."
  [parent child]
  (with-meta (fn [request] (and (parent request) (child request)))
    {:wb/gates (into (parts parent) (parts child))}))

(defn middleware
  "reitit middleware compiled per route, over the route's data as reitit merged it
  from its parents — so a gate on a parent guards every child, a child's own gate
  composes with it — both must admit, so a child can narrow a parent's gate and never
  widen it — and a child's nil leaves the parent's in place. It vanishes from routes
  whose merged data has no `:wb/gate` (absent or nil), and fails at router construction when the gate
  is present but not callable — `false` would otherwise open a route without
  a symptom — or when no `:login-path` is configured, which would otherwise
  be a 500 on the first refused request. reitit hands the compile step the
  route data, not the path, so the errors name the config key."
  [{:keys [login-path] :as opts}]
  {:name    ::gate
   :compile (fn [{:wb/keys [gate]} _router-opts]
              (when (some? gate)
                (when-not (ifn? gate)
                  (throw (ex-info "web-base: a route declares :wb/gate that is not callable"
                                  {:config-key [:wb/gate] :value gate})))
                (when (str/blank? login-path)
                  (throw (ex-info "web-base: a route declares :wb/gate but no :login-path is configured"
                                  {:config-key [:login-path]})))
                (fn [handler]
                  (fn [request]
                    (if (gate request)
                      (handler request)
                      (refuse request opts))))))})

(defn merge-route-data
  "reitit's merge of route data, parent first, except that two gates compose: a child's
  `:wb/gate` narrows its parent's and never replaces it, so one line under a members-only
  group cannot open it to anyone signed in (decided 2026-10-08, booking FRICTION B17). A
  gate that is not callable stays where it is, so the compile step refuses it — a
  parent's `false` is not quietly replaced by a child's function — and a child's nil
  leaves the parent's in place. Public for a test that compiles routes as the base does:
  `testing/router`."
  [left right]
  (let [parent (get left :wb/gate)
        child  (get right :wb/gate)]
    (cond
      (and (ifn? parent) (ifn? child))
      (assoc (mm/meta-merge (dissoc left :wb/gate) (dissoc right :wb/gate)) :wb/gate (compose parent child))
      (and (some? parent) (not (ifn? parent)) (some? child))
      (assoc (mm/meta-merge left right) :wb/gate parent)
      :else
      (mm/meta-merge left right))))
