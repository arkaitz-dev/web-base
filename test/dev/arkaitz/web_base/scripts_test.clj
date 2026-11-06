(ns dev.arkaitz.web-base.scripts-test
  "A page whose script calls the server (since 0.22.0): `security/csrf-meta`, the token
  where a script reads it and the header to send it in; and the shell's `:scripts` slot
  and the route's `:wb/scripts`, each a deferred script under the request's CSP nonce,
  local paths only. Every literal was measured once through the stack and typed here,
  never built from the vars it checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")
(def ^:private HEAD-START "<head><meta charset=\"utf-8\"><meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">")
(def ^:private CSS "<link href=\"/wb/wb.css\" rel=\"stylesheet\">")
(def ^:private CSP "script-src 'nonce-{nonce}'")

(defn- nonce-of [response]
  (second (re-find #"^script-src 'nonce-([A-Za-z0-9+/]{22}==)'$" (str (get-in response [:headers "Content-Security-Policy"])))))

(defn- sign-in [app]
  (let [login (app (mock/header (mock/request :post "/login") "Sec-Fetch-Site" "same-origin"))]
    (is (= 303 (:status login)) "witness: the sign-in passed CSRF and redirected")
    (is (seq (testing/cookies login)) "witness: and set the session cookie")
    login))

(def ^:private login-route
  ["/login" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/"} :body ""} {:user "ann"}))}])

;; --- csrf-meta -------------------------------------------------------------------------

(deftest csrf-meta-is-the-literal-meta-a-script-reads--only-for-a-session-that-holds-a-token--and-reading-it-marks-it-used
  (let [stored (fn [flag] {::security/csrf-stored true :anti-forgery-token "T<&\"" ::security/csrf-used flag})]
    (is (= "T<&\"" (security/csrf-token (stored (volatile! false)))) "witness: csrf-token itself answers this request")
    (is (= [:meta {:name "csrf-token" :content "T<&\"" :data-header "X-CSRF-Token"}] (security/csrf-meta (stored (volatile! false))))
        "the meta: its name, the session's token, and the header to send it in")
    (is (= "<meta content=\"T&lt;&amp;&quot;\" data-header=\"X-CSRF-Token\" name=\"csrf-token\">"
           (render/html (security/csrf-meta (stored (volatile! false)))))
        "as a page carries it, escaped")
    (is (= ["csrf-token" "csrf-token"] [security/csrf-meta-name (:name (second (security/csrf-meta (stored (volatile! false)))))])
        "csrf-meta-name is the name the meta carries")
    (let [flag (volatile! false)]
      (security/csrf-meta (stored flag))
      (is (true? @flag) "reading the meta marks the token used, as a form does"))
    (let [flag (volatile! false)]
      (is (nil? (security/csrf-meta {:anti-forgery-token "T" ::security/csrf-used flag}))
          "an anonymous request carrying the library's token answers no meta — never one with an empty content")
      (is (false? @flag) "and marks nothing"))
    (is (nil? (security/csrf-meta {})) "a request with no token at all: nothing")
    (is (= "F" (:content (second (security/csrf-meta {::security/csrf-fresh (volatile! "F") :anti-forgery-token "F"}))))
        "a rotation minting a token: the minted one")))

(deftest the-meta-carries-the-token-csrf-accepts-under-the-header-it-names--marks-it-used--and-an-anonymous-visitor-gets-none
  (let [app    (wb/handler {:routes   [["/meta" {:wb/layouts [shell/page]
                                                 :get  (fn [r] {:status 200 :body [:p "x"] :wb/slots {:head (security/csrf-meta r)}})
                                                 :post (fn [_] {:status 200 :body [:p "posted"]})}]
                                       ["/plain" {:wb/layouts [shell/page] :get (fn [_] {:status 200 :body [:p "x"]})}]
                                       login-route]
                           :session  {:key KEY}
                           :security {:csp CSP}})
        login  (sign-in app)
        in     (app (testing/with-cookies (mock/request :get "/meta") login))
        [_ content header] (re-find #"<meta content=\"([^\"]+)\" data-header=\"([^\"]+)\" name=\"csrf-token\">" (str (:body in)))
        post   (fn [& headers] (let [r (reduce (fn [q [k v]] (mock/header q k v))
                                                (testing/with-cookies (mock/request :post "/meta") login) (partition 2 headers))
                                     out (app (mock/header r "Accept" "text/plain"))]
                                 [(:status out) (:body out)]))]
    (is (= 200 (:status in)) "witness: the signed-in page was served")
    (is (and (string? content) (re-matches #"[A-Za-z0-9+/]{80}" content)) "witness: the meta rendered, carrying an 80-character token")
    (is (= "X-CSRF-Token" header) "the header it names is the one the shell's htmx sends")
    (is (str/includes? (:body in) (str "<script defer nonce=\"" (nonce-of in) "\" src=\"/wb/htmx.min.js\"></script>"
                                       "<meta content=\"" content "\" data-header=\"X-CSRF-Token\" name=\"csrf-token\"></head>"))
        "the meta sits in the head, after the base's script")
    (is (= [200 "<!DOCTYPE html>\n<html>"] (update (post header content) 1 #(subs % 0 22)))
        "the meta's token, sent under the meta's header, is accepted")
    (is (= 403 (first (post))) "without it: refused")
    (is (= 403 (first (post header "nope"))) "a wrong token: refused")
    (is (= content (testing/csrf-token in)) "reading it marked the token used: the shell sends the same one from <body>")
    (let [plain (app (testing/with-cookies (mock/request :get "/plain") login))]
      (is (= [nil nil] [(testing/csrf-token plain) (re-find #"csrf-token" (:body plain))])
          "control: the same visitor on a page that reads no token gets none — the meta is what read it")
      (is (= ["no-store" "no-store"] [(get-in in [:headers "Cache-Control"]) (get-in plain [:headers "Cache-Control"])])
          "both pages are uncached, as every page of a session is: the meta adds nothing there, and takes nothing away"))
    (let [anon (app (mock/request :get "/meta"))]
      (is (str/ends-with? (:body anon) "</head><body><main class=\"wb-main\"><p>x</p></main></body></html>")
          "witness: the anonymous page rendered, its body bare")
      (is (= [false false nil []] [(str/includes? (:body anon) "csrf-token") (str/includes? (:body anon) "data-header")
                                    (get-in anon [:headers "Cache-Control"]) (testing/header-values (:headers anon) "Set-Cookie")])
          "an anonymous visitor gets no meta, and the page stays cacheable and session-free"))))

;; --- scripts ---------------------------------------------------------------------------

(defn- html [slots] (render/html (shell/page slots)))

(deftest scripts-follow-htmx--the-routes-before-the-slots--each-once-at-its-first-place--deferred-under-the-nonce-iff-present
  (is (= (str "<html>" HEAD-START CSS
              "<script defer nonce=\"n1\" src=\"/wb/htmx.min.js\"></script><script defer nonce=\"n1\" src=\"/r1.js\"></script>"
              "<script defer nonce=\"n1\" src=\"/both.js\"></script><script defer nonce=\"n1\" src=\"/s1.js\"></script>"
              "<meta name=\"x\"></head><body><main class=\"wb-main\"></main></body></html>")
         (html {:request {:wb/nonce "n1" :wb/scripts ["/r1.js" "/wb/htmx.min.js" "/both.js"]}
                :scripts ["/both.js" "/s1.js"] :head [:meta {:name "x"}]}))
      "the route's before the slot's, a shared path once at its first place, htmx never twice, :head after them all")
  (is (= (str "<html>" HEAD-START CSS "<script defer src=\"/wb/htmx.min.js\"></script><script defer src=\"/r.js\"></script>"
              "<script defer src=\"/s.js\"></script></head><body><main class=\"wb-main\"></main></body></html>")
         (html {:request {:wb/scripts ["/r.js"]} :scripts ["/s.js"]}))
      "no nonce on the request: no nonce attribute on any script")
  (is (= (str "<html>" HEAD-START CSS "<script defer src=\"/wb/htmx.min.js\"></script></head><body><main class=\"wb-main\"></main></body></html>")
         (html {:request {} :scripts nil}))
      "no scripts: the skeleton"))

(deftest route-scripts-concatenate-parent-to-child-onto-the-request--load-under-the-csp-nonce--and-never-reach-a-sibling-or-a-rerender
  (let [seen (atom nil)
        hi   (fn [r] (reset! seen r) {:status 200 :body [:p "hi"]})
        app  (wb/handler {:routes   [["/p" {:wb/scripts ["/p.js" "/shared.js"] :wb/layouts [shell/page]}
                                      ["/c" {:wb/scripts ["/shared.js" "/c.js"] :get hi}]
                                      ["/only" {:get hi}]
                                      ["/none" {:wb/scripts ^:replace [] :get hi}]]
                                     ["/bare" {:wb/layouts [shell/page] :get hi}]
                                     ["/save" {:wb/scripts ["/post.js"] :post (fn [r] (wb/rerender r "/bare" {}))}]
                                     ["/save2" {:wb/scripts ["/post.js"] :post (fn [r] (wb/rerender r "/p/c" {}))}]]
                          :session  {:key KEY}
                          :security {:csp CSP}})
        tags (fn [r srcs] (let [n (nonce-of r)] (mapv #(str "<script defer nonce=\"" n "\" src=\"" % "\"></script>") srcs)))
        page (fn [method path]
               (reset! seen nil)
               (let [r (app (cond-> (mock/request method path) (= :post method) (mock/header "Sec-Fetch-Site" "same-origin")))]
                 (is (string? (nonce-of r)) (str path ": witness: the CSP header carries a nonce"))
                 [r (vec (re-seq #"<script[^>]*></script>" (str (:body r))))]))]
    (doseq [[path srcs on-request]
            [["/p/c" ["/wb/htmx.min.js" "/p.js" "/shared.js" "/c.js"] ["/p.js" "/shared.js" "/c.js"]]
             ["/p/only" ["/wb/htmx.min.js" "/p.js" "/shared.js"] ["/p.js" "/shared.js"]]
             ["/p/none" ["/wb/htmx.min.js"] ::absent]
             ["/bare" ["/wb/htmx.min.js"] ::absent]]]
      (let [[r found] (page :get path)]
        (is (= 200 (:status r)) (str path ": witness: served"))
        (is (= (tags r srcs) found) (str path ": the parent's scripts before the child's, each once, every one under the CSP's nonce"))
        (is (= on-request (get @seen :wb/scripts ::absent)) (str path ": what the handler's request carries"))
        (is (= (count found) (count (re-seq #"nonce=\"" (:body r)))) (str path ": no nonce anywhere but on these scripts"))))
    (let [[r found] (page :post "/save")]
      (is (= 422 (:status r)) "witness: the POST rerendered /bare")
      (is (= (tags r ["/wb/htmx.min.js"]) found) "a POST's scripts do not follow it into a page that has none"))
    (let [[r found] (page :post "/save2")]
      (is (= 422 (:status r)) "witness: the POST rerendered /p/c")
      (is (= (tags r ["/wb/htmx.min.js" "/p.js" "/shared.js" "/c.js"]) found) "the page's own scripts, never the POST's beside them"))))

(def ^:private hostile
  [["a relative path" ["app.js"]]
   ["another origin's" ["//evil.example/x.js"]]
   ["a backslash" ["/\\evil.example/x.js"]]
   ["a tab" ["/\t/evil.example/x.js"]]
   ["a space" ["/a b.js"]]
   ["a scheme" ["https://evil.example/x.js"]]
   ["a keyword inside" [:x]]
   ["one string, not a vector" "/app.js"]
   ["a map" {:src "/a.js"}]
   ["a set, whose order is no order" #{"/a.js"}]
   ["a control character" ["/a\u0001b.js"]]
   ["a delete" ["/a\u007Fb.js"]]])

(deftest a-script-that-is-not-a-local-path-is-refused--wb-scripts-when-the-router-is-built--the-scripts-slot-as-the-routes-logged-500
  (let [hi      (fn [_] {:status 200 :body [:p "x"]})
        built   (fn [f] (try (f) ::built (catch ExceptionInfo e [(ex-message e) (dissoc (ex-data e) :reitit.exception/cause)])))
        refusal (fn [k v] [(str "web-base: " k " is a vector of local paths, each starting with one / and holding no backslash,"
                                " space or control character — never another origin's")
                           {:config-key [(keyword (subs k 1))] :value v}])
        slot    (fn [scripts] (wb/handler {:routes   [["/" {:wb/layouts [shell/page]
                                                           :get (fn [_] {:status 200 :body [:p "x"] :wb/slots {:scripts scripts}})}]]
                                           :session  {:key KEY}
                                           :security {:csp CSP}}))]
    (is (= [true false false false] (mapv render/local-path? ["/a/x.js" "//a" "/\\a" nil])) "the predicate, on its edges")
    (let [ok (wb/handler {:routes [["/" {:wb/layouts [shell/page] :wb/scripts ["/ok.js"] :get hi}]] :session {:key KEY} :security {:csp CSP}})
          r  (ok (mock/request :get "/"))]
      (is (str/includes? (:body r) (str "<script defer nonce=\"" (nonce-of r) "\" src=\"/ok.js\"></script>"))
          "control: a local path builds and is served"))
    (let [r ((slot ["/ok.js"]) (mock/request :get "/"))]
      (is (str/includes? (:body r) (str "<script defer nonce=\"" (nonce-of r) "\" src=\"/ok.js\"></script>"))
          "control: a local path in the slot is served"))
    (doseq [[label bad] hostile]
      (testing label
        (is (= (refusal ":wb/scripts" bad) (built #(wb/handler {:routes [["/" {:wb/scripts bad :get hi}]] :session {:key KEY}})))
            "wb/handler refuses the route's, naming the key and the value")
        (is (= (refusal ":wb/scripts" bad) (built #(wb/router {:routes [["/" {:wb/scripts bad :get hi}]]})))
            "and so does wb/router")
        (is (= (refusal ":wb/scripts" bad) (built #(html {:request {:wb/scripts bad}})))
            "and the shell, handed a request a host built with them")
        (lt/with-log
          (let [r ((slot bad) (mock/request :get "/"))]
            (is (= [[:error (refusal ":scripts" bad)]]
                   (mapv (juxt :level (comp (juxt ex-message ex-data) :throwable))
                         (filter #(= 'dev.arkaitz.web-base.error (ns-name (:logger-ns %))) (lt/the-log))))
                "the slot's is refused as the page renders, logged with the key and the value")
            (is (= 500 (:status r)) "and the page is the route's 500, never served")
            (is (= [] (filter #(and (string? %) (str/includes? (str (:body r)) %)) (if (string? bad) [bad] bad)))
                "no path of it reaches the page")))))))
