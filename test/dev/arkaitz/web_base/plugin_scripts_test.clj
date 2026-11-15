(ns dev.arkaitz.web-base.plugin-scripts-test
  "A plugin's `:scripts`, and the config's (since 0.25.0): local paths under the plugin's
  own asset root, loaded by the shell on every page after the base's scripts and before
  the route's, under the response's CSP nonce, each once — and, being on every page,
  never making a page its own, so a whole-page htmx GET still gets the document. Every
  literal was measured once through the stack and typed here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.plugin :as plugin]
            [dev.arkaitz.web-base.shell :as shell]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")
(def ^:private CSP "script-src 'nonce-{nonce}'")

(defn- attempt
  "`[message data]` of what `f` throws, or ::built."
  [f]
  (try (f) ::built (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(defn- nonce-of [response]
  (second (re-find #"^script-src 'nonce-([A-Za-z0-9+/]{22}==)'$" (str (get-in response [:headers "Content-Security-Policy"])))))

(defn- head-of [response]
  (second (re-find #"<head>(.*)</head>" (str (:body response)))))

(def ^:private plugins
  [{:wb.plugin/name :a :assets {:path "/a/" :root "public"} :scripts ["/a/1.js" "/a/2.js"]}
   {:wb.plugin/name :b :assets {:path "/b/" :root "public"} :scripts ["/b/1.js"]}])

(def ^:private app
  (wb/handler {:routes       [["" {:wb/layouts [shell/page]}
                               ["/" {:get (fn [_] {:status 200 :body [:p "hi"]})}]
                               ["/route" {:wb/scripts ["/js/r.js" "/a/1.js"]
                                          :get (fn [_] {:status 200 :body [:p "r"] :wb/slots {:scripts ["/js/s.js"]}})}]]]
               :session      {:key KEY}
               :security     {:csp CSP}
               :error-layout shell/page
               :scripts      ["/host.js"]
               :plugins      plugins}))

(defn- full [request] (-> request (mock/header "HX-Request" "true") (mock/header "HX-Request-Type" "full")))

(defn- tags [nonce srcs]
  (apply str (for [src srcs] (str "<script defer nonce=\"" nonce "\" src=\"" src "\"></script>"))))

;; --- refusals ------------------------------------------------------------------------

(deftest a-plugin-script-is-a-plain-path-under-its-own-asset-root--refused-naming-the-plugin-and-the-path
  (let [ok  {:wb.plugin/name :a :assets {:path "/a/" :root "x"}}
        exp #(attempt (fn [] (plugin/expand {:routes [] :plugins [(assoc ok :scripts %)]})))]
    (is (= ::built (exp ["/a/x.js" "/a/.x.js" "/a/..x.js" "/a/x/y.js?v=1"]))
        "control: paths under the root, dots inside a name, build")
    (doseq [[label scripts message]
            [["outside its root" ["/b/x.js"] "web-base: plugin :a lists script \"/b/x.js\" outside its own :assets path \"/a/\""]
             ["another origin's" ["//evil.example/a/x.js"]
              "web-base: plugin :a lists script \"//evil.example/a/x.js\" outside its own :assets path \"/a/\""]
             ["not a string" [:x] "web-base: plugin :a lists script :x outside its own :assets path \"/a/\""]
             ["a space" ["/a/x y.js"]
              (str "web-base: plugin :a lists script \"/a/x y.js\", which is not a plain path under \"/a/\":"
                   " no backslash, space, control character or dot segment")]
             ["a backslash, which a browser reads as a slash" ["/a/\\x.js"]
              (str "web-base: plugin :a lists script \"/a/\\\\x.js\", which is not a plain path under \"/a/\":"
                   " no backslash, space, control character or dot segment")]
             ["a climb out of the root" ["/a/../x.js"]
              (str "web-base: plugin :a lists script \"/a/../x.js\", which is not a plain path under \"/a/\":"
                   " no backslash, space, control character or dot segment")]
             ["a percent-encoded climb, which a browser resolves too" ["/a/%2E%2e/x.js"]
              (str "web-base: plugin :a lists script \"/a/%2E%2e/x.js\", which is not a plain path under \"/a/\":"
                   " no backslash, space, control character or dot segment")]
             ["a single dot segment" ["/a/./x.js"]
              (str "web-base: plugin :a lists script \"/a/./x.js\", which is not a plain path under \"/a/\":"
                   " no backslash, space, control character or dot segment")]]]
      (is (= [message {:config-key [:plugins 0 :scripts]}] (exp scripts)) (str "refused: " label)))
    (is (= ["web-base: plugin :a lists script \"/a/x.js\" outside its own :assets path nil" {:config-key [:plugins 0 :scripts]}]
           (attempt #(plugin/expand {:routes [] :plugins [{:wb.plugin/name :a :scripts ["/a/x.js"]}]})))
        "refused: a plugin with no asset root")))

(deftest the-handler-refuses-config-scripts-that-are-not-local-or-are-given-twice
  (let [app #(attempt (fn [] (wb/handler (merge {:routes [["/" {:get (fn [_] {:status 200 :body "x"})}]] :session {:key KEY}} %))))]
    (is (= ::built (app {:scripts ["/host.js"]})) "control: a local path builds")
    (is (= [(str "web-base: :scripts is a vector of local paths, each starting with one / and holding no backslash,"
                 " space or control character — never another origin's")
            {:config-key [:scripts] :value ["//evil.example/x.js"]}]
           (app {:scripts ["//evil.example/x.js"]}))
        "another origin's")
    (is (= ["web-base: script /a/x.js is given twice" {:config-key [:scripts "/a/x.js"]}]
           (app {:scripts ["/a/x.js"] :plugins [{:wb.plugin/name :a :assets {:path "/a/" :root "public"} :scripts ["/a/x.js"]}]}))
        "the same script from a plugin and the host")))

;; --- the shell -----------------------------------------------------------------------

(deftest expand-merges-scripts-in-plugin-order-then-the-hosts
  (is (= ["/a/1.js" "/a/2.js" "/b/1.js" "/host.js"]
         (:scripts (plugin/expand {:routes [] :scripts ["/host.js"] :plugins plugins})))))

(deftest every-shell-page-loads-the-site-scripts-after-the-bases-and-before-the-routes--under-its-nonce--each-once
  (let [r (app (mock/request :get "/"))
        n (nonce-of r)]
    (is (= 200 (:status r)) "witness: the page was served")
    (is (string? n) "witness: the response carries a CSP nonce")
    (is (= (str "<meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
                "<link href=\"/wb/wb.css\" rel=\"stylesheet\">"
                (tags n ["/wb/htmx.min.js" "/wb/nav.js" "/a/1.js" "/a/2.js" "/b/1.js" "/host.js"]))
           (head-of r))
        "a page with no scripts of its own: the base's, the plugins' in order, the host's, each under the nonce"))
  (let [r (app (mock/request :get "/route"))
        n (nonce-of r)]
    (is (= (str "<meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
                "<link href=\"/wb/wb.css\" rel=\"stylesheet\">"
                (tags n ["/wb/htmx.min.js" "/wb/nav.js" "/a/1.js" "/a/2.js" "/b/1.js" "/host.js" "/js/r.js" "/js/s.js"]))
           (head-of r))
        "before the route's and the slot's; /a/1.js, also the route's, loaded once"))
  (let [r (app (mock/request :get "/nope"))]
    (is (= 404 (:status r)) "witness: an error page")
    (is (str/includes? (head-of r) (tags (nonce-of r) ["/wb/nav.js" "/a/1.js" "/a/2.js" "/b/1.js" "/host.js"]))
        "the error page drawn with the shell loads them too")))

(deftest site-scripts-keep-a-page-swappable--a-routes-own-scripts-still-redirect
  (is (str/includes? (:body (app (mock/request :get "/"))) "src=\"/a/1.js\"") "witness: / loads a plugin's script")
  (let [r (app (full (mock/request :get "/")))]
    (is (nil? (get-in r [:headers "HX-Redirect"])) "a whole-page swap of / is not redirected")
    (is (= 200 (:status r)) "it is answered")
    (is (str/starts-with? (:body r) "<!DOCTYPE html>\n<html>") "with the document")
    (is (str/includes? (:body r) "<main class=\"wb-main\" tabindex=\"-1\"><p>hi</p></main>") "whose <main> htmx takes"))
  (testing "control: the same app redirects a page with a route's scripts"
    (let [r (app (full (mock/request :get "/route")))]
      (is (= [200 "" "/route"] [(:status r) (:body r) (get-in r [:headers "HX-Redirect"])])))))
