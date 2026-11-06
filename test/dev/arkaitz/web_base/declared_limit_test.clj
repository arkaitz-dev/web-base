(ns dev.arkaitz.web-base.declared-limit-test
  "A body whose declared length is past its route's limit is refused unread (since
  0.23.0) with the host's own 413 page: rendered by `:error-layout` inside the session,
  subject and locale, with nothing inside — the multipart parse, CSRF, the route — run,
  and no byte of the client's body read. A sessionless path and an asset keep the page
  they had, answered before the session. The layout prints what it was handed, so which
  renderer answered and with what is read off the body, never computed from the code.
  Every expected value was measured."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as wt]
            [ring.mock.request :as mock])
  (:import [java.io ByteArrayInputStream InputStream]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(def ^:private calls (atom 0))
(def ^:private ran (atom nil))
(def ^:private touched (atom false))

(defn- reset-all! [] (reset! calls 0) (reset! ran nil) (reset! touched false))

(defn- host-layout
  "Prints everything the refusal's page could only know from inside the stack."
  [{:keys [content request error]}]
  [:main {:data-host "yes"}
   [:p.who (str (:id (:wb/subject request)))]
   [:p.locale (str (:wb/locale request))]
   [:p.limit (str (:wb/limit error))]
   [:p.tr (str (some? (:wb/tr request)))]
   [:p.csrf (str (boolean (security/csrf-field request)))]
   [:p.parsed (str (contains? request :multipart-params))]
   content])

(defn- ran! [k] (fn [_] (reset! ran k) {:status 200 :body (name k)}))

(defn- app []
  (wb/handler
   {:routes         [["/form"   {:wb/layouts [(fn [{:keys [content request]}] [:main content (security/csrf-field request)])]
                                 :get (fn [_] {:status 200 :body [:p "form"]})}]
                     ["/login"  {:post (fn [_] (session/rotate {:status 200 :body "in"} {:user {:id "ann" :lang :es}}))}]
                     ["/post"   {:post (ran! :post)}]
                     ["/upload" {:wb/multipart {:max-file-size 1000 :max-body-bytes 2000} :post (ran! :upload)}]]
    :session        {:key KEY}
    :subject-fn     (fn [r] (swap! calls inc) (get-in r [:session :user]))
    :error-layout   host-layout
    :max-body-bytes 16
    :i18n           {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en :locales [:en :es]
                     :locale-fn (fn [r] (some-> (:wb/subject r) :lang vector))}
    :sessionless    {"/health" (ran! :health)}}))

(defn- throwing-body
  "A body that notes and refuses any attempt to read it."
  ^InputStream []
  (let [refuse (fn [] (reset! touched true) (throw (IllegalStateException. "the refused body was read")))]
    (proxy [InputStream] []
      (read ([] (refuse)) ([_] (refuse)) ([_ _ _] (refuse)))
      (skip [_] (refuse))
      (available [] (refuse)))))

(defn- declared
  "A `method` request to `path` declaring `length` bytes, both as Jetty sets it — the key
  and the header — over `body`, with `headers` added."
  [method path length body headers]
  (-> (mock/request method path)
      (assoc :body body :content-length length)
      (assoc-in [:headers "content-length"] (str length))
      (update :headers merge headers)
      (cond-> (get headers "content-type") (assoc :content-type (get headers "content-type")))))

(def ^:private multipart {"content-type" "multipart/form-data; boundary=B"})
(def ^:private urlencoded {"content-type" "application/x-www-form-urlencoded"})

(defn- cookie-of [b] {"cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) (sort (:jar b))))})

(defn- signed-in
  "A browser signed in as ann, whose language is es."
  [app]
  (-> (wt/browser app) (wt/visit :get "/form") (wt/visit :post "/login") (wt/visit :get "/form")))

(defn- cell [r k] (second (re-find (re-pattern (str "<p class=\"" k "\">([^<]*)</p>")) (str (:body r)))))

(defn- observed
  "What the answer to `request` shows, and what ran to produce it."
  [h request]
  (reset-all!)
  (let [r (h request)]
    {:status  (:status r)
     :host?   (str/includes? (str (:body r)) "data-host=\"yes\"")
     :who     (cell r "who")
     :locale  (cell r "locale")
     :limit   (cell r "limit")
     :tr      (cell r "tr")
     :csrf    (cell r "csrf")
     :parsed  (cell r "parsed")
     :cookie  (get-in r [:headers "Set-Cookie"])
     :calls   @calls
     :ran     @ran
     :touched @touched
     :body    (:body r)}))

(defn- cause
  "The first thing wrong with `o` against the host's 413 answer, named; `page?` when that
  answer is the layout's page rather than a fragment or text."
  ([o] (cause o true))
  ([o page?]
   (cond (:touched o)                         "the client's body was read"
         (:ran o)                             (str "the route ran: " (:ran o))
         (and (= "true" (:parsed o)) (not= 413 (:status o))) (str "the request went on to be judged — the multipart parse ran, the marker ignored or answered inside CSRF: " (:status o))
         (= "true" (:parsed o))               "the multipart parse ran on the refused request: the answer sits inside multipart"
         (zero? (:calls o))                   (str "the subject function was never asked: answered outside the session and subject, " (pr-str (:status o)))
         (and page? (not (:host? o)))         "not the host's layout"
         (not= 413 (:status o))               (str "the request went on to be judged — the marker ignored, or answered inside CSRF: " (:status o))
         (not page?)                          (str "the host's renderer did not negotiate: " (pr-str (:body o)))
         (not= ":body" (:limit o))            (str "the datum lost :wb/limit :body: " (pr-str (:limit o)))
         (contains? #{nil ""} (:locale o))    "rendered outside i18n: no locale on the request"
         :else                                "the host's 413 page, as expected")))

(def ^:private page-cells [:status :host? :who :locale :limit :tr :parsed :calls :ran :touched])

(deftest a-declared-length-past-the-limit-is-the-hosts-page-with-subject-and-locale--nothing-inside-runs-and-the-body-is-never-read
  (let [h   (app)
        b   (signed-in h)
        jar (cookie-of b)]
    (is (= [200 true] [(:status (:response b)) (some? (:token b))]) "witness: the sign-in walk ended on a page with a token")
    (testing "controls"
      (let [o (observed h (declared :post "/upload" 2000 (ByteArrayInputStream. (byte-array 0)) (merge jar multipart)))]
        (is (= [403 true "ann" ":es" "" "true" 1 nil] ((apply juxt [:status :host? :who :locale :limit :parsed :calls :ran]) o))
            "control: at the route's bound the stack goes on — the multipart parse runs, then CSRF refuses the tokenless form — through the same layout, so the 413 below is the declared length's and the parsed cell can say true"))
      (let [o (observed h (merge (declared :post "/post" 4 (ByteArrayInputStream. (.getBytes "a=0123456789abcde")) (merge jar urlencoded))))]
        (is (= [413 false 1 nil] ((juxt :status :host? :calls :ran) o))
            "control: a length that lies is read past the limit by params, outside i18n — still the base's page, so the host layout below is the declared path's")))
    (doseq [[label request] [["multipart" (declared :post "/upload" 2001 (throwing-body) (merge jar multipart))]
                             ["urlencoded" (declared :post "/post" 17 (throwing-body) (merge jar urlencoded))]]]
      (let [o (observed h request)]
        (is (= [413 true "ann" ":es" ":body" "true" "false" 1 nil false] ((apply juxt page-cells) o))
            (str label ": " (cause o)))
        (is (= "false" (:csrf o)) (str label ": no token reaches a page CSRF never saw"))))))

(deftest an-anonymous-request-refused-for-its-declared-length-renders-the-host-layout-with-no-subject-no-token-and-no-cookie
  (let [h (app)
        b (signed-in h)]
    (is (= [404 "true"] ((juxt :status #(cell % "csrf")) (:response (wt/visit b :get "/nope"))))
        "control: the layout prints a token on a signed-in error page inside CSRF, so the false below is the request's")
    (let [o (observed h (declared :post "/post" 17 (throwing-body) urlencoded))]
      (is (= [413 true "" ":en" ":body" "true" "false" 1 nil false] ((apply juxt page-cells) o)) (str "anonymous: " (cause o)))
      (is (= ["false" nil] [(:csrf o) (:cookie o)]) "anonymous: no token on the page, and no session written for it"))))

(deftest an-htmx-request-refused-for-its-declared-length-gets-the-fragment-and-a-non-html-one-text
  (let [h   (app)
        jar (cookie-of (signed-in h))
        frag "<div class=\"wb-error\" data-status=\"413\"><strong class=\"wb-error-status\">413</strong></div>"]
    (let [o (observed h (declared :post "/post" 17 (throwing-body) (merge jar urlencoded {"hx-request" "true"})))]
      (is (= [413 frag 1 nil false] ((juxt :status :body :calls :ran :touched) o))
          (str "an htmx swap receives the fragment alone, from inside the session (the bytes are the old bare renderer's too; calls tells them apart): " (cause o false))))
    (let [o (observed h (declared :post "/post" 17 (throwing-body) (merge jar urlencoded {"accept" "application/json"})))]
      (is (= [413 "413" 1 nil false] ((juxt :status :body :calls :ran :touched) o))
          (str "a non-HTML caller receives text: " (cause o false))))))

(def ^:private bare-413
  (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
       "<title>413</title></head><body>"
       "<div class=\"wb-error\" data-status=\"413\"><strong class=\"wb-error-status\">413</strong></div></body></html>"))

(deftest a-sessionless-path-and-an-asset-keep-the-bare-413--answered-before-the-session
  (let [h   (app)
        jar (cookie-of (signed-in h))]
    (is (= [200 "text/css" 0] (let [o (observed h (mock/request :get "/wb/wb.css"))]
                                [(:status o) (some-> (h (mock/request :get "/wb/wb.css")) :headers (get "Content-Type") (str/split #";") first) (:calls o)]))
        "control: the asset is served to a request declaring no body")
    (doseq [[label request] [["sessionless" (declared :post "/health" 17 (throwing-body) (merge jar urlencoded))]
                             ["asset"       (declared :get "/wb/wb.css" 17 (throwing-body) jar)]]]
      (let [o (observed h request)]
        (is (= [413 bare-413 0 nil false] ((juxt :status :body :calls :ran :touched) o))
            (str label ": the base's own page, before the session — "
                 (cond (:ran o) (str "the path's handler ran: " (:ran o))
                       (pos? (:calls o)) "it was rendered through the session"
                       (= 200 (:status o)) "it was served as if nothing had been refused"
                       :else (pr-str (:status o)))))))))

(deftest an-asset-answer-to-a-refused-request-is-replaced-by-the-refusal-and-its-stream-closed
  ;; From a jar an asset's body is an open stream; from a directory, as in this suite, a
  ;; File. So the closing is observed on the wrapper itself, over a stream of our own.
  (let [refusing #'dev.arkaitz.web-base/refusing-unread
        closed   (atom false)
        stream   (proxy [ByteArrayInputStream] [(.getBytes "body{}")] (close [] (reset! closed true)))
        h        (refusing (fn [_] {:status 200 :body stream}) (fn [datum _] {:status (:status datum) :body "refused"}))
        marked   {:uri "/a.css" ::security/declared-over {:status 413 :wb/limit :body}}]
    (is (= [200 false] [(:status (h {:uri "/a.css"})) @closed]) "control: an unrefused request gets the asset, its stream left to the adapter")
    (is (= [413 "refused" true] ((juxt :status :body (fn [_] @closed)) (h marked)))
        "a refused one gets the refusal, and the asset's stream, never sent, is closed")
    (is (nil? ((refusing (constantly nil) (fn [_ _] {:status 413})) marked))
        "an asset that does not answer leaves the refused request to the stack, whose host page answers it")))
