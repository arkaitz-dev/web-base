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
            [dev.arkaitz.web-base.htmx :as htmx]))

(defn subject-present?
  "The stock predicate: there is a subject. Presence is all the base ever
  checks; it never looks inside, so `false` is a subject like any other."
  [request]
  (some? (:wb/subject request)))

(defn wrap-subject
  "Puts `(subject-fn request)` on every request as `:wb/subject`, gated route
  or not — a public page's layout also paints the identity corner."
  [handler subject-fn]
  (fn [request]
    (handler (assoc request :wb/subject (subject-fn request)))))

(def ^:private refusal-headers
  "A refusal depends on the session and on the kind of request: never cached,
  and varying on the htmx headers like every response whose shape does."
  {"Vary" htmx/vary "Cache-Control" "no-store"})

(defn- refuse [request {:keys [login-path render-error]}]
  (cond
    (subject-present? request)
    (render-error {:status 403} request)

    (htmx/partial-request? request)
    (update (htmx/redirect login-path) :headers merge refusal-headers)

    :else
    {:status  303
     :headers (assoc refusal-headers "Location" login-path)
     :body    ""}))

(defn middleware
  "reitit middleware compiled per route: it vanishes from routes without
  `:wb/gate` (absent or nil), and fails at router construction when the gate
  is present but not callable — `false` would otherwise open a route without
  a symptom — or when no `:login-path` is configured, which would otherwise
  be a 500 on the first refused request. reitit hands the compile step the
  route data, not the path, so the errors name the config key."
  [{:keys [login-path] :as opts}]
  {:name    ::gate
   :compile (fn [{:wb/keys [gate]} _router-opts]
              (when (some? gate)
                (when-not (ifn? gate)
                  (throw (ex-info "a route declares :wb/gate that is not callable"
                                  {:config-key [:wb/gate] :value gate})))
                (when (str/blank? login-path)
                  (throw (ex-info "a route declares :wb/gate but no :login-path is configured"
                                  {:config-key [:login-path]})))
                (fn [handler]
                  (fn [request]
                    (if (gate request)
                      (handler request)
                      (refuse request opts))))))})
