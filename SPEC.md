# web-base — specification

> **Coordinates.** Directory `arkaitz-dev-web-base` · repository `arkaitz-dev/web-base`
> · artifact `dev.arkaitz/web-base`.
>
> **Status.** Specification, briefly. This is a small piece and does not warrant the
> treatment the first consumer's domain received; it moves to implementation soon.
>
> **Provenance.** Extracted from the decision log of
> `the first consumer's own specification` (entries of 2026-09-07 and 2026-09-08 on modularity and on the
> web base). the first consumer is the first consumer, **not the owner**.

## 1 · What it is

A **super-micro-framework**: the operational web foundation a project plugs in so it
does not have to reassemble the same plumbing every time. the first consumer consumes it exactly
as it consumes any other module, and another project should be able to reuse it
**as-is**.

Most of what it needs already exists in the Clojure ecosystem as **libraries**. What
is genuinely ours is therefore **a wiring convention plus a page shell** — much less
than "framework" suggests, which is what allows it to be micro.

## 2 · What it is not

- **Not a framework that calls you.** See §3.
- **Not an application.** It has no screens of its own beyond the shell and its own
  harness.
- **It knows no domain.** Not the first consumer's, not anyone's. No customer, no customer, no
  invoice, no booking.
- **It does not authenticate.** See §5.
- **It does not choose your persistence.** It never opens a database.

## 3 · The rule that governs everything here

> **A library you call. Never a framework that calls you.**

What made Django's `contrib.auth` impossible to lift was not that it carried screens.
It was **inversion of control**: the framework owned the lifecycle and you hooked into
it, so nothing could leave without bringing the framework along.

Applied here, in one direction only:

- **The host plugs in web-base.** The host decides, wires and calls. ✔
- **Modules plug into web-base** — a plugin registry, auto-discovery, a hook system. ✘
  The moment the base *calls* a module, that module must know the base in order to be
  called, and it stops being liftable.

So the host writes its wiring **explicitly**. Verbose and dull, and that is exactly
what keeps every other module free.

**Corollary — no global mutable state.** No ambient `settings` module that code reads
from wherever it happens to run. Configuration is **passed in**, so a component is a
function of its inputs and not of the environment it woke up in. This is the second
half of the Django lesson.

## 4 · The membership test

Before adding anything, ask:

> **Would a completely unrelated project — a bicycle rental, a clinic's appointment
> book — need this, unchanged?**

If no, it does not belong in web-base. The test is deliberately blunt, because the way
this kind of library dies is by absorbing one convenient thing at a time until only
its first consumer can use it.

## 5 · The authentication seam

**This is where the boundary will erode, so it is stated before anything is written.**

The shell needs to know whether there is a session and who the subject is — to paint
the top bar and to gate private pages. The temptation will be to put login inside the
base, which is literally what Django's middleware does.

**The line:**

- web-base **knows there is a session, and that it carries a subject**.
- web-base **does not know how that subject came to be authenticated**, nor what a
  subject *is* beyond an opaque value.
- It receives **a function** from the host: given a request, return the subject or
  nothing.

That keeps the separate `auth` module replaceable, and keeps web-base usable by a
project that authenticates by a means we have not imagined.

**The same shape applies to authorisation**, and more strongly: whether a subject may
see a page is **never** web-base's judgement. It receives a predicate and honours the
answer.

## 6 · Scope

**In:**

- HTTP server and routing.
- Session handling — the mechanism, not the identity.
- Configuration loading, passed explicitly to components.
- Component **lifecycle**: start, stop, and the dependency order between them.
- A **uniform error shape**, so every consumer fails the same way and the shell can
  render it.
- Static asset serving.
- Logging with a **request id** threaded through, so one request can be followed.
- The **page shell**: layout, navigation, the gate on private pages.

**Out:** authentication, authorisation, persistence, background jobs, email, file
storage, and every domain concept of every consumer.

*Anything ambiguous is left out until a second consumer asks for it — see §7.*

## 7 · Build it thin

Built before there are two consumers, it will be built to fit the first — and a base
that only fits the first consumer is not a base, it is a part of the first consumer that we have to maintain
separately, which is worse than not extracting it.

**So: start minimal, and let the *second* project be what pulls things in.** When in
doubt, leave it in the consumer. Moving code *into* a library later is cheap; getting
it back *out* is not.

## 8 · Its own harness

web-base ships a **tiny demo application** that uses nothing but web-base: a couple of
routes, one public page, one gated page, a deliberate error. It is not a showcase — it
is the acceptance test. **If the demo needs anything that is not in web-base, the
seam is in the wrong place.**

## 9 · Settled 2026-09-08 — HTMX and Hiccup, extracted from an existing prototype

**The largest open question — server-rendered HTML, a JSON API, or both — is closed:
server-rendered HTML with HTMX, and Hiccup for the markup.**

This dissolves the fork rather than straddling it. There is one face, not two to keep
in step, and with Hiccup the "one answer, two renderings" discipline is not a rule to
impose but the natural shape: **the view returns data, and the handler decides how to
emit it**. The cost, stated so it is not discovered later: **the day a real API is
needed** — a native app, a third party integrating — **it still has to be built.** The
approach solves the web and leaves that untouched.

### Datastar was considered and set aside — deliberately

**[Datastar](https://github.com/starfederation/datastar)** (starfederation) unifies in
~14 KiB what htmx ends up needing htmx **plus** Alpine for: server interaction and
client-side reactivity in one piece. The deeper difference is that it uses **SSE**
rather than AJAX, so real-time push comes free. It remains **pre-release** (v1.0.0-RC.8
when checked, 2026-09-08). In Clojure there is an official SDK and, more interestingly,
**[Hyperlith](https://github.com/andersmurphy/hyperlith)** (Anders Murphy) — a
hypermedia monolith built on Datastar with Hiccup and server-held state, which is very
nearly our own problem solved by someone else.

**Why htmx nonetheless:**

- **Our need for real-time is close to nil.** The only candidate is a meeting in progress
  with attendance and votes updating live — and it is already established that the
  ordinary case is a show of hands with the result assigned directly, not a live count.
  Billing, contabilidad, archivos and notifications need no push at all. SSE would be
  paid for in long-lived connections, threads, proxies, timeouts and horizontal scaling,
  against almost no benefit here.
- **The plan is an extraction, not a rewrite.** `../prueba` works, with htmx. Datastar
  would turn the extraction into a rewrite of the whole view layer, and **all three of
  the base's htmx-specific behaviours would change** — `HX-Request` for fragment vs
  page, `HX-Redirect` on the gate, and error rendering — since Datastar answers with SSE
  events rather than HTML fragments.

**And what makes the choice reversible:** that coupling is **small and localised** —
one header check, one redirect header, one error renderer. **Three places.** So this is
not a one-way door, provided `HX-` strings are not scattered through the codebase.

**Recorded as an instruction, not as speculative abstraction:** keep those three points
**named and isolated**. We are not building a layer to support both; we are simply not
spreading the dependency around.

**A note on method:** Hyperlith deserves reading, but **afterwards** — as with
an earlier project. Our own implementation first, then it as a second opinion. Read before, we
would adopt its decisions without having understood our own.

### The stack, taken from `../prueba`

| | |
|---|---|
| lifecycle | **integrant** — `config.edn` with `#ig/ref`, `ig/init` / `ig/halt!` |
| routing | **reitit** — with malli coercion and its exception middleware |
| schemas | **malli** |
| markup | **hiccup 2** |
| server | **ring-jetty-adapter** |
| build | **tools.build**, `integrant/repl` under a `:dev` alias |

### Not a fresh start: an extraction

`../prueba` is not a sketch. It is **a working prototype of most of §6**: lifecycle
with its shutdown hook, EDN config with a CLI port override, routing with coercion, an
exception middleware, static assets via `create-resource-handler`, and a page shell in
`views/layout`. Handlers take their dependencies explicitly (`(partial handler store)`)
— no ambient state, which is §3's corollary already honoured.

It also already states the discipline, in a docstring: *"Every function returns plain
Hiccup data; turning it into a string is the responsibility of the handlers."*

**So the honest path is to extract and generalise `prueba`, not to start from zero.**
And §8 comes free: strip the base out and what remains — store, todos, search, signup —
**is** the demo application the spec asks for.

### What web-base must own that the prototype does not yet

1. **Fragment or full page, decided from the `HX-Request` header — not by the
   handler.** *(Refined in §12: with nested layouts the question is not binary but
   "from what height of the layout stack".)* Left to the handler it produces duplicate routes: one for direct
   navigation, another for the htmx swap. Decided by the base, one handler serves both:
   it returns Hiccup, and the base wraps it in the shell **only when the request did not
   come from htmx**. That is what a page shell is for.

2. **The gate must speak HTMX.** Answer an htmx request with a `302` and **htmx follows
   the redirect**, planting the login page inside the target `div` — a form embedded in
   half a screen. The fix is the **`HX-Redirect`** response header. Exactly the kind of
   detail that belongs in the base rather than being rediscovered by every consumer.

   This extends §5: the base receives a function saying whether there is a subject, and
   **translates a refusal into the right outcome for the kind of request** — an ordinary
   redirect, an `HX-Redirect`, or a `401` if an API ever exists.

3. **The uniform error shape, as data with renderers.** The prototype's exception
   middleware answers `text/plain`, with a comment explaining that the app speaks only
   HTML. In the base this becomes **an error datum with several renderings**: a full
   page, a fragment to swap into its target, or plain text. **An error inside an htmx
   swap must not return a whole page.**

4. **Sessions**, which the prototype has none of.

5. **Logging with a request id.** The prototype depends on `slf4j-nop`, which silences
   logging altogether — convenient in a toy, incompatible with §6. That dependency has
   to become a real backend.

## 10 · Dependencies: impose what you are, not what you use

Raised by the author against an earlier, sloppier argument of the assistant's — that
Garden should be avoided because it "imposes a dependency". That distinguishes nothing:
every library imposes one. The usable criterion is different.

> **Does the dependency appear in the *consumer's own code*, or does it stay inside the
> base?**

By that measure ours are not alike:

**Shallow — the consumer never writes them:**

- **Jetty** lives entirely behind the base. The consumer never sees it.
- **Hiccup** — a view returns **plain Clojure vectors and maps**. Nobody requires hiccup
  to write one; only the renderer touches it, inside the base. **The contract is data.**
- **Reitit** — routes are **plain data**. Reitit-shaped, but still vectors and maps.

**Deep — they shape the consumer's own code:**

- **Integrant** would make the consumer write `defmethod ig/init-key`, turn their
  components into multimethods keyed by namespaced keywords, and their configuration
  into EDN with `#ig/ref`. It is also the closest thing in the stack to inversion of
  control, since `ig/init` calls your methods.
- **Malli** would appear wherever the consumer declares a schema.
- **Garden** would appear wherever the consumer wants to restyle anything.

### The cost of an imposition

Popularity is **not** the criterion — Django was popular and well-liked, and that did
not make `contrib.auth` liftable. What popularity changes is not whether the imposition
exists but **what it costs**:

> **cost = depth × probability of disagreement**

Almost nobody objects to malli. **Everybody has opinions about CSS.** So Garden and
malli are not the same case even though both are deep: styling is maximally contested
ground, data validation is not.

### And the distinction that resolves it

**Using a library internally has nothing to do with imposing it.** Only **the contract**
matters. With that, neither has to be given up:

- **Malli** — nothing need be decided. Reitit supports coercion with malli, spec or
  schema, so **the base passes coercion through** and the consumer chooses. We use malli
  in our own consumers because we like it; web-base obliges no one. Zero cost, imposition
  gone.
- **Integrant** — the answer is not to drop it but **not to make it load-bearing**. The
  base exposes ordinary start and stop functions, and **ships an optional namespace with
  the `init-key` methods**. Whoever uses integrant gets it free; whoever does not is not
  blocked. This is what well-behaved Clojure libraries do.
- **CSS** — the base ships **structural CSS only**, no brand colours or typography, and
  exposes the theming seam as **CSS custom properties** (`--wb-bg`, `--wb-text`, …) that
  the consumer redefines. No dependency, no build step. The consumer may then use
  Garden, Tailwind or hand-written CSS, and **the base never finds out**. *(This also
  closes the "how is the shell themed" question that was open here.)*

### The rule, in full

> **Impose what you are; do not impose what you use. And what you use, use without
> apology.**

web-base **is** a routing-and-shell layer, so reitit-shaped route data is legitimately
its contract. It merely **uses** a lifecycle mechanism and a schema library, and those
have no business showing.

With the caution not to over-purify: **a base with no opinions is not a base.** Removing
Ring and the routing would remove its reason to exist.

## 11 · Sessions

**The question is interesting because it collides with §2**, which says the base *never
opens a database* — and a revocable session must, by definition, be kept somewhere on
the server.

**The port need not be invented: Ring already defines one.** Its session store protocol
is `read-session` / `write-session` / `delete-session`, and the neat part is that
**`cookie-store` is one implementation of it**: instead of looking a session up by id, it
encodes the state into the signed key itself. Ring already unified both mechanisms behind
one port.

So **web-base uses that protocol as its port**, ships **`cookie-store` as the default** —
so the demo runs with no infrastructure at all — and the host plugs in whatever it wants.
No database is opened, nothing is imposed, and both mechanisms remain available.

### The real choice is not where it is kept, but whether it can be revoked

**A cookie session cannot be killed from the server.** The cookie stays valid until it
expires, whatever you do. That meets what was established about magic links: the link
attests **control of a mailbox**, and that leap is the weakest link in the whole chain.
The day an user with reach over sixty accounts has their mailbox
compromised, *"end their session now"* must have an answer. With a cookie store it has
none.

So **the first consumer will most likely want a server-side store**, even though the base defaults to
a cookie. That is the intended division: the base carries what makes the demo run, the
consumer supplies what its own risk demands.

### Two traps, written down before they exist

**Never generate the signing key at startup.** It is tempting — no key configured, so
generate a random one and carry on. It works beautifully in development and **destroys
every session on every deployment** in production, and worse, differs per instance when
there is more than one. **The base must fail loudly when the key is missing**, not cope.

**Rotate the session id on login.** The defence against session fixation, and it has a
division problem: the mechanism belongs to the base, but **only the auth module knows
when someone has just authenticated**. So the base must **expose "rotate this session"**
in its API and document that the consumer is required to call it. Unsaid, it does not get
done — and it produces no symptom at all.

## 12 · Layouts: nesting is composition, not inheritance

**Different layouts for different pages, and nested layouts: yes — and more easily than
in any template framework**, for a reason worth stating.

**Template inheritance exists because templates are strings.** `{% extends %}`, blocks,
`yield` — all of it is machinery for composing text, which does not compose by itself.
Here the markup **is data** and a layout **is a function**:

```clojure
(defn app     [content] [:html ... content])
(defn section [content] [:main.panel content])

(app (section page))
```

**Nesting layouts is calling one function inside another.** There is nothing to invent.

With one refinement already reached by another route: a layout usually has not *one*
hole but several — title, breadcrumbs, content, aside. So it takes **a map of slots**
rather than a single argument, which is exactly §13's conclusion that the shell is a set
of slots rather than a layout with fields.

### The real difficulty is not nesting — it is htmx

§9 says the base decides **fragment or full page** from `HX-Request`. With nested layouts
that is too coarse, because **"fragment" stops being binary**: an htmx swap may target
the whole main area — wanting the section layout but not the outer shell — or a small
widget, wanting none at all.

So the question is not *"page or fragment?"* but **"from what height of the stack do I
render?"**

**And the answer is already in the house: the route knows its own stack.** Reitit lets
arbitrary data hang off a route, so a route declares its layouts and the response says
how many layers to apply. No global registry, no magic convention — route data, which is
already in use.

**A default that covers the ordinary case:** `HX-Request` present → render **only the
innermost layer**; absent → **the whole stack**. The handler names the intermediate cases
on the rare occasions they arise. Nothing more.

*Wording settled 2026-09-08, during implementation.* "The innermost layer" is **the
handler's own markup with no layout at all** — the intermediate cases only make sense if
the two named cases are the extremes, and in the prototype five of six htmx routes want
exactly that. The intermediate case — a swap that wants the section but not the shell —
is named by the handler as a height counted from the innermost layout, and **what it
names is obeyed on both kinds of request**: a full navigation with an explicit height
below the stack gets exactly that, without a doctype. And under htmx 4 "`HX-Request`
present" is not enough on its own: a history restore and a body-targeted swap also carry
it and want the whole document, which they say with `HX-Request-Type: full`. The base
reads both headers, in one place.

### The one way to spoil this

**Do not build a layout engine.** The temptation will be a `deflayout` macro, a registry,
an inheritance mechanism. All of it unnecessary: functions and route data suffice. **If we
find ourselves writing a macro for this, we have gone wrong** — because the whole argument
for Hiccup is that the language already solved composition.

## 13 · The subject, and the shell as a set of slots

**Settled: the subject belongs to the auth module, and the base needs to know nothing
about it — not even as an opaque value it inspects.**

The only thing that needs anything from the subject is the shell, to paint the
"signed in as…" corner. That is not solved with a field but with a **slot**: the host
hands the base **an already-rendered Hiccup fragment** for that area. The base never
inspects the subject, never needs it to have a name, an id, or any shape at all. It
carries it in the session and hands it back to whoever asked.

**And that generalises into what is probably the shell's entire design.** Breadcrumbs,
navigation, footer, the identity corner — all of them are **slots the host fills**. The
page shell is therefore **not a layout with fields but a set of slots**, which should fit
in very little code.

### On "it is the base *we* will always use"

Accepted as a criterion, and it is healthy not to design for imaginary third parties.
But the trap deserves naming, because it is the author's own history: *"they always go
together"* is exactly the reasoning that produced `contrib.auth`. Django ships auth
always, and that is precisely why it cannot be taken out.

**What settles it here without appealing to purity: decoupling is the cheaper
implementation, not the more expensive one.** A base that does not understand the
subject is *less* code than one that does; a slot to be filled is simpler than a user
model. No price is being paid for independence — **independence *is* the simple
version.** Where that holds, there is no dilemma to weigh.

### Who §4 actually protects

A restatement of the membership test that fits this project better: **it is not for
strangers, it is for the author in two years.** §7 already says the second consumer is
what should pull things inward — and the second consumer will almost certainly be his
own next project. The base is not being protected from third parties: it is being
protected from a future idea having to argue with assumptions the first consumer made.

*(Theming was settled in §10: structural CSS plus custom properties.)*

## 14 · Internationalisation — settled 2026-09-08, during implementation

Raised by the author after the plan was approved: the base must **allow**
internationalisation and, by the author's decision, **include** it.

**What already allowed it, unsaid.** No base namespace emits visible prose: error data
carry a status and optional strings the host supplies, the default error page shows the
status alone, and the shell exposes a `:lang` slot. A base that knows no domain knows no
language either, and that had to be true before anything here could be built on it.

**What was missing is what a bicycle rental needs unchanged:** knowing, per request,
which language to render. That is request context, exactly like the request id, and it
belongs to the base: the host's handlers, layouts and error pages must all agree on it,
and an htmx fragment must render in the same language as the page it lands in.

### Options considered

1. **Negotiation only**, translations left to the host with whatever library it likes.
2. **Negotiation plus a minimal catalogue of our own** — EDN dictionaries, fallback,
   simple interpolation. Forty lines duplicating what the ecosystem does better.
3. **Negotiation plus [Tempura](https://github.com/taoensso/tempura) integrated.**

The ecosystem, checked 2026-09-08: Tempura (Taoussanis; EDN dictionaries, fallback,
Hiccup content, the choice of Luminus and Kit; 1.5.4, June 2024) and
[Tongue](https://github.com/tonsky/tongue) (Prokopov; zero dependencies, functions for
plurals, number and date formatters; 0.4.4, March 2022) are the two live choices;
Tower is superseded by Tempura; clj-i18n is a gettext workflow for translator teams.
Both live choices use **plain maps as dictionaries**.

**The author chose 3.** Recorded against §10 honestly: Tempura is a **deep** dependency
by §10's own test — the consumer writes its dictionaries in Tempura's shape and calls
its `tr` — and every consumer inherits encore, truss and tools.reader. The cost formula
is `depth × probability of disagreement`, and the judgement is that disagreement is
low: the dictionary is an EDN map, which is also what Tongue would want, and Tempura is
the ecosystem's default. What is bought is that the demo, and every consumer, gets
translation working from the first request with nothing to assemble. The assistant
recommended option 1; the author's call stands, and this paragraph is its paper trail.

### The seam

- The host configures `:i18n {:dict … :default-locale … :locale-fn …}`. `:dict` is a
  Tempura dictionary; its top-level keys are the **supported locales**.
- Per request the base computes the preference list — `(:locale-fn request)` when the
  host supplies one (a preference kept in the session, a cookie, a URL prefix), else the
  parsed `Accept-Language` — followed by the default locale.
- It puts on the request `:wb/tr`, Tempura's translate function bound to that list, and
  `:wb/locale`, the **first supported** locale in the list, resolved the way Tempura
  resolves it (`en-GB` matches a dictionary with `:en`). `:wb/locale` therefore never
  names a language the page is not actually rendered in, and `<html lang>` — which the
  shell fills from it when the host gives no `:lang` — never lies.
- Missing `:i18n` is allowed: single-language hosts get no `:wb/tr` and no `lang`
  unless they set the slot themselves.

**Out, deliberately:** number and date formatting (`java.text` in the host, or Tongue's
formatters), pluralisation rules, translated URLs, locale prefixes in paths. The host
can build all of them on `:wb/locale`; the base does not guess which it wants.

**The trap, written down:** never cache a rendered page per language across requests
without keying on `:wb/locale`, and never read the language from anywhere but the
request — the htmx fragment that arrives a second later carries the same headers and
must land in the same language.

## 15 · Web security — settled 2026-09-08, during implementation

Raised by the author: the base must carry the web security measures that are not
authentication or authorisation (those stay in the auth module and the domain, §5).

**What the design already gave, because §11 and §9 demanded it:** the session cookie is
`Secure`, `HttpOnly` and `SameSite=Lax` by default and its key is never generated at
startup; session rotation is exposed for the login; no exception message and nothing
from the request reaches an error response; the gate answers htmx with `HX-Redirect`;
Hiccup escapes every string and attribute unless the host says `raw`; Ring's resource
handler refuses `..` and symlinks outside the root; the access line never logs the
query string.

**What Ring does not ship**, checked 2026-09-08 in the jars: `ring-core` carries no
security headers and no CSRF protection. They live in sibling libraries by the same
author — `ring-anti-forgery` (synchroniser token kept in the session, `X-CSRF-Token`
header, constant-time comparison), `ring-headers`, `ring-ssl` — and `ring-defaults`
bundles them with an order of its own, which would fight the explicit wiring that is
this base's product.

### What the base carries

- **Response headers**, added only when the response lacks them, on every response
  including static assets and errors: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`;
  `Strict-Transport-Security` only when the host says there is TLS in front.
- **Content-Security-Policy** as the host's own string, with a **per-request nonce**
  substituted and placed on the request, so the shell can mark its own `<script>`.
  Written down so it is not rediscovered: htmx's `hx-on`, `hx-vals js:` and trigger
  filters need `unsafe-eval` or the `hx-csp` extension; a strict policy means not using
  them. The demo ships a strict policy and does without them.
- **CSRF**: `ring-anti-forgery` integrated, on by default, refusal rendered as the
  base's 403 error (a fragment inside an htmx swap, a page otherwise). The shell puts
  the token in `hx-headers` on `<body>` so every htmx request carries it; classic forms
  get a helper for the hidden field. Recorded honestly: `SameSite=Lax` and the custom
  header htmx already sends block cross-site requests in current browsers; the token is
  defence in depth, and the price of it is one small dependency on `ring-core` alone.
- **Behind a proxy**, opt-in: scheme and client address from `X-Forwarded-Proto` and
  `X-Forwarded-For`, only when the host says the proxy is trusted.

**Out, by §5 and §6:** rate limiting, lockout after failed attempts, password hashing,
second factors, authorisation, audit trails. Request size limits stay Jetty's defaults
(200 KB forms, 8 KB headers), documented rather than wrapped.

*(Nothing remains open in this document; what is left is implementation.)*
