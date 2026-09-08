(ns dev.arkaitz.web-base.session-test
  "Set-Cookie strings are literals observed once by hand. Ring returns that
  header as a lazy seq; `=` against a vector literal is true on purpose. The
  attribute order and the `; ` separator are Ring's: a Ring upgrade that
  reorders them reds every literal here, and the literals are what change.

  Rotation is observed through a deterministic recording store: with the real
  cookie store every write differs anyway (random IV), so `the cookie changed`
  would be green with `rotate` doing nothing at all."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.session :as session]
            [ring.middleware.session.memory :as memory]
            [ring.middleware.session.store :refer [SessionStore]]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]
           [java.util Base64]
           [ring.middleware.session.cookie CookieStore]))

(defn- attempt [config]
  (try (session/options config)
       (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(defn- recording-store
  "Reads from `sessions`, records every call, writes under the old key or
  `fresh` when there is none."
  [sessions calls]
  (reify SessionStore
    (read-session   [_ k]      (swap! calls conj [:read k])       (get sessions k))
    (write-session  [_ k data] (swap! calls conj [:write k data]) (or k "fresh"))
    (delete-session [_ k]      (swap! calls conj [:delete k])     nil)))

(def ^:private with-old (mock/header (mock/request :get "/") "Cookie" "ring-session=old"))
(def ^:private anon     (mock/request :get "/"))

(defn- through
  "`[response calls]` for `handler` behind the base's session middleware over a
  recording store seeded with `old`."
  ([handler request] (through handler request {}))
  ([handler request config]
   (let [calls (atom [])
         app   (session/wrap handler (merge {:store (recording-store {"old" {:user "ann"}} calls)} config))]
     [(app request) @calls])))

(def ^:private DEFAULT-COOKIE "ring-session=fresh; Path=/; HttpOnly; SameSite=Lax; Secure")

(deftest missing-key-throws-ex-info-naming-session-key--never-a-random-key
  (doseq [config [{} {:key nil} {:key nil :cookie-attrs {:secure false}}]]
    (is (= ["session config needs :store or :key; the base never generates a key"
            {:config-key [:session :key]}]
           (attempt config))
        (str "no store and no key → named failure, for " (pr-str config)))))

(deftest malformed-key-throws-ex-info-with-shape-only--the-key-value-never-appears
  (let [message "session :key must be a byte array or a base64 string of exactly 16 bytes"
        bytes   (type (byte-array 0))]
    (doseq [[key given decoded] [[(byte-array 15)             bytes                 15]
                                 [(byte-array 17)             bytes                 17]
                                 ["not base64!"               :string               nil]
                                 ["0123456789abcdef"          :string               12]
                                 ["AAAAAAAAAAAAAAAAAAAA"      :string               15]
                                 [""                          :string               0]
                                 [42                          java.lang.Long        nil]
                                 [:kw                         clojure.lang.Keyword  nil]]]
      (is (= [message {:config-key [:session :key] :given given :bytes decoded}]
             (attempt {:key key}))
          (str "refused: " (pr-str key))))
    (doseq [key ["SENTINELNOTB64!" "SENTINELSENTIN"]]
      (let [[msg data :as outcome] (attempt {:key key})]
        (is (= message msg) (str "refused: " (pr-str key) " — got " (pr-str outcome)))
        (is (not (re-find #"SENTINEL" (str msg (pr-str data))))
            "the key itself never appears in the message or the data")))
    (doseq [key [(byte-array 16) "AAAAAAAAAAAAAAAAAAAAAA=="]]
      (let [options (session/options {:key key})]
        (is (instance? CookieStore (:store options)) "a well-formed key selects the cookie store")
        (is (= #{:store :cookie-attrs} (set (keys options))) "and nothing else is configured")))))

(deftest store-and-key-together-are-refused-as-ambiguous
  (doseq [key ["AAAAAAAAAAAAAAAAAAAAAA==" (byte-array 16)]]
    (is (= ["session config takes :store or :key, not both" {:config-key [:session]}]
           (attempt {:store (memory/memory-store) :key key}))
        (str "refused with a key given as " (if (string? key) "base64" "bytes"))))
  (let [store (memory/memory-store)]
    (is (identical? store (:store (session/options {:store store :key nil})))
        "a nil :key next to a store is an absent key, as a config map naturally has it")))

(deftest same-site-is-validated-at-construction-not-at-the-first-response
  (doseq [value ["Lax" :Lax "lax" 1 nil]]
    (is (= ["session :cookie-attrs :same-site must be :strict, :lax or :none"
            {:config-key [:session :cookie-attrs :same-site] :value value}]
           (attempt {:store (memory/memory-store) :cookie-attrs {:same-site value}}))
        (str "refused at construction: " (pr-str value))))
  (is (= ["session :cookie-attrs :same-site :none requires :secure true"
          {:config-key [:session :cookie-attrs :secure] :value false}]
         (attempt {:store (memory/memory-store) :cookie-attrs {:same-site :none :secure false}}))
      "SameSite=None without Secure is a cookie browsers drop silently: refused")
  (is (= ["session config needs :store or :key; the base never generates a key"
          {:config-key [:session :key]}]
         (try (session/wrap identity {}) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
      "wrap itself fails at construction, not on the first request")
  (doseq [[value cookie] [[:strict "ring-session=fresh; Path=/; HttpOnly; SameSite=Strict; Secure"]
                          [:none   "ring-session=fresh; Path=/; HttpOnly; SameSite=None; Secure"]]]
    (is (= {:status 200 :body "x" :headers {"Set-Cookie" [cookie]}}
           (first (through (fn [_] {:status 200 :body "x" :session {:user "bob"}}) anon
                           {:cookie-attrs {:same-site value}})))
        (str "control: " value " reaches the browser"))))

(deftest default-cookie-attrs-reach-set-cookie-and-the-host-overrides-them-key-by-key
  (is (= {:path "/" :http-only true :same-site :lax :secure true}
         (:cookie-attrs (session/options {:store (memory/memory-store)})))
      "the base's defaults")
  (is (= {:path "/" :http-only true :same-site :lax :secure false :max-age 3600}
         (:cookie-attrs (session/options {:store (memory/memory-store) :cookie-attrs {:secure false :max-age 3600}})))
      "host attrs merge over the defaults")
  (let [handler (fn [_] {:status 200 :body "x" :session {:user "ann"}})]
    (doseq [[attrs cookie] [[nil                           DEFAULT-COOKIE]
                            [{:secure false}               "ring-session=fresh; Path=/; HttpOnly; SameSite=Lax"]
                            [{:max-age 3600}               "ring-session=fresh; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=3600"]
                            [{:http-only false}            "ring-session=fresh; Path=/; SameSite=Lax; Secure"]
                            [{:path "/app"}                "ring-session=fresh; Path=/app; HttpOnly; SameSite=Lax; Secure"]
                            [{:secure false :max-age 3600} "ring-session=fresh; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600"]]]
      (is (= {:status 200 :body "x" :headers {"Set-Cookie" [cookie]}}
             (first (through handler anon (if attrs {:cookie-attrs attrs} {}))))
          (str "Set-Cookie with " (pr-str attrs))))))

(deftest store-passes-through-untouched-and-cookie-name-governs-both-directions
  (let [store (memory/memory-store)]
    (is (identical? store (:store (session/options {:store store}))) "the store is the very object given"))
  (let [handler (fn [_] {:status 200 :body "x" :session {:user "bob"}})]
    (is (= [{:status 200 :body "x"} [[:read "old"] [:write "old" {:user "bob"}]]]
           (through handler (mock/header (mock/request :get "/") "Cookie" "sid=old") {:cookie-name "sid"}))
        "the named cookie is read, and the same key is rewritten without a new Set-Cookie")
    (is (= [{:status 200 :body "x" :headers {"Set-Cookie" ["sid=fresh; Path=/; HttpOnly; SameSite=Lax; Secure"]}}
            [[:read nil] [:write nil {:user "bob"}]]]
           (through handler with-old {:cookie-name "sid"}))
        "control: the default name is not read under a custom name, and the custom name is written")
    (is (= [{:status 200 :body "x"} [[:read "old"] [:write "old" {:user "bob"}]]]
           (through handler with-old))
        "without :cookie-name Ring's ring-session applies")
    (is (= [{:status 200 :body "x" :headers {"Set-Cookie" ["sid=fresh; Path=/app; HttpOnly; SameSite=Lax; Secure"]}}
            [[:read nil] [:write nil {:user "bob"}]]]
           (through handler anon {:cookie-name "sid" :cookie-attrs {:path "/app"}}))
        "a custom name and a custom path coexist: nothing else is configured behind the host's back")
    (is (= #{:store :cookie-attrs :cookie-name}
           (set (keys (session/options {:store (memory/memory-store) :cookie-name "sid"}))))
        "the options carry the name and nothing more")))

(deftest rotate-sets-the-session-with-recreate-metadata-and-refuses-non-maps
  (let [r (session/rotate {:status 200 :session {:old 1}} {:user "a"})]
    (is (= {:status 200 :session {:user "a"}} r) "the new session replaces the old")
    (is (= {:recreate true} (meta (:session r))) "and carries the recreate mark"))
  (is (= {:x 1 :recreate true}
         (meta (:session (session/rotate {:status 200} (with-meta {:user "a"} {:x 1})))))
      "the session's own metadata survives")
  (doseq [bad [nil "abc" 42 [1]]]
    (is (= ["rotate needs the new session as a map; nil would delete the session" {:session bad}]
           (try (session/rotate {:status 200} bad)
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "refused: " (pr-str bad)))))

(deftest through-wrap-rotate-deletes-the-old-key-and-writes-under-nil--assoc-writes-under-the-old-key
  (let [rotating (fn [_] (session/rotate {:status 200 :body "x"} {:user "bob"}))
        assoc'ing (fn [_] {:status 200 :body "x" :session {:user "bob"}})]
    (is (= [{:status 200 :body "x" :headers {"Set-Cookie" [DEFAULT-COOKIE]}}
            [[:read "old"] [:delete "old"] [:write nil {:user "bob"}]]]
           (through rotating with-old))
        "rotate: delete the old key, write under nil, issue the fresh cookie")
    (is (= [{:status 200 :body "x" :headers {"Set-Cookie" [DEFAULT-COOKIE]}}
            [[:read nil] [:delete nil] [:write nil {:user "bob"}]]]
           (through rotating anon))
        "rotate on an anonymous request still deletes (nil) and writes under nil")
    (is (= [{:status 200 :body "x"} [[:read "old"] [:write "old" {:user "bob"}]]]
           (through assoc'ing with-old))
        "a plain assoc writes under the old key and issues no cookie")
    (is (= [{:status 200 :body "x"} [[:read "old"] [:delete "old"]]]
           (through (fn [_] {:status 200 :body "x" :session nil}) with-old))
        ":session nil deletes only")
    (is (= [{:status 200 :body "x"} [[:read "old"]]]
           (through (fn [_] {:status 200 :body "x"}) with-old))
        "no :session key touches nothing")))

(deftest memory-store-oracle--after-rotate-only-the-new-record-exists-and-the-cookie-names-it
  (let [sessions (atom {"old" {:user "ann"}})
        app      (session/wrap (fn [_] (session/rotate {:status 200 :body "x"} {:user "bob"}))
                               {:store (memory/memory-store sessions)})
        response (app with-old)
        k        (second (re-find #"^ring-session=([^;]*); Path=/; HttpOnly; SameSite=Lax; Secure$"
                                  (str (first (get-in response [:headers "Set-Cookie"])))))]
    (is (some? k) (str "a fresh cookie was issued: " (pr-str (:headers response))))
    (is (not= "old" k) "the key changed")
    (is (= {k {:user "bob"}} @sessions) "only the new record exists, under the new key")
    (is (= {:status 200 :body "x"} (dissoc response :headers)))))

(deftest generate-key-yields-fresh-16-byte-standard-base64-keys-the-constructor-accepts
  (let [keys*   (repeatedly 32 session/generate-key)
        decoded (mapv #(vec (.decode (Base64/getDecoder) ^String %)) keys*)]
    (is (every? #(re-matches #"[A-Za-z0-9+/]{22}==" %) keys*)
        "standard base64 alphabet with padding, 24 chars — the only form :key decodes")
    (is (every? #(= 16 (count %)) decoded) "each decodes to 16 bytes")
    (is (= 32 (count (set keys*))) "thirty-two calls, thirty-two distinct keys")
    ;; Entropy witness: a key with half its bytes fixed would pass every
    ;; other assertion here. With 32 samples a byte that never varies is
    ;; a defect, not chance.
    (is (every? (fn [position] (> (count (set (map #(nth % position) decoded))) 1)) (range 16))
        "every one of the 16 byte positions varies across keys")
    (is (instance? CookieStore (:store (session/options {:key (first keys*)}))) "accepted by the constructor")))

(deftest cookie-store-round-trip--the-cookie-a-login-sets-is-read-by-an-independent-instance-with-the-same-key
  (let [echo-app  (fn [config]
                    (session/wrap (fn [request]
                                    (if (= "/login" (:uri request))
                                      (session/rotate {:status 200 :body "in"} {:user "ann"})
                                      {:status 200 :body (pr-str (:session request))}))
                                  config))
        key-bytes (byte-array (range 16))
        key-b64   "AAECAwQFBgcICQoLDA0ODw=="
        login     ((echo-app {:key key-b64}) (mock/request :post "/login"))
        v         (second (re-matches #"ring-session=([A-Za-z0-9%]+--[A-Za-z0-9%]+); Path=/; HttpOnly; SameSite=Lax; Secure"
                                      (str (first (get-in login [:headers "Set-Cookie"])))))
        ;; Flip one character of the MAC half, differentially: an absolute
        ;; replacement would be the identity one time in 64.
        tampered  (when v
                    (let [[data mac] (str/split v #"--")]
                      (str data "--" (if (= \A (first mac)) "B" "A") (subs mac 1))))
        read-with (fn [config cookie] ((echo-app config) (mock/header (mock/request :get "/me") "Cookie" (str "ring-session=" cookie))))]
    (is (= {:status 200 :body "in"} (dissoc login :headers)))
    (is (some? v) (str "the sealed cookie has the expected shape: " (pr-str (:headers login))))
    (is (= {:status 200 :body "{:user \"ann\"}"} (read-with {:key key-b64} v))
        "a fresh instance with the same base64 key reads the session")
    (is (= {:status 200 :body "{:user \"ann\"}"} (read-with {:key key-bytes} v))
        "the same key given as bytes is the same key")
    (is (= {:status 200 :body "{}"} (read-with {:key (byte-array (range 1 17))} v))
        "a different key reads nothing")
    (is (= {:status 200 :body "{}"} (read-with {:key key-b64} (or tampered "")))
        "a cookie whose MAC was altered reads nothing")
    (is (= {:status 200 :body "{}"} ((echo-app {:key key-b64}) (mock/request :get "/me")))
        "control: no cookie, no session")))
