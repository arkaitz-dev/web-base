(ns dev.arkaitz.web-base.testing
  "Helpers for a host's own test suite, over plain Ring maps and rendered
  bodies. Nothing here needs ring-mock or any other library, so it ships in
  the jar. The shapes parsed are the base's own — the cookies Ring's session
  middleware sets, the CSRF token the shell and `security/csrf-field` emit —
  which is why the base owns these readers rather than every host's tests."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.security :as security]))

(defn- header-values
  "The values of header `name` in `headers`, whatever the case of the key and
  whether Ring holds one string or several."
  [headers name]
  (let [wanted (str/lower-case name)
        values (some (fn [[k v]] (when (= wanted (str/lower-case (str k))) v)) headers)]
    (cond (nil? values)    []
          (string? values) [values]
          :else            (vec values))))

(defn- pair
  "`[name value]` of one `name=value` fragment; nil without a name."
  [fragment]
  (let [[name value] (str/split (str/trim fragment) #"=" 2)]
    (when (seq name) [name (or value "")])))

(defn- deletion?
  "A `Max-Age` of zero or less among a cookie's attributes: the browser
  forgets the cookie instead of keeping it."
  [attributes]
  (boolean (some (fn [attribute]
                   (when-let [[name value] (pair attribute)]
                     (when (= "max-age" (str/lower-case name))
                       (try (<= (Long/parseLong value) 0)
                            (catch NumberFormatException _ false)))))
                 attributes)))

(defn cookies
  "Every cookie `response` sets, name to value, the way a browser keeps
  them: a deletion (`Max-Age` of zero or less) is nil, a cookie to forget."
  [response]
  (into {}
        (keep (fn [header]
                (let [[cookie & attributes] (str/split header #";")]
                  (when-let [[name value] (pair cookie)]
                    [name (when-not (deletion? attributes) value)]))))
        (header-values (:headers response) "Set-Cookie")))

(defn- request-cookies [request]
  (into {} (keep pair) (str/split (str (first (header-values (:headers request) "cookie"))) #";")))

(defn with-cookies
  "`request` carrying the cookies it already had and every one `response`
  set, the way a browser follows a redirect: a cookie set again replaces the
  old value, a deletion forgets it. The second request of a login test, the
  swap after a page. Unchanged when the jar does not change."
  [request response]
  (let [before (into (sorted-map) (request-cookies request))
        after  (into (sorted-map) (remove (comp nil? val)) (merge before (cookies response)))]
    (cond (= before after) request
          (empty? after)   (update request :headers dissoc "cookie")
          :else            (assoc-in request [:headers "cookie"]
                                     (str/join "; " (map (fn [[name value]] (str name "=" value)) after))))))

(def ^:private hidden-field
  "The input `security/csrf-field` renders, in any attribute order."
  #"<input[^>]*__anti-forgery-token[^>]*>")

(def ^:private field-value #"\bvalue=\"([^\"]*)\"")

(def ^:private header-attribute
  "The token inside the shell's `hx-headers:inherited` JSON, as Hiccup
  escapes it into the attribute."
  (re-pattern (str (java.util.regex.Pattern/quote security/csrf-header)
                   "&quot;:&quot;([^&]+)&quot;")))

(defn csrf-token
  "The CSRF token a rendered `response` carries, from the hidden field of a
  classic form or from the shell's `<body>` attribute; nil when the body is
  not a string or holds neither."
  [response]
  (let [body (:body response)]
    (when (string? body)
      (or (some->> (re-find hidden-field body) (re-find field-value) second)
          (second (re-find header-attribute body))))))

(defn fragment
  "`request` as htmx sends it for a swap, so the base renders a fragment —
  even a request already marked as a whole-document one."
  [request]
  (update request :headers merge htmx/fragment-headers))

;; --- a browser ----------------------------------------------------------------

(defn browser
  "A browser over `handler`, as a value: an empty cookie jar, no CSRF token yet, no
  page. `visit` answers the browser after a request; nothing is mutated, so a test can
  keep two tabs of one person by holding two values.

  Why the base ships it: every host's suite wrote the same one, and the first lost an
  afternoon to the one trap it has — a jar chained from the last response alone drops
  the session cookie at the first page that sets none, so every request after the
  login looks signed out. This one accumulates."
  [handler]
  {:handler handler :jar {} :token nil :response nil :path nil})

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- form-body [params]
  (str/join "&" (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode v))) params)))

(defn- split-path
  "`[uri query-string]` of a path, or of the path part of an absolute URL."
  [path]
  (let [path (str/replace-first (str path) #"^https?://[^/]+" "")
        [uri qs] (str/split path #"\?" 2)]
    [(if (str/blank? uri) "/" uri) qs]))

(defn- request-of
  [{:keys [jar token]} method path params {:keys [htmx? remote-addr]}]
  (let [[uri qs] (split-path path)
        cookie   (when (seq jar) (str/join "; " (map (fn [[k v]] (str k "=" v)) (sort jar))))
        post?    (= :post method)
        body     (when post?
                   (form-body (cond-> (vec params)
                                (not htmx?) (conj ["__anti-forgery-token" token]))))
        bytes    (some-> ^String body (.getBytes "UTF-8"))]
    (cond-> {:request-method method
             :uri            uri
             :scheme         :http
             :server-name    "localhost"
             :server-port    80
             :remote-addr    (or remote-addr "127.0.0.1")
             :protocol       "HTTP/1.1"
             :headers        (cond-> {"host" "localhost"}
                               cookie (assoc "cookie" cookie)
                               htmx?  (merge htmx/fragment-headers)
                               (and htmx? token) (assoc (str/lower-case security/csrf-header) token))}
      qs    (assoc :query-string qs)
      bytes (-> (assoc :body (java.io.ByteArrayInputStream. bytes)
                       :content-length (alength ^bytes bytes)
                       :content-type "application/x-www-form-urlencoded; charset=UTF-8")
                (assoc-in [:headers "content-type"] "application/x-www-form-urlencoded; charset=UTF-8")
                (assoc-in [:headers "content-length"] (str (alength ^bytes bytes)))))))

(defn- location [response]
  (first (header-values (:headers response) "Location")))

(def ^:private redirect? #{301 302 303})

(def ^:private max-redirects
  "A bound on a chain of redirects, so a loop in the host is a red and never a hang."
  10)

(defn visit
  "The browser after sending `method` (`:get` or `:post`) to `path` with form `params`,
  holding the final `:response` and, as `:path`, where it landed — the address bar, with
  its query string, after every redirect was followed.

  - A POST carries the CSRF token of the last page that had one, as the hidden field a
    form would send — or, with `{:htmx? true}`, as the header the shell makes htmx send,
    with the headers of a swap. A POST with no token known throws: a 403 that looked
    like the application's fault is the failure it replaces. After a login the session,
    and with it the token, is new — GET a page before the next POST.
  - A 301, 302 or 303 is followed as a GET through the same jar, up to ten times; an
    `HX-Redirect` is left in the response for the test to read, as htmx would act on it
    and a server-side test cannot.
  - `{:remote-addr \"…\"}` sets the source address, for anything keyed by it.

  The jar keeps what `cookies` reads — a cookie set again replaces the old value and a
  deletion (`Max-Age` of zero or less, which is how Ring deletes) forgets it — and
  ignores `Path`, `Domain` and `Expires`: one site, every path."
  ([b method path] (visit b method path nil nil))
  ([b method path params] (visit b method path params nil))
  ([b method path params opts]
   (when (and (= :post method) (not (:token b)))
     (throw (ex-info (str "web-base testing: a POST to " path " with no CSRF token — GET a page that"
                          " carries one first")
                     {:path path})))
   (loop [b b method method path path params params hops 0]
     (let [response ((:handler b) (request-of b method path params opts))
           b        (assoc b
                           :response response
                           :path (let [[uri qs] (split-path path)] (cond-> uri qs (str "?" qs)))
                           :jar (into {} (remove (comp nil? val)) (merge (:jar b) (cookies response)))
                           :token (or (csrf-token response) (:token b)))]
       (if (and (redirect? (:status response)) (location response))
         (if (< hops max-redirects)
           (recur b :get (location response) nil (inc hops))
           (throw (ex-info (str "web-base testing: more than " max-redirects " redirects from " path)
                           {:path path})))
         b)))))
