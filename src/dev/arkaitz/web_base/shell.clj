(ns dev.arkaitz.web-base.shell
  "The page shell as a set of slots the host fills (SPEC §13): `:lang`,
  `:title`, `:head`, `:scripts`, `:header`, `:nav`, `:identity`, `:content`, `:footer`.
  It is an ordinary layout — a function of one slot map — usable as the
  outermost entry of a route's `:wb/layouts` or called by the host's own
  layout. Absent slots emit nothing.

  What the shell reads from `:request`, when the request carries it: the
  stylesheets of the config's `:stylesheets` and its plugins, linked after the base's
  own and before `:head`; the negotiated locale for `lang` (SPEC §14), the CSP nonce for its own script
  tag and the CSRF token, sent on every htmx request as `X-CSRF-Token`
  through `hx-headers:inherited` on `<body>` (SPEC §15) — htmx 4 inherits
  nothing without the modifier. `:scripts` — local paths, after the route's own
  `:wb/scripts` — is a deferred `<script>` each, under the request's nonce (since
  0.22.0); a `<script>` the host writes into `:head` itself needs its own `nonce`.

  The token goes on `<body>` only when the page uses it: when the rendered body
  carries an htmx attribute (`hx-…` or `data-hx-…`), or when its content already
  read the token for a form. Reading it is what writes it into the session, so a
  page with neither — a public page, crawled — leaves no session behind. The body
  is rendered before that decision, so a form or an htmx attribute inside lazy
  content counts. What it cannot see is htmx reached from a script
  (`htmx.ajax`, `fetch`) on a page with neither, or attributes under a prefix the host
  configured for htmx in place of `data-hx-`: such a page puts `security/csrf-meta` in
  `:head`, which reads the token."
  (:require [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security]
            [hiccup2.core :as h]))

(def css-path "/wb/wb.css")
(def htmx-path "/wb/htmx.min.js")

(defn- csrf-headers
  "The token is base64: no quote or backslash can reach the JSON."
  [token]
  (str "{\"" security/csrf-header "\":\"" token "\"}"))

(def ^:private htmx-attribute
  "An attribute htmx acts on, as the body renders it: htmx 4 reads both prefixes. Text
  that merely spells one only makes the shell send the token, as it always did."
  #"(?i)\s(?:data-)?hx-[a-z]")

(defn- token-for
  "The token, read only when `body` — the rendered body — needs it."
  [request body]
  (when (or (some-> (::security/csrf-used request) deref)
            (re-find htmx-attribute body))
    (security/csrf-token request)))

(defn page
  [{:keys [lang title head scripts header nav content footer request] who :identity}]
  (render/check-scripts! scripts [:scripts])
  (render/check-scripts! (:wb/scripts request) [:wb/scripts])
  (let [lang  (or lang (:wb/locale request))
        nonce (:wb/nonce request)
        body  (render/html (list (when header [:header.wb-header header])
                                 (when nav [:nav.wb-nav nav])
                                 (when who [:div.wb-identity who])
                                 [:main.wb-main content]
                                 (when footer [:footer.wb-footer footer])))
        token (token-for request body)]
    [:html (cond-> {} lang (assoc :lang lang))
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      (when title [:title title])
      [:link {:rel "stylesheet" :href css-path}]
      (for [href (:wb/stylesheets request)] [:link {:rel "stylesheet" :href href}])
      (for [src (distinct (concat [htmx-path] (:wb/scripts request) scripts))]
        [:script (cond-> {:src src :defer true} nonce (assoc :nonce nonce))])
      head]
     [:body (cond-> {} token (assoc (keyword "hx-headers:inherited") (csrf-headers token)))
      (h/raw body)]]))
