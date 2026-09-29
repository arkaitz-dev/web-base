(ns dev.arkaitz.web-base.plugin-test
  "Plugins: values the host passes, merged by `expand`. Each rule is observed twice
  where it can be — as data out of `expand`, and as behaviour through the assembled
  handler — because a handler that ignored the merged config would pass every data
  assertion. Literals are copied from the shapes other tests pin, never required."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.plugin :as plugin]
            [dev.arkaitz.web-base.shell :as shell]
            [ring.middleware.session.store]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")
(def ^:private SEC {"X-Content-Type-Options" "nosniff"
                    "X-Frame-Options"        "DENY"
                    "Referrer-Policy"        "strict-origin-when-cross-origin"})
(def ^:private HEAD-START "<head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">")

(defn- frag [status]
  (str "<div class=\"wb-error\" data-status=\"" status "\"><strong class=\"wb-error-status\">" status "</strong></div>"))

(defn- page [status body]
  (str "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
       "<title>" status "</title></head><body>" body "</body></html>"))

(defn- counting-store
  "A session store that counts what it is asked, holding `session` for any key."
  [reads writes session]
  (reify ring.middleware.session.store/SessionStore
    (read-session [_ _] (swap! reads inc) session)
    (write-session [_ k _] (swap! writes inc) (or k "fresh"))
    (delete-session [_ _] nil)))

(defn- attempt
  "`[message data]` of what `f` throws, or ::built."
  [f]
  (try (f) ::built (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(def ^:private base {:routes [] :session {:key "x"}})

;; --- 1 · expand ----------------------------------------------------------------------

(deftest expand-without-plugins-is-the-config-less-the-key--and-idempotent
  (let [c {:routes [] :session {:key "x"} :extra 1}]
    (doseq [[label cfg] [["absent" c] ["nil" (assoc c :plugins nil)] ["empty vector" (assoc c :plugins [])]
                         ["empty map" (assoc c :plugins {})]]]
      (is (= {:routes [] :session {:key "x"} :extra 1} (plugin/expand cfg))
          (str "no plugins (" label "): the config less :plugins, untouched")))
    ;; `[]` and absent are behaviourally one: a mutant that sends `[]` down the plugin
    ;; path answers the same map, so it is an equivalent mutant, not a gap.
    (let [with    (assoc c :plugins [{:wb.plugin/name :p :sessionless {"/p" identity}}])
          once    (plugin/expand with)]
      (is (= {:routes [] :session {:key "x"} :extra 1 :sessionless {"/p" identity}} once)
          "with a plugin: the merged keys, and no :plugins left")
      (is (= once (plugin/expand once)) "and expanding the result again changes nothing"))))

;; --- 2 · refusals ------------------------------------------------------------------------

(def ^:private TAKES
  "[:assets :i18n :login-path :routes :session :sessionless :stylesheets :subject-fn :wb.plugin/name]")

(deftest every-plugin-refusal-names-what-and-where--message-and-config-key-exact
  (let [ok  {:wb.plugin/name :a}
        ;; No host :session, :subject-fn or :login-path, so two plugins supplying one collide.
        exp #(attempt (fn [] (plugin/expand {:routes [] :plugins %})))]
    (is (= ::built (exp [ok])) "control: a plugin with only its name builds")
    (is (= ::built (exp [(assoc ok :assets {:path "/a/" :root "x"} :stylesheets ["/a/x.css"]
                                :i18n {:dict {}} :sessionless {"/h" identity})]))
        "control: every optional key, well formed, builds")
    (doseq [[label plugins message data]
            [[":plugins not sequential" {:a 1}
              "web-base: :plugins must be a vector of plugin maps" {:config-key [:plugins]}]
             ["a plugin not a map" [1] "web-base: plugin 0 is not a map" {:config-key [:plugins 0]}]
             ["no name" [{}] "web-base: plugin 0 needs :wb.plugin/name, a keyword" {:config-key [:plugins 0 :wb.plugin/name]}]
             ["a name not a keyword" [{:wb.plugin/name "p"}]
              "web-base: plugin 0 needs :wb.plugin/name, a keyword" {:config-key [:plugins 0 :wb.plugin/name]}]
             ["two unknown keys, the first sorted named" [(array-map :wb.plugin/name :p :zzz 1 :aaa 2)]
              (str "web-base: plugin :p has unknown keys [:aaa :zzz] — it takes " TAKES) {:config-key [:plugins 0 :aaa]}]
             ["one unknown key" [{:wb.plugin/name :p :zzz 1}]
              (str "web-base: plugin :p has unknown key [:zzz] — it takes " TAKES) {:config-key [:plugins 0 :zzz]}]
             ["unknown keys of mixed types" [{:wb.plugin/name :p "s" 1 :k 2}]
              (str "web-base: plugin :p has unknown keys [\"s\" :k] — it takes " TAKES) {:config-key [:plugins 0 "s"]}]
             ["an asset not a map" [(assoc ok :assets "/a/")]
              "web-base: [:plugins 0 :assets] must be {:path \"/name/\" :root \"classpath/prefix\"}"
              {:value "/a/" :config-key [:plugins 0 :assets]}]
             ["an asset with an extra key" [(assoc ok :assets {:path "/a/" :root "x" :y 1})]
              "web-base: [:plugins 0 :assets] must be {:path \"/name/\" :root \"classpath/prefix\"}"
              {:value {:path "/a/" :root "x" :y 1} :config-key [:plugins 0 :assets]}]
             ["an asset path with a capital" [(assoc ok :assets {:path "/Ab/" :root "x"})]
              "web-base: an asset :path is one lower-case segment between slashes, like \"/ab/\": \"/Ab/\""
              {:config-key [:plugins 0 :assets :path]}]
             ["an asset path of two segments" [(assoc ok :assets {:path "/a/b/" :root "x"})]
              "web-base: an asset :path is one lower-case segment between slashes, like \"/ab/\": \"/a/b/\""
              {:config-key [:plugins 0 :assets :path]}]
             ["a path climbing out" [(assoc ok :assets {:path "/../" :root "x"})]
              "web-base: an asset :path is one lower-case segment between slashes, like \"/ab/\": \"/../\""
              {:config-key [:plugins 0 :assets :path]}]
             ["the base's own /wb/" [(assoc ok :assets {:path "/wb/" :root "x"})]
              "web-base: an asset :path may not be /wb/, which is the base's own" {:config-key [:plugins 0 :assets :path]}]
             ["a blank root" [(assoc ok :assets {:path "/a/" :root " "})]
              "web-base: an asset :root is a classpath prefix" {:config-key [:plugins 0 :assets :root]}]
             ["a stylesheet outside its asset path" [(assoc ok :assets {:path "/a/" :root "x"} :stylesheets ["/b/x.css"])]
              "web-base: plugin :a lists stylesheet \"/b/x.css\" outside its own :assets path \"/a/\""
              {:config-key [:plugins 0 :stylesheets]}]
             ["a stylesheet that is not a string" [(assoc ok :assets {:path "/a/" :root "x"} :stylesheets [:x])]
              "web-base: plugin :a lists stylesheet :x outside its own :assets path \"/a/\""
              {:config-key [:plugins 0 :stylesheets]}]
             ["a stylesheet with no asset path" [(assoc ok :stylesheets ["/a/x.css"])]
              "web-base: plugin :a lists stylesheet \"/a/x.css\" outside its own :assets path nil"
              {:config-key [:plugins 0 :stylesheets]}]
             ["an :i18n choosing the locale" [(assoc ok :i18n {:dict {} :default-locale :en})]
              "web-base: plugin :a brings :i18n as {:dict …}; the locale is the host's to choose" {:config-key [:plugins 0 :i18n]}]
             ["a :dict whose locale is no map" [(assoc ok :i18n {:dict {:en "oops"}})]
              "web-base: plugin :a brings :i18n as {:dict …}; the locale is the host's to choose" {:config-key [:plugins 0 :i18n]}]
             ["a :dict not a map" [(assoc ok :i18n {:dict []})]
              "web-base: plugin :a brings :i18n as {:dict …}; the locale is the host's to choose" {:config-key [:plugins 0 :i18n]}]
             ["a :sessionless not a map" [(assoc ok :sessionless [["/x" identity]])]
              "web-base: plugin :a :sessionless must be a map of path to handler" {:config-key [:plugins 0 :sessionless]}]
             ["a plugin given twice" [ok ok] "web-base: plugin :a is given twice" {:config-key [:plugins]}]
             ["two plugins, one path" [(assoc ok :sessionless {"/h" identity}) {:wb.plugin/name :b :sessionless {"/h" identity}}]
              "web-base: plugin :b brings :sessionless \"/h\", which is already taken" {:config-key [:plugins :sessionless "/h"]}]
             ["two plugins, one dictionary key" [(assoc ok :i18n {:dict {:en {:k "A"}}}) {:wb.plugin/name :b :i18n {:dict {:en {:k "B"}}}}]
              "web-base: plugins :a and :b both bring i18n key :k under :en" {:config-key [:plugins :i18n :en :k]}]
             ["two plugins, one :login-path" [(assoc ok :login-path "/a") {:wb.plugin/name :b :login-path "/b"}]
              "web-base: plugins [:a :b] both supply :login-path; give it in the config to choose" {:config-key [:plugins :login-path]}]
             ["two plugins, one :subject-fn" [(assoc ok :subject-fn identity) {:wb.plugin/name :b :subject-fn identity}]
              "web-base: plugins [:a :b] both supply :subject-fn; give it in the config to choose" {:config-key [:plugins :subject-fn]}]
             ["two plugins, one :session" [(assoc ok :session {:key "x"}) {:wb.plugin/name :b :session {:key "y"}}]
              "web-base: plugins [:a :b] both supply :session; give it in the config to choose" {:config-key [:plugins :session]}]]]
      (is (= [message data] (if (= label "two plugins, one dictionary key")
                              (attempt #(plugin/expand {:routes [] :plugins plugins :i18n {:default-locale :en}}))
                              (exp plugins)))
          (str "refused: " label))))
  (testing "a plugin's sessionless path beside the host's"
    (let [host (assoc base :sessionless {"/h" identity})]
      (is (= ["web-base: plugin :a brings :sessionless \"/h\", which is already taken" {:config-key [:plugins :sessionless "/h"]}]
             (attempt #(plugin/expand (assoc host :plugins [{:wb.plugin/name :a :sessionless {"/h" identity}}]))))
          "the host's path is taken first")
      (is (= ::built (attempt #(plugin/expand (assoc host :plugins [{:wb.plugin/name :a :sessionless {"/g" identity}}]))))
          "control: another path is merged")
      (is (= ["web-base: config :sessionless must be a map of path to handler" {:config-key [:sessionless]}]
             (attempt #(plugin/expand (assoc base :sessionless [["/x" identity]]
                                                   :plugins [{:wb.plugin/name :a :sessionless {"/g" identity}}]))))
          "a host :sessionless that is no map is refused by name, before any merge")))
  (testing "a plugin's dictionary, with the locale left unchosen"
    (doseq [[label i18n] [["the host has a :dict and no :default-locale" {:dict {:en {}}}]
                          ["the host has no :i18n at all" nil]]]
      (is (= ["web-base: plugins bring dictionaries for [:en]; the host chooses the locale with :i18n :default-locale"
              {:config-key [:i18n :default-locale]}]
             (attempt #(plugin/expand (cond-> (assoc base :plugins [{:wb.plugin/name :p :i18n {:dict {:en {:k "P"}}}}])
                                        i18n (assoc :i18n i18n)))))
          (str "refused, naming the key: " label)))
    (is (= {:en {:k "P"}}
           (get-in (plugin/expand (assoc base :i18n {:default-locale :en}
                                              :plugins [{:wb.plugin/name :p :i18n {:dict {:en {:k "P"}}}}]))
                   [:i18n :dict]))
        "a host that chose the locale and brought no dictionary gets the plugin's")))

(deftest the-handler-refuses-asset-roots-that-collide-and-stylesheets-that-are-not-paths
  (let [app  #(attempt (fn [] (wb/handler (merge {:routes [["/p/:id" {:get (fn [_] {:status 200 :body "x"})}]]
                                                  :session {:key KEY}} %))))
        cover (fn [path] ["web-base: asset path " path " covers one of :routes or :sessionless; it would serve files where a page or a probe answers"])]
    (is (= ::built (app {:assets [{:path "/h/" :root "public"}]})) "control: a root beside the routes builds")
    (is (= ["web-base: asset path /h/ is given twice" {:config-key [:assets "/h/"]}]
           (app {:assets [{:path "/h/" :root "public"} {:path "/h/" :root "public"}]}))
        "two roots, one path")
    (is (= ["web-base: asset path /h/ is given twice" {:config-key [:assets "/h/"]}]
           (app {:assets [{:path "/h/" :root "public"}] :plugins [{:wb.plugin/name :a :assets {:path "/h/" :root "public"}}]}))
        "a plugin's root and the host's on one path")
    (is (= [(apply str (cover "/p/")) {:config-key [:assets "/p/"]}] (app {:assets [{:path "/p/" :root "public"}]}))
        "a root over a route")
    (is (= [(apply str (cover "/s/")) {:config-key [:assets "/s/"]}]
           (app {:assets [{:path "/s/" :root "public"}] :sessionless {"/s/health" identity}}))
        "a root over a sessionless path")
    (is (= [(apply str (cover "/s/")) {:config-key [:assets "/s/"]}]
           (app {:assets [{:path "/s/" :root "public"}] :sessionless {"/s/api/" identity}}))
        "a root around a sessionless prefix")
    (is (= ::built (app {:assets [{:path "/s/" :root "public"}] :sessionless {"/sq" identity}}))
        "control: a sessionless path that only shares letters")
    (is (= ["web-base: an asset :path is one lower-case segment between slashes, like \"/ab/\": \"/Ab/\""
            {:config-key [:assets 0 :path]}]
           (app {:assets [{:path "/Ab/" :root "x"}]}))
        "a host root is checked like a plugin's, named where it is")
    (doseq [[label sheets] [["a relative path" ["app.css"]] ["another origin's" ["//evil.example/x.css"]]
                            ["a backslash, which a browser reads as a slash" ["/\\evil.example/x.css"]]
                            ["a tab, which a browser drops" ["/\t/evil.example/x.css"]]
                            ["a keyword" :app] ["one string, not a vector" "/app.css"]]]
      (is (= [(str "web-base: :stylesheets is a vector of local paths, each starting with one /"
                   " and holding no backslash, space or control character — never another origin's")
              {:config-key [:stylesheets]}]
             (app {:stylesheets sheets}))
          (str "refused: " label)))
    (is (= ["web-base: stylesheet /a/x.css is given twice" {:config-key [:stylesheets "/a/x.css"]}]
           (app {:stylesheets ["/a/x.css"] :plugins [{:wb.plugin/name :a :assets {:path "/a/" :root "public"}
                                                      :stylesheets ["/a/x.css"]}]}))
        "the same stylesheet from a plugin and the host")
    (is (= [(apply str (cover "/p/")) {:config-key [:assets "/p/"]}]
           (attempt #(wb/handler {:routes [["/p/" {:get (fn [_] {:status 200 :body "x"})}]] :session {:key KEY}
                                  :assets [{:path "/p/" :root "public"}]})))
        "a root exactly on a route")
    (is (= [(apply str (cover "/a/")) {:config-key [:assets "/a/"]}]
           (attempt #(wb/handler {:routes [["/*path" {:get (fn [_] {:status 200 :body "x"})}]] :session {:key KEY}
                                  :assets [{:path "/a/" :root "public"}]})))
        "a root under a catch-all route")
    (is (= ["web-base: config :assets is a vector of {:path \"/name/\" :root \"classpath/prefix\"}" {:config-key [:assets]}]
           (app {:assets {:path "/a/" :root "public"}}))
        "a lone asset map, a plugin's shape, is refused as the host's")
    (is (= ::built (app {:assets [{:path "/hub/" :root "public"}] :sessionless {"/h" identity}}))
        "control: an exact sessionless path that shares letters with a root is no overlap")))

;; --- 3 · merge semantics, through the handler ------------------------------------------

(def ^:private priv
  ["/priv" {:wb/gate wb/subject-present? :get (fn [r] {:status 200 :body (str "hi " (pr-str (:wb/subject r)))})}])

(deftest a-plugins-routes-and-sessionless-paths-are-served--the-latter-before-the-session
  (let [reads  (atom 0)
        writes (atom 0)
        seen   (fn [r] {:status 200 :headers {"Content-Type" "text/plain"}
                        :body (pr-str [(contains? r :session) (:wb/subject r) (:anti-forgery-token r)])})
        app    (wb/handler {:routes      [["/me" {:get (fn [_] {:status 200 :body "me"})}]]
                            :session     {:store (counting-store reads writes {:user "ann"})}
                            :sessionless {"/host-health" seen}
                            :plugins     [{:wb.plugin/name :p
                                           :routes         ["/plug" {:get (fn [_] {:status 200 :body "plug"})}]
                                           :sessionless    {"/plug-health" seen}}]})
        cookie "ring-session=old"]
    (is (= [200 "plug"] ((juxt :status :body) (app (mock/request :get "/plug")))) "the plugin's route answers")
    ;; Ring asks the store even for a request with no cookie, under a nil key.
    (reset! reads 0)
    (app (mock/header (mock/request :get "/me") "Cookie" cookie))
    (is (= 1 @reads) "control: a routed request with that cookie reads the store")
    (reset! reads 0)
    (doseq [path ["/plug-health" "/host-health"]]
      (let [r (app (mock/header (mock/request :get path) "Cookie" cookie))]
        (is (= [200 "[false nil nil]"] [(:status r) (:body r)]) (str path ": outside session, subject and token"))
        (is (= [0 0 nil] [@reads @writes (get-in r [:headers "Set-Cookie"])])
            (str path ": the store neither read nor written, no cookie"))))))

(deftest the-hosts-scalars-beat-a-plugins--and-a-lone-plugin-supplies-what-the-host-left-out
  (let [host-reads (atom 0) plug-reads (atom 0) w (atom 0)
        plugin     {:wb.plugin/name :p
                    :login-path     "/plug-login"
                    :subject-fn     (constantly "plug")
                    :session        {:store (counting-store plug-reads w {})}}
        get*       (fn [app path] (app (mock/request :get path)))]
    (let [app (wb/handler {:routes [priv] :login-path "/host-login" :subject-fn (constantly "host")
                           :session {:store (counting-store host-reads w {})} :plugins [plugin]})]
      (is (= "hi \"host\"" (:body (get* app "/priv"))) "the host's :subject-fn is the one asked")
      (is (= [1 0] [@host-reads @plug-reads]) "and the host's :session store the one read"))
    (let [app (wb/handler {:routes [priv] :login-path "/host-login" :session {:key KEY}
                           :plugins [(assoc plugin :subject-fn (constantly nil))]})]
      (is (= "/host-login?next=%2Fpriv" (get-in (get* app "/priv") [:headers "Location"]))
          "the host's :login-path is where the gate sends a visitor"))
    (reset! plug-reads 0)
    (let [cfg {:routes [priv] :plugins [plugin]}
          app (wb/handler cfg)]
      (is (not-any? cfg [:subject-fn :login-path :session]) "witness: the host gives none of the three")
      (is (= "hi \"plug\"" (:body (get* app "/priv"))) "the plugin's :subject-fn is the one asked")
      (is (= 1 @plug-reads) "and the plugin's :session store the one read"))
    (let [app (wb/handler {:routes [priv] :plugins [(assoc plugin :subject-fn (constantly nil))]})]
      (is (= "/plug-login?next=%2Fpriv" (get-in (get* app "/priv") [:headers "Location"]))
          "and the plugin's :login-path is where the gate sends a visitor"))
    (let [app (wb/handler {:routes [priv] :subject-fn nil :session {:key KEY} :plugins [plugin]})]
      (is (= "hi \"plug\"" (:body (get* app "/priv")))
          "an explicit nil in the host's config is no value: the plugin's is used"))))

(deftest the-hosts-dictionary-rewords-one-key-and-the-plugins-other-keys-and-locales-survive
  (let [cfg {:routes  [["/t" {:get (fn [r] {:status 200 :body (str ((:wb/tr r) [:k]) "|" ((:wb/tr r) [:j]))})}]]
             :session {:key KEY}
             :i18n    {:dict {:en {:k "HOST"}} :default-locale :en}
             :plugins [{:wb.plugin/name :a :i18n {:dict {:en {:k "A" :j "AJ"} :es {:k "AES" :j "AJES"}}}}
                       ;; A plugin with no dictionary, after one with it: it must erase nothing.
                       {:wb.plugin/name :b :sessionless {"/b" identity}}]}
        app (wb/handler cfg)
        say (fn [lang] (:body (app (mock/header (mock/request :get "/t") "Accept-Language" lang))))]
    (is (= {:en {:k "HOST" :j "AJ"} :es {:k "AES" :j "AJES"}} (get-in (wb/expand cfg) [:i18n :dict]))
        "the merged dictionary: the host's one key over the plugin's, everything else the plugin's")
    (is (= "AES|AJES" (say "es")) "witness: Spanish is negotiated, and entirely the plugin's")
    (is (= "HOST|AJ" (say "en")) "English: the host's word for :k, the plugin's for :j")))

(deftest a-plugins-routes-follow-the-hosts-and-a-path-both-claim-is-refused
  (let [r (fn [path] [path {:get (fn [_] {:status 200 :body path})}])
        h (r "/h") a (r "/a") b (r "/b")]
    (is (= [h a b]
           (:routes (wb/expand {:routes [h] :session {:key KEY}
                                :plugins [{:wb.plugin/name :a :routes [a]} {:wb.plugin/name :b :routes b}]})))
        "the host's routes, then each plugin's in order — a plugin's single route taken as one")
    (let [e (try (wb/handler {:routes [(r "/x")] :session {:key KEY} :plugins [{:wb.plugin/name :a :routes [(r "/x")]}]})
                 nil (catch ExceptionInfo e e))]
      (is (= :path-conflicts (:type (ex-data e))) "a path the host and a plugin both claim is reitit's conflict, at construction"))))

;; --- 4 · assets ----------------------------------------------------------------------

(deftest an-asset-root-answers-before-the-session-with-304s--the-bases-wb-untouched
  (let [reads  (atom 0)
        writes (atom 0)
        app    (wb/handler {:routes  [["/me" {:get (fn [_] {:status 200 :body "me"})}]]
                            :session {:store (counting-store reads writes {:user "ann"})}
                            :csrf    false
                            :assets  [{:path "/h/" :root "public"}]
                            :plugins [{:wb.plugin/name :p :assets {:path "/p/" :root "public"}}]})
        cookie "ring-session=old"
        lm-re  #"[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT"]
    (app (mock/header (mock/request :get "/me") "Cookie" cookie))
    (is (= 1 @reads) "control: a routed request with that cookie reads the store")
    (reset! reads 0)
    (doseq [root ["/p/" "/h/"]]
      (let [path   (str root "host.txt")
            first* (app (mock/header (mock/request :get path) "Cookie" cookie))
            lm     (get-in first* [:headers "Last-Modified"])
            again  (app (mock/header (mock/request :get path) "If-Modified-Since" lm))
            older  (app (mock/header (mock/request :get path) "If-Modified-Since" "Wed, 01 Jan 2020 00:00:00 GMT"))]
        (is (= [200 "HOST-ASSET\n" "text/plain"] [(:status first*) (slurp (:body first*)) (get-in first* [:headers "Content-Type"])])
            (str path ": the file"))
        (is (re-matches lm-re (str lm)) (str path ": with Last-Modified"))
        (is (= [0 0 nil] [@reads @writes (get-in first* [:headers "Set-Cookie"])]) (str path ": before the session"))
        (is (= SEC (select-keys (:headers first*) (keys SEC))) (str path ": with the security headers"))
        (is (= [304 nil] [(:status again) (:body again)]) (str path ": the same If-Modified-Since, a 304"))
        (is (= 200 (:status older)) (str path ": an earlier one, a 200"))))
    (is (str/starts-with? (slurp (:body (app (mock/request :get "/wb/wb.css")))) "/* web-base")
        "the base's own /wb/ still serves the base's stylesheet")
    (is (not (str/starts-with? (slurp (:body (app (mock/request :get "/p/wb/wb.css")))) "/* web-base"))
        "while a plugin root serves its own files, even one spelt like the base's")))

;; --- 5 · stylesheets -------------------------------------------------------------------

(deftest the-shell-links-wb-css-then-plugin-sheets-in-order-then-the-hosts-then-head--on-pages-and-error-pages
  (let [layout (fn [{:keys [content request]}] (shell/page {:content content :request request :head [:meta {:name "a"}]}))
        seen   (atom :unset)
        cfg    {:routes       [["" {:wb/layouts [layout]}
                                ["/" {:get (fn [_] {:status 200 :body [:p "hi"]})}]
                                ["/no" {:wb/gate (fn [_] false) :get (fn [_] {:status 200 :body "never"})}]]]
                :session      {:key KEY}
                :csrf         false
                :subject-fn   (constantly "ann")
                :login-path   "/login"
                :error-layout layout
                :stylesheets  ["/host.css"]
                :plugins      [{:wb.plugin/name :a :assets {:path "/a/" :root "public"} :stylesheets ["/a/1.css" "/a/2.css"]}
                               {:wb.plugin/name :b :assets {:path "/b/" :root "public"} :stylesheets ["/b/1.css"]}]}
        app    (wb/handler cfg)
        links  (str "<link href=\"/wb/wb.css\" rel=\"stylesheet\"><link href=\"/a/1.css\" rel=\"stylesheet\">"
                    "<link href=\"/a/2.css\" rel=\"stylesheet\"><link href=\"/b/1.css\" rel=\"stylesheet\">"
                    "<link href=\"/host.css\" rel=\"stylesheet\"><script defer src=\"/wb/htmx.min.js\"></script><meta name=\"a\">")
        hrefs  #(vec (map second (re-seq #"<link href=\"([^\"]+)\"" (str %))))
        ;; The default CSP gives the script tag a fresh nonce per request; it is not what
        ;; this test is about, so it is taken out before the comparison.
        body   #(some-> % :body (str/replace #" nonce=\"[^\"]+\"" ""))]
    (let [body (body (app (mock/request :get "/")))]
      (is (= (str "<!DOCTYPE html>\n<html>" HEAD-START links "</head><body><main class=\"wb-main\"><p>hi</p></main></body></html>") body)
          (str "a page: wb.css, the plugins' in order, the host's, the script, :head — links were " (hrefs body))))
    (doseq [[path status] [["/nope" 404] ["/no" 403]]]
      (let [r (app (mock/request :get path))]
        (is (= [status (str "<!DOCTYPE html>\n<html>" HEAD-START links "</head><body><main class=\"wb-main\">" (frag status)
                            "</main></body></html>")]
               [(:status r) (body r)])
            (str "an error page through the host's layout (" status "): the same links — " (hrefs (:body r))))))
    (let [csrf (wb/handler (-> cfg (dissoc :csrf) (update :routes conj ["/post" {:wb/layouts [layout] :post (fn [_] {:status 200 :body "no"})}])))
          r    (csrf (mock/request :post "/post"))]
      (is (= [403 ["/wb/wb.css" "/a/1.css" "/a/2.css" "/b/1.css" "/host.css"]] [(:status r) (hrefs (:body r))])
          "the CSRF refusal, drawn outside the router with the host's layout, links the same sheets"))
    (let [app (wb/handler {:routes [["/" {:get (fn [r] (reset! seen (find r :wb/stylesheets)) {:status 200 :body "x"})}]]
                           :session {:key KEY}})]
      (app (mock/request :get "/"))
      (is (nil? @seen) "with no stylesheets from anyone, the request carries no :wb/stylesheets key"))))
