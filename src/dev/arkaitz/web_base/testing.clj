(ns dev.arkaitz.web-base.testing
  "Helpers for a host's own test suite, over plain Ring maps and rendered
  bodies. Nothing here needs ring-mock or any other library, so it ships in
  the jar. The shapes parsed are the base's own — the cookies Ring's session
  middleware sets, the CSRF token the shell and `security/csrf-field` emit —
  which is why the base owns these readers rather than every host's tests."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.gate :as gate]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.security :as security]
            [reitit.ring :as ring])
  (:import [java.util Locale]))

(defn- lower
  "Locale/ROOT, so a Turkish default locale cannot turn `I` into a dotless `ı`."
  [s]
  (.toLowerCase (str s) Locale/ROOT))

(defn header-values
  "The values of header `name` in `headers`, whatever the case of the key and
  whether Ring holds one string or several — always a vector, empty when absent. Public
  for a host's assertions on a response's headers."
  [headers name]
  (let [wanted (lower name)
        values (some (fn [[k v]] (when (= wanted (lower k)) v)) headers)]
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
                     (when (= "max-age" (lower name))
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

(def ^:private unsafe?
  "The methods CSRF protects, and so the ones that carry the token."
  #{:post :put :patch :delete})

(def ^:private params-in-query?
  "The methods whose parameters travel in the URL: a browser form's GET, and htmx's GET
  and DELETE, which move the form into the query string and send no body."
  #{:get :delete})

(defn- with-params
  "`path` with `params` in its query when `method` carries them there: a browser form's
  GET replaces the action's query with its fields, as HTML says; htmx's DELETE adds them
  to the URL it was given. Anything else is left alone — its params go in the body."
  [method path params]
  (if (and (params-in-query? method) (seq params))
    (let [[uri qs] (split-path path)
          query    (if (and (= :delete method) qs) (str qs "&" (form-body params)) (form-body params))]
      (str uri "?" query))
    path))

(def ^:private htmx-only?
  "The methods only htmx sends: a browser form knows GET and POST."
  #{:put :patch :delete})

(def ^:private owned-headers
  "The headers `visit` writes itself: a test that set one would fake the jar, the
  token or the swap instead of exercising them."
  (into #{"host" "cookie" "content-type" "content-length" (lower security/csrf-header)}
        (keys htmx/fragment-headers)))

(defn- extra-headers
  "`headers` with lower-cased names, refused naming the first one `who` owns, or the
  first name, in sort order, given twice in different cases — which spelling a map kept
  would be chance."
  ([path headers] (extra-headers "visit" owned-headers "pass the jar, the token or {:htmx? true} instead of setting it"
                                 path headers))
  ([who owned instead path headers]
   (let [lowered (into {} (map (fn [[k v]] [(lower (name k)) v])) headers)]
     (when (< (count lowered) (count headers))
       (let [twice (first (sort (for [[k n] (frequencies (map #(lower (name %)) (keys headers))) :when (< 1 n)] k)))]
         (throw (ex-info (str "web-base: " who " was given the " twice " header twice, in different cases")
                         {:path path :header twice}))))
     (when-let [taken (first (filter owned (sort (keys lowered))))]
       (throw (ex-info (str "web-base: " who " writes the " taken " header itself — " instead)
                       {:path path :header taken})))
     lowered)))

(def ^:private boundary "wb-test-boundary-7MA4YWxkTrZu0gW")

(defn- multipart-body
  "The bytes of a `multipart/form-data` body with `fields` (name → string) and `files`
  (name → `{:filename :content-type :bytes}`), as a browser's form sends them."
  ^bytes [fields files]
  (let [out  (java.io.ByteArrayOutputStream.)
        text (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[k v] fields]
      (text (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" (name k) "\"\r\n\r\n" v "\r\n")))
    (doseq [[k {:keys [filename content-type bytes]}] files]
      (text (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" (name k) "\"; filename=\"" filename "\"\r\n"
                 "Content-Type: " (or content-type "application/octet-stream") "\r\n\r\n"))
      (.write out ^bytes bytes)
      (text "\r\n"))
    (text (str "--" boundary "--\r\n"))
    (.toByteArray out)))

(defn- request-of
  [{:keys [jar token]} method path params {:keys [htmx? remote-addr headers files multipart?]}]
  (let [[uri qs] (split-path path)
        cookie   (when (seq jar) (str/join "; " (map (fn [[k v]] (str k "=" v)) (sort jar))))
        fields   (when (and (unsafe? method) (not (params-in-query? method)))
                   (cond-> (vec params) (not htmx?) (conj ["__anti-forgery-token" token])))
        [bytes type] (cond (and fields (or (seq files) multipart?))
                           [(multipart-body fields files) (str "multipart/form-data; boundary=" boundary)]
                           fields
                           [(.getBytes ^String (form-body fields) "UTF-8") "application/x-www-form-urlencoded; charset=UTF-8"])]
    (cond-> {:request-method method
             :uri            uri
             :scheme         :http
             :server-name    "localhost"
             :server-port    80
             :remote-addr    (or remote-addr "127.0.0.1")
             :protocol       "HTTP/1.1"
             :headers        (cond-> (assoc headers "host" "localhost")
                               cookie (assoc "cookie" cookie)
                               htmx?  (merge htmx/fragment-headers)
                               (and htmx? token) (assoc (lower security/csrf-header) token))}
      qs    (assoc :query-string qs)
      bytes (-> (assoc :body (java.io.ByteArrayInputStream. bytes)
                       :content-length (alength ^bytes bytes)
                       :content-type type)
                (assoc-in [:headers "content-type"] type)
                (assoc-in [:headers "content-length"] (str (alength ^bytes bytes)))))))

(defn header
  "The one value of header `name` in a response, whatever the case of its key, or nil —
  the first, when Ring holds several (since 0.17.0)."
  [response name]
  (first (header-values (:headers response) name)))

(defn call
  "One request to `handler` as a program sends it — no jar, no CSRF token, no redirect
  followed — for an API mounted under `:sessionless`, which `visit`, a browser, cannot
  drive (since 0.17.0). `path` may carry a query string, sent as written, malformed
  escapes included; an absolute URL is taken for its path and query, the host being
  always `localhost`. `opts`:

  - `:headers` — a map, names lower-cased as Ring has them; `host`, `content-type` and
    `content-length` are `call`'s own and refused, and so is a name given twice in
    different cases;
  - `:body` — a string, sent as UTF-8, or bytes; with it `:content-type` defaults to
    `application/json`;
  - `:content-type` — the body's type;
  - `:chunked? true` — no declared length, as a chunked body arrives;
  - `:remote-addr` — the source address.

  `:content-type` or `:chunked?` without a body, or a body that is neither a string nor
  bytes, is refused: a test would otherwise assert about a request that was never sent.
  Answers the handler's response as it is; reading a JSON body is the host's codec."
  ([handler method path] (call handler method path {}))
  ([handler method path {:keys [headers body content-type chunked? remote-addr]}]
   (when-not (or (nil? body) (string? body) (bytes? body))
     (throw (ex-info (str "web-base: call sends a body that is a string or bytes, and was given a "
                          (.getName (class body)) " — encode it first")
                     {:path path})))
   (when (and (nil? body) (or content-type chunked?))
     (throw (ex-info "web-base: call was given :content-type or :chunked? with no :body to send"
                     {:path path})))
   (let [[uri qs]  (split-path path)
         headers   (extra-headers "call" #{"host" "content-type" "content-length"}
                                  "pass :body, :content-type or :chunked? instead" path headers)
         bytes     (cond (nil? body) nil
                         (bytes? body) body
                         :else (.getBytes ^String body "UTF-8"))
         type      (when bytes (or content-type "application/json"))]
     (handler
      (cond-> {:request-method method
               :uri            uri
               :scheme         :http
               :server-name    "localhost"
               :server-port    80
               :remote-addr    (or remote-addr "127.0.0.1")
               :protocol       "HTTP/1.1"
               :headers        (assoc headers "host" "localhost")}
        qs    (assoc :query-string qs)
        bytes (-> (assoc :body (java.io.ByteArrayInputStream. ^bytes bytes) :content-type type)
                  (assoc-in [:headers "content-type"] type))
        (and bytes (not chunked?)) (-> (assoc :content-length (alength ^bytes bytes))
                                       (assoc-in [:headers "content-length"] (str (alength ^bytes bytes)))))))))

(defn location
  "The response's `Location`, whatever the case of the header's key, or nil."
  [response]
  (first (header-values (:headers response) "Location")))

(def ^:private redirect? #{301 302 303})

(def ^:private max-redirects
  "A bound on a chain of redirects, so a loop in the host is a red and never a hang."
  10)

(defn visit
  "The browser after sending `method` (`:get`, `:post`, `:put`, `:patch` or `:delete`) to
  `path` with form `params`, holding the final `:response` and, as `:path`, where it
  landed — the address bar, with its query string, after every redirect was followed.

  - `params` go where a browser puts them: in the query string for a GET, and for a
    DELETE, which htmx sends with no body; as a form body for a POST, PUT or PATCH.
  - A POST, PUT, PATCH or DELETE carries the CSRF token of the last page that had one,
    as the hidden field a form would send — or, with `{:htmx? true}`, as the header the
    shell makes htmx send, with the headers of a swap. A PUT, PATCH or DELETE without
    `{:htmx? true}` throws, because no browser form can send one. One with no token
    known throws: a 403 that looked like the application's fault is the failure it
    replaces. After a login the session, and with it the token, is new — GET a page
    before the next one.
  - A 301, 302 or 303 is followed as a GET through the same jar, up to ten times; an
    `HX-Redirect` is left in the response for the test to read, as htmx would act on it
    and a server-side test cannot.
  - `{:remote-addr \"…\"}` sets the source address, for anything keyed by it.
  - `{:headers {\"accept-language\" \"es\"}}` adds headers to every request, redirects
    followed included, as a browser keeps sending its own. Names are lower-cased; one
    `visit` writes itself — host, cookie, content-type, content-length, the CSRF header
    or a swap's — throws, because setting it would fake what the test exercises.
  - `{:files {\"photo\" {:filename \"a.png\" :content-type \"image/png\" :bytes b}}}` sends a
    POST, PUT or PATCH as `multipart/form-data`, with its params and the CSRF field as
    parts beside the files — what a form with a file input sends. A vector of pairs,
    `[[\"photo\" a] [\"photo\" b]]`, repeats the field as `<input multiple>` does. On any
    other method it throws, since there is no body to carry them.
  - `{:multipart? true}` sends a POST, PUT or PATCH as `multipart/form-data` with no file
    (since 0.17.0) — what a form with `enctype=\"multipart/form-data\"` sends when nothing
    was chosen. Without it, a form with no file is sent urlencoded: `:params` still holds
    its fields, but `:multipart-params` — what a handler of a multipart form reads — is
    nil. Sent to a route that declares no `:wb/multipart`, nothing parses it and its token
    is unseen: the 403 of `:files` there.
  - `{:follow? false}` sends one request and follows nothing, for a test that asks who
    answered: a handler that redirects to a gated page lands on the login page exactly
    as the gate would. The jar still takes what the response set.

  The jar keeps what `cookies` reads — a cookie set again replaces the old value and a
  deletion (`Max-Age` of zero or less, which is how Ring deletes) forgets it — and
  ignores `Path`, `Domain` and `Expires`: one site, every path."
  ([b method path] (visit b method path nil nil))
  ([b method path params] (visit b method path params nil))
  ([b method path params opts]
   (when (and (htmx-only? method) (not (:htmx? opts)))
     (throw (ex-info (str "web-base: a browser form sends only GET and POST — a "
                          (.toUpperCase (name method) Locale/ROOT) " to " path " is htmx's; pass {:htmx? true}")
                     {:path path :method method})))
   (when (and (or (seq (:files opts)) (:multipart? opts)) (not (#{:post :put :patch} method)))
     (throw (ex-info (str "web-base: {:files …} or {:multipart? true} goes in the body of a POST, PUT or PATCH, and a "
                          (.toUpperCase (name method) Locale/ROOT) " to " path " has none")
                     {:path path :method method})))
   (when (and (unsafe? method) (not (:token b)))
     (throw (ex-info (str "web-base: a " (.toUpperCase (name method) Locale/ROOT) " to " path
                          " with no CSRF token — GET a page that carries one first")
                     {:path path})))
   (let [opts (assoc opts :headers (extra-headers path (:headers opts)))]
     (loop [b b method method path (with-params method path params) params params hops 0]
       (let [response ((:handler b) (request-of b method path params opts))
             b        (assoc b
                             :response response
                             :path (let [[uri qs] (split-path path)] (cond-> uri qs (str "?" qs)))
                             :jar (into {} (remove (comp nil? val)) (merge (:jar b) (cookies response)))
                             :token (or (csrf-token response) (:token b)))]
         (if (and (not (false? (:follow? opts))) (redirect? (:status response)) (location response))
           (if (< hops max-redirects)
             (recur b :get (location response) nil (inc hops))
             (throw (ex-info (str "web-base: more than " max-redirects " redirects from " path)
                             {:path path})))
           b))))))

(defn gate-refusal?
  "Whether `response` is the gate's refusal of a request with no subject, sent to
  `login-path`: a 303 for a navigation — to `login-path` itself, or carrying the page it
  asked for as `next` (since 0.10.0) — and a 200 with `HX-Redirect` to `login-path` and
  no `Location` for an htmx swap. The refusal's own headers are part of the test,
  because they are what tell it from a handler that redirects to the login page by
  itself."
  [response login-path]
  (let [headers  (:headers response)
        location (get headers "Location")]
    (boolean
     (and (every? (fn [[k v]] (= v (get headers k))) gate/refusal-headers)
          (or (and (= 303 (:status response))
                   (or (= login-path location)
                       (some-> location (str/starts-with? (str login-path (if (str/includes? login-path "?") "&" "?") "next=")))))
              (and (= 200 (:status response))
                   (= login-path (get headers "HX-Redirect"))
                   (nil? (get headers "Location"))))))))

(defn router
  "`routes` compiled as the base compiles them — a child's gate composed with its
  parent's — for a test that reads which gates guard which endpoint. A composed gate is
  identical to none of its parts; its metadata lists them under `:wb/gates`."
  [routes]
  (ring/router routes {:meta-merge gate/merge-route-data}))
