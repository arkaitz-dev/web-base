(ns dev.arkaitz.web-base.paths-test
  "Paths built from a route's name. The router a path is built from must be the one that
  answers the requests — read from the handler under test, never rebuilt here, since two
  routers built from the same routes agree with each other whatever the handler does."
  (:require [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- ok [_] {:status 200 :body "ok"})

(defn- config
  "A handler config with three named routes, a plugin's named route, a stylesheet and a
  sessionless probe — every wrapper `handler` applies — and `seen` keeping the last
  request a route answered."
  [seen sessionless-seen]
  {:session     {:key KEY}
   :csrf        false
   :stylesheets ["/app.css"]
   :sessionless {"/health" (fn [request] (reset! sessionless-seen request) {:status 200 :body "ok"})}
   :plugins     [{:wb.plugin/name :pl :routes [["/pl/:id" {:name :pl/item :get ok}]]}]
   :routes      [["/t/:team-id" {:name ::team :get (fn [request] (reset! seen request) (ok request))}]
                 ["/plain" {:name ::plain :get ok}]
                 ["/x/:a/:b" {:name ::two :get ok}]]})

(defn- refusal [f]
  (try (f) :not-refused (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(deftest the-handlers-metadata-carries-the-router-that-answers-its-requests
  (let [seen   (atom nil)
        app    (wb/handler (config seen (atom nil)))
        router (::r/router (meta app))
        status (:status (app (mock/request :get "/t/7")))]
    (is (= 200 status) "witness: the route answered")
    (is (some? @seen) "witness: and its handler kept the request")
    (is (some? router) "the handler carries a router in its metadata: no wrapper was applied after it")
    (is (identical? router (::r/router @seen)) "the very router that answered the request")
    (is (identical? router (ring/get-router app)) "and the one reitit's own get-router reads from the handler")
    (is (= #{::team ::plain ::two :pl/item} (set (r/route-names router))) "naming every route, a plugin's included")))

(deftest path-for-builds-the-path-from-request-handler-and-router-alike
  (let [seen (atom nil)
        app  (wb/handler (config seen (atom nil)))
        p    (partial wb/path-for app)]
    (is (= ["/t/7" "/t/7" "/t/a%20b" "/t/a%2Fb" "/t/%C3%B1" "/plain" "/plain?outcome=saved" "/plain?q=a+b"
            "/plain?q=a%26b%3Dc" "/plain?q=%C3%B1" "/plain?q=7" "/plain?q=kw" "/plain?q=a&q=b" "/plain?q=" "/plain"
            "/plain" "/x/1/2" "/pl/9"]
           [(p ::team {:team-id "7"}) (p ::team {:team-id 7}) (p ::team {:team-id "a b"}) (p ::team {:team-id "a/b"})
            (p ::team {:team-id "ñ"}) (p ::plain {:extra 1}) (p ::plain nil {:outcome "saved"}) (p ::plain nil {:q "a b"})
            (p ::plain nil {:q "a&b=c"}) (p ::plain nil {:q "ñ"}) (p ::plain nil {:q 7}) (p ::plain nil {:q :kw})
            (p ::plain nil {:q ["a" "b"]}) (p ::plain nil {:q nil}) (p ::plain nil {}) (p ::plain)
            (p ::two {:a "1" :b "2"}) (p :pl/item {:id "9"})])
        "params in place, URL-encoded; the query form-encoded; nothing after a path with no query")
    (app (mock/request :get "/t/7"))
    (let [id   "a/b"
          path (wb/path-for @seen ::team {:team-id id})]
      (is (= ["/t/a%2Fb" "/t/a%2Fb"] [path (wb/path-for (::r/router (meta app)) ::team {:team-id id})])
          "the request a page answers and the router itself build the same path")
      (is (= 200 (:status (app (mock/request :get path)))) "and that path, followed, is answered"))
    (let [other (wb/handler (assoc (config (atom nil) (atom nil))
                                   :routes [["/teams/:team-id" {:name ::team :get ok}]]))]
      (is (= "/teams/7" (wb/path-for other ::team {:team-id "7"}))
          "another handler answers its own path for the same name: the source decides"))))

(deftest an-unknown-name-or-a-missing-parameter-is-refused-naming-the-route
  (let [app (wb/handler (config (atom nil) (atom nil)))]
    (is (= "/t/7" (wb/path-for app ::team {:team-id "7"})) "control: a known name with its parameter is built")
    (is (= ["web-base: no path for route :dev.arkaitz.web-base.paths-test/nope"
            {:route ::nope :missing nil}]
           (refusal #(wb/path-for app ::nope)))
        "an unknown name is refused, naming it")
    (doseq [params [nil {:other 1} {:team-id nil} {:team-id ""}]]
      (is (= ["web-base: no path for route :dev.arkaitz.web-base.paths-test/team"
              {:route ::team :missing #{:team-id}}]
             (refusal #(wb/path-for app ::team params)))
          (str (pr-str params) ": refused, naming the parameter — an empty one builds a path no route answers")))
    (is (= {:route ::two :missing #{:b}} (second (refusal #(wb/path-for app ::two {:a "1"}))))
        "the one parameter missing of two is the one named")
    (is (= {:route "plain" :missing nil} (second (refusal #(wb/path-for app "plain"))))
        "a name of another type is no name")))

(deftest a-request-the-router-did-not-answer-carries-no-router--and-path-for-refuses-it-naming-the-route
  (let [sl  (atom nil)
        app (wb/handler (config (atom nil) sl))]
    (is (= [200 "ok"] ((juxt :status :body) (app (mock/request :get "/health")))) "witness: the sessionless probe ran")
    (is (false? (contains? @sl ::r/router)) "a sessionless request carries no router: it is answered before it")
    (doseq [[label source message]
            [["a sessionless request" @sl
              "web-base: path-for :dev.arkaitz.web-base.paths-test/team: this request was not answered by the router — a sessionless route or an asset — so it carries none; pass the handler `wb/handler` returned"]
             ["a map" {}
              "web-base: path-for :dev.arkaitz.web-base.paths-test/team: this request was not answered by the router — a sessionless route or an asset — so it carries none; pass the handler `wb/handler` returned"]
             ["a bare function" ok
              "web-base: path-for :dev.arkaitz.web-base.paths-test/team: this function carries no router; pass the handler `wb/handler` returned"]
             ["a var" #'ok "web-base: path-for :dev.arkaitz.web-base.paths-test/team: the source must be a request, the handler or a router"]
             ["a string" "/t" "web-base: path-for :dev.arkaitz.web-base.paths-test/team: the source must be a request, the handler or a router"]
             ["nil" nil "web-base: path-for :dev.arkaitz.web-base.paths-test/team: the source must be a request, the handler or a router"]]]
      (is (= [message {:route ::team}] (refusal #(wb/path-for source ::team {:team-id "7"})))
          (str label ": refused naming the route, never reitit's own error")))))

(deftest integrants-handler-key-hands-the-host-the-function-that-carries-its-router
  (let [seen (atom nil)
        sys  (ig/init {:dev.arkaitz.web-base/handler (config seen (atom nil))})
        h    (:dev.arkaitz.web-base/handler sys)]
    (try
      (is (= 200 (:status (h (mock/request :get "/t/7")))) "witness: the system's handler answers")
      (is (identical? (::r/router (meta h)) (::r/router @seen))
          "Integrant hands over the function wb/handler built, its router the one requests carry")
      (is (= "/t/7" (wb/path-for h ::team {:team-id "7"})) "so a job given the key builds a link from it")
      (finally (ig/halt! sys)))))
