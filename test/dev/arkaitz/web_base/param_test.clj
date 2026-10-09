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

(def ^:private app
  (wb/handler {:session {:key KEY}
               :routes  [["/form" {:get (fn [r] (response/ok [:form (security/csrf-field r)]))}]
                         ["/p" {:get echo :post echo}]
                         ["/m" {:wb/multipart {:max-file-size 1024} :post echo}]]}))

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

(deftest param-never-trims-never-coerces-and-reads-string-keys-only
  (doseq [[value expected] [[" Ada " [" Ada " " Ada " nil nil nil]] ["Ada\r\n" ["Ada\r\n" "Ada\r\n" nil nil nil]]
                            ["12" ["12" "12" nil nil nil]] ["on" ["on" "on" nil nil nil]]]]
    (let [[status observation] (observed :post "/p" [["who" value]] {})]
      (is (= 200 status) "witness: the request reached the handler")
      (is (= expected observation) (str (pr-str value) ": returned as :params holds it, and a keyword key is no key")))))
