(ns dev.arkaitz.web-base.htmx
  "The named places where web-base touches htmx (SPEC §9). Every `HX-` literal in
  the base lives in this file: the request classifier that decides fragment or
  page, and the redirect response the gate answers with. The error renderer is
  the third place and it calls `partial-request?` rather than reading headers.

  Verified against htmx 4.0.0: `HX-Request` is sent on every htmx request, and
  `HX-Request-Type` says `full` when the swap targets the whole document — a
  history restore, a body-targeted swap — and `partial` otherwise. Deciding on
  `HX-Request` alone would hand a bare fragment to the back button.")

(def vary
  "Value of the `Vary` header for any response whose shape depends on the
  request being an htmx fragment or a page, so a shared cache never serves a
  fragment to a navigation."
  "HX-Request, HX-Request-Type")

(defn partial-request?
  "True when htmx asked for a fragment rather than a whole document. Ring
  lowercases request header names."
  [request]
  (let [headers (:headers request)]
    (and (= "true" (get headers "hx-request"))
         (not= "full" (get headers "hx-request-type")))))

(defn redirect
  "A response that makes htmx navigate the whole window to `location`. A `302`
  would be followed by htmx and its target swapped with the login page; and
  `HX-Location` would do the same through an AJAX navigation. Only
  `HX-Redirect` leaves the fragment's target alone."
  [location]
  {:status  200
   :headers {"HX-Redirect" location}
   :body    ""})
