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
  "`attachment` with the name twice, as RFC 6266 has it: an ASCII fallback, every
  character outside printable ASCII and every quote or backslash an underscore, and the
  name itself percent-encoded as RFC 5987 spells it — `URLEncoder` writes a space as `+`
  and leaves `*` alone, neither of which it allows."
  [filename]
  (str "attachment; filename=\"" (str/replace filename #"[^\x20-\x7E]|[\"\\]" "_") "\"; filename*=UTF-8''"
       (-> (URLEncoder/encode ^String filename "UTF-8") (str/replace "+" "%20") (str/replace "*" "%2A"))))

(defn attachment
  "A download: `body` — bytes, a stream, a file — sent as `application/octet-stream`, to
  be saved under `filename` and never shown, whatever type it was uploaded with (since
  0.17.0). A blank name is refused: a download with none is saved under whatever the
  browser makes of the URL. No caching rule is imposed: a file of somebody's is `(assoc-in r [:headers
  \"Cache-Control\"] \"private, no-store\")`, a brochure is not."
  [body filename]
  (when (str/blank? (str filename))
    (throw (ex-info "web-base: attachment needs a file name, and was given none" {:filename filename})))
  {:status  200
   :headers {"Content-Type" "application/octet-stream" "Content-Disposition" (disposition (str filename))}
   :body    body})
