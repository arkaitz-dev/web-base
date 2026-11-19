(ns dev.arkaitz.web-base.nav-probe
  "A shell with a nav, served for `test/browser/nav-check.mjs`: what no JVM test can see of
  `/wb/nav.js` — the chrome's current page following `wb/link` swaps, Back and Forward.
  `clojure -M:nav-probe [port]`. The nav holds every kind of link the script must judge:
  pages, a host's own section mark, a `false` mark, a fragment and a query of the same page,
  a link to a section of page A,
  and another origin's link to the same path. Under `/hw/<mode>` (and `/hw/<mode>/tall`, a page
  that overflows, and `/hw/aligned/themed`, whose host redefines `--wb-gap` and `--wb-measure`)
  it serves the shell's header at each `:header-width`, with `/hw.css`, whose `#hw-bleed` bleeds
  over the header's `--wb-gap` as uikit's bar does."
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

(def ^:private hw-css
  (str "#hw-bleed { margin-inline: calc(-1 * var(--wb-gap)); }\n"
       "#hw-fixed { position: fixed; inset-inline-end: 0; bottom: 0; inline-size: 10px; block-size: 10px; }\n"
       ;; As uikit's sticky bar makes it: sticky within the body, so the body must keep its height.
       "header.wb-header { position: sticky; inset-block-start: 0; }\n"
       ;; Chromium, launched without --hide-scrollbars, then gives the root a scrollbar that takes
       ;; 15px, so the body's width and 100vw differ.
       "::-webkit-scrollbar { inline-size: 15px; width: 15px; background: #888; }\n"))

(def ^:private hw-theme-css ":root { --wb-gap: 24px; --wb-measure: 40rem; }\n")

(defn- hw-layout [{:keys [request] :as slots}]
  (let [mode (get-in request [:path-params :mode])]
    (shell/page (assoc slots
                       :head (list [:link {:rel "stylesheet" :href "/hw.css"}]
                                   (when (.endsWith ^String (:uri request) "/themed") [:link {:rel "stylesheet" :href "/hw-theme.css"}]))
                       :header [:div {:id "hw-child"} "h" [:div {:id "hw-bleed"} "b"]]
                       :header-width (when-not (= mode "default") (keyword mode))))))

(defn- hw-page [tall?]
  (fn [_] (response/ok (list [:p {:id "hw-text"} "text"] [:div {:id "hw-fixed"}]
                             (when tall? (repeat 200 [:p "x"]))))))

(defn handler []
  (wb/handler {:session  {:key (byte-array 16 (byte 7))}
               :routes   [["" {:wb/layouts [layout]}
                           ["/a" {:get (page "page a")}]
                           ["/b" {:get (page "page b")}]
                           ["/c" {:get (page "page c")}]]
                          ["/hw.css" {:get (fn [_] {:status 200 :headers {"Content-Type" "text/css; charset=utf-8"} :body hw-css})}]
                          ["/hw-theme.css" {:get (fn [_] {:status 200 :headers {"Content-Type" "text/css; charset=utf-8"} :body hw-theme-css})}]
                          ["/hw/:mode" {:wb/layouts [hw-layout]}
                           ["" {:get (hw-page false)}]
                           ["/tall" {:get (hw-page true)}]
                           ["/themed" {:get (hw-page false)}]]]
               :security {:csp csp}}))

(defn -main [& [port]]
  (let [{:keys [port]} (server/start (handler) {:port (if port (Long/parseLong port) 3092)})]
    (println (str "nav-probe on http://localhost:" port "/a"))))
