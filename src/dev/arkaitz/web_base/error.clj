(ns dev.arkaitz.web-base.error
  "Errors as data with three renderings (SPEC §9): a full page, a fragment for
  an htmx swap, or plain text when the client does not accept HTML. A datum is
  `{:status n}` plus optional `:title` and `:detail` strings; a coercion
  failure adds `:wb/coercion`, the humanized explanation as data. The base's
  own datums carry a status and structured data, never prose — it knows no
  language (SPEC §14); words come from the host's `:error-layout`, which
  receives the request and with it whatever the host put there.

  The default error page is self-contained on purpose: a layout that just
  threw must not be asked to render its own failure."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.render :as render]
            [reitit.coercion :as coercion]
            [reitit.core :as r]))

(def vary
  "Error responses vary on the htmx headers and on `Accept`."
  (str htmx/vary ", Accept"))

(defn- check-datum!
  "A datum without an integer status would fail much later, inside the server,
  with nothing pointing back at the host code that built it."
  [datum]
  (when-not (int? (:status datum))
    (throw (ex-info "error datum has no integer :status" {:datum datum}))))

(defn throw!
  "Throws `datum` as an exception the middleware turns back into a rendered
  error. For a handler that already holds the datum, returning the rendered
  response is the shorter path; this is for code deeper down."
  [datum]
  (check-datum! datum)
  (throw (ex-info (str "web-base error " (:status datum))
                  (assoc datum :type ::error))))

(defn- accepts-html?
  "A missing Accept means anything. Substring matching, q-values ignored: only
  an explicit list naming neither text/html nor a wildcard asks for something
  else."
  [request]
  (let [accept (get-in request [:headers "accept"])]
    (or (str/blank? accept)
        (str/includes? accept "text/html")
        (str/includes? accept "text/*")
        (str/includes? accept "*/*"))))

(defn fragment
  "The htmx rendering: what lands inside the target. `data-status` carries the
  status for the host's CSS and scripts."
  [{:keys [status title detail]}]
  [:div.wb-error {:data-status status}
   [:strong.wb-error-status status]
   (when title [:span.wb-error-title title])
   (when detail [:p.wb-error-detail detail])])

(defn- default-page [{:keys [content error request]}]
  [:html {:lang (:wb/locale request)}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (:status error)]]
   [:body content]])

(defn- text-body [{:keys [status title detail]}]
  (str/join "\n" (remove nil? [(str status (when title (str " " title))) detail])))

(defn renderer
  "Returns `(fn [datum request] response)`. `:error-layout`, when given, is a
  layout like any other — a function of a slot map — and receives `:content`
  (the fragment), `:request` and `:error` (the datum); it is used only for the
  page rendering."
  [{:keys [error-layout]}]
  (let [page    (or error-layout default-page)
        ;; An error may depend on the session (a 403) and is never worth
        ;; caching; a shared cache serving one to the next visitor is a leak.
        headers {"Vary" vary "Cache-Control" "no-store"}]
    (fn [{:keys [status] :as datum} request]
      (check-datum! datum)
      (cond
        (htmx/partial-request? request)
        {:status  status
         :headers (assoc headers "Content-Type" "text/html; charset=utf-8")
         :body    (render/html (fragment datum))}

        (not (accepts-html? request))
        {:status  status
         :headers (assoc headers "Content-Type" "text/plain; charset=utf-8")
         :body    (text-body datum)}

        :else
        {:status  status
         :headers (assoc headers "Content-Type" "text/html; charset=utf-8")
         :body    (str render/doctype
                       (render/html (page {:content (fragment datum)
                                           :request request
                                           :error   datum})))}))))

(defn- coercion-data
  "Only the humanized explanation leaves the base, as data under
  `:wb/coercion` for the host's layout to word: the raw ex-data carries the
  whole request (cookies, session) and the value the user typed. The
  explanation itself names the keys of the offending map, never its values."
  [e]
  (try
    (:humanized (coercion/encode-error (ex-data e)))
    ;; Throwable: this runs inside the middleware's own catch, so anything
    ;; escaping here would leave the middleware entirely. The degradation is
    ;; logged so a bare 400 in production is not a mystery.
    (catch Throwable t
      (log/warn t "coercion explanation could not be encoded")
      nil)))

(defn- datum-of [^Throwable e request]
  (let [data (ex-data e)]
    (case (:type data)
      ::error
      (dissoc data :type)

      :reitit.coercion/request-coercion
      (if-let [explanation (coercion-data e)]
        {:status 400 :wb/coercion explanation}
        {:status 400})

      (do (log/error e "unhandled exception" {:request-id (:wb/request-id request)
                                               :uri        (:uri request)})
          {:status 500}))))

(defn middleware
  "reitit middleware that turns any throw into a rendered error. Unexpected
  throwables are logged with their stack trace and answered with a bare 500:
  the message never reaches the client."
  [render-error]
  {:name ::exception
   :wrap (fn [handler]
           (fn [request]
             (try
               (handler request)
               (catch Throwable e
                 (render-error (datum-of e request) request)))))})

(defn- allowed-methods
  "The `Allow` value a 405 must carry (RFC 9110 §15.5.6), from the route's
  compiled methods. reitit adds an OPTIONS endpoint to every route by default,
  so OPTIONS is listed unless the host disabled that."
  [result]
  (->> result
       (keep (fn [[method handler]] (when handler method)))
       (map (comp str/upper-case name))
       sort
       (str/join ", ")))

(defn default-handler
  "The handler behind the router: 404 for no route, 405 with `Allow` for a
  route without the method, and 500 — logged — when the matched handler
  answered nil, which is a programming error, not content negotiation.
  Router middleware never runs here, so it renders on its own."
  [render-error]
  (fn [request]
    (if-let [match (::r/match request)]
      ;; reitit fills every method for a route declared with a bare :handler,
      ;; so a matched route with no entry for the method means 405.
      (let [result (:result match)]
        (if (get result (:request-method request))
          (do (log/error "handler returned nil" {:request-id (:wb/request-id request)
                                                 :uri        (:uri request)})
              (render-error {:status 500} request))
          (assoc-in (render-error {:status 405} request)
                    [:headers "Allow"] (allowed-methods result))))
      (render-error {:status 404} request))))
