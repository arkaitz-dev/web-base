# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking**, says what a host must change, and the README says "since" beside
the behaviour. Every release is on Clojars as `dev.arkaitz/web-base` and tagged `vX.Y.Z`.

## 0.17.0 — unreleased

From what building `helpdesk`, a fourth application, cost (its `FRICTION.md`).

- **Breaking:** the subject is computed just inside the session, where it was innermost:
  a `:locale-fn` reads `(:wb/subject request)` — a person's chosen language without a
  second read of the session — and the page of a refused CSRF token or upload knows who
  asked (H1). The subject function therefore sees the session, the headers, the request
  id and the nonce, and no longer `:params`, `:query-params`, `:form-params`,
  `:multipart-params`, `:wb/tr`, `:wb/locale` or the CSRF token. A subject read from
  a parameter is no subject now; none of the set's consumers read one. It is still
  called once per request, and never for a sessionless route.
- `wb/path-for` builds a route's path from its `:name`, from a request the router
  answered, the handler or a router; an unknown name, or a missing or empty path
  parameter, is refused naming the route. The handler carries its router in its
  metadata, so `reitit.ring/get-router` on it is true at last — the README's recipe,
  `get-router` on a request, answered nil (H20).
- `wb/router` builds the router `handler` answers with, for a path built where neither a
  request nor the handler is at hand: an API mounted under `:sessionless`.
- **Breaking (a log line):** a request that matches no route but starts with the static
  part of a route marked `:wb/log-path :template` — a trailing slash, an appended segment,
  a truncated link — logs that part and `…` (`GET /login/redeem/… 404`), where it logged
  the secret whole (H9). auth-base's own redeem and attach routes are covered without a
  word from the host. A marked `/:token` makes every unmatched request log as `/…`; a
  prefix in another case or with an encoded slash still logs as it came.
- `wb/param` reads a field as one string or nil — absent, repeated (Ring's vector) or a
  file — never trimmed or coerced (H23).

## 0.16.0 — 2026-10-08

From what building `booking`, a third application, cost (its `FRICTION.md`).

- **Breaking:** a child route's `:wb/gate` composes with its parent's — both must admit,
  the parent asked first — where it replaced it: one line under a members-only group
  opened it to anybody signed in. A child that meant to widen a gated group no longer
  can; move such a page out of the group. A composed gate is a new function, listing its
  parts under `:wb/gates` in its metadata, so a test comparing gates by identity now
  names them: `testing/router` compiles routes as the base does, for such a test.
- **Breaking:** a `:wb/gate` under a method of a route that has routes under it is
  refused at construction — a child's method data or plain handler replaced it there —
  and so is a gated route whose `:middleware ^:replace […]` dropped the gate. A gate
  that is not callable is refused whether it is a parent's or a child's.
- A word the dictionary lacks still answers nil, and is now logged at WARN once per id.
- `wb/translator`: the words of the site outside a request, for the mail a job sends.
- `testing/header-values` and `testing/location` are public.
- The README says where a credential of a sessionless endpoint goes (the query string or
  a header, never the path, which the access line prints), how a notice survives a
  redirect without a session, how to link to a named route, and that a sessionless
  handler gets the raw request.
- `meta-merge/meta-merge` is declared: the base now requires it by name.

**In your app:** a `routes_test` that compiles routes with `ring/router` to read their
gates uses `testing/router` instead, and reads a composed gate's parts from
`(:wb/gates (meta gate))`.

## 0.15.0 — 2026-10-06

- `:session :renew {:every-ms :absolute-ms}`: a used session is written again, and its
  cookie sent again, once per `every-ms`, until `absolute-ms` after it was born. A page
  within the window writes nothing, a request with no session writes nothing, and a
  response that sets `:session` itself is never replaced — a write of the same session
  counts as the renewal and sends the cookie too. `every-ms` must be shorter than
  `:cookie-attrs :max-age`, and unknown keys under `:renew` are refused naming their
  path, like everywhere else the base owns.

## 0.14.0 — 2026-10-02

- The jar carries its native-image metadata, registering its CSS and htmx — the files,
  never their directory, which an image would serve as a listing. A host's image needs
  no `-H:IncludeResources` for `dev/arkaitz/web_base/public`; remove that line, and the
  traced entries naming it, from your configuration.
- A plugin's dictionary refused for want of `:i18n :default-locale` now says the line to
  paste, with a locale the plugins bring.

## 0.13.0 — 2026-09-30

- **Breaking:** with a gate on any route, `:login-path` must be a page of the router — a
  route that answers GET there and is not gated itself — or `handler` refuses to build,
  naming `[:login-path]`. It used to send every refused visitor to a 404, or round a
  loop, and say nothing until somebody signed out. A host `:login-path` that wins over a
  plugin's while the plugin's login page stays where it mounted it is the case that
  asked for it. A gate on a route or on one of its methods counts; the login path's query
  or fragment is ignored for the match, and a login page on another origin — a single
  sign-on's — is not checked.

## 0.12.0 — 2026-09-29

- **Breaking:** `:i18n :locales` lists the languages the site speaks; without it, the
  site speaks its `:default-locale` alone. It was the dictionary's keys, so a plugin's
  English and Spanish dictionary made an English-only site answer a Spanish browser with
  a Spanish login and `<html lang="es">` over every page. A plugin's dictionary is now
  taken only in the listed languages, and a host `:dict` entry in any other is refused
  at construction — a bilingual host adds `:locales [:en :es]`. A listed language with
  no strings still names the request (`:wb/locale`, `<html lang>`), its strings falling
  back to the default's.

## 0.11.0 — 2026-09-29

- **Breaking:** `:assets`, `:stylesheets` and `:plugins` are the base's names now. A host
  that kept its own values under them is refused at construction; rename them.
- `:plugins [...]`: values a library prepares from the keys a host writes — `:routes`,
  `:sessionless`, `:i18n {:dict …}`, one asset root, `:stylesheets`, and at most one
  `:subject-fn`, `:login-path`, `:session` — merged by `wb/expand` into the plain config,
  every collision refused by name, the host's own keys winning. Nothing is discovered: a
  plugin is data the host hands over, one line each, and the base calls its functions
  only where it already called the host's.
- `:assets [{:path "/name/" :root "classpath/prefix"}]`: resource roots served beside
  `/wb/`, before the session; `:stylesheets [...]`: linked by the shell after `wb.css`,
  plugins' first and the host's last, on every page and error page drawn with the shell. A host links its
  own CSS with `:stylesheets ["/app.css"]` instead of through `:head`.

## 0.10.1 — 2026-09-29

- A native image of 0.10.0 died at boot: the rate floor reached each connector's
  factories by reflection. Every call in the base now resolves at compile time, and a
  test says so.

## 0.10.0 — 2026-09-29

- **Breaking:** `:security :proxy?` is refused, naming its replacement. It took the first
  `X-Forwarded-For` entry, which the client writes: behind nginx, AWS, Heroku or Fly,
  which append, anyone chose their own rate-limit bucket (measured: 12 sign-ins, no 429).
  `:proxy-hops n` takes the entry `n` from the right; brackets and a port are removed.
- **Breaking:** a request body larger than `:max-body-bytes` (200 000 by default) is a
  413, whether it declares its length or not. Jetty's form limit never applied, since
  Ring reads the body; four 1 GB POSTs filled a 4 GB heap.
- **Breaking:** the gate sends a refused navigation to `login-path?next=<page>`;
  `testing/gate-refusal?` accepts it.
- **Breaking:** a response to a request with a subject, one that writes the session, or
  one whose page read the CSRF token carries `Cache-Control: no-store` unless the
  handler said how it may be cached.
- **Breaking:** idle connections close after 30 s (Ring's default was 200 s) and a body
  slower than 500 bytes a second is cut with a 408; responses no longer name Jetty's
  version.
- **Breaking:** a `:sessionless` path ending in `/` is a prefix answering its whole
  subtree, where it was one exact path; `/` itself, and a prefix covering one of the
  routes, are refused at construction.
- **Breaking:** `:hsts :max-age` past a billion is refused as milliseconds: it is seconds.
- A client that hangs up mid-body, or is cut for its rate, is one INFO line, never an
  ERROR with a stack. Only a failure read through the request body counts: the same
  Jetty exception from anything else is still an error.
- Route data `:wb/multipart {:max-file-size n …}` parses a file upload for that route
  alone, before CSRF, into temporary files deleted when the request ends;
  `testing/visit` takes `{:files …}` on a POST, PUT or PATCH.
- `wb/redirect-for`, the gate's redirect for a host's own detour; `response/health`, a
  probe that answers `ok` or 503 and nothing else.

## 0.9.0 — 2026-09-29

- **Breaking:** every request runs on a virtual thread of its own. With platform
  threads, once as many connections waited on a database pool as there were threads —
  50 by default — a new connection was not answered until the load dropped, and a
  balancer's health probe timed out on an instance that was merely busy (measured: 4
  to 27 s at 48 to 200 connections; on virtual threads, milliseconds at 1000). An
  overload now arrives as the pool's own timeout, a logged 500 and a 503 from a health
  check, instead of silence. `:virtual-threads? false` restores the platform pool; a
  host `:thread-pool` is used as given. Needs JDK 21 or later. Measured after the
  release in GraalVM native images, macOS and static musl, through this default path.
- `testing/visit` takes `{:headers {…}}`, sent on every request of the visit, redirects
  included; a header `visit` writes itself is refused by name.
- Route data `:wb/log-path :template` logs a route by its template
  (`/login/redeem/:token`) instead of its path — in the access line and in every error
  line of the base, even when the session store throws before the router runs. Any other
  value, or the key under one method, is refused at construction.
- README: what a layout sees of the request, per-account facts through a composed
  `:subject-fn`, a page and its fragment on one route, and why an error layout draws no
  form.

## 0.8.0 — 2026-09-28

- **Breaking:** inside the maps the base owns — `:session`, `:security` and its `:hsts`,
  `:i18n`, `:static` — an unknown key is refused at construction, naming its path. A
  misspelt key used to be ignored in silence.
- `refuse-form` and `form-done`, for a form that works with and without JavaScript.
- `testing/visit` sends PUT, PATCH and DELETE with the POST's CSRF rules, puts params
  where htmx does, and refuses what no browser form can send.
- `wbi/init`'s failure names the Integrant key that threw and its `:config-key`.
- Case conversions use `Locale/ROOT`: under a Turkish default locale `Allow` said
  `OPTİONS`.
- Every refusal's message starts with `web-base:`.

## 0.7.0 — 2026-09-28

- `stop` drains in-flight requests for up to `:stop-timeout-ms` (10 s by default).
- `wbi/init` is public; a halt that fails on shutdown is logged.
- `testing/visit` with `{:follow? false}`, and `testing/gate-refusal?`.

## 0.6.0 — 2026-09-27

- An error boundary outside the session: a session store or subject function that
  throws is the base's 500 page, never a raw exception at the adapter.

## 0.5.0 — 2026-09-26

- `wbi/run!`, a host's whole `-main`.
- The CSRF token never crosses a session rotation.

## 0.4.0 — 2026-09-26

- `:sessionless` routes, answered before the session — a health probe that carries a
  cookie answers while the session store is down.
- A CSRF token reaches the session only when the request used it, so a probe, JSON or a
  redirect writes no session row.

## 0.3.0 — 2026-09-25

- `testing/browser` and `testing/visit`, a browser for a host's suite.
- `rerender` and `response/unprocessable`, a form's round trip.

## 0.2.0 — 2026-09-09

- Compiles to a GraalVM native image.

## 0.1.0 — 2026-09-08

- First release.
