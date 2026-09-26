(ns dev.arkaitz.web-base.security
  "Web security measures that are neither authentication nor authorisation
  (SPEC §15): response headers added when absent, the host's Content Security
  Policy with a per-request nonce, opt-in trust of a reverse proxy's headers,
  and CSRF protection through ring-anti-forgery's synchroniser token.

  Written down so it is not rediscovered: htmx's `hx-on`, `hx-vals js:` and
  trigger filters need `unsafe-eval` or the `hx-csp` extension; a strict
  policy means doing without them, which the demo does."
  (:require [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.anti-forgery.session :as anti-forgery-session]
            [ring.middleware.anti-forgery.strategy :as strategy])
  (:import [java.security SecureRandom]
           [java.util Base64]))

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
    (throw (ex-info "security :hsts must be a map with :max-age"
                    {:config-key [:security :hsts] :value hsts})))
  (let [{:keys [max-age include-subdomains?]} hsts]
    (when-not (nat-int? max-age)
      (throw (ex-info "security :hsts needs a non-negative integer :max-age"
                      {:config-key [:security :hsts :max-age] :value max-age})))
    (str "max-age=" max-age (when include-subdomains? "; includeSubDomains"))))

(defn- check-frame-options! [frame-options]
  (when-not (or (nil? frame-options) (false? frame-options) (string? frame-options))
    (throw (ex-info "security :frame-options must be a string, false to omit it, or absent"
                    {:config-key [:security :frame-options] :value frame-options}))))

(defn- check-csp! [csp]
  (when-not (or (nil? csp) (and (string? csp) (not (str/blank? csp))))
    (throw (ex-info "security :csp must be a non-blank policy string, or absent"
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
  (let [present (set (map str/lower-case (keys headers)))]
    (reduce-kv (fn [acc k v]
                 (if (present (str/lower-case k)) acc (assoc acc k v)))
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

(defn- first-forwarded [value]
  (some-> value (str/split #",") first str/trim not-empty))

(defn wrap-proxy
  "Trusts `X-Forwarded-Proto` and `X-Forwarded-For` for `:scheme` and
  `:remote-addr`. Only behind a proxy the host controls: anyone else can
  send those headers. The first `X-Forwarded-For` entry is taken, which is
  the one the client itself may have written before the proxy appended its
  own — a host that must trust the address should read the last hop it
  controls instead."
  [handler]
  (fn [request]
    (let [proto (some-> (first-forwarded (get-in request [:headers "x-forwarded-proto"])) str/lower-case)
          for   (first-forwarded (get-in request [:headers "x-forwarded-for"]))]
      (handler (cond-> request
                 (#{"http" "https"} proto) (assoc :scheme (keyword proto))
                 for                       (assoc :remote-addr for))))))

(defn- lazy-session-strategy
  "ring-anti-forgery's own session strategy — the same token, the same constant-time
  check — except that a token is written into the session only when the request used
  it. The library's default writes one on every request that lacks it, so a health
  probe, a JSON endpoint or a redirect each created a session: a row, with a
  server-side store. Validation is untouched, so a token that was never written fails
  closed like any other."
  []
  (let [inner (anti-forgery-session/session-strategy)]
    (reify strategy/Strategy
      (get-token [_ request] (strategy/get-token inner request))
      (valid-token? [_ request token] (strategy/valid-token? inner request token))
      (write-token [_ request response token]
        (if (some-> (::csrf-used request) deref)
          (strategy/write-token inner request response token)
          response)))))

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
    (fn [request]
      (protected (assoc request ::csrf-used (volatile! false))))))

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
