(ns dev.arkaitz.web-base.csrf-origin-test
  "The hybrid CSRF of 0.21.0, through the whole stack. A session holding a token is
  checked by it; a session holding none passes an unsafe method only on the browser's
  own word that it came from this origin — `Sec-Fetch-Site` same-origin or none, or, with
  that header absent, an `Origin` equal to the request's scheme and `Host` — and neither
  header is a refusal. A token is minted only when the session rotates, so an anonymous
  page with a form costs no session row. Every request is sent by `testing/call`, which
  adds neither header, so each case states exactly what it sends. A refusal is told from
  any other 403 by the error page's `data-status`; a pass by the handler's own body."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as wt]
            [ring.middleware.session.memory :as memory]))

(def ^:private token-key @#'security/session-token-key)

(defn- app [sessions]
  (wb/handler {:session {:store (memory/memory-store sessions)}
               :routes  [["/page" {:get (fn [r] {:status 200 :body [:form (security/csrf-field r)]})}]
                         ["/p" {:post (fn [_] {:status 200 :body "posted"})}]
                         ["/sign-in" {:post (fn [r] (session/rotate {:status 303 :headers {"Location" "/page"} :body ""}
                                                                    (assoc (:session r) :user "ann")))}]
                         ["/sign-in-page" {:wb/layouts [(fn [{:keys [content request]}] [:main content [:form (security/csrf-field request)]])]
                                           :post (fn [r] (session/rotate {:status 200 :body [:p "welcome"]} (assoc (:session r) :user "ann")))}]
                         ["/sign-out" {:post (fn [_] {:status 303 :headers {"Location" "/page"} :body "" :session nil})}]
                         ["/theme" {:post (fn [r] {:status 200 :body "themed" :session {:user (:user (:session r)) :theme "dark"}})}]
                         ["/cart" {:post (fn [r] {:status 200 :body "added" :session (assoc (:session r) :cart [1])})}]]}))

(defn- body [r] (let [b (:body r)] (if (string? b) b (some-> b slurp))))

(defn- outcome
  "`[status data-status body-if-200]`: the error page's own status marks the base's
  refusal, and only the handler writes \"posted\"."
  [r]
  [(:status r) (second (re-find #"data-status=\"(\d+)\"" (str (body r)))) (when (= 200 (:status r)) (body r))])

(def ^:private refused [403 "403" nil])
(def ^:private posted [200 nil "posted"])

(defn- post
  [h path {:keys [sid headers token]}]
  (wt/call h :post path {:headers      (cond-> (or headers {}) sid (assoc "cookie" (str "ring-session=" sid)))
                         :body         (str "a=1" (when token (str "&__anti-forgery-token=" (java.net.URLEncoder/encode ^String token "UTF-8"))))
                         :content-type "application/x-www-form-urlencoded"}))

(defn- page [h sid]
  (wt/call h :get "/page" {:headers (if sid {"cookie" (str "ring-session=" sid)} {})}))

(defn- sid-of [r] (get (wt/cookies r) "ring-session"))

(deftest an-anonymous-post-is-vouched-for-by-sec-fetch-site-or-a-matching-origin--never-by-less
  (let [sessions (atom {})
        h        (app sessions)]
    (doseq [[headers expected] [[{"sec-fetch-site" "same-origin"} posted]
                                [{"sec-fetch-site" "none"} posted]
                                [{"sec-fetch-site" "same-site"} refused]
                                [{"sec-fetch-site" "cross-site"} refused]
                                [{"sec-fetch-site" "cross-site" "origin" "http://localhost"} refused]
                                [{"sec-fetch-site" "same-origin, cross-site"} refused]
                                [{"origin" "http://localhost"} posted]
                                [{"origin" "HTTP://LOCALHOST"} posted]
                                [{"origin" "null"} refused]
                                [{"origin" "http://evil.test"} refused]
                                [{"origin" "https://localhost"} refused]
                                [{"origin" "http://localhost:80"} refused]
                                [{} refused]]]
      (is (= expected (outcome (post h "/p" {:headers headers})))
          (str "an anonymous POST sending " (pr-str headers) " is " (if (= posted expected) "the handler's" "the 403 refusal"))))
    (is (= {} @sessions) "no request of the matrix wrote a session: the check mints nothing")
    (is (= posted (outcome (post h "/p" {:headers {"sec-fetch-site" "same-origin"} :token "stale-from-an-old-page"})))
        "an anonymous POST from its own origin carrying a token from an older page is checked by origin, not refused")
    (is (= refused (outcome (post h "/p" {:headers {"sec-fetch-site" "cross-site"} :token "stale-from-an-old-page"})))
        "and the same token cross-site is refused")))

(deftest the-origin-is-compared-with-the-host-header--never-with-the-server-name
  (let [h (app (atom {}))
        raw (fn [origin & [host]]
              (h {:request-method :post :uri "/p" :scheme :http :server-name "localhost" :server-port 80
                  :remote-addr "127.0.0.1" :protocol "HTTP/1.1"
                  :headers {"host" (or host "app.test") "origin" origin "content-type" "application/x-www-form-urlencoded"}
                  :content-type "application/x-www-form-urlencoded"
                  :body (java.io.ByteArrayInputStream. (.getBytes "a=1" "UTF-8"))}))]
    (is (= posted (outcome (raw "http://app.test"))) "Origin equal to scheme and Host passes, whatever :server-name says")
    (is (= posted (outcome (raw "http://app.test" "APP.Test"))) "the Host is compared lower-cased, as the Origin is")
    (is (= refused (outcome (raw "http://localhost"))) "Origin equal to :server-name but not to Host is refused")))

(deftest a-session-holding-a-token-needs-it--its-origin-does-not-substitute
  (let [sessions (atom {})
        h        (app sessions)
        signed   (post h "/sign-in" {:headers {"sec-fetch-site" "same-origin"}})
        sid      (sid-of signed)
        row      (get @sessions sid)
        stored   (get row token-key)]
    (is (and (= 303 (:status signed)) (some? sid) (= 1 (count @sessions)) (= "ann" (:user row)))
        "witness: the anonymous sign-in passed by origin and rotated into one row holding the user")
    (is (and (string? stored) (not (str/blank? stored)))
        "the rotation minted a token into the session, before any page rendered one")
    (is (= refused (outcome (post h "/p" {:sid sid :headers {"sec-fetch-site" "same-origin"}})))
        "a session holding a token is refused without it, even from its own origin")
    (let [rendered (page h sid)
          token    (wt/csrf-token rendered)
          wrong    (str "x" token)]
      (is (= stored token) "the page renders the token the rotation stored")
      (is (= "no-store" (wt/header rendered "Cache-Control")) "a page carrying a token is not cached")
      (is (= posted (outcome (post h "/p" {:sid sid :token token :headers {"sec-fetch-site" "same-origin"}})))
          "with its token it passes")
      (is (= posted (outcome (post h "/p" {:sid sid :token token :headers {"sec-fetch-site" "cross-site"}})))
          "the token decides: its origin is not consulted")
      (is (= refused (outcome (post h "/p" {:sid sid :token wrong :headers {"sec-fetch-site" "same-origin"}})))
          "a wrong token is refused from its own origin")
      (let [themed (post h "/theme" {:sid sid :token token :headers {"sec-fetch-site" "same-origin"}})]
        (is (and (= 200 (:status themed)) (= {:user "ann" :theme "dark"} (dissoc (get @sessions sid) token-key)))
            "witness: the handler replaced the session with a map of its own, holding no token")
        (is (= stored (get-in @sessions [sid token-key])) "the session keeps its token through it")
        (is (= refused (outcome (post h "/p" {:sid sid :headers {"sec-fetch-site" "same-origin"}})))
            "so it still needs the token: it never falls to the origin check"))
      (let [out (post h "/sign-out" {:sid sid :token token :headers {"sec-fetch-site" "same-origin"}})]
        (is (= 303 (:status out)) "witness: the sign-out passed the token check")
        (is (= {} @sessions) "a sign-out ends the session: no token is put back into a session set to nil")
        (is (nil? (wt/csrf-token (page h sid))) "and its old cookie renders no token")))))

(deftest a-first-sign-in-that-renders-a-page-shows-the-token-its-new-session-holds
  (let [sessions (atom {})
        h        (app sessions)
        r        (post h "/sign-in-page" {:headers {"sec-fetch-site" "same-origin"}})
        sid      (sid-of r)
        token    (wt/csrf-token r)]
    (is (and (= 200 (:status r)) (some? sid) (= "ann" (get-in @sessions [sid :user])))
        "witness: an anonymous sign-in passed by origin and rotated into a row holding the user")
    (is (and (string? token) (= token (get-in @sessions [sid token-key])))
        "the page it rendered carries the token the new session holds")
    (is (= posted (outcome (post h "/p" {:sid sid :token token :headers {"sec-fetch-site" "same-origin"}})))
        "so its form posts")))

(deftest an-anonymous-form-page-renders-no-token-writes-no-row-and-stays-cacheable
  (let [sessions (atom {})
        h        (app sessions)
        r        (page h nil)]
    (is (= 200 (:status r)) "witness: the page was served")
    (is (and (nil? (wt/csrf-token r)) (not (str/includes? (str (body r)) "__anti-forgery-token")))
        "an anonymous page renders no token")
    (is (and (nil? (sid-of r)) (= {} @sessions)) "and writes no session")
    (is (nil? (wt/header r "Cache-Control")) "and is not marked uncacheable by the CSRF read")))

(deftest an-anonymous-session-with-a-cart-is-still-vouched-for-by-origin--a-token-is-never-minted-lazily
  (let [sessions (atom {})
        h        (app sessions)
        added    (post h "/cart" {:headers {"sec-fetch-site" "same-origin"}})
        sid      (sid-of added)]
    (is (and (= [200 "added"] [(:status added) (body added)]) (some? sid) (= {:cart [1]} (get @sessions sid)))
        "witness: the cart is this session's row, and it holds no token")
    (let [r (page h sid)]
      (is (nil? (wt/csrf-token r)) "a page under that session renders no token")
      (is (= {:cart [1]} (get @sessions sid)) "nor writes one into the row"))
    (is (= posted (outcome (post h "/p" {:sid sid :headers {"sec-fetch-site" "same-origin"}})))
        "a tab opened before anything changed still posts, by origin")
    (is (= refused (outcome (post h "/p" {:sid sid :headers {"sec-fetch-site" "cross-site"}})))
        "and a cross-site post into that session is refused")))

(deftest visit-sends-both-origin-headers-on-an-unsafe-method-and-neither-on-a-get
  (let [echo (fn [r] {:status 200 :headers {"Content-Type" "text/plain"}
                      :body (pr-str (select-keys (:headers r) ["sec-fetch-site" "origin"]))})
        sent (fn [method] (:body (:response (wt/visit (wt/browser echo) method "/x" {} (when (not= :get method) {:htmx? (not= :post method)})))))]
    (is (= "{}" (sent :get)) "a GET carries neither: nothing checks it")
    (doseq [method [:post :put :patch :delete]]
      (is (= (pr-str {"sec-fetch-site" "same-origin" "origin" "http://localhost"}) (sent method))
          (str (name method) " carries both, as a browser sends them from the site's own page")))))
