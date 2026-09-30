# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking**, says what a host must change, and the README says "since" beside
the behaviour. Every release is on Clojars as `dev.arkaitz/web-base` and tagged `vX.Y.Z`.

## 0.13.0 — 2026-09-30

- **Breaking:** with a gate on any route, `:login-path` must be a page of the router — a
  route that answers GET there and is not gated itself — or `handler` refuses to build,
  naming `[:login-path]`. It used to send every refused visitor to a 404, or round a
  loop, and say nothing until somebody signed out. A host `:login-path` that wins over a
  plugin's while the plugin's login page stays where it mounted it is the case that
  asked for it. Its query, if it has one, is ignored for the match.

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
