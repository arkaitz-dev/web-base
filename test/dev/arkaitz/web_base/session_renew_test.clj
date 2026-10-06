(ns dev.arkaitz.web-base.session-renew-test
  "`:session :renew`: a used session slides forward once per window, until its cap, and
  a session someone else just set — a sign-in, a sign-out, a revocation — is never
  replaced. Observed through a store that records every call it receives, and through
  the Set-Cookie header the browser would get."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base.session :as session]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.session.store :as store :refer [SessionStore]]
            [ring.mock.request :as mock]
            [ring.util.codec :as codec])
  (:import [clojure.lang ExceptionInfo]
           [java.time Duration]))

(def ^:private every-ms 600000)
(def ^:private absolute-ms (* 12 3600000))

(defn- recording-store [sessions calls]
  (reify SessionStore
    (read-session   [_ k]      (swap! calls conj [:read k])       (get sessions k))
    (write-session  [_ k data] (swap! calls conj [:write k data]) (or k "fresh"))
    (delete-session [_ k]      (swap! calls conj [:delete k])     nil)))

(defn- through
  "`[response writes deletes]` for `handler` behind the session middleware with `:renew`,
  over a store holding `stored` under the key \"old\", for a request carrying that
  cookie (or none, when `anonymous?`)."
  [handler stored & {:keys [anonymous?]}]
  (let [calls   (atom [])
        app     (session/wrap handler {:store        (recording-store {"old" stored} calls)
                                       :cookie-attrs {:max-age 3600}
                                       :renew        {:every-ms every-ms :absolute-ms absolute-ms}})
        request (cond-> (mock/request :get "/") (not anonymous?) (mock/header "Cookie" "ring-session=old"))
        response (app request)]
    [response
     (filterv #(= :write (first %)) @calls)
     (filterv #(= :delete (first %)) @calls)]))

(def ^:private plain (fn [_] {:status 200 :body "x"}))

(defn- ago [ms] (- (System/currentTimeMillis) ms))

(deftest a-session-last-written-a-window-ago-is-written-again-once--with-its-cookie
  (let [before (System/currentTimeMillis)
        born   (ago 3600000)
        [response writes] (through plain {:user "ann" ::session/renewed-at (ago every-ms) ::session/born-at born})
        after  (System/currentTimeMillis)
        [[_ k data]] writes]
    (is (= 1 (count writes)) (str "one write: " writes))
    (is (= "old" k) "under the same key, so whatever refers to it — a device row — still does")
    (is (= [{:user "ann" ::session/born-at born}] [(dissoc data ::session/renewed-at)])
        "the data as it was, born when it was born")
    (is (<= before (::session/renewed-at data) after) "renewed now")
    (is (= ["ring-session=old; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=3600"]
           (get-in response [:headers "Set-Cookie"]))
        "and the cookie sent again with its Max-Age, so the browser keeps it as long as the row")))

(deftest a-session-written-within-the-window-is-left-alone
  (let [[response writes] (through plain {:user "ann" ::session/renewed-at (ago (quot every-ms 2)) ::session/born-at (ago 3600000)})]
    (is (= [] writes) "no write: a page in steady state writes nothing")
    (is (nil? (get-in response [:headers "Set-Cookie"])) "and sends no cookie")))

(deftest a-session-past-its-cap-is-no-longer-renewed
  (let [[_ writes] (through plain {:user "ann" ::session/renewed-at (ago every-ms) ::session/born-at (ago absolute-ms)})]
    (is (= [] writes) "it lives out its last lifetime and ends")))

(deftest a-session-from-before-renewal-was-on-is-renewed-and-stamped
  (let [before (System/currentTimeMillis)
        [_ writes] (through plain {:user "ann"})
        after  (System/currentTimeMillis)
        [[_ _ data]] writes]
    (is (= 1 (count writes)))
    (is (<= before (::session/born-at data) after) "born now")
    (is (= (::session/renewed-at data) (::session/born-at data)) "and renewed in the same instant")))

(deftest a-request-with-no-session-writes-nothing
  (let [[response writes] (through plain nil :anonymous? true)]
    (is (= [] writes) "a probe or an anonymous page costs no row")
    (is (nil? (get-in response [:headers "Set-Cookie"])))))

(deftest a-session-the-handler-set-is-never-replaced--only-stamped
  (let [stored {:user "ann" ::session/renewed-at (ago every-ms) ::session/born-at (ago 3600000)}]
    (let [[_ writes deletes] (through (fn [_] {:status 200 :session nil}) stored)]
      (is (= [[[:delete "old"]] []] [deletes writes]) "a sign-out's nil deletes, and nothing writes it back"))
    (let [before (System/currentTimeMillis)
          [response writes deletes] (through (fn [_] (session/rotate {:status 200} (assoc stored :user "bob"))) stored)
          after  (System/currentTimeMillis)
          [[_ k data]] writes]
      (is (= [[[:delete "old"]] 1 nil] [deletes (count writes) k]) "a sign-in deletes the old key and writes under a new one")
      (is (= "bob" (:user data)) "with the session it set")
      (is (<= before (::session/born-at data) (::session/renewed-at data) after)
          "born and renewed at the sign-in, not inherited from the session it copied")
      (is (str/starts-with? (first (get-in response [:headers "Set-Cookie"])) "ring-session=fresh") "the new cookie"))
    (let [before (System/currentTimeMillis)
          [response writes] (through (fn [_] {:status 200 :session (assoc stored :token "t")}) stored)
          after  (System/currentTimeMillis)
          [[_ k data]] writes]
      (is (= [1 "old" "t"] [(count writes) k (:token data)]) "a handler's own write is one write, with its data")
      (is (= (::session/born-at stored) (::session/born-at data)) "keeping when the session was born")
      (is (<= before (::session/renewed-at data) after) "and counting as a renewal")
      (is (= ["ring-session=old; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=3600"]
             (get-in response [:headers "Set-Cookie"]))
          "so its cookie goes again: a stamp without it would let the cookie die before the next renewal"))
    (let [[_ writes] (through (fn [_] {:status 200 :session {:user "ann" :token "t"}}) stored)
          [[_ k data]] writes]
      (is (= [1 "old" (::session/born-at stored)] [(count writes) k (::session/born-at data)])
          "a fresh map under the same key is the same session, so it cannot reset the cap"))
    (let [[response writes] (through (fn [_] {:status 200 :session (assoc stored :token "t")
                                              :session-cookie-attrs {:max-age 99}})
                                     stored)]
      (is (= 1 (count writes)))
      (is (str/ends-with? (first (get-in response [:headers "Set-Cookie"])) "Max-Age=99")
          "cookie attributes the handler set are its own, and kept"))))

(deftest under-the-cookie-store-a-due-session-is-sealed-again-and-a-fresh-one-is-not
  (let [key    (byte-array (range 16))
        sealed (fn [renewed] (store/write-session (cookie/cookie-store {:key key}) nil
                                                  {:user "ann" ::session/renewed-at renewed
                                                   ::session/born-at (ago 3600000)}))
        app    (session/wrap plain {:key key :cookie-attrs {:max-age 3600}
                                    :renew {:every-ms every-ms :absolute-ms absolute-ms}})
        visit  (fn [value] (get-in (app (mock/header (mock/request :get "/") "Cookie" (str "ring-session=" (codec/form-encode value))))
                                   [:headers "Set-Cookie"]))
        due    (sealed (ago every-ms))
        [cookie-sent] (visit due)]
    (is (and (string? cookie-sent) (str/ends-with? cookie-sent "Max-Age=3600")
             (not (str/includes? cookie-sent (codec/form-encode due))))
        (str "a due session goes back sealed anew, with its Max-Age: " cookie-sent))
    (is (nil? (visit (sealed (ago (quot every-ms 2))))) "a fresh one sends nothing")))

(deftest over-a-key-store-a-handler-write-past-the-cap-sends-no-cookie
  (let [[response writes] (through (fn [_] {:status 200 :session {:user "ann" :token "t"}})
                                   {:user "ann" ::session/renewed-at (ago every-ms) ::session/born-at (ago absolute-ms)})]
    (is (= 1 (count writes)) "the handler's write happens, as Ring makes it")
    (is (nil? (get-in response [:headers "Set-Cookie"])) "but it does not carry the cookie past the cap")))

(deftest a-handler-that-answers-nothing-writes-nothing
  (let [[response writes] (through (constantly nil) {:user "ann" ::session/renewed-at (ago every-ms) ::session/born-at (ago 3600000)})]
    (is (nil? response))
    (is (= [] writes))))

(deftest renew-options-are-refused-at-construction-naming-the-key
  (doseq [[cfg key] [[{:renew {:every-ms 0 :absolute-ms 10}} [:session :renew :every-ms]]
                     [{:renew {:absolute-ms 10}} [:session :renew :every-ms]]
                     [{:renew {:every-ms 10}} [:session :renew :absolute-ms]]
                     [{:renew {:every-ms 10 :absolute-ms 10}} [:session :renew :absolute-ms]]
                     [{:renew {:every-ms 10 :absolute-ms 20.5}} [:session :renew :absolute-ms]]
                     [{:renew {:every-ms 60000 :absolute-ms 120000} :cookie-attrs {:max-age 60}} [:session :renew :every-ms]]
                     [{:renew {:every-ms 60000 :absolute-ms 120000} :cookie-attrs {:max-age (Duration/ofMinutes 1)}}
                      [:session :renew :every-ms]]
                     [{:renew {:every-ms 60000 :absolute-ms 120000} :cookie-attrs {:max-age Long/MAX_VALUE}} nil]]]
    (is (= (when key {:config-key key})
           (ex-data (try (session/wrap plain (assoc cfg :key (byte-array 16))) nil (catch ExceptionInfo e e))))
        (pr-str cfg)))
  (is (fn? (session/wrap plain {:key (byte-array 16) :renew {:every-ms 59999 :absolute-ms 120000}
                                :cookie-attrs {:max-age 60}}))
      "control: a window just inside the cookie's life is accepted"))
