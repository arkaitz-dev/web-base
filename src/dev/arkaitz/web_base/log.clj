(ns dev.arkaitz.web-base.log
  "A request id threaded through one request (SPEC §6): on the request as
  `:wb/request-id`, on the response as `X-Request-Id`, in the SLF4J MDC as
  `request-id` for the duration, and in one access line. The base ships no
  logging backend — a library must not — so the line goes through
  tools.logging and the MDC key reaches whatever pattern the host configures
  (`%X{request-id}` in logback).

  An incoming `X-Request-Id` is not trusted: a client could then choose what
  the logs say. Behind a proxy that assigns ids, the host maps them."
  (:require [clojure.tools.logging :as log])
  (:import [java.security SecureRandom]
           [java.util Base64 Locale]
           [org.slf4j MDC]))

(def mdc-key "request-id")

;; A delay, not a value: a SecureRandom in a Var root is baked into a
;; GraalVM native image at build time, seed and all.
(def ^:private random (delay (SecureRandom.)))

(defn- new-id
  "96 random bits as 16 url-safe characters."
  []
  (let [bytes (byte-array 12)]
    (.nextBytes ^SecureRandom @random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- access-line [request status elapsed-ns]
  ;; Locale/ROOT: under a Turkish default locale "options" upper-cases to OPTİONS.
  (log/infof "%s %s %s %dms"
             (.toUpperCase ^String (name (:request-method request)) Locale/ROOT)
             (:uri request)
             (or status "-")
             (quot elapsed-ns 1000000)))

(defn wrap-request-id
  "The outermost middleware: every response, including a 404 or a static
  asset, carries the id. If the handler throws, the access line still goes
  out with a 500 and the throwable propagates; the MDC is cleared either way."
  [handler]
  (fn [request]
    (let [id    (new-id)
          start (System/nanoTime)]
      (MDC/put mdc-key id)
      (try
        (let [response (try
                         (handler (assoc request :wb/request-id id))
                         (catch Throwable t
                           ;; A failing logging backend must not replace the
                           ;; handler's own throwable with its own.
                           (try (access-line request 500 (- (System/nanoTime) start))
                                (catch Throwable _))
                           (throw t)))]
          (access-line request (:status response) (- (System/nanoTime) start))
          (some-> response (assoc-in [:headers "X-Request-Id"] id)))
        (finally
          (MDC/remove mdc-key))))))
