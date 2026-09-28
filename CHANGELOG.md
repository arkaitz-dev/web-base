# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking**, says what a host must change, and the README says "since" beside
the behaviour. Every release is on Clojars as `dev.arkaitz/web-base` and tagged `vX.Y.Z`.

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
