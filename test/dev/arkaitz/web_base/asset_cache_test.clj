(ns dev.arkaitz.web-base.asset-cache-test
  "Every asset the base serves — its own `/wb/`, an `:assets` root, a plugin's, the
  host's `:static` — is revalidated on each load. With `Last-Modified` alone a browser
  keeps an asset for a tenth of its age without asking, and served the previous deploy's
  stylesheet after one (2026-11-05). And a 304 needs the very date the file carries: a
  later one is a rollback, whose older file must reach the browser."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock])
  (:import [java.io File]
           [java.time Instant ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^:private app
  (wb/handler {:routes  [["/me" {:get (fn [_] {:status 200 :body "me"})}]]
               :session {:store (memory/memory-store)}
               :csrf    false
               :assets  [{:path "/h/" :root "public"}]
               :plugins [{:wb.plugin/name :p :assets {:path "/p/" :root "public"}}]
               :static  {:root "public"}}))

(defn- body [r] (let [b (:body r)] (if (string? b) b (some-> b slurp))))

(defn- rfc-850
  "`http-date` spelt the RFC 850 way: the same instant, another valid HTTP date."
  [http-date]
  (.format (DateTimeFormatter/ofPattern "EEEE, dd-MMM-yy HH:mm:ss 'GMT'" Locale/ROOT)
           (.withZoneSameInstant (ZonedDateTime/parse http-date DateTimeFormatter/RFC_1123_DATE_TIME) ZoneOffset/UTC)))

(defn- conditional [path header value]
  (app (mock/header (mock/request :get path) header value)))

(deftest every-asset-root-answers-304-only-to-its-own-last-modified--any-other-date-is-the-file
  (doseq [[path starts] [["/wb/wb.css" "/* web-base"] ["/h/host.txt" "HOST-ASSET"]
                         ["/p/host.txt" "HOST-ASSET"] ["/host.txt" "HOST-ASSET"]]]
    (let [first* (app (mock/request :get path))
          lm     (get-in first* [:headers "Last-Modified"])
          served (fn [r] [(:status r) (str/starts-with? (str (body r)) starts) (get-in r [:headers "Cache-Control"])])]
      (is (and (= 200 (:status first*)) (str/starts-with? (str (body first*)) starts) (some? lm)
               (some? (get-in first* [:headers "Content-Length"])) (nil? (get-in first* [:headers "Set-Cookie"])))
          (str path ": witness: the asset layer served the file, with Last-Modified, a length and no session"))
      (is (= "no-cache" (get-in first* [:headers "Cache-Control"])) (str path ": the 200 carries no-cache"))
      (let [again (conditional path "If-Modified-Since" lm)]
        (is (= [304 "no-cache" lm nil nil]
               [(:status again) (get-in again [:headers "Cache-Control"]) (get-in again [:headers "Last-Modified"])
                (body again) (get-in again [:headers "Content-Length"])])
            (str path ": its own date is a 304, no-cache and its date kept, no body and no length")))
      (is (= [200 true "no-cache"] (served (conditional path "If-Modified-Since" "Fri, 01 Jan 2100 00:00:00 GMT")))
          (str path ": a LATER date is the file — a rollback to an older one reaches the browser"))
      (is (= [200 true "no-cache"] (served (conditional path "If-Modified-Since" "Wed, 01 Jan 2020 00:00:00 GMT")))
          (str path ": an earlier date is the file"))
      (let [same-instant (rfc-850 lm)]
        (is (and (not= lm same-instant)
                 (= (.toInstant (ZonedDateTime/parse lm DateTimeFormatter/RFC_1123_DATE_TIME))
                    (.toInstant (ZonedDateTime/parse same-instant (.withZone (DateTimeFormatter/ofPattern "EEEE, dd-MMM-yy HH:mm:ss 'GMT'" Locale/ROOT)
                                                                             ZoneOffset/UTC)))))
            (str path ": witness: the RFC 850 spelling is another string for the same instant"))
        (is (= [200 true "no-cache"] (served (conditional path "If-Modified-Since" same-instant)))
            (str path ": the same instant spelt another way is the file — the date is compared as the"
                 " string the browser was given, which it echoes; a miss costs one download")))
      (is (= [200 true "no-cache"] (served (conditional path "If-None-Match" "\"x\"")))
          (str path ": If-None-Match alone, with no tag served, is the file")))))

(deftest a-routed-page-is-not-marked-by-the-asset-wrapper
  (let [r (app (mock/request :get "/me"))]
    (is (= [200 "me"] [(:status r) (body r)]) "witness: the route answered")
    (is (nil? (get-in r [:headers "Cache-Control"])) "an anonymous page keeps what it had: no Cache-Control from the assets")))

(deftest revalidated-keeps-a-handlers-own-cache-control-in-any-spelling
  (let [revalidated @#'wb/revalidated]
    (doseq [spelling ["cache-control" "Cache-Control" "CACHE-CONTROL"]]
      (is (= {:status 200 :headers {spelling "max-age=60"}}
             ((revalidated (constantly {:status 200 :headers {spelling "max-age=60"}})) {}))
          (str "a handler's own header, spelt " spelling ", is kept and not doubled")))
    (is (= {:status 200 :headers {"Cache-Control" "no-cache"}} ((revalidated (constantly {:status 200})) {}))
        "none of its own: no-cache")
    (is (nil? ((revalidated (constantly nil)) {})) "no answer stays no answer, so the next route is tried")))

(defn- http-date
  "`millis` as an HTTP date, the day in two digits as RFC 9110 spells it — Java's
  RFC_1123 formatter writes one digit for days below ten."
  [millis]
  (.format (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'" Locale/ROOT)
           (.atZone (Instant/ofEpochMilli millis) ZoneOffset/UTC)))

(deftest an-asset-dated-by-a-clamping-build-carries-no-last-modified-and-is-never-a-304
  (let [^File f (io/file (io/resource "public/clamped.txt"))
        before  (.lastModified f)]
    (try
      (doseq [[label millis kept?] [["1970" 0 false]
                                    ["1980-01-01" 315532800000 false]
                                    ["1980-01-02, the floor itself" 315619200000 false]
                                    ["a second after the floor" 315619201000 true]
                                    ["1980-01-03" 315705600000 true]]
              path ["/h/clamped.txt" "/clamped.txt"]]
        (is (and (.setLastModified f millis) (= millis (.lastModified f)))
            (str label ": witness: the file carries the date the test set"))
        (let [r     (app (mock/request :get path))
              date  (http-date millis)
              again (app (mock/header (mock/request :get path) "If-Modified-Since" date))]
          (is (= [200 true "no-cache" (when kept? date)]
                 [(:status r) (str/starts-with? (str (body r)) "CLAMPED") (get-in r [:headers "Cache-Control"])
                  (get-in r [:headers "Last-Modified"])])
              (str path ", " label ": " (if kept? "its date is served" "a clamped date is no date: none is served")))
          (is (= (if kept? 304 200) (:status again))
              (str path ", " label ": that date asked back " (if kept? "is a 304" "is the file, never a 304")))))
      (finally
        (is (.setLastModified f before) "the fixture's date is put back")))))

(deftest dated-passes-no-answer-and-an-undated-answer-through
  (let [dated @#'wb/dated]
    (is (nil? ((dated (constantly nil)) {})) "no answer stays no answer, so the next route is tried")
    (is (= {:status 200 :headers {"X" "y"}} ((dated (constantly {:status 200 :headers {"X" "y"}})) {}))
        "an answer with no date is left as it is")))
