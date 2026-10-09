(ns dev.arkaitz.web-base.log-path-test
  "A request that misses a route marked `:wb/log-path :template` — a trailing slash, an
  appended segment, a truncated link — logs the template's static part and an ellipsis,
  so the secret it carried reaches no log line (since 0.17.0). Observed on the access
  line and on every captured entry, as an operator reads them; each expected line was
  measured. Redacted cells and the controls that do carry the token are captured apart,
  or the sweep would red on the control."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [ring.mock.request :as mock]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- ok [_] {:status 200 :body ""})

(defn- app [routes & {:as more}]
  (wb/handler (merge {:session {:key KEY} :routes routes} more)))

(defn- log-lines
  "Every entry captured, as text, the throwable's message and data included."
  []
  (pr-str (mapv (juxt :logger-ns :level :message #(some-> % :throwable ex-message) #(some-> % :throwable ex-data))
                (lt/the-log))))

(defn- access-lines []
  (->> (lt/the-log)
       (filter #(= 'dev.arkaitz.web-base.log (ns-name (:logger-ns %))))
       (mapv #(str/replace (:message %) #"\d+ms$" "<n>ms"))))

(defn- lines
  "The access lines of `requests` sent to `handler`, and the whole log as text."
  [handler requests]
  (lt/with-log
    (doseq [[method path] requests] (handler (mock/request method path)))
    [(access-lines) (log-lines)]))

(def ^:private marked
  [["/t/:token" {:wb/log-path :template :get ok :post ok}]
   ["/login/redeem/:token" {:wb/log-path :template :get ok}]
   ["/admin" {:wb/log-path :template} ["/:secret" {:get ok}]]
   ["/confirm/:token/now" {:wb/log-path :template :get ok}]])

(deftest a-near-miss-under-a-marked-prefix-logs-that-prefix-and-an-ellipsis--the-token-on-no-line
  (let [h (app marked)
        [access text] (lines h [[:get "/t/SECRET-TOKEN/"] [:get "/t/SECRET-TOKEN/extra"] [:get "/t/"]
                                [:get "/t/SECRET-TOKEN/?next=%2Fx"] [:post "/t/SECRET-TOKEN/"]
                                [:get "/login/redeem/SECRET-TOKEN/"] [:get "/login/redeem/"]
                                [:get "/admin/SECRET-TOKEN/"] [:get "/confirm/SECRET-TOKEN"]
                                [:get "/confirm/SECRET-TOKEN/now/"]])]
    (is (= 10 (count access)) "witness: one access line per request")
    (is (= ["GET /t/… 404 <n>ms" "GET /t/… 404 <n>ms" "GET /t/… 404 <n>ms" "GET /t/… 404 <n>ms" "POST /t/… 403 <n>ms"
            "GET /login/redeem/… 404 <n>ms" "GET /login/redeem/… 404 <n>ms" "GET /admin/… 404 <n>ms"
            "GET /confirm/… 404 <n>ms" "GET /confirm/… 404 <n>ms"]
           access)
        "each near miss logs the marked template's static part — a parent's mark included — and an ellipsis")
    (is (not (str/includes? text "SECRET-TOKEN")) (str "the token reaches no log line: " text)))
  (let [[access] (lines (app marked) [[:get "/t"] [:get "/login/redeem"]])]
    (is (= ["GET /t 404 <n>ms" "GET /login/redeem 404 <n>ms"] access)
        "control: a path outside the prefix, which carries nothing to hide, logs as it came")))

(defn- store-that-throws []
  (reify ring.middleware.session.store/SessionStore
    (read-session [_ _] (throw (java.sql.SQLException. "pool closed: jdbc:SECRET-URL")))
    (write-session [_ k _] k)
    (delete-session [_ _] nil)))

(deftest a-session-store-that-throws-on-a-near-miss-logs-the-redacted-path-on-the-error-line-too
  (doseq [[path line uri] [["/t/SECRET-TOKEN/" "GET /t/… 500 <n>ms" "/t/…"]
                           ["/things/SECRET-TOKEN/" "GET /things/SECRET-TOKEN/ 500 <n>ms" "/things/SECRET-TOKEN/"]]]
    (let [h (app [["/t/:token" {:wb/log-path :template :get ok}] ["/things/:id" {:get ok}]]
                 :session {:store (store-that-throws)})]
      (lt/with-log
        (let [r      (h (mock/header (mock/request :get path) "Cookie" "ring-session=old"))
              errors (filter #(= :error (:level %)) (lt/the-log))]
          (is (= 500 (:status r)) (str path ": witness: the store threw before the router ran"))
          (is (= 1 (count errors)) (str path ": witness: the boundary logged it, once"))
          (is (= [line] (access-lines)) (str path ": the access line"))
          (is (re-matches (re-pattern (str "^unhandled exception \\{:request-id [A-Za-z0-9_-]{16}, :uri \\Q" uri "\\E\\}$"))
                          (str (:message (first errors))))
              (str path ": the error line names the same path: " (:message (first errors)))))))))

(deftest redaction-reaches-only-a-miss-under-a-marked-prefix
  (let [h (app [["/t/:token" {:wb/log-path :template :get ok}] ["/t/stats/all" {:get ok}] ["/things/:id" {:get ok}]])]
    (is (= ["GET /t/:token 200 <n>ms" "GET /t/stats/all 200 <n>ms" "GET /t/:token 200 <n>ms" "GET /t/… 404 <n>ms"]
           (first (lines h [[:get "/t/SECRET-TOKEN"] [:get "/t/stats/all"] [:get "/t/stats"] [:get "/t/stats/all/"]])))
        "a matched marked route logs its template; a matched unmarked one under the prefix its own path; a miss there the prefix")
    (is (= ["GET /things/SECRET-TOKEN/ 404 <n>ms" "GET /nowhere/SECRET-TOKEN 404 <n>ms" "GET /x/t/SECRET-TOKEN/ 404 <n>ms"]
           (first (lines h [[:get "/things/SECRET-TOKEN/"] [:get "/nowhere/SECRET-TOKEN"] [:get "/x/t/SECRET-TOKEN/"]])))
        "a miss under an unmarked route, under no route, or with a marked prefix mid-path logs as it came")
    (testing "the documented residuals: the prefix is compared as spelt"
      (is (= ["GET /T/SECRET-TOKEN/ 404 <n>ms" "GET /t%2FSECRET-TOKEN 404 <n>ms"]
             (first (lines h [[:get "/T/SECRET-TOKEN/"] [:get "/t%2FSECRET-TOKEN"]])))
          "another case, or an encoded slash, is no near miss of the route and logs as it came"))))

(deftest the-longest-marked-prefix-wins-whatever-the-order--and-an-unmarked-route-mints-none
  (let [shallow ["/t/:token" {:wb/log-path :template :get ok}]
        deep    ["/t/x/:token" {:wb/log-path :template :get ok}]
        cells   [[:get "/t/x/SECRET-TOKEN/"] [:get "/t/x/"] [:get "/t/SECRET-TOKEN/"] [:get "/t/xSECRET/"] [:get "/t/x"]]
        want    ["GET /t/x/… 404 <n>ms" "GET /t/x/… 404 <n>ms" "GET /t/… 404 <n>ms" "GET /t/… 404 <n>ms" "GET /t/:token 200 <n>ms"]]
    (is (= want (first (lines (app [shallow deep]) cells))) "the most specific marked route an operator can act on")
    (is (= want (first (lines (app [deep shallow]) cells))) "and the same declared deepest first")
    (is (= ["GET /t/x/… 404 <n>ms" "GET /t/42/ 404 <n>ms" "GET /t/x/… 404 <n>ms" "GET /t/x 200 <n>ms"]
           (first (lines (app [deep ["/t/:id" {:get ok}]])
                         [[:get "/t/x/SECRET-TOKEN/"] [:get "/t/42/"] [:get "/t/x/"] [:get "/t/x"]])))
        "an unmarked shallower route mints no prefix")))

(deftest a-marked-route-starting-with-its-parameter-makes-every-unmatched-request-log-as-a-bare-ellipsis
  (let [h (app [["/:token" {:wb/log-path :template :get ok :post ok}] ["/auth/login" {:get ok}]]
               :sessionless {"/h/x" ok})]
    (is (= ["GET /… 404 <n>ms" "GET /… 404 <n>ms" "GET /… 404 <n>ms" "GET /… 404 <n>ms" "POST /… 403 <n>ms"
            "GET /… 200 <n>ms" "GET /… 404 <n>ms" "GET /auth/login 200 <n>ms" "GET /:token 200 <n>ms"]
           (first (lines h [[:get "/SECRET-TOKEN/"] [:get "/"] [:get "/a/b/c"] [:get "/auth/login/"] [:post "/SECRET-TOKEN/"]
                            [:get "/h/x"] [:get "/h/x/y"] [:get "/auth/login"] [:get "/SECRET-TOKEN"]])))
        "every request no route matches — a sessionless one answered 200 included — logs as /…; a match as before")))

(deftest a-sessionless-request-is-redacted-only-under-a-marked-prefix
  (let [h (app [["/t/:token" {:wb/log-path :template :get ok}]] :sessionless {"/probe" ok "/t/a/b" ok})]
    (is (= ["GET /probe 200 <n>ms" "GET /t/… 200 <n>ms" "GET /t/… 404 <n>ms"]
           (first (lines h [[:get "/probe"] [:get "/t/a/b"] [:get "/t/a/b/c"]])))
        "a sessionless path matches no route: outside every marked prefix it logs as it came, under one it is redacted"))
  (let [h (app [["/t/:token" {:wb/log-path :template :get ok}]]
               :sessionless {"/t/a/b" (fn [_] (throw (ex-info "boom" {:leak "SECRET-DATA"})))})]
    (lt/with-log
      (let [r      (h (mock/request :get "/t/a/b"))
            errors (filter #(= :error (:level %)) (lt/the-log))]
        (is (= [500 1] [(:status r) (count errors)]) "witness: the sessionless handler threw, logged once")
        (is (= ["GET /t/… 500 <n>ms"] (access-lines)) "the access line is redacted")
        (is (re-matches #"^unhandled exception \{:request-id [A-Za-z0-9_-]{16}, :uri /t/…\}$" (str (:message (first errors))))
            "and so is the error line's path")
        (is (= {:leak "SECRET-DATA"} (ex-data (:throwable (first errors))))
            "the documented limit: the host's own ex-info data is logged as thrown")))))

(deftest a-plugins-marked-route-redacts-its-near-misses-like-a-hosts
  (let [h (wb/handler {:session {:key KEY}
                       :routes  [["/home" {:get ok}]]
                       :plugins [{:wb.plugin/name :auth
                                  :routes [["/login/redeem/:token" {:wb/log-path :template :get ok}]
                                           ["/addresses/confirm/:token" {:wb/log-path :template :get ok}]]}]})
        [access text] (lines h [[:get "/addresses/confirm/SECRET-TOKEN"] [:get "/login/redeem/SECRET-TOKEN/"]
                                [:get "/addresses/confirm/SECRET-TOKEN/x"]])]
    (is (= ["GET /addresses/confirm/:token 200 <n>ms" "GET /login/redeem/… 404 <n>ms" "GET /addresses/confirm/… 404 <n>ms"]
           access)
        "witness first: the plugin's route is mounted; then its near misses are redacted without the host marking anything")
    (is (not (str/includes? text "SECRET-TOKEN")) (str "the token reaches no line: " text))
    (is (= ["GET /login/redeem 404 <n>ms"] (first (lines h [[:get "/login/redeem"]])))
        "control: outside the prefix, as it came")))

(deftest the-static-prefix-ends-at-the-first-parameter-of-any-spelling--a-marked-route-without-one-mints-none
  (doseq [[template requests expected]
          [["/files-:id" [[:get "/files-SECRET"] [:get "/files-SECRET/"] [:get "/files"] [:get "/filesSECRET"]]
            ["GET /files-:id 200 <n>ms" "GET /files-… 404 <n>ms" "GET /files 404 <n>ms" "GET /filesSECRET 404 <n>ms"]]
           ["/users/{id}.json" [[:get "/users/SECRET.json"] [:get "/users/SECRET"] [:get "/users/"]]
            ["GET /users/{id}.json 200 <n>ms" "GET /users/… 404 <n>ms" "GET /users/… 404 <n>ms"]]
           ["/files/*path" [[:get "/files/a/b"] [:get "/files/"] [:get "/files"]]
            ["GET /files/*path 200 <n>ms" "GET /files/*path 200 <n>ms" "GET /files 404 <n>ms"]]
           ["/t/:token/now" [[:get "/t/SECRET/now"] [:get "/t/SECRET"] [:get "/t/SECRET/now/"]]
            ["GET /t/:token/now 200 <n>ms" "GET /t/… 404 <n>ms" "GET /t/… 404 <n>ms"]]
           ["/secretpage" [[:get "/secretpage"] [:get "/secretpage/x"]]
            ["GET /secretpage 200 <n>ms" "GET /secretpage/x 404 <n>ms"]]
           ;; Two spellings in one template: the earliest parameter ends the prefix,
           ;; whichever spelling it is.
           ["/{org}/t/:token" [[:get "/acme/t/SECRET-TOKEN"] [:get "/acme/t/SECRET-TOKEN/"]]
            ["GET /{org}/t/:token 200 <n>ms" "GET /… 404 <n>ms"]]]]
    (testing template
      (is (= expected (first (lines (app [[template {:wb/log-path :template :get ok}]]) requests)))))))
