(ns dev.arkaitz.web-base.response
  "Constructors for the responses a handler writes most: the Ring maps the
  base reads, with nothing else in them. `ok` carries Hiccup for the route's
  layouts; `see-other` is the redirect after a classic form. There is no
  `not-found` or `forbidden` here on purpose: those are `error/throw!`, so
  they reach the error renderer and not the layout stack."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.net URLEncoder]))

(defn health
  "A handler for a health probe, for `:sessionless`: `ready?` is asked on every request,
  and answers 200 `ok` when it is truthy and 503 when it is not or throws — the throw
  logged, since a probe that says only 503 would leave the operator guessing. Plain text,
  uncached, and nothing else in the body: a probe is public, and the state behind it is
  the operator's to read, not the internet's."
  [ready?]
  (fn [_request]
    (let [ok? (try (ready?)
                   (catch Exception e
                     (log/warn e "health check failed")
                     false))]
      {:status  (if ok? 200 503)
       :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"}
       :body    (if ok? "ok" "unavailable")})))

(defn ok
  "`{:status 200 :body body}`. `:slots` becomes `:wb/slots`, what the layouts
  receive besides `:content` and `:request`; `:height` becomes `:wb/height`,
  how many layouts render (SPEC §12). An option not given stays absent, so
  the renderer's own defaults apply."
  ([body] {:status 200 :body body})
  ([body {:keys [slots height]}]
   (cond-> {:status 200 :body body}
     (some? slots)  (assoc :wb/slots slots)
     (some? height) (assoc :wb/height height))))

(defn see-other
  "The redirect after a POST: a 303 makes the browser GET `location`, and an
  empty body is what the gate's own redirect carries."
  [location]
  {:status 303 :headers {"Location" location} :body ""})

(defn unprocessable
  "`ok` with status 422: a form that came back with its errors, rendered by the handler
  that refused it — the road for an htmx form that swaps only itself, where re-running
  the page's GET would render more than the target. htmx 4 swaps a 422; htmx 2 swapped
  no 4xx unless `htmx.config.responseHandling` said so. For a whole-page form,
  `dev.arkaitz.web-base/rerender` renders the page's own GET instead."
  ([body] (assoc (ok body) :status 422))
  ([body opts] (assoc (ok body opts) :status 422)))

(defn- disposition
  "`kind` — `attachment` or `inline` — with the name twice, as RFC 6266 has it: an ASCII
  fallback, every character outside printable ASCII, every quote or backslash, and every
  `/` or `:` (since 0.22.0: a directory or a drive in a name a client honours) an
  underscore; and the name itself percent-encoded as RFC 5987 spells it — `URLEncoder`
  writes a space as `+` and leaves `*` alone, neither of which it allows."
  [kind filename]
  (str kind "; filename=\"" (str/replace filename #"[^\x20-\x7E]|[\"\\/:]" "_") "\"; filename*=UTF-8''"
       (-> (URLEncoder/encode ^String filename "UTF-8") (str/replace "+" "%20") (str/replace "*" "%2A"))))

(def ^:private inline-types
  "The types a browser may show in place: a viewer of its own draws them, and none runs
  a script in this origin as an SVG or an HTML document would."
  #{"application/pdf" "image/png" "image/jpeg" "image/gif" "image/webp"})

(def ^:private attachment-options #{:content-type :inline?})

(def ^:private media-type
  "A type and a subtype, with parameters if any, and no ASCII control character — CR and
  LF among them: what may stand as a `Content-Type` header's value."
  #"[^\p{Cntrl}/]+/[^\p{Cntrl}]+")

(defn- refuse!
  "Throws the refusal `message` with `data`, closing `body` first when it is something to
  close: a stream the caller opened for a download that is not sent would otherwise
  hold its file until the collector finds it. A close that fails rides on the refusal as
  suppressed, so the refusal still names the caller's mistake."
  [body message data]
  (let [refusal (ex-info message data)]
    (when (instance? java.io.Closeable body)
      (try (.close ^java.io.Closeable body)
           (catch Exception e (.addSuppressed ^Throwable refusal e))))
    (throw refusal)))

(defn attachment
  "A download: `body` — bytes, a stream, a file — to be saved under `filename`. With two
  arguments it is sent as `application/octet-stream` and never shown, whatever type it
  was uploaded with (since 0.17.0). A blank name is refused: a download with none is
  saved under whatever the browser makes of the URL.

  With options (since 0.22.0), `:content-type` is the type it is sent as, and `:inline?
  true` lets the browser show it in place — a PDF the host has checked is one — under its
  name, as a page of its own: inside a frame of the host's page only where the security
  headers let the page be framed (`:frame-options`, a CSP's `frame-ancestors`). Only
  `application/pdf`, `image/png`, `image/jpeg`, `image/gif` and `image/webp`, spelt
  exactly, may be shown in place: an SVG or an HTML document would run its script in
  this origin, so anything else with `:inline?` is refused at the call, as is a type that
  is no media type or an option it does not take. A refused stream is closed.

  No caching rule is imposed here. A response to a request with a subject or a session
  is already `no-store` (`gate/wrap-subject`); a file of somebody's reached with neither —
  by a credential in its URL, from a mail — is the host's to mark `no-store`."
  ([body filename] (attachment body filename {}))
  ([body filename {:keys [content-type inline?] :as opts}]
   (when (str/blank? (str filename))
     (refuse! body "web-base: attachment needs a file name, and was given none" {:filename filename}))
   (when-let [unknown (not-empty (vec (sort-by pr-str (remove attachment-options (keys opts)))))]
     (refuse! body (str "web-base: attachment takes :content-type and :inline?, not " (str/join " " (map pr-str unknown)))
              {:filename filename :unknown unknown}))
   (when (and (contains? opts :content-type) (not (and (string? content-type) (re-matches media-type content-type))))
     (refuse! body (str "web-base: attachment was given a :content-type that is no media type: " (pr-str content-type))
              {:filename filename :content-type content-type}))
   (let [type    (or content-type "application/octet-stream")
         inline? (boolean inline?)]
     (when (and inline? (not (contains? inline-types type)))
       (refuse! body (str "web-base: attachment shows a file in place only as application/pdf, image/png, image/jpeg,"
                          " image/gif or image/webp, spelt exactly — not " (pr-str type) "; anything else could run a"
                          " script in this origin, so send it as a download")
                {:filename filename :content-type type}))
     {:status  200
      :headers {"Content-Type" type "Content-Disposition" (disposition (if inline? "inline" "attachment") (str filename))}
      :body    body})))
