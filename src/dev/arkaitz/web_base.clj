(ns dev.arkaitz.web-base
  "Public entry point of web-base: the handler that wires the base together and
  the server lifecycle. A library the host calls; it never calls the host back
  except through the functions the host hands it (SPEC §3).

  The wiring convention IS the product, so here it is, outermost first:

    request-id → security headers → proxy (opt-in) → body limit
    → [/wb/ assets → sessionless routes → host static → ] error boundary → session → params
    → i18n → multipart (declared routes only) → csrf → subject
    → ring-handler
        router, per matched route: error → gate → render → coercion → handler
        default handler: 404 / 405 / nil-handler 500

  Static assets answer before the session: a stylesheet fetch must not mint a
  session cookie, and a cookie on an asset defeats shared caches. They still
  carry the request id and the security headers. So do the host's `:sessionless`
  routes — a health probe, a webhook — which read no session and write none, whatever
  cookie arrives. The body limit sits outside all of them, so nothing reads more than
  it allows; multipart sits outside csrf because the token is one of its fields, and
  inside i18n so a refused upload's page speaks the negotiated language. Session sits
  outside subject
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
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.i18n :as i18n]
            [dev.arkaitz.web-base.log :as log]
            [dev.arkaitz.web-base.plugin :as plugin]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.session :as session]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as tools-log]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.util.codec :as codec]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.params :as params]
            [ring.util.request :as req])
  (:import [java.io File]
           [org.apache.commons.fileupload2.core FileUploadException]))

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
  than 200 is returned as it is.

  On an htmx request the page renders as any GET does under htmx: its content without
  its layouts, which lands inside whatever the form targeted. A form that swaps only
  itself, and still works without JavaScript, answers through `refuse-form` instead.

  Throws when `path` has no `:get` route, and when the request already carries
  `:wb/form` — a page that re-rendered into itself would never stop. Not a validation
  helper: the base still knows no schema (SPEC §7).

  **`path` is the host's own route, never input from the request.** Whatever GET it
  names runs as a side effect of this POST, as the caller — a logout, a link's
  redemption — so a path taken from a form field would let whoever submits it choose
  which."
  [request path form]
  (when (contains? request :wb/form)
    (throw (ex-info (str "web-base: rerender: " path " was reached from a rerender already")
                    {:path path})))
  (let [[uri qs] (str/split (str path) #"\?" 2)
        match    (some-> (::r/router request) (r/match-by-path uri))
        handler  (get-in match [:result :get :handler])]
    (when-not handler
      (throw (ex-info (str "web-base: rerender: no GET route for " path) {:path path})))
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

(defn refuse-form
  "The answer to a form the host refused, for a page that works with and without
  JavaScript: an htmx swap gets `fragment` — the form with its errors, drawn by the
  handler — as a 422 (`response/unprocessable`), and a navigation gets the page at
  `path` rendered again with `form` under `:wb/form` (`rerender`, whose rules apply,
  `path` never taken from the request among them).

    (if-let [errors (validate values)]
      (wb/refuse-form request \"/things\" {:values values :errors errors}
                      (thing-form request values errors))
      (wb/form-done request \"/things\" (thing-row saved)))"
  [request path form fragment]
  (if (htmx/partial-request? request)
    (response/unprocessable fragment)
    (rerender request path form)))

(defn form-done
  "The answer to a form the host accepted, for a page that works with and without
  JavaScript: an htmx swap gets `fragment` with a 200, and a navigation a 303 to
  `location` (`response/see-other`), so a reload never posts twice."
  [request location fragment]
  (if (htmx/partial-request? request)
    (response/ok fragment)
    (response/see-other location)))

(defn- require-key! [config k]
  (when (nil? (get config k))
    (throw (ex-info (str "web-base: config needs " k) {:config-key [k]}))))

(def ^:private owned-keys
  "The keys of every map inside the config that the base reads, by path. The top level
  stays open — its unknown keys are the host's — but inside these a key nobody reads is
  a setting the host believes is in force: `:hts` for `:hsts` would otherwise serve
  without HSTS and say nothing. `:static` is reitit 0.10's `create-resource-handler`
  options. `:session`'s `:cookie-attrs` is Ring's, which refuses an unknown attribute on
  its own, at the first cookie it writes."
  {[:session]         #{:key :store :cookie-attrs :cookie-name :renew}
   [:session :renew]  #{:every-ms :absolute-ms}
   [:security]        #{:frame-options :csp :hsts :proxy-hops}
   [:security :hsts]  #{:max-age :include-subdomains?}
   [:i18n]            #{:dict :default-locale :locale-fn :locales}
   ;; `:paths` too: reitit reads it though its docstring does not list it.
   [:static]          #{:parameter :root :path :loader :index-files :index-redirect?
                        :canonicalize-uris? :not-found-handler :mime-types :allow-symlinks? :paths}})

(defn- refuse-unknown-keys!
  "Sorted by printed form so keys of mixed types cannot make the refusal throw."
  [config]
  (doseq [[path allowed] owned-keys
          :let [m (get-in config path)]
          :when (map? m)]
    (when-let [unknown (not-empty (sort-by pr-str (remove allowed (keys m))))]
      (throw (ex-info (str "web-base: unknown key" (when (next unknown) "s") " " (pr-str (vec unknown))
                           " in " (pr-str path) " — it takes " (pr-str (vec (sort allowed))))
                      {:config-key (conj path (first unknown))})))))

(def ^:private base-assets
  (ring/create-resource-handler {:path "/wb/" :root "dev/arkaitz/web_base/public"}))

(defn- sessionless-handler
  "The host's `:sessionless` routes, any method, each inside the base's error middleware
  so a throw renders the base's 500 rather than reaching the server. A path ending in `/`
  answers everything under it — an API mounted beside the pages — and an exact path wins
  over it, the longest such prefix over a shorter one. A nil answer is a 500 too, logged:
  falling through to the next handler would hand the request to the session this mount
  exists to avoid."
  [routes render-error]
  (when (seq routes)
    (let [wrap     (:wrap (error/middleware render-error))
          handlers (update-vals routes wrap)
          prefixes (sort-by (comp - count) (filter #(str/ends-with? % "/") (keys routes)))
          find     (fn [uri] (or (get handlers uri)
                                 (some #(when (str/starts-with? uri %) (get handlers %)) prefixes)))]
      (fn [request]
        (when-let [h (find (:uri request))]
          (or (h request)
              (do (tools-log/error "sessionless handler returned nil" {:request-id (:wb/request-id request)
                                                                :uri        (log/path-of request)})
                  (render-error {:status 500} request))))))))

(defn- validate-sessionless! [routes]
  (when (some? routes)
    (when-not (and (map? routes)
                   (every? #(and (string? %) (str/starts-with? % "/") (not= "/" %) (not (str/starts-with? % "/wb/")))
                           (keys routes))
                   (every? #(or (fn? %) (var? %)) (vals routes)))
      (throw (ex-info (str "web-base: config :sessionless must be a map of path to handler, each path"
                           " starting with / and none under /wb/, which is the base's, nor / itself,"
                           " which would take every page away from the session")
                      {:config-key [:sessionless]})))))

(defn- covers?
  "Whether the sessionless `prefix` answers some path the route `template` matches,
  beyond the prefix itself, which `refuse-shadowing!` has already put to the router:
  segment by segment, a segment holding a parameter anywhere — `:org`, `{org}`,
  `acme-{id}`, `{ns/id}`, whose slash is no boundary — matching any segment. A
  catch-all, always last, needs no case of its own: a prefix that reaches it is a path
  the router matches, and one that stops short covers it as the route's remaining
  segments do. Over-matching only refuses at construction; missing one would open a
  gated page."
  [prefix template]
  (let [wild?    #(or (str/includes? % ":") (str/includes? % "{"))
        segments #(rest (str/split % #"/"))]
    ;; Only the template's braces are a parameter's: the prefix is a literal path, and
    ;; collapsing a `{` in it would misalign its segments.
    (loop [[p & ps :as pre] (segments prefix)
           [t & ts :as tem] (segments (str/replace template #"\{[^}]*\}" "{}"))]
      (cond (empty? pre) (boolean (seq tem))
            (empty? tem) false
            (or (= p t) (wild? t)) (recur ps ts)
            :else false))))

(defn- refuse-shadowing!
  "A sessionless path the router also matches — or a sessionless prefix that covers one
  of the router's routes — would serve that route with no gate, no subject, no session
  and no CSRF: silently, and a gated page would be open. Refused at construction, naming
  the path."
  [router routes]
  (when-let [taken (first (sort (filter (fn [path]
                                          (or (r/match-by-path router path)
                                              (and (str/ends-with? path "/")
                                                   (some #(covers? path %) (map first (r/routes router))))))
                                        (keys routes))))]
    (throw (ex-info (str "web-base: config :sessionless path " taken " is also one of :routes;"
                         " it would be served without the route's gate, session or CSRF")
                    {:config-key [:sessionless taken]}))))

(defn- logged-path
  "What the access line shows for a request's path: the matched route's template for a
  route whose data says `:wb/log-path :template` — a path that carries a secret, such
  as a sign-in token — and the `:uri` otherwise. Matched here, against the router, so
  the answer does not depend on any inner layer having run. Any other value, or the key
  on one method's data where a match cannot see it, is refused naming the route.

  A path that matches no route has no template, and logs as it came: a mistyped or
  truncated link to a marked route — a trailing slash, a segment a scanner appended —
  is a 404 whose line shows the secret it carried."
  [router]
  (doseq [[path data] (r/routes router)]
    (when-not (contains? #{nil :template} (:wb/log-path data))
      (throw (ex-info (str "web-base: route " path " has :wb/log-path " (pr-str (:wb/log-path data))
                           "; the only value is :template")
                      {:config-key [:routes path :wb/log-path]})))
    (doseq [[method method-data] (select-keys data ring/http-methods)
            :when (and (map? method-data) (contains? method-data :wb/log-path))]
      (throw (ex-info (str "web-base: route " path " sets :wb/log-path under " method
                           "; put it on the route's own data, where it covers every method")
                      {:config-key [:routes path method :wb/log-path]}))))
  (fn [request]
    (let [match (r/match-by-path router (:uri request))]
      (if (= :template (get-in match [:data :wb/log-path]))
        (:template match)
        (:uri request)))))

(defn- too-large! [^Throwable e]
  (if (security/body-failure e)
    (throw e)
    (error/throw! {:status 413})))

(def ^:private multipart-keys #{:max-file-size :max-file-count :max-body-bytes})

(defn- multipart-spec
  "`(fn [request] spec)`: the `:wb/multipart` spec of the route a `multipart/form-data`
  request is for, or nil. Only declared routes parse a multipart body — to disk, and
  before CSRF has judged the request, since the token is one of its fields — so an
  anonymous upload to any other path is never written anywhere. Each spec is checked at
  construction: `:max-file-size` required, `:max-file-count` and `:max-body-bytes`
  optional, positive, no other key, and never under one method, where a match would not
  see it. The body limit of a declared route is its `:max-body-bytes`, or its file size
  plus the base's own limit, for the fields beside the file."
  [router default-limit]
  (doseq [[path data] (r/routes router)
          :let [spec (:wb/multipart data)]]
    (when-not (or (nil? spec) (and (map? spec) (every? multipart-keys (keys spec)) (pos-int? (:max-file-size spec))
                   (every? #(or (nil? (get spec %)) (pos-int? (get spec %))) [:max-file-count :max-body-bytes])))
      (throw (ex-info (str "web-base: route " path " has :wb/multipart " (pr-str spec) "; it takes "
                           (pr-str (vec (sort multipart-keys))) ", positive numbers, :max-file-size required")
                      {:config-key [:routes path :wb/multipart]})))
    (doseq [[method method-data] (select-keys data ring/http-methods)
            :when (and (map? method-data) (contains? method-data :wb/multipart))]
      (throw (ex-info (str "web-base: route " path " sets :wb/multipart under " method
                           "; put it on the route's own data")
                      {:config-key [:routes path method :wb/multipart]}))))
  (fn [request]
    (when (= "multipart/form-data" (req/content-type request))
      (when-let [spec (get-in (r/match-by-path router (:uri request)) [:data :wb/multipart])]
        (update spec :max-body-bytes #(or % (+ (:max-file-size spec) default-limit)))))))

(defn- request-store
  "A multipart store writing each file to a temporary file of its own, noted in
  `created` so the request can delete it when it ends. Ring's own store keeps them for
  an hour, and the parse runs before CSRF or any gate has judged the request, so an
  anonymous POST to a declared route would leave its upload on disk that long."
  [created]
  (fn [item]
    (let [file (File/createTempFile "wb-upload-" nil)]
      (swap! created conj file)
      (io/copy (:stream item) file)
      (-> (select-keys item [:filename :content-type])
          (assoc :tempfile file :size (.length file))))))

(defn- delete-all! [created]
  (doseq [^File file @created]
    (when-not (or (.delete file) (not (.exists file)))
      (tools-log/warn "an uploaded temporary file could not be deleted" {:file (.getPath file)}))))

(defn- wrap-multipart
  "Parses the multipart body of a request whose route declared `:wb/multipart`, with its
  limits, into `:multipart-params` and `:params`, where CSRF then reads its token. A part
  too large, too many, or a body past the route's limit is the host's 413 page; a body
  the client broke off answers as the base's body limit does. Uploaded files live for
  the request: they are deleted once the response is returned, so a handler moves or
  copies what it keeps before answering, and never answers with the file itself."
  [handler spec-for render-error]
  (let [parse ((:wrap (error/middleware render-error))
               (fn [{::keys [spec created] :as request}]
                 (try {::parsed (multipart/multipart-params-request
                                 (dissoc request ::spec ::created)
                                 (assoc (select-keys spec [:max-file-size :max-file-count])
                                        :store (request-store created)))}
                      ;; What Ring's own middleware answers 413 for: a part or body past
                      ;; its limit (FileUploadException) and its own refusals, "Max file
                      ;; count exceeded" among them, which it throws as ex-info. The body's
                      ;; own failures, read through this parse, keep their status.
                      (catch FileUploadException e (too-large! e))
                      (catch clojure.lang.ExceptionInfo e (too-large! e)))))]
    (fn [request]
      (if-let [spec (spec-for request)]
        (let [created (atom [])]
          (try
            (let [answer (parse (assoc request ::spec spec ::created created))]
              (if-let [parsed (::parsed answer)]
                (handler parsed)
                answer))
            (finally (delete-all! created))))
        (handler request)))))

(defn- with-assets
  "Assets first — the base's `/wb/` before any other, so nothing can shadow the base's
  own, then each `:assets` root a plugin or the host named, then the host's sessionless
  routes, then its `:static`, then `app` for everything else. Only the assets answer
  conditional GETs with a 304: the resource handlers emit `Last-Modified` and nothing
  else honoured it, so every page load re-sent htmx whole."
  [assets static sessionless app]
  (apply ring/routes
         (remove nil? [(not-modified/wrap-not-modified base-assets)
                       (when (seq assets)
                         (apply ring/routes
                                (for [asset assets]
                                  (not-modified/wrap-not-modified (ring/create-resource-handler asset)))))
                       sessionless
                       (when static
                         (not-modified/wrap-not-modified
                          (ring/create-resource-handler (merge {:path "/"} static))))
                       app])))

(def ^:private local-href
  "A path on this origin. A browser reads a backslash as a slash and drops tabs and
  newlines, so `/\\evil.example/x.css` is `//evil.example/x.css`: another origin's."
  #"/[^/\\\s\p{Cntrl}][^\\\s\p{Cntrl}]*")

(defn- other-origin?
  "Whether a browser takes `href` to another origin, read as it reads one: spaces and
  controls trimmed from the start, tabs and newlines dropped, a backslash taken for a
  slash — then a scheme, or two slashes. The end is never trimmed: only the start
  decides."
  [href]
  (let [read (-> href
                 (str/replace #"^[\x00-\x20]+" "")
                 (str/replace #"[\t\n\r]" "")
                 (str/replace "\\" "/"))]
    (boolean (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)" read))))

(defn- route-vector? [x] (and (vector? x) (string? (first x))))

(defn- check-method-gates!
  "A `:wb/gate` under a method of a route that has children is refused (since 0.16.0):
  reitit merges a parent's method data into its children's endpoints, where a child's
  handler or method gate replaces it — a child declaring nothing, `:get handler`, wipes
  the parent's `:get` map whole — so a gate there could be widened by any route under it.
  On a route with children the gate goes on the route's own data, where it composes."
  [routes]
  (letfn [(walk [x]
            (cond
              (route-vector? x)
              (let [[path & more] x
                    data     (when (map? (first more)) (first more))
                    ;; Any sequence: reitit mounts children written as a list or made by
                    ;; `for`, and a check that saw vectors alone would miss them.
                    children (filter sequential? (if data (rest more) more))]
                (when (and (seq children)
                           (some #(some? (get-in data [% :wb/gate])) ring/http-methods))
                  (throw (ex-info (str "web-base: route " path " declares :wb/gate under a method and has routes"
                                       " under it, which could widen it — put the gate on the route's own data")
                                  {:config-key [:routes] :path path})))
                (run! walk children))
              (sequential? x) (run! walk x)))]
    (walk routes)))

(defn- check-gates-wired!
  "Every endpoint whose data carries a `:wb/gate` has the gate's middleware: a route's
  `:middleware ^:replace […]` replaces the base's four, the gate's among them, and the
  gate would be declared and never asked (since 0.16.0)."
  [router]
  (doseq [[path data result] (r/compiled-routes router)
          method ring/http-methods
          :let [endpoint (get result method)]
          :when (and endpoint (some? (get-in endpoint [:data :wb/gate])))]
    (when-not (some #(= ::gate/gate (:name %)) (get-in endpoint [:data :middleware]))
      (throw (ex-info (str "web-base: route " path " declares :wb/gate but its middleware no longer has the"
                           " gate — a :middleware ^:replace drops the base's own")
                      {:config-key [:routes] :path path})))))

(defn- check-login-path!
  "With a gate anywhere, the `:login-path` it sends people to must be a page: a route of
  this router that answers GET and is not gated itself. Otherwise every refusal is a
  redirect to a 404, or a loop — silent until somebody signs out (since 0.13.0). A plugin
  that mounts the login page and a host `:login-path` that wins over the plugin's while
  its routes stay put is how it happens. A gate on a route or on one of its methods
  counts. A login page on another origin — a single sign-on's — is not this router's to
  check, and passes."
  [router login-path]
  (when (and (string? login-path)
             (not (other-origin? login-path))
             (some (fn [[_ data]]
                     (or (some? (:wb/gate data))
                         (some #(some? (get-in data [% :wb/gate])) ring/http-methods)))
                   (r/routes router)))
    (let [path  (first (str/split login-path #"[?#]" 2))
          match (r/match-by-path router path)]
      (when-not (and match (get-in match [:result :get]))
        (throw (ex-info (str "web-base: :login-path " login-path " is not a page of this router — no route"
                             " answers GET there, so every gated refusal would lead nowhere")
                        {:config-key [:login-path]})))
      (when (some? (or (get-in match [:data :wb/gate]) (get-in match [:result :get :data :wb/gate])))
        (throw (ex-info (str "web-base: :login-path " login-path " is itself gated, so a refusal would"
                             " redirect to a page that refuses too")
                        {:config-key [:login-path]}))))))

(defn- check-assets!
  "Every asset root well formed, no path twice, none covering a route or a sessionless
  path — a stylesheet root that swallowed a page would serve it without its gate — and
  every stylesheet a local path, given once. A root matches the decoded path while the
  router matches the raw one, so `/p%2Fx` reaches a `/p/` root rather than a `/:x` route:
  it can only ever answer with a file of that root, never a page."
  [assets stylesheets router sessionless]
  (doseq [[i asset] (map-indexed vector assets)]
    (plugin/check-asset! asset [:assets i]))
  (when-let [twice (first (for [[p c] (frequencies (map :path assets)) :when (< 1 c)] p))]
    (throw (ex-info (str "web-base: asset path " twice " is given twice") {:config-key [:assets twice]})))
  (doseq [{:keys [path]} assets]
    (when (or (some #(covers? path %) (map first (r/routes router)))
              (r/match-by-path router path)
              (some #(str/starts-with? % path) (keys sessionless)))
      (throw (ex-info (str "web-base: asset path " path " covers one of :routes or :sessionless; it would"
                           " serve files where a page or a probe answers")
                      {:config-key [:assets path]}))))
  (when-not (and (or (nil? stylesheets) (sequential? stylesheets))
                 (every? #(and (string? %) (re-matches local-href %)) stylesheets))
    (throw (ex-info (str "web-base: :stylesheets is a vector of local paths, each starting with one /"
                         " and holding no backslash, space or control character — never another origin's")
                    {:config-key [:stylesheets]})))
  (when-let [twice (first (for [[p c] (frequencies stylesheets) :when (< 1 c)] p))]
    (throw (ex-info (str "web-base: stylesheet " twice " is given twice") {:config-key [:stylesheets twice]}))))

(defn- wrap-stylesheets
  "Puts `:wb/stylesheets` on every request, outermost, so the shell links them on a page
  and on an error page alike."
  [handler stylesheets]
  (let [sheets (vec stylesheets)]
    (fn [request] (handler (assoc request :wb/stylesheets sheets)))))

(defn handler
  "Builds the Ring handler from the host's config:

    :routes       reitit route data; per route `:wb/layouts` and `:wb/gate`, both
                  inherited by nested routes (layouts concatenate, a child's gate composes with its parent's);
                  `:wb/log-path :template` logs the route's template instead of its path,
                  for a path that carries a secret; `:wb/multipart {:max-file-size n …}`
                  parses a file upload for that route alone
    :session      `{:key base64-or-bytes}` or `{:store s}` (required); `:renew
                  {:every-ms n :absolute-ms m}` slides the session (since 0.15.0, see
                  `session/wrap`)
    :subject-fn   request → subject or nil (default: always nil)
    :login-path   where a refusal without a subject goes (required iff a route has :wb/gate)
    :coercion     a reitit coercion, passed through (optional)
    :static       create-resource-handler options for the host's assets (optional)
    :error-layout slot function for error pages (optional)
    :i18n         `{:dict … :default-locale … :locales […] :locale-fn …}` (optional;
                  `:locales` since 0.12.0, the default alone when absent)
    :security     `{:frame-options … :csp … :hsts {:max-age seconds} :proxy-hops n}` (optional)
    :csrf         false to disable the anti-forgery token (on for anything else, nil included)
    :sessionless  `{\"/health\" handler \"/api/\" handler}` — answered before the session,
                  CSRF, i18n and subject, with the request id, security headers and body
                  limit only; a path ending in `/` takes everything under it, an exact
                  path winning; a handler is a function or a var; a path or prefix that
                  covers one of :routes is refused, and so is `/` (optional)
    :max-body-bytes  the largest request body read, 200 000 by default; a route's
                  `:wb/multipart` sets its own (optional)
    :assets       `[{:path \"/name/\" :root \"classpath/prefix\"}]`, served beside `/wb/`,
                  before the session (optional)
    :stylesheets  `[\"/app.css\"]`, linked by the shell after the base's own (optional)
    :plugins      values that contribute these same keys, merged by `expand` (optional)

  Unknown keys are the host's own business — all but the names above, `:assets`,
  `:stylesheets` and `:plugins` among them since 0.11.0. Inside the maps the base owns —
  `:session`, `:security` and its `:hsts`, `:i18n`, `:static` — an unknown key is
  refused, naming its path. Every failure of a required or malformed value is raised
  here, at construction."
  [config]
  (let [{:keys [routes coercion subject-fn login-path static error-layout i18n security csrf sessionless
                max-body-bytes assets stylesheets]
         :as   config} (plugin/expand config)]
  (require-key! config :routes)
  (require-key! config :session)
  (when (contains? security :proxy?)
    (throw (ex-info (str "web-base: :security :proxy? took the first X-Forwarded-For entry, which the client"
                         " writes; say :proxy-hops 1 behind one proxy that appends (nginx, a cloud load"
                         " balancer), 2 behind a CDN and a load balancer")
                    {:config-key [:security :proxy?]})))
  (when-not (or (nil? (:proxy-hops security)) (pos-int? (:proxy-hops security)))
    (throw (ex-info "web-base: :security :proxy-hops must be a positive number of proxies"
                    {:config-key [:security :proxy-hops] :value (:proxy-hops security)})))
  (when-not (or (nil? max-body-bytes) (pos-int? max-body-bytes))
    (throw (ex-info "web-base: :max-body-bytes must be a positive number of bytes"
                    {:config-key [:max-body-bytes] :value max-body-bytes})))
  (refuse-unknown-keys! config)
  (validate-sessionless! sessionless)
  ;; Explicit nils — a config map assembled from an absent setting — must not
  ;; switch protection off or leave a function unbound.
  (let [subject-fn   (or subject-fn (constantly nil))
        csrf?        (not (false? csrf))
        render-error (error/renderer {:error-layout error-layout})
        ;; Outside the session, i18n and subject layers the host's error layout would
        ;; be handed a request it was never written for — no translations, no session —
        ;; and a layout that throws there has nothing left to catch it (and must not be
        ;; asked to render its own failure). The base's own page asks for nothing.
        bare-error   (error/renderer {})
        ;; Before the router: reitit refuses some of these trees for its own reasons,
        ;; which would hide this one's.
        _            (check-method-gates! routes)
        router       (ring/router routes
                                  {:meta-merge gate/merge-route-data
                                   :data (cond-> {:middleware [(error/middleware render-error)
                                                               (gate/middleware {:login-path   login-path
                                                                                 :render-error render-error})
                                                               render/middleware
                                                               coercion/coerce-request-middleware]}
                                           coercion (assoc :coercion coercion))})]
    (refuse-shadowing! router sessionless)
    (check-gates-wired! router)
    (check-login-path! router login-path)
    (check-assets! assets stylesheets router sessionless)
    (let [body-limit    (or max-body-bytes security/default-max-body-bytes)
          multipart-for (multipart-spec router body-limit)]
    (-> (ring/ring-handler router (error/default-handler render-error))
        (gate/wrap-subject subject-fn)
        (cond-> csrf? (security/wrap-csrf render-error))
        (wrap-multipart multipart-for render-error)
        (cond-> i18n (i18n/wrap i18n))
        params/wrap-params
        (session/wrap (:session config))
        ;; The outer error boundary: what throws in the session store, params, i18n,
        ;; csrf or the subject function — a database that is down, above all — is the
        ;; base's rendered 500, not a raw exception at the adapter. Outside i18n, so it
        ;; renders without a negotiated locale.
        ((:wrap (error/middleware bare-error)))
        (->> (with-assets assets static (sessionless-handler sessionless bare-error)))
        ;; Outside the assets and the sessionless routes, so no path reads a body the
        ;; limit has not seen. A body read past it throws the 413 datum wherever it is
        ;; read, and the error boundary around the reader renders it.
        (security/wrap-body-limit #(or (:max-body-bytes (multipart-for %)) body-limit) bare-error)
        (cond-> (:proxy-hops security) (security/wrap-proxy (:proxy-hops security)))
        (security/wrap-headers security)
        (log/wrap-request-id (logged-path router))
        (cond-> (seq stylesheets) (wrap-stylesheets stylesheets)))))))

(def translator
  "`(translator i18n-config prefs)` → the translate function `:wb/tr` is, for words
  outside a request — a job's mail. `i18n-config` is `(:i18n (expand config))`."
  i18n/translator)

(def expand
  "`(expand config)` → the plain config its `:plugins` stand for, merged by the rules of
  `dev.arkaitz.web-base.plugin` — what `handler` builds from, to read at the REPL."
  plugin/expand)

(def redirect-for
  "`(redirect-for request path)`: a 303 for a navigation, an `HX-Redirect` for an htmx
  swap, uncached — how the gate sends a refused visitor away, for a host's own detour."
  gate/redirect-for)

(def start
  "`(start handler {:port n})` → `{:server s :port n}`."
  server/start)

(def stop
  "Stops the handle returned by `start`."
  server/stop)
