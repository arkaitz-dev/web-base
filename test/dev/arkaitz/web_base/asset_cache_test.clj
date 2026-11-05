(ns dev.arkaitz.web-base.asset-cache-test
  "Every asset the base serves — its own `/wb/`, an `:assets` root, a plugin's, the
  host's `:static` — is revalidated on each load. With `Last-Modified` alone a browser
  keeps an asset for a tenth of its age without asking, and served the previous deploy's
  stylesheet after one (2026-11-05)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock]))

(def ^:private app
  (wb/handler {:routes  [["/me" {:get (fn [_] {:status 200 :body "me"})}]]
               :session {:store (memory/memory-store)}
               :csrf    false
               :assets  [{:path "/h/" :root "public"}]
               :plugins [{:wb.plugin/name :p :assets {:path "/p/" :root "public"}}]
               :static  {:root "public"}}))

(defn- body [r] (let [b (:body r)] (if (string? b) b (some-> b slurp))))

(deftest every-asset-root-answers-no-cache--its-200-and-its-304-alike
  (doseq [[path starts] [["/wb/wb.css" "/* web-base"] ["/h/host.txt" "HOST-ASSET"]
                         ["/p/host.txt" "HOST-ASSET"] ["/host.txt" "HOST-ASSET"]]]
    (let [first* (app (mock/request :get path))
          lm     (get-in first* [:headers "Last-Modified"])
          again  (app (mock/header (mock/request :get path) "If-Modified-Since" lm))]
      (is (and (= 200 (:status first*)) (str/starts-with? (str (body first*)) starts) (some? lm)
               (nil? (get-in first* [:headers "Set-Cookie"])))
          (str path ": witness: the asset layer served the file, with Last-Modified and no session"))
      (is (= "no-cache" (get-in first* [:headers "Cache-Control"])) (str path ": the 200 carries no-cache"))
      (is (= [304 "no-cache"] [(:status again) (get-in again [:headers "Cache-Control"])])
          (str path ": the 304 on the same date carries it too"))
      (is (= [304 "no-cache"] ((juxt :status #(get-in % [:headers "Cache-Control"]))
                               (app (mock/header (mock/request :get path) "If-Modified-Since" "Fri, 01 Jan 2100 00:00:00 GMT"))))
          (str path ": a known limit, Ring's: a LATER date is a 304 too, so a rollback to an older file"
               " is not seen — pinned so a change is noticed, not because it is wanted")))))

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
