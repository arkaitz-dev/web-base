# web-base

[![Clojars Project](https://img.shields.io/clojars/v/dev.arkaitz/web-base.svg)](https://clojars.org/dev.arkaitz/web-base)

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
;; deps.edn — from Clojars
dev.arkaitz/web-base {:mvn/version "0.8.0"}

;; or straight from git, to track a commit
dev.arkaitz/web-base {:git/url "https://github.com/arkaitz-dev/web-base"
                      :git/sha "<commit>"}
```

The badge at the top is the version actually published.

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
- **A gate that speaks htmx.** `:wb/gate` is a predicate over the request, declared
  on a route or on the parent of a group of them — then every route nested there is
  gated, including one added later without a word. A refusal becomes a `303` for a navigation, an `HX-Redirect` for an
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
  namespace for hosts that use it, and an optional one that lets the whole
  thing compile to a native binary.

What it does not do: authenticate, authorise, persist, or know your domain.

## A host, in full

```clojure
(ns my.app
  (:require [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]   ; csrf-field, in a view with a form
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
                  ["" {:wb/gate wb/subject-present?}   ; every route below is private
                   ["/private" {:get private}]]]]
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
`:parameters`. Without a coercion a path parameter is reitit's, a string under
`:path-params` — `/things/:id` answers `{:id "7"}`; with one, `(:parameters request)`
holds it typed.

Gate the group, not each route: a private route added under the gated parent is
private without anyone remembering to say so. Public routes, a login included, go
outside it. A child's own `:wb/gate` replaces the parent's, and a child's `nil` does
not open it — reitit's merge keeps the parent's — so a page that must be public moves
out of the group.

### Sessions

The session travels in a cookie signed with a 16-byte key. Generate one once, in
a REPL, and keep it in the environment or in the file the last section of this
README describes:

```clojure
(dev.arkaitz.web-base.session/generate-key)   ; => "AAECAwQFBgcICQoLDA0ODw=="
```

The base refuses to invent one: a key generated at startup destroys every session
on every deploy, silently, and differs per instance when there is more than one.
Changing the key later has the same effect on purpose — it is how you invalidate
everything at once.

**Rotate the session on login. This one is yours to call.** The mechanism belongs
to the base and the moment belongs to you, because only your code knows when
someone has just authenticated:

```clojure
(session/rotate (response/see-other "/private")
                (assoc (:session request) :subject who))
```

It is the defence against session fixation, and skipping it produces no symptom
at all: everything works, and an attacker who planted a session id before the
login still holds a valid one after it.

The CSRF token does not cross the rotation — copying the old session, as above, drops
it. A redirect leaves the new session without one until the next page; a login that
renders a page through the base shows the fresh token its new session holds, as long as
the token is read in a layout (a form the handler built before rotating would carry the
old one, and is logged by name). An htmx login answered with a fragment makes htmx reload
the page (`HX-Refresh`), since only a whole page can replace the token in `<body>`.

**On `http://localhost`, opt out of `Secure`.** The cookie carries
`Secure`/`HttpOnly`/`SameSite=Lax` by default, and a browser drops a `Secure`
cookie sent over plain HTTP without a word, so you get no session and no error:

```clojure
:session {:key … :cookie-attrs {:secure false}}   ; development only
```

The default is the safe one so that the opt-out is the thing you write, not the
thing you forget. And note what the cookie store cannot do: a cookie session
cannot be revoked from the server, it only expires. Pass your own `:store` — any
implementation of Ring's session store protocol — when you need to end a session
on demand.

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
| `:sessionless` | `{"/health" handler}`: exact paths, any method, answered before the session — no session read or written, no CSRF, no locale, no subject; the request id and security headers still apply. A handler may be a var; a path one of `:routes` also matches is refused |

Every malformed or missing required value fails at construction, naming the key, and
every refusal the base throws says `web-base:` first, so a host's log names who refused.

Unknown keys at the top level are yours — the base ignores them. **Inside the maps the
base owns they are refused** (since 0.8.0, a breaking change): `:session`, `:security`
and its `:hsts`, `:i18n` and `:static` each take exactly the keys above (`:static` takes
reitit's `create-resource-handler` options), and anything else fails at construction as
`{:config-key [:security :hts]}`, because a misspelt `:hts` would otherwise serve without
HSTS and say nothing. `:cookie-attrs` is Ring's map and Ring refuses an unknown attribute
itself, at the first cookie it writes.

### The wiring order

It is the product, so it is written down, outermost first:

```
request-id → security headers → proxy (opt-in) → [assets, sessionless] → error boundary
→ session → params → i18n → csrf → subject → router: error → gate → render → coercion → handler
```

The outer error boundary exists for what throws before the router — a session store
whose database is down, above all, or a subject function that fails: that is the base's
500 page (fragment, text), logged once, never the exception at the adapter. It is the
base's own page, never your `:error-layout`: out there the request has no session,
translations or subject for a layout to read, and a layout that threw would have nothing
left to catch it. No negotiated language either, since i18n is inside. `:sessionless`
routes that throw get the same page, for the same reason.

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
`(error/throw! {:status 404})`: it reaches the error renderer, not the layouts. A thing
that exists but is not the subject's is a 404 too — "not yours" answered as "not found",
so an id tried at random says nothing about what exists.

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
add `(security/csrf-field request)`. A token is stored in the session only when a
request read it through `csrf-field` or `csrf-token`, so an anonymous request that
renders neither — a health probe, JSON, a redirect — creates no session; read it through
those two, while the handler runs, and never from `:anti-forgery-token` directly.

A layout receives the request as the router saw it: what a route's own `:middleware` adds
never reaches it, because that middleware sits innermost, around the handler alone. What
a layout, a gate and a handler all need about the person — a role, a time zone, the
address they signed in with — belongs in the subject: compose `:subject-fn` so it answers
a map instead of an id, and `:wb/subject` carries it everywhere at once.

```clojure
:subject-fn (fn [request]
              (when-let [id ((auth/subject-fn ceremony) request)]
                (account-of datasource id)))   ; {:id … :role … :zone …}; nil when there is none
```

Answer nil exactly when there is no live subject — a gate reads non-nil as "signed in",
and auth-base's `wrap-revoked` reads nil after a session as "revoked". Anything only one
page needs stays in its handler and reaches the layouts through `:wb/slots`.

An `:error-layout` renders without a session token on its request, so a crawler's 404
writes no session row: a shell fragment that draws a form with `csrf-field` — a language
switcher in the footer, a logout button — belongs in the page layouts only.

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
      (wb/rerender request "/signup" {:values values :errors (me/humanize explanation)})
      (response/see-other "/welcome"))))
```

`wb/rerender` renders the page's own GET again — its gate, layouts and CSRF token
included — with `{:values … :errors …}` under `:wb/form` on the request and status 422,
so the GET handler that draws the form draws it refused too, and no redirect loses what
was typed. Give the form the same path for GET and POST and a reload does the sensible
thing.

On an htmx request `rerender` renders the page as any GET renders under htmx: its content
without its layouts, which lands inside whatever the form targeted — the whole page's
content inside the form, for a form that swaps only itself. Such a form, when it must also
work without JavaScript, answers through the two helpers instead of branching by hand:

```clojure
(defn save [request]
  (let [values (select-keys (:form-params request) ["name"])]
    (if-let [errors (validate values)]
      (wb/refuse-form request "/things" {:values values :errors errors}
                      (thing-form request values errors))   ; the form with its errors
      (wb/form-done request "/things" (thing-row (save! values))))))
```

`refuse-form` answers an htmx swap with that fragment as a 422
(`response/unprocessable`) and a navigation with `rerender` of the page's GET; `form-done`
answers a swap with its fragment, 200, and a navigation with a 303 to the location. All of
this relies on htmx 4 swapping a 422; under htmx 2 a host would have to allow it in
`htmx.config.responseHandling`.

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

For a whole flow, `testing/browser` is that chain as a value:

```clojure
(let [b (-> (testing/browser app)
            (testing/visit :get "/login")                     ; a page with a CSRF token
            (testing/visit :post "/login" {"name" "ada"}))]   ; the token rides along
  (:path b)                                                   ; "/me" — where the 303 landed
  (:response b))                                              ; the page it landed on
```

`visit` answers a new browser: the jar accumulated (a deletion forgets), the token of
the last page that had one, 301/302/303 followed as a GET up to ten times, and `:path`
— the last request's path and query string, which is the address bar. Params go where a
browser puts them: the query string for a GET and for htmx's DELETE, a form body for a
POST, PUT or PATCH. A POST carries the token as its hidden field; `{:htmx? true}` sends it
as the header with the headers of a swap, and leaves an `HX-Redirect` for the test to
read. A PUT, PATCH or DELETE needs `{:htmx? true}`, since no browser form sends one, and
a request with no token known throws instead of sending.
`{:follow? false}` sends one request and follows nothing, for a test that asks *who*
answered — a handler that redirects to a gated page lands on the login page exactly as
the gate would — and the jar still takes what that response set.
`{:headers {"accept-language" "es"}}` adds headers to every request, redirects followed
included, as a browser keeps sending its own; one `visit` writes itself — the cookie, the
host, the body's, the CSRF header or a swap's — throws, naming it.

Hiccup 2 escapes `'` as `&apos;`, never as `&#39;`, so a negative assertion on a
rendered body — `(not (str/includes? body "&#39;"))` — is vacuous: it passes whatever
the page says. Assert on the spelling the renderer produces.

`(testing/gate-refusal? response login-path)` says whether a response is the gate's
refusal of somebody with no subject: a 303 for a navigation, an `HX-Redirect` for a
swap, each with the refusal's own `Vary` and `Cache-Control: no-store`. Those headers
are what tell it from a handler that redirects to the login page by itself, so a host
asserting "no private route is open" asks this rather than copying them.

### htmx

The base's htmx coupling lives in three named places: the request classifier
(`HX-Request` and `HX-Request-Type`), the redirect (`HX-Redirect`) and the error
renderer. htmx 4 swaps every response, 4xx and 5xx included, so an error inside
a swap lands inside its target. `hx-on`, `hx-vals js:` and trigger filters need
`unsafe-eval`; under a strict CSP, do without them — the demo does.

Target the element the server answers with, by its id, and swap its outer HTML: a form
whose answer is `[:form#thing …]` wants `hx-target="#thing" hx-swap="outerHTML"`. Aimed
at a parent, or swapping the inner HTML, the answer lands inside what it was meant to
replace, and the page nests a form in a form.

A page and its own fragment can share one route — a list that filters as you type, whose
address must stay shareable. Branch on `htmx/partial-request?`: a swap gets the list
alone, a navigation the page with the list inside. `hx-push-url="true"` on the input keeps
the address bar on the filtered URL, so a reload or a copied link lands on the same page;
an element elsewhere that the answer must also refresh — a counter, the list after an
add — rides along with `hx-swap-oob="true"` and its own id.

```clojure
(defn contacts [request]
  (let [q    (get-in request [:query-params "q"] "")
        rows (list-of (search q))]
    (if (htmx/partial-request? request)
      (response/ok rows)
      (response/ok (contacts-page q rows)))))
```

### Lifecycle

```clojure
(def server (wb/start #'app {:port 3000}))   ; => {:server <jetty> :port 3000}
(wb/stop server)
```

`start` takes the handler and ring-jetty-adapter's options. `:port` is required,
and `0` asks the operating system for a free one — which is why the handle
carries the port back: with `:port 0` there is no other way to learn it without
reaching into Jetty. `:join?` is forced to false whatever you pass, because a
start that blocks its caller forever is a hang, not a server; keep the process
alive yourself, and stop the server on the way out:

```clojure
(.addShutdownHook (Runtime/getRuntime) (Thread. #(wb/stop server)))
```

`stop` takes the handle `start` returned, not the Jetty object inside it. Passing
the var `#'app` rather than `app` lets a REPL redefine the handler without
restarting Jetty.

**Stopping drains.** `stop` closes the connector at once and lets requests already in
flight finish within `:stop-timeout-ms`, a `start` option — 10 000 by default, which
fits well inside the grace Kubernetes (30 s) and systemd (90 s) give a process before
killing it. A request arriving meanwhile on a connection that was already open is
answered 503. One still running when the window closes is cut, and `stop` logs a WARN
and returns rather than throwing, so the rest of a system still halts. `0` cuts at once.
Through Integrant the option goes in `:dev.arkaitz.web-base/server`'s map beside
`:port`.

### A native binary

A web-base application compiles to a GraalVM native image. Measured on one
machine, the same application both ways:

| | startup | resident |
|---|---|---|
| native binary | 41 ms | 25 MB |
| JVM | 625 ms | 220 MB |

The very first run of a freshly built binary takes about a second on macOS,
which is the system verifying it, not the program starting: run it twice.

Require `dev.arkaitz.web-base.native` from your main namespace — like the
Integrant one, requiring it is the opt-in — put
`com.github.clj-easy/graal-build-time` on the classpath, AOT-compile your main
namespace, and then:

```
native-image -cp $(clojure -Spath) \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time=org.slf4j \
  -H:+UnlockExperimentalVMOptions \
  -H:IncludeResourceBundles=jakarta.servlet.LocalStrings \
  -H:IncludeResources='dev/arkaitz/web_base/public/[^/]+\.[a-z]+$' \
  -H:IncludeResources='public/.*[^/]\.[a-z]+$' \
  -o target/app my.app
```

Why each one, because a flag nobody can explain is a flag nobody can remove:

- `graal-build-time` registers Clojure's namespaces for build-time
  initialisation, which every Clojure image needs.
- slf4j's logger factory reaches the image heap through `tools.logging`.
- Jetty's servlet layer reads `jakarta.servlet.LocalStrings` when it writes a
  response; without the bundle every request answers `500`.
- Without the resources the base's own CSS and htmx are not in the binary. The
  second pattern is your own `:static` root.

**Match files, not directories**, as those patterns do. A directory registered
as a resource is served as a listing of its names: measured, `GET /css` on a
host root containing `css/site.css` answered `200` with `site.css` where the
jar answers `404`. The library refuses the spellings it can recognise — `/wb/`
and `/css/` — and an include pattern that ends in an extension closes the rest.

These are GraalVM's flags, not the library's, and they move between releases;
this set was observed working with GraalVM CE 25. Anything else your
application brings — a database driver, a JSON library — is yours to
configure, and you do not have to guess: run it once on a JVM under the
tracing agent and it writes the configuration by watching.

```
java -agentlib:native-image-agent=config-output-dir=native-config -cp $(clojure -Spath) my.app
```

Exercise the application, stop it, and pass that directory to `native-image`
with `-H:ConfigurationFileDirectories=native-config`.

On macOS a Homebrew GraalVM is deliberately not linked, so the `java` on your
PATH stays the one you had. Use it one command at a time with
`JAVA_HOME=/opt/homebrew/opt/graalvm`, or call `native-image` by its full path.

### Integrant

```clojure
{:my/web-config {…}                                       ; your component building the map above
 :dev.arkaitz.web-base/handler #ig/ref :my/web-config
 :dev.arkaitz.web-base/server  {:handler #ig/ref :dev.arkaitz.web-base/handler :port 3000}}
```

`dev.arkaitz.web-base.integrant/read-string` reads `#ig/ref` and `#wb/env "VAR"`
in the same EDN; requiring that namespace is what adds the methods. Its
two-argument form takes readers of your own, which is what the next section
uses.

A host's whole `-main` can be `run!`: it reads the resource (with the readers of the
env file you name, and no other), puts a port given on the command line at the path you
name, starts the system, prints your banner, halts on shutdown and blocks.

```clojure
(defn -main [& args]
  (wbi/run! {:config    "config.edn"
             :env-file  "env.local.edn"
             :port-path [:dev.arkaitz.web-base/server :port]
             :banner    #(str "serving on " (get-in % [:dev.arkaitz.web-base/server :port]))}
            args))
```

`:port-path` is where in the configuration the port given on the command line goes —
here straight into the server's own map, beside the `:port` it replaces. A key of its own
(`:my/port`, referred to with `#ig/ref`) is needed only when two components read the
port, and it then needs an `init-key` that returns its value.

A bad port, a missing or malformed resource, a variable nobody set, a system that fails
to start or a banner that throws is one line on stderr and exit status 1 — the failing
key's own message, never the configuration: Integrant's own failure carries the resolved
configuration of the key that threw, password included. Whatever had started is halted
rather than leaked, and SIGTERM halts the running system; a halt that fails then is
logged at ERROR through tools.logging rather than printed by the JVM, configuration and
all.

`wbi/init` is that start without the rest, for a host's tests or a `-main` of its own:
`(wbi/init config)` or `(wbi/init config keys)` answers the system, and a key that throws
halts what had started and throws an `ex-info` whose message is that key's own and
whose data is `{:key <the Integrant key that threw> :config-key <the path its own
refusal named>}` — `:config-key` only when the key's exception carries one, as web-base's
and db-base's refusals do. Nothing else of either exception's data is copied: Integrant's
carries the key's resolved configuration, and the key's own may carry what it refused.

### Logging, for a library that plugs in

The base logs through `clojure.tools.logging` and ships no backend; the host chooses
one. For the length of a request the SLF4J MDC holds `request-id`, the id the response
carries in `X-Request-Id`, so `%X{request-id}` in the host's pattern puts it on every
line written on that request's thread. A library that plugs into a host of the base —
auth-base does — logs through the same facade and gets the id for nothing, without
depending on the base.

Every request gets one access line — method, path, status, milliseconds — and never its
query string. A path that carries a secret, a sign-in token above all, is logged by its
route template instead when the route says so in its data:

```clojure
["/login/redeem/:token" {:wb/log-path :template :get redeem}]   ; GET /login/redeem/:token 303 4ms
```

The decision is taken against the router before anything else runs, so it holds when a
session store throws first, and every error line of the base shows the same path. It
belongs on the route's own data, where it covers every method; under `:get` it would hide
nothing, and is refused, as is any value but `:template`.

A parent's mark reaches every route under it, and a child cannot take it back — reitit
merges a `nil` as no value — so a group marked for a secret stays hidden whole.

It hides the path only where the route matches. A request that misses it — a trailing
slash, a segment a mail client or link scanner appended — is a 404 logged as it came, and
a `:sessionless` path is one no route may match, so a secret there cannot be hidden this
way. Nor can the base keep a secret out of an exception the host throws: a logged
`ex-info` prints its data, so an `ex-info` carrying the request or its `:path-params`
logs the token with it.

### Configuration, and not exporting secrets on every start

`#wb/env "VAR"` resolves while the config is read, and has no default form:
a default is how a development key reaches production. On a development
machine, name the variables in an EDN file instead and hand the readers in:

```clojure
;; env.local.edn — never committed
{"WB_SESSION_KEY" "…base64 of 16 bytes…"}

(wbi/read-string (config/env-file-readers "env.local.edn")   ; or config/read-string, read-resource, read-file
                 (slurp (io/resource "config.edn")))
```

Both sides of the map are strings, and anything else is refused when the
readers are built, naming the file. Then:

- **The base never looks for that file.** It reads the path you pass and
  nothing else; an absent file is not an error, it is the production case, and
  yields the plain `readers`.
- **The environment always wins**, including a variable exported empty — an
  operator who exported it named it. An empty session key then fails at
  startup asking for sixteen bytes, which names the key but not the variable.
- **A value that comes from the file is logged at warn** by
  `dev.arkaitz.web-base.config`, naming the variable and the file's absolute
  path, so a file left next to a deployment is visible and findable. Silencing
  that logger removes the only signal there is.
- A `#wb/env` inside the file still means the environment.

Keep the file out of the repository. `*.local.edn` in `.gitignore` is the
pattern this project uses, and the demo's test asks git itself rather than
trusting the pattern.

## The demo

`demo/` is the acceptance test: a small application using nothing but web-base —
tasks, live search, a validated signup, a login of its own, a gated page, a
deliberate error, two languages, a strict CSP.

```
WB_SESSION_KEY=<base64 of 16 bytes> clojure -M:demo [port]
clojure -M:dev     # REPL: (go) (reset) (halt)
```

Or write the key once into `env.local.edn` in the directory you start from,
and drop the variable:

```
echo '{"WB_SESSION_KEY" "<base64 of 16 bytes>"}' > env.local.edn
clojure -M:demo [port]
clojure -T:build demo-uber && java -jar target/web-base-demo-0.8.0.jar [port]
```

## Development

```
clojure -M:test                 # the whole suite, demo included
clojure -T:build jar            # target/web-base-0.8.0.jar
clojure -T:build install        # into ~/.m2
CLOJARS_USERNAME=… CLOJARS_PASSWORD=<deploy token> clojure -T:build deploy
```

Every test was written against a contract and watched go red by named mutations;
the security-adjacent ones went through an adversarial review panel. The rules
are in `CLAUDE.md`.

## License

MIT. See `LICENSE`.
