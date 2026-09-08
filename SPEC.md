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

## 9 · Open, to settle before or during implementation

- **Server-rendered HTML, a JSON API with a separate frontend, or both?** This decides
  what "page shell" even means, and it is the largest open question here.
- **Which libraries** for routing, lifecycle and sessions. All of them exist; the
  choice is about weight and about which of them try to invert control.
- **Where sessions live** — in a signed cookie, or server-side with a store the host
  supplies as a port.
- **What a "subject" is** to the base. Probably an opaque value it never inspects.
- **How the shell is themed** without web-base learning anything about the consumer's
  brand.
