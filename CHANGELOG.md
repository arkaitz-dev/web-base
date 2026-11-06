# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking**, says what a host must change, and the README says "since" beside
the behaviour. Every release is on Clojars as `dev.arkaitz/web-base` and tagged `vX.Y.Z`.

## 0.23.0 — 2026-11-06

The declared-length 413 reaches the host's page.

- **Breaking:** a body whose declared length is past its route's bound is answered by
  your `:error-layout` — the 413 datum with `:wb/limit :body`, inside the session,
  subject and negotiated language, a fragment for an htmx swap and text where text is
  asked for — instead of the base's bare page. A browser uploading a file past
  `:max-body-bytes` now sees your translated "the whole body is too large" page. The
  body is still never read: the limit puts an empty one in its place, and the multipart
  parse, CSRF and the route never run for it. A layout that reads `:wb/limit` already
  words it; one that throws turns this 413 into the base's 500, as it does for every
  other page it draws. A refused request now reads its session and asks `:subject-fn`
  once, and a session due under `:renew` is renewed by it.
- Unchanged: a `:sessionless` path is answered at once by its own `:render-error` or the
  base's page, and so is an asset its handler serves; a body found past its bound only as
  it is read — sent in chunks, or with a false length — is still the base's page.
- `security/wrap-body-limit` takes a `refuse-here?` predicate before its renderer, and
  `security/wrap-declared-over` answers what it marks; `handler` wires both.

## 0.22.0 — 2026-11-06

The academy's friction (A2–A35), decided by a panel and chosen by the user.

- **Breaking:** `:wb/multipart :max-file-count` is gone, refused at construction as an
  unknown key. It counted every part, the CSRF field included. `:max-files` counts the
  parts that carry a file name and refuses the one past it before anything of it is
  written; the empty part of a file input left empty is written nowhere. A host renames
  the key and drops the fields it was adding to the count: `{:max-file-count 2}` for
  one file and its form is `{:max-files 1}`. A form that used to reach its own count
  check with one file too many now gets the 413.
- **Breaking:** every 413 the base produces names the bound under `:wb/limit` —
  `:file-size`, `:files` or `:body`, for the error layout to say which to fix. A body
  the parser cannot read, or a text field past `:max-file-size`, is a 413 naming none;
  a body whose declared length is past the bound is refused before any layout runs.
- **Breaking:** `response/attachment`'s ASCII fallback turns `/`, `\` and `:` into `_`
  too; `filename*` still carries the name whole. `csv/attachment` goes through it.
- **Breaking (tests):** `testing/visit` writes a multipart part's name and file name as
  a browser does, `"`, CR and LF as `%22`, `%0D` and `%0A`; a host test that uploaded
  such a name now sees the browser's spelling. Its refusals name `{:fetch? true}`
  beside `{:htmx? true}`.
- `:wb/guard (fn [request] truthy)`: asked after the gate and after coercion, its no is
  the 404 of a path no route matches, for a stranger signed in or not. Composed parent,
  route, method, as gates are; refused when not callable, under a method of a route
  with children, or dropped by `:middleware ^:replace`. `testing/route-checks` reads
  `{[path method] {:gates … :guards …}}`.
- `wb/upload`, the one file of a field or nil; `wb/params`, a field's values as a
  vector of strings, read body-first like `param`.
- `security/csrf-meta`: `<meta name="csrf-token" content=… data-header=…>` for a
  page's script, nil without a stored token. The shell's `:scripts` slot and route
  `:wb/scripts` load local scripts under the CSP nonce; another origin's is refused.
- `testing/visit {:fetch? true}`: the token as the CSRF header, no form field, no htmx
  headers.
- `response/attachment` takes `{:content-type t :inline? b}`; `:inline?` only for
  `application/pdf`, `image/png`, `image/jpeg`, `image/gif` and `image/webp`, spelt
  exactly — never SVG or HTML. A refused stream is closed.
- Docs: the coercion coordinate and its cost, and malli's upper-case `:uuid`; an
  `:assets` prefix serves everything under it; `unprocessable`'s three callers; the
  keyset predicate and its three-page test; who marks a download `no-store`; the CSV
  guard's price on identifiers; `:frame-options` beside `frame-ancestors`.

## 0.21.0 — 2026-11-06

Three items left open, decided by a panel and chosen by the user.

- **Breaking:** CSRF has two checks. A token is born only when the session rotates, and
  a session holding one needs it, as before. A session holding none — every anonymous
  visitor — passes a POST, PUT, PATCH or DELETE only when `Sec-Fetch-Site` is
  `same-origin` or `none`, or, without that header, when `Origin` is this scheme and
  `Host`; `same-site`, `cross-site` and a request with neither are the 403. An anonymous
  page with forms now renders no token, writes no session row and is not marked
  `no-store` — a crawler of the shop's catalogue wrote one row per request. A host must:
  set `:proxy-hops` behind a TLS proxy and pass the browser's `Host` through, or Safari
  before 16.4 is refused; expect `csrf-field` and `csrf-token` to answer nil for an
  anonymous request, a hand-built `{:anti-forgery-token "T"}` included; sign in before
  reading a token in its tests. `testing/visit` sends `Sec-Fetch-Site: same-origin` and
  `Origin: http://localhost` on every unsafe method and no longer throws without a token.
- `paging/keyset`: a list paged from a cursor, for one that changes under the reader —
  a status-filtered queue skipped a row under numbered pages when the row ahead left the
  filter. Strict cursors, the host's own `decode` and `fetch`, a 303 to the first page
  when the anchor is gone; no SQL and no markup.
- An asset dated at or before 1980-01-02 carries no `Last-Modified` and so is never a
  304: that date is the constant a build that clamps file times writes, under which
  changed bytes would have read as unchanged.

## 0.20.0 — 2026-11-06

The items 0.19.0 recorded, decided by a panel.

- **Breaking:** on a method other than GET, HEAD or OPTIONS, `wb/param` reads the body
  over the query. Ring's `:params` let the query win on a urlencoded form and the body
  on a multipart one, so adding a file input to a form changed which value a handler
  saw. The query still fills in what the body lacks — a DELETE's fields, a POST's own
  `?page=2`. A host that posted a field in both places and read the query's now reads
  the body's; none of ours does. Ring's raw `:params` keeps its own order.
- Fixed: an asset answered 304 to an If-Modified-Since later than its own date, so
  redeploying the previous image — older files, older dates — left every returning
  browser on the newer ones. A 304 now needs the very date the file carries, as nginx's
  `if_modified_since exact`; any other date is the file, which costs one download, never
  the wrong one. Different bytes under an identical date — a build that clamps file
  times — still read as unchanged.
- Measured, nothing to change: a GraalVM native image gives its assets the build's file
  times, so they revalidate as a jar's do.

## 0.19.0 — 2026-11-05

From the two candidates the shop round left open, decided by a panel.

- Fixed: a POST carrying the CSRF field twice answered 500 — Ring reads a repeated field
  as a vector, and ring-anti-forgery's comparison throws on one — so any anonymous POST
  wrote a stack trace to the error log. It is the 403 refusal now, urlencoded and
  multipart alike, and a field sent twice is never passed over for a header.
- `wb/param` takes a value for an absent field: `(wb/param request k not-found)` answers it
  only when the field was not sent, and nil, as before, when it was sent twice or is a
  file. A host tells "not sent" from "sent wrong" and picks the status itself; the
  two-argument form is unchanged.
- Every asset — `/wb/`, each `:assets` root, the host's `:static` — answers
  `Cache-Control: no-cache` unless its handler set one, the `304` included. A browser
  kept the previous deploy's stylesheet for a tenth of its age; now it asks on every
  load and an unchanged file costs a `304`. Ring answers `304` to an If-Modified-Since
  later than the file, so a rollback to an older file is not seen without a reload.

## 0.18.0 — 2026-11-05

From what building `shop`, a fifth application, cost (its `FRICTION.md`).

- **Breaking:** the shell reads the CSRF token, and so writes it into the session, only
  for a page whose rendered body carries an htmx attribute (`hx-…`, `data-hx-…`) or whose
  content already read the token for a form (S5). A public page with neither, crawled,
  leaves no session behind; until now every page the shell drew wrote one. A page that
  calls htmx only from a script, with no form and no attribute, reads
  `security/csrf-token` itself, into a `meta` of `:head`.
- A response to a request that arrived with a session is marked `no-store`, as one to a
  subject or one that writes the session already was. The shell's read had been doing it
  for every page; an anonymous visitor's page painted from the session — a cart in the
  header — keeps it. A request with no session, a crawler's, stays cacheable, and a
  shared cache in front of the host keys HTML on `Cookie`.
- `dev.arkaitz.web-base.csv`: `write` and `attachment`, a CSV export a spreadsheet
  cannot run — UTF-8 with a byte order mark, every field quoted, CRLF, and a field
  beginning with one of OWASP's CSV-injection characters (the full-width forms and the
  line feed included) prefixed with an apostrophe.
- `dev.arkaitz.web-base.paging`: `paged` and `path`, a list in numbered pages. A
  well-formed `page` past the last answers a 303 to the last page with the rest of the
  query kept, where the shop answered a 404 — its staff landed on that 404 after moving
  the last order of a filtered page, since htmx reloads the page it is on. Junk, zero, a
  leading zero or a repeated `page` is a 404. No SQL and no markup.
- Fixed: content built lazily (a `for` in the body) that read the CSRF token of a
  response rotating the session read it only as the page was rendered, after the check,
  so the form carrying the pre-rotation token was refused with nothing logged. It is now
  logged by name, as an eager read was.

## 0.17.0 — 2026-10-09

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
- A `:sessionless` value may be `{:handler h :render-error f}`: `f` renders the errors the
  base decides for that path — a throw, a nil answer, the body limit's 413 — so an API
  under `/api/` answers them in its own format, not the base's page (H19). A renderer
  that throws or answers nil is logged and falls back to the base's page.
- `wb/uploads` answers a multipart field's files, one or several alike — the empty part
  of an empty file input left out, the name its last segment — and
  `response/attachment` answers a download under a name, RFC 6266's `filename*` for
  anything past ASCII (H22). `:max-file-count` keeps its meaning — every part — now said
  plainly in the README.
- `testing/call` sends one request as a program does — no jar, no CSRF token, a raw body
  and query string — for an API under `:sessionless`, which `visit` cannot drive;
  `visit {:multipart? true}` sends a form with no file as multipart; `testing/header`
  reads one header's value (H16, H8). The README's API section gathers the protocol an
  API host otherwise re-finds.
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
