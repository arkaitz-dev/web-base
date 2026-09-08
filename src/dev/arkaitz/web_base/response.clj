(ns dev.arkaitz.web-base.response
  "Constructors for the responses a handler writes most: the Ring maps the
  base reads, with nothing else in them. `ok` carries Hiccup for the route's
  layouts; `see-other` is the redirect after a classic form. There is no
  `not-found` or `forbidden` here on purpose: those are `error/throw!`, so
  they reach the error renderer and not the layout stack.")

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
