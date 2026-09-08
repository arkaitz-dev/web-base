(ns dev.arkaitz.web-base.log-test
  "The access line is observed through tools.logging's test factory; the MDC
  through SLF4J directly, which needs a real adapter on the classpath (with
  slf4j-nop every MDC read is nil and these assertions would mean nothing);
  and once, end to end, through a logback appender that sees the event's MDC."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as tl]
            [clojure.tools.logging.impl :as tl-impl]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base.log :as log]
            [ring.mock.request :as mock])
  (:import [ch.qos.logback.classic LoggerContext]
           [ch.qos.logback.classic.spi ILoggingEvent]
           [ch.qos.logback.classic.util LogbackMDCAdapter]
           [ch.qos.logback.core AppenderBase]
           [java.util Locale]
           [org.slf4j LoggerFactory MDC]))

(def ^:private id-pattern #"[A-Za-z0-9_-]{16}")

(defn- normalised
  "The elapsed field is real time; it is bounded elsewhere, never pinned."
  [message]
  (str/replace message #"\d+ms$" "<n>ms"))

(defn- entries []
  (mapv (fn [{:keys [logger-ns level throwable message]}]
          [(ns-name logger-ns) level throwable (normalised message)])
        (lt/the-log)))

(deftest every-response-carries-a-fresh-url-safe-x-request-id-that-the-handler-also-saw
  (let [seen (atom nil)
        app  (log/wrap-request-id (fn [request]
                                    (reset! seen (:wb/request-id request))
                                    {:status 404 :headers {"Content-Type" "text/plain"} :body "nope"}))]
    (lt/with-log
      (let [response (app (mock/request :get "/nope"))
            id       (get-in response [:headers "X-Request-Id"])]
        (is (re-matches id-pattern id) "the header holds a 16-char url-safe id")
        (is (= {:status 404 :headers {"Content-Type" "text/plain" "X-Request-Id" id} :body "nope"} response)
            "the handler's own headers and status survive alongside the id")
        (is (= id @seen) "the handler saw the same id on the request"))
      (let [ids (repeatedly 50 #(get-in (app (mock/request :get "/n")) [:headers "X-Request-Id"]))]
        (is (every? #(re-matches id-pattern %) ids) "every id is well formed")
        (is (= 50 (count (set ids))) "fifty requests, fifty distinct ids")))))

(deftest an-incoming-x-request-id-is-never-reused
  (let [seen (atom nil)
        app  (log/wrap-request-id (fn [request]
                                    (reset! seen {:rid (:wb/request-id request) :mdc (MDC/get log/mdc-key)})
                                    {:status 200}))]
    (lt/with-log
      (let [response (app (mock/header (mock/request :get "/x") "X-Request-Id" "CLIENTCHOSEN0000"))
            id       (get-in response [:headers "X-Request-Id"])]
        (is (re-matches id-pattern id) "a fresh id was issued")
        (is (not= "CLIENTCHOSEN0000" id) "the client's id is not echoed")
        (is (not= "CLIENTCHOSEN0000" (:rid @seen)) "nor put on the request")
        (is (not= "CLIENTCHOSEN0000" (:mdc @seen)) "nor into the MDC")))))

(deftest nil-response-passes-through-as-nil-and-still-gets-its-access-line
  (lt/with-log
    (let [response ((log/wrap-request-id (fn [_] nil)) (mock/request :post "/nil"))]
      (is (nil? response) "nil stays nil — no response is fabricated")
      (is (= [['dev.arkaitz.web-base.log :info nil "POST /nil - <n>ms"]] (entries))
          "one access line with - in the status slot"))))

(deftest access-line-is-one-info-entry-from-the-log-ns-with-method-uri-status-and-a-real-elapsed
  (let [spin-ms 20
        app     (log/wrap-request-id (fn [_]
                                       (let [deadline (+ (System/nanoTime) (* spin-ms 1000000))]
                                         (while (< (System/nanoTime) deadline)))
                                       {:status 418}))]
    (lt/with-log
      (let [before   (System/nanoTime)
            _        (app (mock/request :get "/slow"))
            outer-ms (quot (- (System/nanoTime) before) 1000000)
            message  (:message (first (lt/the-log)))
            elapsed  (some-> (re-find #" (\d+)ms$" message) second Long/parseLong)]
        (is (= [['dev.arkaitz.web-base.log :info nil "GET /slow 418 <n>ms"]] (entries))
            "one info entry from the log ns: METHOD uri status elapsed")
        ;; Both bounds derived: the handler occupies at least spin-ms of the
        ;; middleware's interval, which is inside the test's interval, same clock.
        (is (and (some? elapsed) (<= spin-ms elapsed outer-ms))
            (str "elapsed " elapsed "ms is between the handler's " spin-ms "ms and the test's " outer-ms "ms"))
        (is (false? (lt/logged? 'dev.arkaitz.web-base.error :info #"^GET /slow")) "control: the ns matcher is live")
        (is (false? (lt/logged? 'dev.arkaitz.web-base.log :debug #"^GET /slow")) "control: the level matcher is live")))))

(deftest the-method-is-upper-cased-locale-independently
  (let [saved (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale. "tr" "TR"))
      (lt/with-log
        ((log/wrap-request-id (fn [_] {:status 200})) (mock/request :options "/x"))
        (is (= [['dev.arkaitz.web-base.log :info nil "OPTIONS /x 200 <n>ms"]] (entries))
            "OPTIONS stays ASCII under a Turkish default locale"))
      (finally
        (Locale/setDefault saved)))))

(deftest a-throwing-handler-still-gets-a-500-access-line-and-the-throwable-propagates-identical
  (doseq [[label boom] [["RuntimeException" (RuntimeException. "BOOM")]
                        ["Error" (Error. "ERR")]]]
    (testing label
      (lt/with-log
        (let [thrown (try ((log/wrap-request-id (fn [_] (throw boom))) (mock/request :delete "/boom"))
                          ::no-throw
                          (catch Throwable t t))]
          (is (identical? boom thrown) (str "the very same throwable escapes, got " (pr-str thrown)))
          (is (= [['dev.arkaitz.web-base.log :info nil "DELETE /boom 500 <n>ms"]] (entries))
              "one access line with 500, and no throwable on it"))))))

(deftest mdc-request-id-equals-the-request-id-during-the-request-and-is-cleared-after-even-on-throw
  (is (instance? LogbackMDCAdapter (MDC/getMDCAdapter))
      "a real SLF4J backend is on the test classpath — otherwise every MDC read is nil")
  (is (nil? (MDC/get log/mdc-key)) "precondition: the MDC is clean before the request")
  (let [seen    (atom nil)
        observe (fn [request] (reset! seen {:rid (:wb/request-id request) :mdc (MDC/get log/mdc-key)}))]
    (lt/with-log
      ((log/wrap-request-id (fn [request] (observe request) {:status 200})) (mock/request :get "/x"))
      (is (re-matches id-pattern (:mdc @seen)) "during the request the MDC holds an id")
      (is (= (:rid @seen) (:mdc @seen)) "and it is the request's id")
      (is (nil? (MDC/get log/mdc-key)) "after a normal return the MDC is cleared"))
    (lt/with-log
      (try ((log/wrap-request-id (fn [request] (observe request) (throw (RuntimeException. "BOOM"))))
            (mock/request :get "/x"))
           (catch RuntimeException _))
      (is (= (:rid @seen) (:mdc @seen)) "during a throwing request the MDC still held the id")
      (is (nil? (MDC/get log/mdc-key)) "after a throw the MDC is cleared too"))))

(deftest the-real-backend-receives-the-access-line-with-the-request-id-in-its-mdc
  (is (= "org.slf4j" (tl-impl/name tl/*logger-factory*)) "tools.logging routes to SLF4J")
  (let [context (LoggerFactory/getILoggerFactory)]
    (is (instance? LoggerContext context) "SLF4J is bound to logback")
    (when (instance? LoggerContext context)
      (let [logger   (.getLogger ^LoggerContext context "dev.arkaitz.web-base.log")
            captured (atom [])
            ;; Like any appender that hands events to another thread: the MDC
            ;; is snapshotted at append time, not when the test reads it later.
            appender (doto (proxy [AppenderBase] []
                             (append [event]
                               (.prepareForDeferredProcessing ^ILoggingEvent event)
                               (swap! captured conj event)))
                       (.setContext context)
                       (.start))]
        (.addAppender logger appender)
        (try
          (let [response ((log/wrap-request-id (fn [_] {:status 200})) (mock/request :get "/real"))
                id       (get-in response [:headers "X-Request-Id"])
                events   @captured]
            (is (re-matches id-pattern id) "the response carries an id")
            (is (= 1 (count events)) "logback received exactly one event")
            (when (= 1 (count events))
              (let [^ILoggingEvent event (first events)]
                (is (= ["dev.arkaitz.web-base.log" "INFO" {"request-id" id}]
                       [(.getLoggerName event) (str (.getLevel event)) (into {} (.getMDCPropertyMap event))])
                    "logger name, level, and the MDC as %X{request-id} would read it, still holding the id")
                (is (= "GET /real 200 <n>ms" (normalised (.getFormattedMessage event)))
                    "the access line as the backend formats it"))))
          (finally
            (.detachAppender logger appender)
            (.stop appender)))))))
