(ns dev.arkaitz.web-base.rotation-test
  "A response that rotates the session, through the whole stack with CSRF on — which no
  earlier test ran: every login in the suite redirected or answered a plain string. The
  rule under test is `security/rotated-session`'s: the new session holds the token
  minted for it in this request, or none, and never one from before the rotation.

  Every observation reads the memory store the stack writes to, and every token is
  proved by posting it, never by comparing it with itself."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock]))

(def ^:private token-key :ring.middleware.anti-forgery/anti-forgery-token)

(defn- with-form
  "A layout that puts a form with the request's token after the content — the one a
  login page renders through the base."
  [{:keys [content request]}]
  [:main content [:form (security/csrf-field request)]])

(defn- app [sessions]
  (wb/handler
   {:session {:store (memory/memory-store sessions)}
    :routes  [["/form" {:wb/layouts [with-form] :get (fn [_] {:status 200 :body [:p "sign in"]})}]
              ["/login-page" {:wb/layouts [with-form]
                              :post (fn [r] (session/rotate {:status 200 :body [:p "welcome"]}
                                                            (assoc (:session r) :user "ann")))}]
              ["/login-redirect" {:post (fn [r] (session/rotate {:status 303 :headers {"Location" "/form"} :body ""}
                                                                (assoc (:session r) :user "ann")))}]
              ["/login-string" {:post (fn [r] (session/rotate {:status 200
                                                               :body (str "<form>" (security/csrf-token r) "</form>")}
                                                              (assoc (:session r) :user "ann")))}]
              ["/login-content" {:wb/layouts [with-form]
                                 :post (fn [r] (session/rotate {:status 200 :body [:p (security/csrf-field r)]}
                                                               (assoc (:session r) :user "ann")))}]
              ["/post" {:post (fn [_] {:status 200 :body "posted"})}]]}))

(defn- sid [response] (get (testing/cookies response) "ring-session"))

(defn- post
  "A POST with `sid` as its cookie and `token` in the header."
  [handler sid token path]
  (:status (handler (-> (mock/request :post path)
                        (mock/header "Cookie" (str "ring-session=" sid))
                        (mock/header "X-CSRF-Token" token)))))

(defn- signed-in-page
  "`[handler sessions old-sid old-token]` after GETting the form a visitor signs in from."
  []
  (let [sessions (atom {})
        handler  (app sessions)
        page     (handler (mock/request :get "/form"))]
    [handler sessions (sid page) (testing/csrf-token page)]))

(deftest a-login-that-renders-a-page-gives-it-the-rotated-sessions-fresh-token
  (let [[handler sessions old-sid old] (signed-in-page)
        _        (is (= 200 (post handler old-sid old "/post")) "control: the pre-login token works before the login")
        response (handler (-> (mock/request :post "/login-page")
                              (mock/header "Cookie" (str "ring-session=" old-sid))
                              (mock/header "X-CSRF-Token" old)))
        new-sid  (sid response)
        fresh    (testing/csrf-token response)]
    (is (= 200 (:status response)) "the login answered with a page")
    (is (and (some? new-sid) (not= old-sid new-sid)) "and rotated the session id")
    (is (and (some? fresh) (not= old fresh)) "the page's form carries a token that is not the pre-login one")
    (is (= fresh (get-in @sessions [new-sid token-key])) "and it is the one the rotated session holds")
    (is (= 200 (post handler new-sid fresh "/post")) "so the form posts")
    (is (= 403 (post handler new-sid old "/post")) "while the pre-login token is refused in the new session")))

(deftest a-login-that-copies-the-old-session-and-redirects-carries-no-pre-login-token-over
  ;; The shape the README showed: the old session, and with it its token, copied into the
  ;; new one. Whoever fixed the pre-login session knew that token.
  (let [[handler sessions old-sid old] (signed-in-page)
        response (handler (-> (mock/request :post "/login-redirect")
                              (mock/header "Cookie" (str "ring-session=" old-sid))
                              (mock/header "X-CSRF-Token" old)))
        new-sid  (sid response)]
    (is (= [303 "ann"] [(:status response) (get-in @sessions [new-sid :user])]) "precondition: signed in, redirected")
    (is (not (contains? (get @sessions new-sid) token-key)) "the rotated session holds no token at all")
    (is (= 403 (post handler new-sid old "/post")) "so the pre-login token is refused there")
    (let [page (handler (-> (mock/request :get "/form") (mock/header "Cookie" (str "ring-session=" new-sid))))]
      (is (= 200 (post handler new-sid (testing/csrf-token page) "/post"))
          "and the first page after it mints one that works"))))

(deftest a-login-rendered-outside-the-base-is-logged-and-its-session-gets-a-token-it-never-showed
  (let [[handler sessions old-sid old] (signed-in-page)]
    (lt/with-log
      (let [response (handler (-> (mock/request :post "/login-string")
                                  (mock/header "Cookie" (str "ring-session=" old-sid))
                                  (mock/header "X-CSRF-Token" old)))
            new-sid  (sid response)
            stored   (get-in @sessions [new-sid token-key])]
        (is (some #(and (= :error (:level %))
                        (str/starts-with? (str (:message %)) "a response that rotates the session rendered a CSRF token"))
                  (lt/the-log))
            (str "named in the log: " (mapv :message (lt/the-log))))
        (is (and (some? stored) (not= old stored)) "the new session holds a fresh token, not the one the page showed")
        (is (= 403 (post handler new-sid old "/post")) "so the page's form is refused, loudly logged rather than silent")))))

(deftest content-that-read-the-token-before-rotating-is-logged-by-name
  (let [[handler _ old-sid old] (signed-in-page)]
    (lt/with-log
      (handler (-> (mock/request :post "/login-content")
                   (mock/header "Cookie" (str "ring-session=" old-sid))
                   (mock/header "X-CSRF-Token" old)))
      (is (some #(str/starts-with? (str (:message %)) "a response that rotates the session read its CSRF token")
                (lt/the-log))
          (str "named in the log: " (mapv :message (lt/the-log)))))))

(deftest an-htmx-login-fragment-asks-for-a-full-reload-to-carry-the-new-token
  ;; The page around a fragment keeps the pre-login token in `<body>`'s hx-headers;
  ;; only a reload can replace it.
  (let [[handler sessions old-sid old] (signed-in-page)
        response (handler (-> (mock/request :post "/login-page")
                              (mock/header "Cookie" (str "ring-session=" old-sid))
                              (mock/header "X-CSRF-Token" old)
                              (mock/header "HX-Request" "true")))
        new-sid  (sid response)]
    (is (= "true" (get-in response [:headers "HX-Refresh"])) "the fragment asks htmx to reload the page")
    (is (some? (get-in @sessions [new-sid token-key])) "and the rotated session holds a fresh token")
    (is (not= old (get-in @sessions [new-sid token-key])) "not the pre-login one")
    (let [reloaded (handler (-> (mock/request :get "/form") (mock/header "Cookie" (str "ring-session=" new-sid))))]
      (is (= 200 (post handler new-sid (testing/csrf-token reloaded) "/post"))
          "and the reloaded page's token posts"))
    (is (nil? (get-in (handler (-> (mock/request :get "/form") (mock/header "HX-Request" "true")))
                      [:headers "HX-Refresh"]))
        "control: a fragment that rotates nothing asks for no reload")))
