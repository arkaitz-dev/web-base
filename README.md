# web-base

A super-micro-framework for server-rendered Clojure web applications: the
operational plumbing a project plugs in so it does not reassemble it every time.
Server-rendered HTML with [htmx 4](https://four.htmx.org) and
[Hiccup](https://github.com/weavejester/hiccup), routing with
[reitit](https://github.com/metosin/reitit), Jetty behind two functions.

`SPEC.md` says what it is and why every boundary sits where it sits. This file
says how to use it.

## The two rules

**A library you call, never a framework that calls you.** The host wires the
modules explicitly. There is no plugin registry, no auto-discovery, no hook
system, no ambient global state: configuration is passed in.

**The membership test.** Before adding anything: would a bicycle rental or a
clinic's appointment book need this, unchanged? If not, it does not belong here.

## Coordinates

```clojure
;; deps.edn — as a git dependency
dev.arkaitz/web-base {:git/url "https://github.com/arkaitz-dev/web-base"
                      :git/sha "<commit>"}

;; or as a Maven artifact, once published
dev.arkaitz/web-base {:mvn/version "0.1.0"}
```

The library depends on `metosin/reitit-ring`, `hiccup`, `ring-jetty-adapter`,
`tools.logging`, `tempura`, `ring-anti-forgery` and `integrant` — and nothing
else reaches your classpath. Coercion is passed through: bring malli, spec or
schema yourself.

## What it gives you

- **Routing and a page shell.** Routes are reitit data. A route declares its
  layout stack in route data (`:wb/layouts`, outermost first; nested routes
  concatenate). Handlers return a Ring response whose `:body` is Hiccup —
  `response/ok` builds it; the base renders it through the stack — the whole
  stack for a navigation, nothing for an htmx swap, or exactly the height the
  handler names with `:wb/height`.
- **A gate that speaks htmx.** Per route, `:wb/gate` is a predicate over the
  request. A refusal becomes a `303` for a navigation, an `HX-Redirect` for an
  htmx swap (never a `302` htmx would follow into its target), or a `403` when a
  subject is present.
- **Errors as data with three renderings** — page, fragment, plain text — chosen
  per request, never a whole page inside a swap.
- **Sessions** over Ring's store protocol, cookie store by default, the signing
  key never generated at startup, `Secure`/`HttpOnly`/`SameSite=Lax` by default,
  and `session/rotate` for the moment someone logs in.
- **A request id** on every request, response and log line.
- **Locale negotiation** with a Tempura dictionary: `:wb/locale` and `:wb/tr` on
  every request, `<html lang>` that never lies.
- **Web security**: `nosniff`, `X-Frame-Options`, `Referrer-Policy`, optional
  HSTS, a per-request CSP nonce, CSRF through ring-anti-forgery, opt-in proxy
  headers. Static assets answer before the session, and answer a conditional
  GET with `304`.
- **Test helpers** for the host's own suite: the cookies a response set, the
  CSRF token a page carries, a request shaped like an htmx swap.
- **Lifecycle** as plain `start`/`stop` functions; an optional Integrant
  namespace for hosts that use it.

What it does not do: authenticate, authorise, persist, or know your domain.

## A host, in full

```clojure
(ns my.app
  (:require [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [reitit.coercion.malli :as malli-coercion]))

(defn my-shell [{:keys [content request] :as slots}]
  (shell/page (assoc slots
                     :title    "My app"
                     :nav      [:a {:href "/"} "Home"]
                     :identity (if-let [who (:wb/subject request)] (str "hi, " who) "anonymous")
                     :content  content)))

(defn home [{:keys [wb/tr]}]
  (response/ok [:p (tr :hi)]))

(defn private [request]
  (response/ok [:p (str "Only you, " (:wb/subject request))] {:slots {:title "Private"}}))

(defn login [request]
  ;; the host authenticates however it likes; the base only learns a subject exists
  (session/rotate (response/see-other "/private")
                  (assoc (:session request) :subject (get-in request [:form-params "name"]))))

(def app
  (wb/handler
   {:routes     [["" {:wb/layouts [my-shell]}
                  ["/" {:get home}]
                  ["/login" {:post login}]
                  ["/private" {:wb/gate wb/subject-present?
                               :get private}]]]
    :coercion   malli-coercion/coercion
    :subject-fn #(get-in % [:session :subject])
    :login-path "/login"
    :session    {:key (System/getenv "WB_SESSION_KEY")}   ; base64 of 16 bytes
    :static     {:root "public"}
    :i18n       {:dict {:en {:hi "Hello"} :es {:hi "Hola"}} :default-locale :en}
    :security   {:csp "default-src 'self'; script-src 'nonce-{nonce}'"}}))

(def server (wb/start #'app {:port 3000}))   ; => {:server … :port 3000}; (wb/stop server)
```

A method's value is the handler itself or a map with `:handler` and reitit's
`:parameters`. Passing the var `#'app` lets a REPL redefine `app` without
restarting Jetty.

Generate a key once, in a REPL, and keep it in the environment:

```clojure
(dev.arkaitz.web-base.session/generate-key)
```

The base refuses to invent one: a key generated at startup destroys every session
on every deploy, silently.

### Configuration keys

| key | meaning |
|---|---|
| `:routes` | reitit route data (required) |
| `:session` | `{:key base64-or-bytes}` or `{:store ring-session-store}` (required); `:cookie-attrs` and `:cookie-name` (default `ring-session`) optional |
| `:subject-fn` | request → subject or nil; default: always nil |
| `:login-path` | where a refusal without a subject goes; required iff a route has `:wb/gate` |
| `:coercion` | a reitit coercion, passed through |
| `:static` | `create-resource-handler` options for the host's assets, e.g. `{:root "public"}` |
| `:error-layout` | slot function used for error pages: receives `:content`, `:request`, `:error` |
| `:i18n` | `{:dict tempura-dict :default-locale k :locale-fn (fn [request] preferences)}` |
| `:security` | `{:frame-options "DENY" :csp "…{nonce}…" :hsts {:max-age n} :proxy? bool}` |
| `:csrf` | `false` to disable the anti-forgery token; on for anything else |

Every malformed or missing required value fails at construction, naming the key.

### The wiring order

It is the product, so it is written down, outermost first:

```
request-id → security headers → proxy (opt-in) → [assets] → session → params
→ i18n → csrf → subject → router: error → gate → render → coercion → handler
```

A route may add its own `:middleware` in route data; reitit merges it
**innermost**, inside the base's four, so it wraps the handler only. It sees
a handler's exception before `error` does; it does not see a layout, gate or
coercion failure, nor the default 404. `:middleware ^:replace […]` replaces the
base's four as well, silently. Three things a host may want there:

- a stack trace in the browser during development — `ring-devel`'s
  `wrap-stacktrace`, or your own catcher; in production a 500 is logged with its
  stack under the request id the response carries in `X-Request-Id`;
- `ring.middleware.flash/wrap-flash` — with a hazard: it consumes the flash on
  any request that carries it, so an htmx fragment (a poll, a keyup in flight)
  arriving between the 303 and the navigation eats the message. Consume it only
  when `(not (htmx/partial-request? request))`;
- `ring.middleware.keyword-params/wrap-keyword-params` — note Ring's merged
  `:params` lets a query-string key shadow a form field; reitit's `:parameters`
  give typed, keyword access without that.

### Responses

`(response/ok body)` is `{:status 200 :body body}`; `(response/ok body {:slots m
:height n})` adds `:wb/slots` and `:wb/height`. `(response/see-other path)` is
the 303 after a classic form. A 404 or 403 the handler decides is
`(error/throw! {:status 404})`: it reaches the error renderer, not the layouts.

`:wb/height` is rarely written. A navigation renders the whole stack and an htmx
swap renders none of it; name a height only for the case in between — a tab
swap that wants its section but not the shell. It counts from the innermost
layout, so an outer layout added later never invalidates a height already
written.

### Layouts

A layout is a function of one map of slots: `:content`, `:request`, plus whatever
the handler put under `:wb/slots`. Nesting is composition. `shell/page` is one
such layout, with slots `:lang :title :head :header :nav :identity :content
:footer`; it links `/wb/wb.css` (structural CSS, every colour a `--wb-*` custom
property you redefine) and `/wb/htmx.min.js`, and puts the CSRF token in
`hx-headers:inherited` on `<body>` so every htmx request carries it. Classic forms
add `(security/csrf-field request)`.

### Forms and validation

Two roads, and they do not meet. Route `:parameters` with a coercion refuse an
invalid request **before the handler**, as a `400` through the error renderer
with the humanized explanation under `:wb/coercion` for your error layout. A
form that must come back re-rendered with its errors is validated **in the
handler** — with malli:

```clojure
(defn signup [request]
  (let [values (select-keys (:form-params request) ["name" "email"])
        parsed (m/decode Signup values (mt/string-transformer))]
    (if-let [explanation (m/explain Signup parsed)]
      (response/ok (signup-form values (me/humanize explanation)))
      (response/see-other "/welcome"))))
```

### Internationalisation

`:wb/tr` takes a resource id, `(tr :nav/home)`, an id with arguments,
`(tr :greet ["Ann"])`, or Tempura's vector of ids with fallbacks,
`(tr [:nav/home :nav/default])`. An id the dictionary lacks answers `nil` —
no exception, no placeholder — so a missing translation shows as an empty
element.

### Testing a host

`dev.arkaitz.web-base.testing` reads the shapes the base emits, so a host's
tests need no regex of their own. With ring-mock:

```clojure
(let [page   (app (mock/request :get "/login"))
      token  (testing/csrf-token page)              ; hidden field or the shell's body attribute
      login  (app (-> (mock/request :post "/login" {"name" "ada" "__anti-forgery-token" token})
                      (testing/with-cookies page)))  ; the session cookie the page minted
      swap   (app (-> (mock/request :get "/private") (testing/with-cookies login) testing/fragment))]
  …)
```

`(testing/cookies response)` is the map behind `with-cookies`: every cookie a
response set, with a deletion — `Max-Age` zero or less — as `nil`. `with-cookies`
keeps the request's own jar, so chaining it across a flow does what a browser
does.

### htmx

The base's htmx coupling lives in three named places: the request classifier
(`HX-Request` and `HX-Request-Type`), the redirect (`HX-Redirect`) and the error
renderer. htmx 4 swaps every response, 4xx and 5xx included, so an error inside
a swap lands inside its target. `hx-on`, `hx-vals js:` and trigger filters need
`unsafe-eval`; under a strict CSP, do without them — the demo does.

### Integrant

```clojure
{:my/web-config {…}                                       ; your component building the map above
 :dev.arkaitz.web-base/handler #ig/ref :my/web-config
 :dev.arkaitz.web-base/server  {:handler #ig/ref :dev.arkaitz.web-base/handler :port 3000}}
```

`dev.arkaitz.web-base.integrant/read-string` reads `#ig/ref` and `#wb/env "VAR"`
in the same EDN; requiring that namespace is what adds the methods.

## The demo

`demo/` is the acceptance test: a small application using nothing but web-base —
tasks, live search, a validated signup, a login of its own, a gated page, a
deliberate error, two languages, a strict CSP.

```
WB_SESSION_KEY=<base64 of 16 bytes> clojure -M:demo [port]
clojure -M:dev     # REPL: (go) (reset) (halt)
```

## Development

```
clojure -M:test                 # the whole suite, demo included
clojure -T:build jar            # target/web-base-0.1.0.jar
clojure -T:build install        # into ~/.m2
CLOJARS_USERNAME=… CLOJARS_PASSWORD=<deploy token> clojure -T:build deploy
```

Every test was written against a contract and watched go red by named mutations;
the security-adjacent ones went through an adversarial review panel. The rules
are in `CLAUDE.md`.

## License

MIT. See `LICENSE`.
