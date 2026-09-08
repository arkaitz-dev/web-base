# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`SPEC.md` says **what** to build. This file says **how to work here**, and repeats only
the few rules that get broken silently.

## What this is

`dev.arkaitz/web-base` — a **super-micro-framework**: the operational web foundation a
project plugs in. Server-rendered HTML with **HTMX** and **Hiccup**.

It was extracted from the modularity decisions of a separate project, **the first consumer**
(`the first consumer`), a management system for Spanish a specific domain. **the first consumer is
the first consumer, not the owner.** The reasoning behind every boundary here lives in
`the first consumer's own specification` §3 (decision log, entries of 2026-09-07 and 2026-09-08) and is drawn
in `the first consumer's own map` §5. Read those before questioning a boundary; do not re-derive
them.

## Project state

Specification settled; **no code yet**. The next step is implementation, and it is an
**extraction from `../prueba`** — a working prototype that already covers most of the
scope — not a fresh start. See `SPEC.md` §9.

Build, test and REPL commands are recorded here **only once they have actually been run
and observed to work**, never from convention. Nothing has been run yet. The reference
for the likely shape is `../prueba/deps.edn`, whose aliases are `:run`, `:dev` and
`:build`.

## The two rules that must survive contact with code

Everything else in `SPEC.md` is descriptive. These two are the ones that get violated by
accident, one convenient commit at a time:

**1 · A library you call. Never a framework that calls you.** The host wires the modules
explicitly. The moment web-base *calls* a module — a plugin registry, auto-discovery, a
hook system — that module must know web-base in order to be called, and it stops being
liftable. This is precisely what made Django's `contrib.auth` impossible to extract, and
avoiding it is the reason this project exists. Corollary: **no ambient global state**;
configuration is passed in.

**2 · The membership test.** Before adding anything: *would a bicycle rental or a
clinic's appointment book need this, unchanged?* If not, it does not belong here. This
kind of library dies by absorbing one convenient thing at a time until only its first
consumer can use it.

## Where the boundary is expected to erode

**Authentication.** The shell needs to know whether there is a session and who the
subject is; the temptation is to put login inside the base, which is what Django's
middleware does. web-base knows *that* there is a subject — an opaque value — and never
*how* it was authenticated. It receives a function. Same for authorisation, more
strongly: it receives a predicate and obeys it.

## Traps already identified — do not rediscover them

Kept here rather than only in `SPEC.md` because this file loads by itself and the spec
has to be opened deliberately. **Every one of these fails silently.**

- **`HX-Redirect`.** Answering an htmx request with a `302` makes htmx follow the
  redirect and plant the login page inside the target `div`. The gate must translate a
  refusal into the right outcome per request kind.
- **Fragment vs full page** is decided from the `HX-Request` header **by the base**, not
  by the handler — otherwise every route gets duplicated. With nested layouts the
  question is not binary but *from what height of the layout stack* (SPEC §12).
- **Errors are data with several renderings** (page, fragment, plain text). An error
  inside an htmx swap must not return a whole page.
- **Never generate the session signing key at startup.** Works beautifully in
  development; destroys every session on every deploy, and differs per instance. Fail
  loudly when it is missing (SPEC §11).
- **Rotate the session id on login.** The defence against session fixation. The
  mechanism belongs to the base but only the auth module knows when someone has just
  authenticated, so the base must *expose* rotation and the consumer must call it.
  Unsaid, it does not get done, and it produces no symptom (SPEC §11).
- **Keep the htmx coupling in three named, isolated places** — `HX-Request`,
  `HX-Redirect`, the error renderer. Not an abstraction layer to support alternatives;
  simply do not scatter `HX-` strings through the codebase (SPEC §9).
- **Do not build a layout engine.** No `deflayout` macro, no registry, no inheritance.
  Layouts are functions and nesting is composition; route data carries the stack. **If a
  macro seems necessary here, the whole argument for Hiccup has been lost** (SPEC §12).

## The demo is the acceptance test

web-base ships a tiny demo application using nothing but web-base. It is not a showcase:
**if the demo needs anything web-base does not provide, the seam is in the wrong
place.** When the extraction is done, what remains of `../prueba` — store, todos, search,
signup — is that demo, and **it lives in this repository**, not next door: the demo is
part of what web-base ships.

⚠ **`../prueba` is versioned but has no remote** — it exists only on this machine, and
the extraction plan depends on it. Losing it turns "extract" into "rewrite from a
specification".
