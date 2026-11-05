(ns dev.arkaitz.web-base.csrf-repeat-test
  "A CSRF field that is not one string, through the whole stack. Sent twice it reaches
  ring-anti-forgery as a vector, whose comparison throws: until 0.19.0 that was a 500
  and a stack trace in the error log, from any anonymous POST. The refusal is told from
  that 500 by the error page's `data-status` and by the log, which the 500 path always
  writes at error level and a refusal never does."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock])
  (:import [java.io ByteArrayInputStream]
           [java.net URLEncoder]))

(defn- app []
  (wb/handler {:session {:store (memory/memory-store)}
               :routes  [["/f" {:get (fn [r] {:status 200 :body [:form (security/csrf-field r)]})}]
                         ["/p" {:post (fn [_] {:status 200 :body "posted"})}]
                         ["/m" {:wb/multipart {:max-file-size 1024}
                                :post (fn [_] {:status 200 :body "posted"})}]]}))

(defn- established
  "`[handler sid token]` after GETting a page that renders the token."
  []
  (let [h    (app)
        page (h (mock/request :get "/f"))]
    [h (get (testing/cookies page) "ring-session") (testing/csrf-token page)]))

(defn- urlencoded [sid fields]
  (-> (mock/request :post "/p")
      (mock/header "Cookie" (str "ring-session=" sid))
      (mock/content-type "application/x-www-form-urlencoded")
      (mock/body (str/join "&" (for [[k v] fields] (str k "=" (URLEncoder/encode ^String v "UTF-8")))))))

(defn- multipart
  "`fields` as `[name value]`, or `[name value filename]` for a file part."
  [sid fields]
  (let [boundary "b0undary"
        body     (str (apply str (for [[k v filename] fields]
                                   (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" k "\""
                                        (when filename (str "; filename=\"" filename "\"\r\nContent-Type: text/plain"))
                                        "\r\n\r\n" v "\r\n")))
                      "--" boundary "--\r\n")
        bytes    (.getBytes body "UTF-8")
        ctype    (str "multipart/form-data; boundary=" boundary)]
    (-> (mock/request :post "/m")
        (mock/header "Cookie" (str "ring-session=" sid))
        (assoc :body (ByteArrayInputStream. bytes) :content-length (alength bytes) :content-type ctype)
        (assoc-in [:headers "content-type"] ctype))))

(defn- answer
  "`[status data-status error-lines]` for `request`: the status, the error page's own
  status if one was rendered, and every line logged at warn or error."
  [h request]
  (lt/with-log
    (let [r (h request)]
      [(:status r)
       (second (re-find #"data-status=\"(\d+)\"" (str (:body r))))
       (vec (keep #(when (#{:warn :error} (:level %)) (str (:message %))) (lt/the-log)))])))

(def ^:private refused [403 "403" []])
(def ^:private passed [200 nil []])

(deftest a-csrf-field-sent-twice-is-the-403-refusal--never-a-500--urlencoded-and-multipart
  (let [[h sid t] (established)
        wrong     (str (if (= \A (first t)) "B" "A") (subs t 1))
        tok       "__anti-forgery-token"]
    (is (and (some? t) (not= t wrong)) "witness: a session with a token, and a token that is not it")
    (doseq [[label build] [["urlencoded" urlencoded] ["multipart" multipart]]]
      (testing label
        (is (= passed (answer h (build sid [[tok t] ["a" "1"]]))) "control: one right token passes")
        (is (= refused (answer h (build sid [[tok wrong] ["a" "1"]]))) "control: one wrong token is refused")
        (doseq [[case pair] [["right+wrong" [t wrong]] ["wrong+right" [wrong t]] ["right+right" [t t]]]]
          (is (= refused (answer h (build sid [[tok (first pair)] [tok (second pair)]])))
              (str case ": refused with the 403 page, and nothing logged")))
        (is (= refused (answer h (-> (build sid [[tok t] [tok t]]) (mock/header "X-CSRF-Token" t))))
            "a field sent twice is not passed over for a valid header")
        (is (= passed (answer h (-> (build sid [["a" "1"]]) (mock/header "X-CSRF-Token" t))))
            "no field: the X-CSRF-Token header is read, as htmx sends it")))
    (is (= passed (answer h (-> (urlencoded sid [["a" "1"]]) (mock/header "X-XSRF-Token" t))))
        "no field: the X-XSRF-Token header too")
    (is (= refused (answer h (multipart sid [[tok t "token.txt"]])))
        "a file uploaded under the token's name is no token")))
