(ns dev.arkaitz.web-base.shell
  "The page shell as a set of slots the host fills (SPEC §13): `:lang`,
  `:title`, `:head`, `:header`, `:nav`, `:identity`, `:content`, `:footer`.
  It is an ordinary layout — a function of one slot map — usable as the
  outermost entry of a route's `:wb/layouts` or called by the host's own
  layout. Absent slots emit nothing.

  What the shell reads from `:request`, when the request carries it: the
  negotiated locale for `lang` (SPEC §14), the CSP nonce for its own script
  tag and the CSRF token, sent on every htmx request as `X-CSRF-Token`
  through `hx-headers:inherited` on `<body>` (SPEC §15) — htmx 4 inherits
  nothing without the modifier. A `<script>` the host adds through `:head`
  needs its own `nonce` attribute, read from `(:wb/nonce request)`."
  (:require [dev.arkaitz.web-base.security :as security]))

(def css-path "/wb/wb.css")
(def htmx-path "/wb/htmx.min.js")

(defn- csrf-headers
  "The token is base64: no quote or backslash can reach the JSON."
  [token]
  (str "{\"" security/csrf-header "\":\"" token "\"}"))

(defn page
  [{:keys [lang title head header nav content footer request] who :identity}]
  (let [lang  (or lang (:wb/locale request))
        nonce (:wb/nonce request)
        token (security/csrf-token request)]
    [:html (cond-> {} lang (assoc :lang lang))
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      (when title [:title title])
      [:link {:rel "stylesheet" :href css-path}]
      [:script (cond-> {:src htmx-path :defer true} nonce (assoc :nonce nonce))]
      head]
     [:body (cond-> {} token (assoc (keyword "hx-headers:inherited") (csrf-headers token)))
      (when header [:header.wb-header header])
      (when nav [:nav.wb-nav nav])
      (when who [:div.wb-identity who])
      [:main.wb-main content]
      (when footer [:footer.wb-footer footer])]]))
