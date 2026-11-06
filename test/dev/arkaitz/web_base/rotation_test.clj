(ns dev.arkaitz.web-base.rotation-test
  "A response that rotates the session, through the whole stack with CSRF on — which no
  earlier test ran: every login in the suite redirected or answered a plain string. The
  rule under test is `security/rotated-session`'s: the new session holds the token
  minted for it in this request, or a fresh one, and never one from before the rotation.

  Since 0.21.0 an anonymous session holds no token — a token is born only at a
  rotation — so the session every test starts from has signed in once already, and the
  rotation under test is the second: a step-up or a sign-in as someone else, whose
  pre-rotation token whoever fixed the session may know, as they once knew a
  pre-login one.

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

(defn- with-lazy-form
  "`with-form` with its field built lazily, from the rotated request the layout is
  given — so the page carries only the fresh token, which no check may mistake for a
  stale one."
  [{:keys [content request]}]
  [:main content [:form (for [_ [1]] (security/csrf-field request))]])

(def ^:private lazy-read-after-minting
  "Whether the last lazy read below ran after `rotate-token` had minted the new
  session's token — the witness that the read really was lazy."
  (atom nil))

(defn- lazy-login
  "A login whose content reads the pre-rotation token lazily: realised only as the page
  is rendered, after `rotate-token` has looked."
  [r]
  (session/rotate {:status 200
                   :body   [:p (for [_ [1]]
                                 (do (reset! lazy-read-after-minting
                                             (some? (some-> (::security/csrf-fresh r) deref)))
                                     (security/csrf-field r)))]}
                  (assoc (:session r) :user "ann")))

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
              ["/login-lazy" {:wb/layouts [with-form] :post lazy-login}]
              ["/login-plain" {:post (fn [r] (session/rotate {:status 200 :body [:p "welcome"]}
                                                             (assoc (:session r) :user "ann")))}]
              ["/login-lazy-layout" {:wb/layouts [with-lazy-form]
                                     :post (fn [r] (session/rotate {:status 200 :body [:p "welcome"]}
                                                                   (assoc (:session r) :user "ann")))}]
              ;; The same three logins under a path carrying a secret the route hides, as a
              ;; magic link's redemption would.
              ["/redeem-string/:token" {:wb/log-path :template
                                        :post (fn [r] (session/rotate {:status 200
                                                                       :body (str "<form>" (security/csrf-token r) "</form>")}
                                                                      (assoc (:session r) :user "ann")))}]
              ["/redeem-content/:token" {:wb/log-path :template :wb/layouts [with-form]
                                         :post (fn [r] (session/rotate {:status 200 :body [:p (security/csrf-field r)]}
                                                                       (assoc (:session r) :user "ann")))}]
              ["/redeem-lazy/:token" {:wb/log-path :template :wb/layouts [with-form] :post lazy-login}]
              ["/post" {:post (fn [_] {:status 200 :body "posted"})}]]}))

(defn- sid [response] (get (testing/cookies response) "ring-session"))

(defn- post
  "A POST with `sid` as its cookie and `token` in the header."
  [handler sid token path]
  (:status (handler (-> (mock/request :post path)
                        (mock/header "Cookie" (str "ring-session=" sid))
                        (mock/header "X-CSRF-Token" token)))))

(defn- signed-in-page
  "`[handler sessions old-sid old-token]` after a first sign-in — an anonymous POST the
  browser vouches for as same-origin — and GETting the form the next rotation starts
  from, which renders the token that sign-in stored."
  []
  (let [sessions (atom {})
        handler  (app sessions)
        old-sid  (sid (handler (mock/header (mock/request :post "/login-plain") "Sec-Fetch-Site" "same-origin")))
        page     (handler (-> (mock/request :get "/form") (mock/header "Cookie" (str "ring-session=" old-sid))))
        old      (testing/csrf-token page)]
    (is (and (some? old) (= old (get-in @sessions [old-sid token-key])))
        "witness: the first sign-in stored a token and the form renders it")
    [handler sessions old-sid old]))

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

(deftest a-login-that-copies-the-old-session-and-redirects-carries-no-pre-rotation-token-over
  ;; The shape the README showed: the old session, and with it its token, copied into the
  ;; new one. Whoever fixed the pre-login session knew that token.
  (let [[handler sessions old-sid old] (signed-in-page)
        response (handler (-> (mock/request :post "/login-redirect")
                              (mock/header "Cookie" (str "ring-session=" old-sid))
                              (mock/header "X-CSRF-Token" old)))
        new-sid  (sid response)]
    (is (= [303 "ann"] [(:status response) (get-in @sessions [new-sid :user])]) "precondition: signed in, redirected")
    ;; Since 0.21.0 a redirect's rotation stores a fresh token instead of none: a rotation
    ;; is the only place a token is born.
    (let [stored (get-in @sessions [new-sid token-key])]
      (is (and (some? stored) (not= old stored)) "the rotated session holds a fresh token, not the pre-rotation one")
      (is (= 403 (post handler new-sid old "/post")) "so the pre-rotation token is refused there")
      (let [page (handler (-> (mock/request :get "/form") (mock/header "Cookie" (str "ring-session=" new-sid))))]
        (is (= stored (testing/csrf-token page)) "the first page after it renders the token the rotation stored")
        (is (= 200 (post handler new-sid stored "/post")) "and that token posts")))))

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

(def ^:private read-before-message "a response that rotates the session read its CSRF token")

(defn- errors [] (filter #(#{:warn :error} (:level %)) (lt/the-log)))

(defn- named-once?
  "Exactly one error in the log, and it is the read-before-rotation one: a second, or
  the other rotation message, is a different defect."
  []
  (let [es (errors)]
    (and (= 1 (count es)) (str/starts-with? (str (:message (first es))) read-before-message))))

(deftest content-that-read-the-token-before-rotating-is-logged-by-name-once
  (let [[handler _ old-sid old] (signed-in-page)]
    (lt/with-log
      (handler (-> (mock/request :post "/login-content")
                   (mock/header "Cookie" (str "ring-session=" old-sid))
                   (mock/header "X-CSRF-Token" old)))
      (is (named-once?) (str "named once in the log: " (mapv :message (errors)))))))

(deftest lazy-content-that-read-the-pre-rotation-token-is-logged-by-name-once
  (let [[handler sessions old-sid old] (signed-in-page)]
    (reset! lazy-read-after-minting nil)
    (lt/with-log
      (let [response (handler (-> (mock/request :post "/login-lazy")
                                  (mock/header "Cookie" (str "ring-session=" old-sid))
                                  (mock/header "X-CSRF-Token" old)))
            new-sid  (sid response)
            stored   (get-in @sessions [new-sid token-key])]
        (is (= 200 (:status response)) "witness: the login answered with a page")
        (is (and (some? new-sid) (not= old-sid new-sid)) "witness: and rotated the session id")
        (is (true? @lazy-read-after-minting) "witness: the content read the token lazily, after the new one was minted")
        (is (str/includes? (:body response) old) "witness: the page shows the pre-rotation token")
        (is (and (some? stored) (not= old stored)) "witness: the rotated session holds a token of its own")
        (is (= 403 (post handler new-sid old "/post")) "so the form built with the old token is refused")
        (is (= 200 (post handler new-sid stored "/post")) "while the session's own token still posts")
        (is (named-once?) (str "and that is named once in the log: " (mapv :message (errors))))))))

(deftest a-lazy-read-in-an-htmx-login-fragment-is-logged-by-name-once
  (let [[handler _ old-sid old] (signed-in-page)]
    (reset! lazy-read-after-minting nil)
    (lt/with-log
      (let [response (handler (-> (mock/request :post "/login-lazy")
                                  (mock/header "Cookie" (str "ring-session=" old-sid))
                                  (mock/header "X-CSRF-Token" old)
                                  (mock/header "HX-Request" "true")))]
        (is (= "true" (get-in response [:headers "HX-Refresh"])) "witness: answered as a fragment that rotated")
        (is (true? @lazy-read-after-minting) "witness: the content read the token lazily, after the new one was minted")
        (is (str/includes? (:body response) old) "witness: the fragment shows the pre-rotation token")
        (is (named-once?) (str "and that is named once in the log: " (mapv :message (errors))))))))

(deftest rotating-content-that-renders-only-the-fresh-token-logs-nothing
  (let [[handler sessions old-sid old] (signed-in-page)]
    (lt/with-log
      (let [response (handler (-> (mock/request :post "/login-lazy-layout")
                                  (mock/header "Cookie" (str "ring-session=" old-sid))
                                  (mock/header "X-CSRF-Token" old)))
            stored   (get-in @sessions [(sid response) token-key])]
        (is (some? stored) "witness: the rotated session holds a token")
        (is (str/includes? (:body response) stored) "witness: the lazy layout rendered that token")
        (is (not (str/includes? (:body response) old)) "witness: and not the pre-rotation one")
        (is (= 200 (post handler (sid response) stored "/post")) "so the page's form posts")
        (is (empty? (errors)) (str "and nothing is logged: " (mapv :message (errors))))))
    (let [[handler sessions old-sid old] (signed-in-page)]
      (lt/with-log
        (let [response (handler (-> (mock/request :post "/login-plain")
                                    (mock/header "Cookie" (str "ring-session=" old-sid))
                                    (mock/header "X-CSRF-Token" old)))
              new-sid  (sid response)]
          (is (= [200 "ann"] [(:status response) (get-in @sessions [new-sid :user])])
              "witness: a rotating page that renders no token at all")
          (is (and (some? new-sid) (not= old-sid new-sid)) "witness: and it rotated")
          (is (empty? (errors)) (str "logs nothing either: " (mapv :message (errors)))))))
    (lt/with-log
      (handler (-> (mock/request :get "/form") (mock/header "Cookie" (str "ring-session=" old-sid))))
      (is (empty? (errors)) (str "control: a page that rotates nothing logs nothing: " (mapv :message (errors)))))))

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

(deftest the-rotation-errors-show-a-marked-routes-template-and-never-its-secret
  (doseq [[path message] [["/redeem-string/SECRET-TOKEN" "a response that rotates the session rendered a CSRF token"]
                          ["/redeem-content/SECRET-TOKEN" read-before-message]
                          ["/redeem-lazy/SECRET-TOKEN" read-before-message]]]
    (let [[handler _ old-sid old] (signed-in-page)]
      (lt/with-log
        (handler (-> (mock/request :post path)
                     (mock/header "Cookie" (str "ring-session=" old-sid))
                     (mock/header "X-CSRF-Token" old)))
        (let [error (first (filter #(str/starts-with? (str (:message %)) message) (lt/the-log)))]
          (is (some? error) (str path ": witness: the rotation error was logged: " (mapv :message (lt/the-log))))
          (is (str/includes? (str (:message error)) (str/replace path "SECRET-TOKEN" ":token"))
              (str path ": its datum names the route by its template: " (:message error)))
          (is (not (str/includes? (pr-str (mapv :message (lt/the-log))) "SECRET-TOKEN"))
              (str path ": and no line carries the secret: " (mapv :message (lt/the-log))))
          (is (not (str/includes? (pr-str (mapv :message (lt/the-log))) old))
              (str path ": nor the CSRF token: " (mapv :message (lt/the-log)))))))))
