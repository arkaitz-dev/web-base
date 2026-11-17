(ns dev.arkaitz.web-base.nav-probe
  "A shell with a nav, served for `test/browser/nav-check.mjs`: what no JVM test can see of
  `/wb/nav.js` — the chrome's current page following `wb/link` swaps, Back and Forward.
  `clojure -M:nav-probe [port]`. The nav holds every kind of link the script must judge:
  pages, a host's own section mark, a `false` mark, a fragment and a query of the same page,
  a link to a section of page A,
  and another origin's link to the same path."
  (:require [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.shell :as shell]))

(def csp "default-src 'self'; script-src 'nonce-{nonce}'; style-src 'self'")

(defn- layout [slots]
  (shell/page (assoc slots :nav (list (wb/link "/a" {:id "nav-a"} "A")
                                      (wb/link "/b" {:id "nav-b"} "B")
                                      (wb/link "/c" {:id "nav-c" :aria-current "false"} "C")
                                      [:a {:id "nav-section" :href "/a" :aria-current "true"} "Section"]
                                      [:a {:id "nav-fragment" :href "#top"} "Top"]
                                      [:a {:id "nav-query" :href "?q=1"} "Query"]
                                      [:a {:id "nav-section-of-a" :href "/a#top"} "A's top"]
                                      [:a {:id "nav-elsewhere" :href "http://example.test/a"} "Elsewhere's /a"]))))

(defn- page [label]
  (fn [_] (response/ok [:div [:p {:id "label"} label]
                        (wb/link "/c" {:id "main-c"} "C from main")])))

(defn handler []
  (wb/handler {:session  {:key (byte-array 16 (byte 7))}
               :routes   [["" {:wb/layouts [layout]}
                           ["/a" {:get (page "page a")}]
                           ["/b" {:get (page "page b")}]
                           ["/c" {:get (page "page c")}]]]
               :security {:csp csp}}))

(defn -main [& [port]]
  (let [{:keys [port]} (server/start (handler) {:port (if port (Long/parseLong port) 3092)})]
    (println (str "nav-probe on http://localhost:" port "/a"))))
