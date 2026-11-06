(ns dev.arkaitz.web-base.fetch-test
  "`testing/visit` as a page's own script sends with `fetch` (since 0.22.0), and the
  multipart body it writes, its names encoded as a browser encodes them. What reached
  the handler is logged per request — the headers a browser would put on the wire — so
  an absent header is seen absent, not merely unread."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- fetch-app
  "`{:app :log}`: a handler with sessions and CSRF on, whose `/echo` answers what it
  received and whose every request is logged as `[method uri headers-of-interest]`."
  []
  (let [log  (atom [])
        echo (fn [r] {:status 200 :body (pr-str {:form   (:form-params r)
                                                 :query  (:query-params r)
                                                 :hdr    (get-in r [:headers "x-csrf-token"])
                                                 :method (:request-method r)})})
        app  (wb/handler {:session {:key KEY}
                          :routes  [["/form" {:wb/layouts [shell/page] :get (fn [r] (response/ok [:form (security/csrf-field r)]))}]
                                    ["/login" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/form"} :body ""} {:user "ann"}))}]
                                    ["/echo" {:post echo :put echo :patch echo :delete echo}]
                                    ["/frag" {:wb/layouts [shell/page] :put (fn [_] (response/ok [:p "put"]))}]
                                    ["/u" {:wb/multipart {:max-file-size 1024}
                                           :post (fn [r] {:status 200
                                                          :body   (pr-str (into (sorted-map)
                                                                                (for [[k v] (:multipart-params r)]
                                                                                  [k (if (map? v) [(:filename v) (:size v)] v)])))})}]]})]
    {:log log
     :app (fn [r] (swap! log conj [(:request-method r) (:uri r)
                                   (select-keys (:headers r) ["x-csrf-token" "hx-request" "hx-request-type"])])
            (app r))}))

(defn- signed-in [app]
  (let [b (-> (testing/browser app) (testing/visit :post "/login"))]
    (is (and (= "/form" (:path b)) (string? (:token b))) "witness: signed in, on the form, holding its token")
    b))

(deftest fetch-sends-the-token-as-the-header-and-no-field--no-htmx-headers-so-a-page-is-whole--and-an-anonymous-browser-sends-none
  (let [{:keys [app log]} (fetch-app)
        b    (signed-in app)
        sent (fn [b method path params opts]
               (reset! log [])
               (let [r (:response (testing/visit b method path params opts))]
                 [(:status r) (when (= 200 (:status r)) (edn/read-string (:body r))) @log]))]
    (is (= 403 (first (sent (assoc b :token "wrong") :post "/echo" {:x "1"} {:fetch? true})))
        "control: CSRF guards these, so the token sent below is what lets them through")
    (doseq [method [:post :put :patch :delete]]
      (is (= [200
              {:form (if (= :delete method) {} {"x" "1"}) :query (if (= :delete method) {"kept" "1" "x" "1"} {"kept" "1"})
               :hdr (:token b) :method method}
              [[method "/echo" {"x-csrf-token" (:token b)}]]]
             (sent b method "/echo?kept=1" {:x "1"} {:fetch? true}))
          (str method ": the token as the header and nowhere in the form, no htmx header on the wire, the params where a script puts them")))
    (let [r (:response (testing/visit b :put "/frag" {} {:fetch? true}))]
      (is (= [200 true] [(:status r) (str/starts-with? (:body r) "<!DOCTYPE html>")]) "a fetch gets the whole page")
      (is (= "<p>put</p>" (:body (:response (testing/visit b :put "/frag" {} {:htmx? true}))))
          "control: htmx gets the fragment of the same route"))
    (is (= [200 {:form {} :query {"x" "1"} :hdr nil :method :delete} [[:delete "/echo" {}]]]
           (sent (testing/browser app) :delete "/echo" {:x "1"} {:fetch? true}))
        "a browser with no token sends no header at all, and its origin carries it")))

(deftest fetch-and-htmx-together-are-refused-before-sending--and-a-fetchs-own-header-cannot-be-forged
  (let [{:keys [app log]} (fetch-app)
        b       (signed-in app)
        refused (fn [b method opts]
                  (reset! log [])
                  (let [e (try (testing/visit b method "/echo" {:x "1"} opts) nil (catch clojure.lang.ExceptionInfo e e))]
                    [(ex-message e) (ex-data e) (count @log)]))]
    (doseq [method [:post :put :patch :delete]
            [who browser] [["signed in" b] ["anonymous" (testing/browser app)]]]
      (is (= [(str "web-base: a request to /echo is sent by htmx or by a fetch, not both — pass one of"
                   " {:htmx? true} and {:fetch? true}")
              {:path "/echo" :method method} 0]
             (refused browser method {:htmx? true :fetch? true}))
          (str who " " method ": refused, naming the path and the method, before anything is sent")))
    (is (= [nil nil 1] (refused b :post {:fetch? true})) "control: the same call with only :fetch? is sent once")
    (is (= [(str "web-base: visit writes the x-csrf-token header itself — pass the jar, the token, {:htmx? true} or"
                 " {:fetch? true} instead of setting it")
            {:path "/echo" :header "x-csrf-token"} 0]
           (refused b :post {:fetch? true :headers {"X-CSRF-Token" "forged"}}))
        "the header a fetch carries cannot be set by hand")
    (is (= [(str "web-base: a request to /x is sent by htmx or by a fetch, not both — pass one of"
                 " {:htmx? true} and {:fetch? true}")
            {:path "/x" :method :delete}]
           (try (testing/visit b :delete "/x" nil {:htmx? true :fetch? true :files {"f" {:filename "a" :bytes (byte-array 1)}}}) nil
                (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
        "the two together are refused before anything else is judged")))

(deftest multipart-encodes-a-quote-cr-and-lf-in-a-name-as-a-browser-does--the-server-reads-each-name-whole
  (let [{:keys [app]} (fetch-app)
        b      (signed-in app)
        parsed (fn [params files] (let [r (:response (testing/visit b :post "/u" params {:files files}))]
                                    [(:status r) (when (= 200 (:status r)) (dissoc (edn/read-string (:body r)) "__anti-forgery-token"))]))
        file   (fn [n] {"f" {:filename n :content-type "application/pdf" :bytes (.getBytes "PDF" "UTF-8")}})]
    (doseq [[sent received] [["a\"b.pdf" "a%22b.pdf"]
                             ["x\r\ny.pdf" "x%0D%0Ay.pdf"]
                             ["q\"\r\n.pdf" "q%22%0D%0A.pdf"]
                             ["lf\ny.pdf" "lf%0Ay.pdf"]
                             ["cr\ry.pdf" "cr%0Dy.pdf"]
                             ["plain.pdf" "plain.pdf"]
                             ["50%.pdf" "50%.pdf"]
                             ["informe ñ 日本.pdf" "informe ñ 日本.pdf"]]]
      (is (= [200 {"f" [received 3] "x" "1"}] (parsed {"x" "1"} (file sent)))
          (str (pr-str sent) ": the parser reads the browser's encoding whole — not cut at a quote, not ended at a line break — and the file after it")))
    (doseq [[sent received] [["na\"me" "na%22me"] ["n\r\nx" "n%0D%0Ax"]]]
      (is (= [200 {received "v" "f" ["plain.pdf" 3]}] (parsed {sent "v"} (file "plain.pdf")))
          (str (pr-str sent) ": a field's name is encoded the same way")))))
