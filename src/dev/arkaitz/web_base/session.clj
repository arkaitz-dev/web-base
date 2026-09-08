(ns dev.arkaitz.web-base.session
  "Sessions over Ring's store port (SPEC §11). The host gives `{:store s}` — any
  `ring.middleware.session.store/SessionStore` — or `{:key k}`, which selects
  Ring's cookie store with that signing key. Nothing else: the base never
  generates a key at startup, because a generated key destroys every session
  on every deploy and differs per instance, and it does so without a symptom.

  Two things the cookie store cannot do, so the host knows what it is choosing:
  a cookie session cannot be revoked from the server, and its sealed payload
  carries no timestamp, so the cookie's `Max-Age` is the only expiry there is."
  (:require [ring.middleware.session :as ring-session]
            [ring.middleware.session.cookie :as cookie])
  (:import [java.security SecureRandom]
           [java.util Base64]))

(def ^:private key-length 16)

(def ^:private same-site-values #{:strict :lax :none})

(def default-cookie-attrs
  "Ring itself only defaults `:path` and `:http-only`. `:secure` is on so that
  the trap is the opt-out a developer writes for http://localhost, not a
  default that works beautifully until production. `:secure` and
  `:http-only` can be switched off with `false`; `:same-site` cannot be
  removed. No `:max-age` by default: the host sets the expiry, knowing that
  under the cookie store there is no other."
  {:path "/" :http-only true :same-site :lax :secure true})

(defn generate-key
  "A fresh signing key as standard base64 with padding (what `:key` accepts),
  for the operator to run once and keep in the environment. Not for startup
  code."
  []
  (let [bytes (byte-array key-length)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- decode-key [k]
  (cond
    (bytes? k)  k
    (string? k) (try (.decode (Base64/getDecoder) ^String k)
                     (catch IllegalArgumentException _ nil))))

(defn- store-of
  "The error data never carries the key itself — it may be a real secret."
  [{:keys [store key]}]
  (cond
    (and store key)
    (throw (ex-info "session config takes :store or :key, not both"
                    {:config-key [:session]}))

    store store

    (nil? key)
    (throw (ex-info "session config needs :store or :key; the base never generates a key"
                    {:config-key [:session :key]}))

    :else
    (let [bytes (decode-key key)]
      (when-not (and bytes (= key-length (count bytes)))
        (throw (ex-info (str "session :key must be a byte array or a base64 string of exactly "
                             key-length " bytes")
                        {:config-key [:session :key]
                         :given      (if (string? key) :string (type key))
                         :bytes      (some-> bytes count)})))
      (cookie/cookie-store {:key bytes}))))

(defn- cookie-attrs-of
  "Ring validates `:same-site` while writing the first response; the base
  validates it here, at construction."
  [attrs]
  (let [attrs (merge default-cookie-attrs attrs)]
    (when-not (same-site-values (:same-site attrs))
      (throw (ex-info "session :cookie-attrs :same-site must be :strict, :lax or :none"
                      {:config-key [:session :cookie-attrs :same-site]
                       :value      (:same-site attrs)})))
    ;; Browsers drop a SameSite=None cookie that is not Secure, silently.
    (when (and (= :none (:same-site attrs)) (not (:secure attrs)))
      (throw (ex-info "session :cookie-attrs :same-site :none requires :secure true"
                      {:config-key [:session :cookie-attrs :secure] :value (:secure attrs)})))
    attrs))

(defn options
  "Ring `wrap-session` options built from the base's `:session` config, every
  failure raised here and named."
  [config]
  (cond-> {:store        (store-of config)
           :cookie-attrs (cookie-attrs-of (:cookie-attrs config))}
    (:cookie-name config) (assoc :cookie-name (:cookie-name config))))

(defn wrap
  "`ring.middleware.session/wrap-session` with the base's options."
  [handler config]
  (ring-session/wrap-session handler (options config)))

(defn rotate
  "Sets `new-session` on `response` and asks Ring to issue a fresh session id
  for it — the defence against session fixation, which the host must call
  right after authenticating someone (SPEC §11). Setting and marking are one
  act on purpose: a mark added first and a session assoc'd later would lose
  the mark, silently. Under the cookie store there is no id to rotate and
  Ring's recreate is a no-op. With a server-side store Ring deletes the old
  session first — with a nil key when the request was anonymous, which is
  the ordinary login — so a host's store must accept `delete-session` with
  nil."
  [response new-session]
  (when-not (map? new-session)
    (throw (ex-info "rotate needs the new session as a map; nil would delete the session"
                    {:session new-session})))
  (assoc response :session (vary-meta new-session assoc :recreate true)))
