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
   handler.** Left to the handler it produces duplicate routes: one for direct
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

## 12 · Still open

- **What a "subject" is** to the base. Probably an opaque value it never inspects.

*(Theming was settled in §10: structural CSS plus custom properties.)*
