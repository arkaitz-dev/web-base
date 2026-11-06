(ns dev.arkaitz.web-base.param-test
  "`wb/param` through the assembled stack: what a field is in `:params` depends on how
  it arrived — once, twice, empty, absent, a file — and `param` must answer one string
  or nil whichever carrier filled `:params`. The handler answers what it observed in its
  body, beside the raw value the stack delivered, so a request refused before it (a 403,
  a 413) cannot pass for a stale observation."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.testing :as wt]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- shown
  "A value as the body can carry it back: a file part by its name and size, so a
  `param` that answered one is read as that, not as an unreadable temporary file."
  [v]
  (if (map? v) (select-keys v [:filename :size]) v))

(defn- echo [r]
  {:status 200
   :body   (pr-str (mapv shown [(get-in r [:params "who"])
                                (wb/param r "who")
                                (wb/param r :who)
                                (wb/param r "photo")
                                (get-in r [:params "photo"])]))})

(defn- echo-not-found
  "What `param` answers with a not-found value, for a text field and for a file field."
  [r]
  {:status 200
   :body   (pr-str [(wb/param r "who" ::absent) (wb/param r "photo" ::absent)])})

(defn- echo-precedence
  "Ring's raw value beside what `param` reads, for a field the query and the body may
  both carry, and for one only the query carries."
  [r]
  {:status 200
   :body   (pr-str [(get-in r [:params "who"]) (wb/param r "who") (wb/param r :who)
                    (wb/param r "who" ::absent) (wb/param r "page" ::absent)])})

(def ^:private app
  (wb/handler {:session {:key KEY}
               :routes  [["/form" {:get (fn [r] (response/ok [:form (security/csrf-field r)]))}]
                         ["/p" {:get echo :post echo}]
                         ["/m" {:wb/multipart {:max-file-size 1024} :post echo}]
                         ["/n" {:get echo-not-found :post echo-not-found}]
                         ["/nm" {:wb/multipart {:max-file-size 1024} :post echo-not-found}]
                         ["/q" {:get echo-precedence :post echo-precedence}]
                         ["/qm" {:wb/multipart {:max-file-size 1024} :post echo-precedence}]]}))

(def ^:private photo {"photo" {:filename "a.png" :content-type "image/png" :bytes (.getBytes "PNG" "UTF-8")}})

(defn- observed
  "`[status observation]` for one request from a browser holding the CSRF token."
  [method path params opts]
  (let [b        (wt/visit (wt/browser app) :get "/form")
        response (:response (wt/visit b method path params opts))]
    [(:status response) (when (= 200 (:status response)) (edn/read-string (:body response)))]))

(deftest param-answers-one-string-and-nil-for-a-repeated-field-a-file-or-nothing--query-form-and-multipart-alike
  (doseq [[carrier method path opts file]
          [["query" :get "/p" {} nil]
           ["form" :post "/p" {} nil]
           ["multipart" :post "/m" {:files photo} {:filename "a.png" :size 3}]]
          [shape params expected]
          [["once" [["who" "Ada"]] ["Ada" "Ada" nil nil]]
           ["twice" [["who" "Ada"] ["who" "Bob"]] [["Ada" "Bob"] nil nil nil]]
           ["empty" [["who" ""]] ["" "" nil nil]]
           ["absent" [] [nil nil nil nil]]]]
    (testing (str carrier " " shape)
      (let [[status observation] (observed method path params opts)]
        (is (= 200 status) "witness: the request reached the handler")
        (is (= (conj expected file) observation)
            "[raw value in :params, param \"who\", param :who, param \"photo\", the file part parsed]")))))

(deftest param-with-not-found-answers-it-only-for-an-absent-field--a-repeat-or-a-file-is-still-nil
  (doseq [[carrier method path opts photo-nf]
          [["query" :get "/n" {} ::absent]
           ["form" :post "/n" {} ::absent]
           ["multipart" :post "/nm" {:files photo} nil]]
          [shape params who-nf]
          [["once" [["who" "Ada"]] "Ada"]
           ["twice" [["who" "Ada"] ["who" "Bob"]] nil]
           ["empty" [["who" ""]] ""]
           ["absent" [] ::absent]]]
    (testing (str carrier " " shape)
      (let [[status observation] (observed method path params opts)]
        (is (= 200 status) "witness: the request reached the handler")
        (is (= [who-nf photo-nf] observation)
            "[param \"who\" with not-found, param \"photo\" with not-found — a file there is nil, never not-found]")))))

(deftest param-on-an-unsafe-method-reads-the-body-over-the-query-and-the-query-for-what-the-body-lacks
  (doseq [[label method path params opts expected]
          [["urlencoded POST, the same field in query and body" :post "/q?who=evil" [["who" "good"]] {}
            ["evil" "good" nil "good" ::absent]]
           ["multipart POST, the same field in query and body" :post "/qm?who=evil&page=2" [["who" "good"]] {:files photo}
            ["good" "good" nil "good" "2"]]
           ["urlencoded POST, a field only the query carries" :post "/q?page=2" [["who" "good"]] {}
            ["good" "good" nil "good" "2"]]
           ["GET, the query" :get "/q?who=a" [] {}
            ["a" "a" nil "a" ::absent]]
           ["urlencoded POST, a field sent twice in the body" :post "/q?who=evil" [["who" "a"] ["who" "b"]] {}
            ["evil" nil nil nil ::absent]]
           ["urlencoded POST, absent everywhere" :post "/q" [] {}
            [nil nil nil ::absent ::absent]]]]
    (testing label
      (let [[status observation] (observed method path params opts)]
        (is (= 200 status) "witness: the request reached the handler, CSRF passed")
        (is (= expected observation)
            "[Ring's raw value, param \"who\", param :who, param \"who\" with not-found, param \"page\" with not-found]"))))
  (testing "the method decides, not the shape of the request"
    (let [request {:params {"who" "query"} :form-params {"who" "body"}}]
      (is (= ["query" "query" "query" "body" "body" "body" "body"]
             (mapv #(wb/param (assoc request :request-method %) "who") [:get :head :options :post :put :patch :delete]))
          "the methods CSRF lets through take Ring's :params; every other method the body")
      (is (= "query" (wb/param {:request-method :post :params {"who" "query"}} "who"))
          "a POST with no body field reads the query"))))

(deftest param-never-trims-never-coerces-and-reads-string-keys-only
  (doseq [[value expected] [[" Ada " [" Ada " " Ada " nil nil nil]] ["Ada\r\n" ["Ada\r\n" "Ada\r\n" nil nil nil]]
                            ["12" ["12" "12" nil nil nil]] ["on" ["on" "on" nil nil nil]]]]
    (let [[status observation] (observed :post "/p" [["who" value]] {})]
      (is (= 200 status) "witness: the request reached the handler")
      (is (= expected observation) (str (pr-str value) ": returned as :params holds it, and a keyword key is no key")))))
