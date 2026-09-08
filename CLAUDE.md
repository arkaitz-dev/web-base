# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`SPEC.md` says **what** to build. This file says **how to work here**, and repeats only
the few rules that get broken silently.

## What this is

`dev.arkaitz/web-base` — a **super-micro-framework**: the operational web foundation a
project plugs in. Server-rendered HTML with **HTMX** and **Hiccup**.

It was extracted from the modularity decisions of a separate, domain-specific
application that is **its first consumer, not its owner**. The reasoning behind every
boundary is recorded in `SPEC.md`; read it before questioning a boundary, and do not
re-derive it.

## Project state

**Implemented** (2026-09-08) as an **extraction from `../prueba`**; the commit history
is the record, one step per commit. Every namespace under `src/dev/arkaitz/web_base/`
is one seam of `SPEC.md` §6–§15, and `src/dev/arkaitz/web_base.clj`'s docstring is the
wiring order — read it before touching the stack. The demo under `demo/` is the
acceptance test (SPEC §8) and never enters the jar.

Build, test and REPL commands are recorded here **only once they have actually been run
and observed to work**, never from convention. Observed:

```
clojure -M:test                       # whole suite incl. demo/test; exit ≠ 0 on failure
clojure -M:test -n <namespace>        # one namespace (several -n allowed)
clojure -T:build jar                  # library jar → target/web-base-0.2.0.jar (no demo inside)
clojure -T:build install              # jar + pom into ~/.m2; consumed by :mvn/version (verified from another project)
clojure -T:build deploy               # to Clojars with CLOJARS_USERNAME/CLOJARS_PASSWORD (not run yet: needs credentials)
WB_SESSION_KEY=<base64 of 16 bytes> clojure -M:demo [port]   # the demo, default port 3000
clojure -M:demo [port]                # the same, with the key in ./env.local.edn (git-ignored)
clojure -T:build demo-uber            # runnable demo → target/web-base-demo-0.2.0.jar (18 MB, never published)
WB_SESSION_KEY=<base64 of 16 bytes> java -jar target/web-base-demo-0.2.0.jar [port]
clojure -M:dev                        # REPL with dev/user.clj: (go) (reset) (halt) (store)
```

⚠ `jar`, `install` and `deploy` delete `target/` before building, so they also
delete the demo uberjar. Build `demo-uber` last, or rebuild it afterwards.

A key: `(dev.arkaitz.web-base.session/generate-key)` in any REPL, once, kept in the
environment. The base refuses to invent one (SPEC §11).

Test discipline in force here: every test was written under `/write-test` with a
contract, watched go red by named mutations, and the security-adjacent ones
(session, gate, errors, security, assembly) through a mixed-model review panel.
`test/resources/logback-test.xml` keeps INFO enabled with no appender: the MDC tests
need a real SLF4J backend. `dependencies_test.clj` scans `src/` with the reader and
fails if any namespace other than `dev.arkaitz.web-base.integrant` references
integrant — the only signal such a require would ever produce.

Browser smoke of the demo (done through Playwright on 2026-09-08, repeat after
touching views or the shell): tab swap leaves one `#app`; add a todo over htmx; invalid
signup re-renders inside the form; `/boom` button swaps the 500 into its target;
login → `/private` names the subject; language switch; back button restores a whole
page, and back onto `/boom` shows the boom page, not the error; console free of CSP
violations.

Known limitation: htmx 4's `hx-on`, `hx-vals js:` and trigger filters need
`unsafe-eval`; the demo runs a strict CSP and does without them.

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
- **Never put a random generator in a var root.** `(def r (SecureRandom.))`
  works perfectly on a JVM and has no symptom there, and GraalVM's
  `native-image` bakes it into the binary with its seed. A `delay` holds it
  instead. `native_test.clj` walks every var root of the base for one, through
  closures, collections and atoms, because no behavioural test can see it.
- **Never look for a configuration file the host did not name.** A base that knows a
  file name can look for it, and then the directory a process was started from decides
  the signing key. `config/env-file-readers` reads the path it is given and nothing
  else. The scan in `dependencies_test` only catches an EDN file name written into
  `src/`; the condition itself is a review rule (SPEC §11).
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

*`../prueba` is versioned but has no remote, and the author is content with that: it was
an experiment, and what mattered from it — the stack, the patterns, the discipline — is
already captured in `SPEC.md`. Extracting from it is a convenience, not a dependency. If
it is gone, build from the spec.*
