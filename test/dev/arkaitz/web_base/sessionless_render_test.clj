(ns dev.arkaitz.web-base.sessionless-render-test
  "A `:sessionless` path may bring its own `:render-error` (since 0.17.0): the errors the
  base decides for a request it answers — a throw, a nil answer, the body limit's 413,
  declared or found by reading — are rendered by it, in the host's format; every other
  path keeps the base's own page. The renderer answers with a tag naming itself, so
  which renderer answered is read off the body, never computed from the code. Every
  expected value was measured."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.plugin :as plugin]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page [status body]
  (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
       "<title>" status "</title></head><body>" body "</body></html>"))

(def ^:private seen (atom []))

(defn- json-renderer [tag]
  (fn [datum request]
    (swap! seen conj {:tag tag :datum datum :request request})
    {:status (:status datum) :headers {"Content-Type" "application/json"}
     :body (str "{\"error\":" (:status datum) ",\"by\":\"" tag "\"}")}))

(defn- slurper [r] {:status 200 :body (str (count (slurp (:body r))))})

(defn- api [r]
  (case (:uri r)
    "/api/throw" (throw (ex-info "secret detail" {:leak "SECRET-DATA"}))
    "/api/nil"   nil
    "/api/datum" (error/throw! {:status 422 :title "bad" :detail "x"})
    (slurper r)))

(defn- app [& {:as more}]
  (wb/handler (merge {:routes         [["/raw" {:post slurper}]
                                       ["/t/:token" {:wb/log-path :template :get (fn [_] {:status 200 :body ""})}]]
                      :session        {:key KEY}
                      :csrf           false
                      :max-body-bytes 16
                      :sessionless    {"/api/"     {:handler api :render-error (json-renderer "api")}
                                       "/api/v2/"  {:handler slurper :render-error (json-renderer "v2")}
                                       "/api/ping" {:handler slurper :render-error (json-renderer "ping")}
                                       "/plain/"   {:handler slurper}
                                       "/probe"    slurper
                                       "/t/a/"     {:handler (fn [_] (throw (ex-info "boom" {:leak "SECRET-DATA"})))
                                                    :render-error (json-renderer "ta")}}}
                     more)))

(def ^:private over "a=0123456789abcde")
(def ^:private at-limit "a=0123456789abcd")

(defn- post-body [path ^String body declared]
  (let [r (-> (mock/request :post path) (mock/body body) (mock/content-type "application/x-www-form-urlencoded"))]
    (if (= :absent declared)
      (-> r (dissoc :content-length) (update :headers dissoc "content-length"))
      (-> r (assoc :content-length declared) (assoc-in [:headers "content-length"] (str declared))))))

(defn- answered
  "`[status content-type body]` and the tags of the renderers called."
  [h request]
  (reset! seen [])
  (let [r (h request)]
    [[(:status r) (get-in r [:headers "Content-Type"]) (:body r)] (mapv :tag @seen)]))

(defn- json [status tag] [status "application/json" (str "{\"error\":" status ",\"by\":\"" tag "\"}")])

(def ^:private bare-413 [413 "text/html; charset=utf-8" (page 413 (frag 413))])

(deftest a-sessionless-paths-render-error-answers-every-failure-the-base-decides-under-it
  (let [h (app)]
    (is (= [16 17] [(count at-limit) (count over)]) "witness: the two bodies straddle the 16-byte limit")
    (is (= [[200 nil "16"] []] (answered h (post-body "/api/x" at-limit 16)))
        "witness: a request that succeeds is the handler's, and no renderer is asked")
    (doseq [[label request status]
            [["a throw" (mock/request :get "/api/throw") 500]
             ["a throw, the client asking for JSON" (mock/header (mock/request :get "/api/throw") "accept" "application/json") 500]
             ["a nil answer" (mock/request :get "/api/nil") 500]
             ["the host's own datum" (mock/request :get "/api/datum") 422]
             ["a declared length past the limit" (post-body "/api/x" over 17) 413]
             ["a body past the limit found by reading it" (post-body "/api/x" over :absent) 413]
             ["a length that lies" (post-body "/api/x" over 4) 413]]]
      (is (= [(json status "api") ["api"]] (answered h request)) (str label ": the path's own renderer, once")))
    (doseq [[label request] [["a sessionless map without a renderer" (post-body "/plain/x" over 17)]
                             ["…found by reading" (post-body "/plain/x" over :absent)]
                             ["a sessionless function" (post-body "/probe" over 17)]
                             ["a routed page" (post-body "/raw" over 17)]
                             ["no route at all" (post-body "/nowhere" over 17)]
                             ["a path that only shares letters with the prefix" (post-body "/apix" over 17)]]]
      (is (= [bare-413 []] (answered h request)) (str label ": the base's own page")))
    (is (= [[413 "text/plain; charset=utf-8" "413"] []]
           (answered h (mock/header (post-body "/probe" over 17) "accept" "application/json")))
        "a path that did not opt in keeps the base's negotiation — the symptom of H19, now confined to it")))

(deftest the-413-is-rendered-by-the-sessionless-path-that-would-answer-the-request
  (let [h (app)]
    (doseq [[path tag] [["/api/" "api"] ["/api/x" "api"] ["/api/orgs/7" "api"] ["/api/v2/x" "v2"] ["/api/v2/" "v2"]
                        ["/api/ping" "ping"] ["/api/pingpong" "api"] ["/api/v2" "api"]]]
      (is (= [(json 413 tag) [tag]] (answered h (post-body path over 17)))
          (str path ": the exact path first, then the longest prefix — rendered by " tag)))
    (doseq [[path tag] [["/api/x" "16"] ["/api/v2/x" "16"] ["/api/ping" "16"]]]
      (is (= [200 tag] ((juxt :status :body) (h (post-body path at-limit 16))))
          (str path ": witness: within the limit the same path's handler answers")))))

(deftest a-sessionless-map-value-is-accepted-and-validated--and-a-plugin-may-bring-one
  (let [f       (fn [_] {:status 200 :body ""})
        g       (fn [d _] {:status (:status d) :body ""})
        attempt (fn [v] (try (wb/handler {:routes [] :session {:key KEY} :sessionless {"/s/" v}}) ::built
                             (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        refusal ["web-base: config :sessionless must be a map of path to handler — a function, or {:handler f :render-error g} — each path starting with / and none under /wb/, which is the base's, nor / itself, which would take every page away from the session"
                 {:config-key [:sessionless]}]]
    (doseq [v [{:handler f} {:handler f :render-error nil} {:handler #'slurper :render-error #'slurper}]]
      (is (= ::built (attempt v)) (str (pr-str (keys v)) ": accepted")))
    (doseq [[label v] [["an unknown key" {:handler f :extra 1}] ["a handler that is no function" {:handler "ok"}]
                       ["a renderer that is a string" {:handler f :render-error "x"}]
                       ["a renderer that is a map" {:handler f :render-error {}}]
                       ["no handler" {:render-error g}] ["an empty map" {}] ["a vector" [f]]]]
      (is (= refusal (attempt v)) (str label ": refused")))
    (let [plug {:wb.plugin/name :p :sessionless {"/p/" {:handler (fn [_] (throw (ex-info "x" {})))
                                                          :render-error (json-renderer "plug")}}}
          cfg  {:routes [] :session {:key KEY} :plugins [plug]}]
      (is (= {:routes [] :session {:key KEY} :sessionless (:sessionless plug)} (plugin/expand cfg))
          "a plugin's map value is merged as data")
      (is (= [(json 500 "plug") ["plug"]] (answered (wb/handler cfg) (mock/request :get "/p/x")))
          "and its renderer answers its path"))))

(deftest a-render-error-sees-the-datum-and-a-pre-session-request--and-the-throw-is-logged-once-with-the-redacted-path
  (let [h (app)]
    (lt/with-log
      (answered h (mock/request :get "/api/throw"))
      (let [{:keys [datum request]} (first @seen)
            errors (filter #(= :error (:level %)) (lt/the-log))]
        (is (= {:status 500} datum) "the datum the base decided")
        (is (= {} (select-keys request [:session :wb/subject :wb/tr :wb/locale :anti-forgery-token :params :reitit.core/match]))
            "a request before the session: nothing the session stack computes")
        (is (every? #(contains? request %) [:wb/request-id :wb/logged-path :wb/nonce]) "and what the outer layers put on it")
        (is (= 1 (count errors)) "witness: the throw is logged once")
        (is (re-matches #"^unhandled exception \{:request-id [A-Za-z0-9_-]{16}, :uri /api/throw\}$" (str (:message (first errors))))
            "as an unhandled exception, with its path")))
    (lt/with-log
      (let [r    (do (reset! seen []) (h (mock/request :get "/t/a/SECRET-TOKEN")))
            text (pr-str (mapv (juxt :message #(some-> % :throwable ex-data)) (lt/the-log)))]
        (is (= 500 (:status r)) "witness: the handler under the marked prefix threw")
        (is (= ["/t/a/SECRET-TOKEN" "/t/…"] ((juxt :uri :wb/logged-path) (:request (first @seen))))
            "the renderer sees the real path and the redacted one")
        (is (not (str/includes? text "SECRET-TOKEN")) (str "and the token reaches no line: " text))))
    (lt/with-log
      (answered h (mock/request :get "/api/nil"))
      (is (= [{:status 500} ["sessionless handler returned nil"]]
             [(:datum (first @seen)) (mapv #(first (str/split (str (:message %)) #" \{")) (filter #(= :error (:level %)) (lt/the-log)))])
          "a nil answer is logged once, and rendered as a 500"))
    (doseq [declared [17 :absent]]
      (lt/with-log
        (answered h (post-body "/api/x" over declared))
        (is (= [{:status 413} []] [(:datum (first @seen)) (filter #(= :error (:level %)) (lt/the-log))])
            (str declared ": a 413 is no error to log"))))
    (answered h (mock/request :get "/api/datum"))
    (is (= {:status 422 :title "bad" :detail "x"} (:datum (first @seen))) "the host's own datum, as thrown")))

(deftest a-render-error-that-throws-or-answers-nil-falls-back-to-the-bases-page--logged--never-the-session
  (doseq [[label renderer message thrown]
          [["throws" (fn [_ _] (throw (IllegalStateException. "renderer bug"))) "sessionless :render-error threw" IllegalStateException]
           ["throws an Error" (fn [_ _] (throw (AssertionError. "renderer assert"))) "sessionless :render-error threw" AssertionError]
           ["answers nil" (fn [_ _] nil) "sessionless :render-error answered nil" nil]]]
    (let [h (wb/handler {:routes [["/x/:id" {:get (fn [_] {:status 200 :body "routed"})}]]
                         :session {:key KEY} :csrf false :max-body-bytes 16
                         :sessionless {"/n/" {:handler (fn [r] (case (:uri r)
                                                                 "/n/throw" (throw (ex-info "x" {}))
                                                                 "/n/nil"   nil
                                                                 (slurper r)))
                                              :render-error renderer}}})]
      (doseq [[cell request status] [["a throw" (mock/request :get "/n/throw") 500]
                                     ["a nil answer" (mock/request :get "/n/nil") 500]
                                     ["a declared 413" (post-body "/n/x" over 17) 413]
                                     ["a 413 found by reading" (post-body "/n/x" over :absent) 413]]]
        (lt/with-log
          (let [r      (try (h request) (catch Throwable t {:escaped (.getName (class t))}))
                guard  (filter #(str/starts-with? (str (:message %)) message) (lt/the-log))]
            (is (= [status "text/html; charset=utf-8" (page status (frag status))]
                   [(:status r) (get-in r [:headers "Content-Type"]) (:body r)])
                (str "a renderer that " label ", " cell ": the base's own page, never the adapter or the session: " (pr-str (:escaped r))))
            (is (= 1 (count guard)) (str label ", " cell ": logged once as such"))
            (when thrown
              (is (instance? thrown (:throwable (first guard)))
                  (str label ", " cell ": with the renderer's own throwable, for its stack trace")))))))))

(deftest a-render-error-of-nil-is-no-renderer--no-error-logged-for-it
  (let [h (wb/handler {:routes [] :session {:key KEY} :csrf false :max-body-bytes 16
                       :sessionless {"/z/" {:handler slurper :render-error nil}}})]
    (lt/with-log
      (let [r (h (post-body "/z/x" over 17))]
        (is (= bare-413 [(:status r) (get-in r [:headers "Content-Type"]) (:body r)]) "the base's own page")
        (is (= [] (filter #(= :error (:level %)) (lt/the-log))) "and nothing logged against a renderer that is not there")))))
