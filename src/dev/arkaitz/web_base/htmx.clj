(ns dev.arkaitz.web-base.htmx
  "The named places where web-base touches htmx (SPEC §9). Every `HX-` literal in
  the base lives in this file: the request classifier that decides fragment or
  page, and the redirect response the gate answers with. The error renderer is
  the third place and it calls `partial-request?` rather than reading headers.

  Verified against htmx 4.0.0: `HX-Request` is sent on every htmx request but a history
  restore, whose headers htmx builds anew with `HX-History-Restore-Request` alone, and
  `HX-Request-Type` says `full` when the swap targets the whole document — a restore, a
  body-targeted swap, an `hx-select` — and `partial` otherwise. A restore therefore reads
  as a navigation, as it must; deciding on `HX-Request` alone would hand a bare fragment
  to any other `full` request.")

(def vary
  "Value of the `Vary` header for any response whose shape depends on the
  request being an htmx fragment or a page, so a shared cache never serves a
  fragment to a navigation."
  "HX-Request, HX-Request-Type")

(def fragment-headers
  "The request headers htmx 4 sends for a swap, as Ring delivers them: what
  a host's test sends to stand in for one. `partial-request?` answers true."
  {"hx-request" "true" "hx-request-type" "partial"})

(def link-headers
  "The request headers htmx 4 sends when a `link` is followed, as Ring delivers them: a
  whole-page GET (`hx-select` asks for the whole document) swapped into `<main>`, which
  htmx names by its tag since it has no id. What a host's test sends to stand in for a
  click (`testing/visit` with `{:link? true}`, since 0.26.0); a page that loads scripts
  of its own answers it with `HX-Redirect` to itself."
  {"hx-request" "true" "hx-request-type" "full" "hx-target" "main"})

(defn partial-request?
  "True when htmx asked for a fragment rather than a whole document. Ring
  lowercases request header names."
  [request]
  (let [headers (:headers request)]
    (and (= "true" (get headers "hx-request"))
         (not= "full" (get headers "hx-request-type")))))

(defn refresh
  "`response` asking htmx to reload the whole page. For a fragment that rotated the
  session: the page it lands in still carries the old CSRF token in `<body>`'s
  `hx-headers`, which only a full page can replace. htmx 4 reads it as
  `hx.refresh === \"true\"` (measured in the bundled build)."
  [response]
  (assoc-in response [:headers "HX-Refresh"] "true"))

(defn redirect
  "A response that makes htmx navigate the whole window to `location`. A `302`
  would be followed by htmx and its target swapped with the login page; and
  `HX-Location` would do the same through an AJAX navigation. Only
  `HX-Redirect` leaves the fragment's target alone."
  [location]
  {:status  200
   :headers {"HX-Redirect" location}
   :body    ""})
