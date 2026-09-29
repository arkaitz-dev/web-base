(ns dev.arkaitz.web-base.security
  "Web security measures that are neither authentication nor authorisation
  (SPEC §15): response headers added when absent, the host's Content Security
  Policy with a per-request nonce, opt-in trust of a reverse proxy's headers,
  and CSRF protection through ring-anti-forgery's synchroniser token.

  Written down so it is not rediscovered: htmx's `hx-on`, `hx-vals js:` and
  trigger filters need `unsafe-eval` or the `hx-csp` extension; a strict
  policy means doing without them, which the demo does."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [dev.arkaitz.web-base.log :as request-log]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.anti-forgery.session :as anti-forgery-session]
            [ring.middleware.anti-forgery.strategy :as strategy])
  (:import [java.io FilterInputStream InputStream]
           [java.security SecureRandom]
           [java.util Base64 Locale]))

(defn- lower
  "Locale/ROOT, so a Turkish default locale cannot turn `I` into a dotless `ı`."
  [s]
  (.toLowerCase (str s) Locale/ROOT))

(def default-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"
   "Referrer-Policy"        "strict-origin-when-cross-origin"})

;; See log.clj: a delay keeps it out of the image.
(def ^:private random (delay (SecureRandom.)))

(defn- nonce
  "128 random bits, base64: the value CSP expects after `nonce-`."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes ^SecureRandom @random bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- hsts-value [hsts]
  (when-not (map? hsts)
    (throw (ex-info "web-base: security :hsts must be a map with :max-age"
                    {:config-key [:security :hsts] :value hsts})))
  (let [{:keys [max-age include-subdomains?]} hsts]
    (when-not (nat-int? max-age)
      (throw (ex-info "web-base: security :hsts needs a non-negative integer :max-age, in seconds"
                      {:config-key [:security :hsts :max-age] :value max-age})))
    ;; Seconds, the header's own unit, beside keys that are all milliseconds: a year
    ;; written as 31536000000 would pass and be a thousand years. A billion seconds is
    ;; thirty-one years; no HSTS policy asks for more.
    (when (< 1000000000 max-age)
      (throw (ex-info "web-base: security :hsts :max-age looks like milliseconds; HSTS max-age is seconds (a year is 31536000)"
                      {:config-key [:security :hsts :max-age] :value max-age})))
    (str "max-age=" max-age (when include-subdomains? "; includeSubDomains"))))

(defn- check-frame-options! [frame-options]
  (when-not (or (nil? frame-options) (false? frame-options) (string? frame-options))
    (throw (ex-info "web-base: security :frame-options must be a string, false to omit it, or absent"
                    {:config-key [:security :frame-options] :value frame-options}))))

(defn- check-csp! [csp]
  (when-not (or (nil? csp) (and (string? csp) (not (str/blank? csp))))
    (throw (ex-info "web-base: security :csp must be a non-blank policy string, or absent"
                    {:config-key [:security :csp] :value csp}))))

(defn- static-headers [{:keys [frame-options hsts csp]}]
  (check-frame-options! frame-options)
  (check-csp! csp)
  (cond-> default-headers
    (false? frame-options)  (dissoc "X-Frame-Options")
    (string? frame-options) (assoc "X-Frame-Options" frame-options)
    hsts                    (assoc "Strict-Transport-Security" (hsts-value hsts))))

(defn- add-missing
  "The base's headers under the handler's, matched without regard to case:
  a handler writing `x-frame-options` must not end up with two."
  [headers base]
  (let [present (set (map lower (keys headers)))]
    (reduce-kv (fn [acc k v]
                 (if (present (lower k)) acc (assoc acc k v)))
               (or headers {})
               base)))

(defn wrap-headers
  "Outer middleware: every response — routed, static, error — gets the
  security headers it lacks; a header the handler set is kept. Every request
  gets `:wb/nonce`; when the host configures `:csp`, `{nonce}` in it is
  replaced by that request's nonce and the policy is sent. `:frame-options`
  is `DENY` by default, a string to change it, `false` to omit it."
  [handler {:keys [csp] :as config}]
  (let [static (static-headers config)]
    (fn [request]
      (let [nonce    (nonce)
            response (handler (assoc request :wb/nonce nonce))
            headers  (cond-> static
                       csp (assoc "Content-Security-Policy" (str/replace csp "{nonce}" nonce)))]
        (some-> response (update :headers add-missing headers))))))

(defn private
  "`response` with `Cache-Control: no-store` unless it already says how it may be cached.
  For what is somebody's own — a signed-in page, a page carrying a session's CSRF token —
  which a shared cache must never hand to the next visitor, nor a shared computer keep."
  [response]
  (some-> response (update :headers add-missing {"Cache-Control" "no-store"})))

(def default-max-body-bytes
  "The largest request body the base reads when the host names no other: 200 000 bytes,
  Jetty's own form limit — which never applied here, because Ring reads the body itself
  (`wrap-params`), so a single anonymous POST could fill the heap (measured: 1 GB raised
  it by 2 GB; four at once, an OutOfMemoryError)."
  200000)

(defn- over-limit!
  "The 413 a body past its limit is answered with, marked as the body's own failure."
  []
  (throw (ex-info "web-base: request body over its limit" {:type ::body :status 413})))

(defn- implements?
  "Whether `c` implements the interface named `interface`, by name: Jetty lives behind
  server.clj alone (SPEC §10), so no Jetty class is imported here."
  [^Class c interface]
  (boolean (some #(= interface (.getName ^Class %)) (supers c))))

(defn- quiet-cause
  "The first throwable in `e`'s cause chain that Jetty marks as quiet — a client that
  hung up mid-body, a body that arrived slower than the minimum rate — or nil."
  [^Throwable e]
  (some #(when (implements? (class %) "org.eclipse.jetty.io.QuietException") %)
        (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- quiet-status
  "The status Jetty's own `HttpException` carries, or 400."
  [^Throwable t]
  (or (when (implements? (class t) "org.eclipse.jetty.http.HttpException")
        (let [code (try (.invoke (.getMethod (class t) "getCode" (make-array Class 0)) t (object-array 0))
                        (catch Exception _ nil))]
          (when (and (int? code) (<= 400 code 599)) code)))
      400))

(defn- lost!
  "Rethrows a failure to read the body: marked as the body's own, with the status Jetty
  gave it, when Jetty calls it the client's doing; untouched otherwise. Any exception,
  not only an `IOException`: Jetty's rate floor throws its `BadMessageException`, a
  runtime one, straight out of the read (measured)."
  [^Exception e]
  (if-let [quiet (quiet-cause e)]
    (throw (ex-info "web-base: request body not received"
                    {:type ::body :status (quiet-status quiet) :lost (.getName (class quiet))} e))
    (throw e)))

(defn body-failure
  "The request body's own failure somewhere in `e`'s cause chain, as data — `{:status
  413}` past its limit, `{:status s :lost class-name}` when the client did not send it —
  or nil. Marked where the body is read, so a failure of anything else that merely looks
  like Jetty's (an upstream client, a driver) is never mistaken for the client's."
  [^Throwable e]
  (some #(let [data (ex-data %)] (when (= ::body (:type data)) (dissoc data :type)))
        (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- limited
  "`in`, throwing the 413 once more than `limit` bytes have been read from it — the bound
  for a body whose length was not declared, or was declared falsely — and marking a read
  the client broke off. No mark: a reset would count the same bytes twice."
  ^InputStream [^InputStream in limit]
  (let [seen (volatile! 0)
        note (fn [n] (when (pos? n) (when (< limit (vswap! seen + n)) (over-limit!))) n)]
    (proxy [FilterInputStream] [in]
      (read
        ([] (let [b (try (.read in) (catch Exception e (lost! e)))] (when (<= 0 b) (note 1)) b))
        ([bytes] (note (try (.read in ^bytes bytes) (catch Exception e (lost! e)))))
        ([bytes off len] (note (try (.read in ^bytes bytes (int off) (int len)) (catch Exception e (lost! e))))))
      (skip [n] (note (try (.skip in (long n)) (catch Exception e (lost! e)))))
      (markSupported [] false)
      (mark [_])
      (reset [] (throw (java.io.IOException. "mark/reset not supported"))))))

(defn wrap-body-limit
  "Refuses a request body larger than `(limit-for request)` bytes with a 413: at once,
  unread, when its declared length says so; otherwise as it is read, whoever reads it.
  `render` answers the refusal made here, before anything else runs."
  [handler limit-for render]
  (fn [request]
    (let [limit    (limit-for request)
          declared (or (:content-length request)
                       (some-> (get-in request [:headers "content-length"]) parse-long))]
      (if (and declared (< limit declared))
        (render {:status 413} request)
        (handler (cond-> request (:body request) (update :body limited limit)))))))

(defn- forwarded
  "The entry `hops` from the right of a comma-separated forwarded header, trimmed, or nil
  when the header has fewer entries: what the outermost of `hops` appending proxies saw.
  The entries to its left were written by whoever the proxies talked to — for the client
  address, the client itself."
  [value hops]
  (let [entries (some->> (some-> value (str/split #",")) (map str/trim) (remove str/blank?) vec)]
    (when (<= hops (count entries))
      (nth entries (- (count entries) hops)))))

(defn- address
  "`entry` as an address: brackets and a port taken off (`[2001:db8::1]:443`,
  `203.0.113.7:51000`), as proxies write them. What remains is the proxy's spelling;
  auth-base's limiter canonicalises it for its key."
  [entry]
  (let [[_ bracketed] (re-matches #"\[([^\]]+)\](?::\d+)?" entry)
        [_ v4]        (re-matches #"(\d{1,3}(?:\.\d{1,3}){3}):\d+" entry)]
    (or bracketed v4 entry)))

(defn wrap-proxy
  "Trusts `X-Forwarded-For` and `X-Forwarded-Proto` for `:remote-addr` and `:scheme`,
  behind `hops` proxies the host runs, each APPENDING what it saw — nginx's
  `$proxy_add_x_forwarded_for`, AWS's load balancers, Heroku's and Fly's routers all do.
  The entry `hops` from the right is the address the outermost of them saw; everything
  to its left the client could have written. A header with fewer entries than `hops`
  leaves the socket's address, and a request that did not come through the proxies is
  never trusted beyond it. `X-Forwarded-Proto` is taken from its last entry whatever the
  count: proxies overwrite it rather than append, so it holds one value, the nearest
  proxy's. Only behind proxies the host controls: anyone else can send these headers."
  [handler hops]
  (fn [request]
    (let [proto (some-> (forwarded (get-in request [:headers "x-forwarded-proto"]) 1) lower)
          for   (some-> (forwarded (get-in request [:headers "x-forwarded-for"]) hops) address)]
      (handler (cond-> request
                 (#{"http" "https"} proto) (assoc :scheme (keyword proto))
                 for                       (assoc :remote-addr for))))))

(def ^:private session-token-key
  "Where ring-anti-forgery's session strategy keeps the token."
  :ring.middleware.anti-forgery/anti-forgery-token)

(defn- fresh-token
  "A token of ring-anti-forgery's own shape — 60 random bytes, base64 unpadded. The
  generator is made here, per call: one in a var root is baked into a native image."
  []
  (let [bytes (byte-array 60)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (java.util.Base64/getEncoder)) bytes)))

(defn- rotating? [response]
  (boolean (:recreate (meta (:session response)))))

(defn- rotated-session
  "The session a rotating response leaves behind: holding the token minted for it in
  this request, or none — never one from before the rotation. A pre-login token is
  known to whoever fixed the pre-login session, so carrying it over, whether the host
  copied the old session into the new one or the library did, would hand them the
  logged-in session's token."
  [request response]
  (let [minted (some-> (::csrf-fresh request) deref)
        used?  (some-> (::csrf-used request) deref)
        token  (or minted
                   (when used?
                     (log/error (str "a response that rotates the session rendered a CSRF token the new"
                                     " session cannot keep — render it through the base, or redirect")
                                {:request-id (:wb/request-id request) :uri (request-log/path-of request)})
                     (fresh-token)))
        session (:session response)]
    (assoc response :session (if token (assoc session session-token-key token) (dissoc session session-token-key)))))

(defn- lazy-session-strategy
  "ring-anti-forgery's own session strategy — the same token, the same constant-time
  check — except that a token is written into the session only when the request used
  it. The library's default writes one on every request that lacks it, so a health
  probe, a JSON endpoint or a redirect each created a session: a row, with a
  server-side store. Validation is untouched, so a token that was never written fails
  closed like any other.

  A response that rotates the session is the exception, whatever its body: its new
  session gets the token minted for it by `rotate-token`, or none (`rotated-session`)."
  []
  (let [inner (anti-forgery-session/session-strategy)]
    (reify strategy/Strategy
      (get-token [_ request] (strategy/get-token inner request))
      (valid-token? [_ request token] (strategy/valid-token? inner request token))
      (write-token [_ request response token]
        (cond
          (rotating? response)                 (rotated-session request response)
          (some-> (::csrf-used request) deref) (strategy/write-token inner request response token)
          :else                                response)))))

(defn wrap-csrf
  "ring-anti-forgery inside the session: every request not GET/HEAD/OPTIONS
  needs the session's token, read from the `__anti-forgery-token` form field
  or the `X-CSRF-Token` header — which the shell makes htmx send on every
  request (the library also honours `X-XSRF-Token`; the base documents one
  name). A refusal is the base's own 403 datum, so an htmx swap receives a
  fragment and a navigation a page.

  **A token reaches the session only if the request used it** — through
  `csrf-token` or `csrf-field`, which the shell calls for every page it renders,
  and while the handler runs. A request that renders neither writes no session, so
  an anonymous `/health`, a JSON answer or a redirect leaves no row behind. Reading
  `:anti-forgery-token` or ring-anti-forgery's dynamic var directly, or reading the
  token after the handler returned (a body built lazily later), mints a token that is
  never stored: the form built with it earns a 403."
  [handler render-error]
  (let [protected (anti-forgery/wrap-anti-forgery
                   handler
                   {:error-handler (fn [request] (render-error {:status 403} request))
                    :strategy      (lazy-session-strategy)})]
    ;; A flag of this request's own: one made when the middleware was built would be
    ;; shared by every request after the first that used a token.
    ;; A page that read the token is somebody's own, signed in or not.
    (fn [request]
      (let [used     (volatile! false)
            response (protected (assoc request ::csrf-used used ::csrf-fresh (volatile! nil)))]
        (cond-> response @used private)))))

(defn rotate-token
  "`request` carrying a fresh CSRF token for a response that rotates the session, and
  the token recorded so the rotated session keeps exactly it. The render step calls it
  before turning such a response into HTML, so a login page's forms carry the token its
  new session holds. The request unchanged when it went through no CSRF, or carries no
  token. A token read before this — a handler whose own content called `csrf-field` —
  cannot follow the rotation, and is logged by name."
  [request]
  (if-let [minted (and (:anti-forgery-token request) (::csrf-fresh request))]
    (do (when (some-> (::csrf-used request) deref)
          (log/error (str "a response that rotates the session read its CSRF token before the base"
                          " rendered it — the form built with it will be refused; read it in a layout,"
                          " or redirect")
                     {:request-id (:wb/request-id request) :uri (request-log/path-of request)}))
        (assoc request :anti-forgery-token (or @minted (vreset! minted (fresh-token)))))
    request))

(def csrf-header "X-CSRF-Token")

(defn csrf-token
  "The request's token, for the host's own markup — and the read that makes it stick:
  a token nobody read through here is not written into the session (`wrap-csrf`)."
  [request]
  (when-let [token (:anti-forgery-token request)]
    (some-> (::csrf-used request) (vreset! true))
    token))

(defn csrf-field
  "The hidden input a classic form needs; htmx requests carry the header
  instead. Nothing when the request carries no token — under `:csrf false`
  there is nothing to send, and an empty field would only earn a 403."
  [request]
  (when-let [token (csrf-token request)]
    [:input {:type "hidden" :name "__anti-forgery-token" :value token}]))
