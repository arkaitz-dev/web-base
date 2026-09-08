(ns dev.arkaitz.web-base.render
  "Turns a handler's Hiccup body into HTML, applying the route's layout stack
  from the height the request asks for (SPEC §12).

  A layout is a function of one map of slots — `:content`, `:request` and
  whatever the response put under `:wb/slots` — and nesting is composition:
  the stack folds from the innermost layout outwards. Route data `:wb/layouts`
  is a vector, outermost first; reitit concatenates a parent's vector with its
  children's, so nested routes accumulate their stack and a child opts out with
  `^:replace`. Height counts from the innermost layout, which is why adding an
  outer layout later never invalidates a height a handler already names."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.htmx :as htmx]
            [hiccup2.core :as h]))

(defn html
  "Hiccup data → HTML string. `:mode :html` because hiccup 2 defaults to XHTML
  self-closing tags. Correctness for runtime data rests on hiccup's HtmlRenderer
  protocol, not on the macro's compile-time folding, which is inert here."
  [markup]
  (str (h/html {:mode :html} markup)))

(def doctype "<!DOCTYPE html>\n")

(defn- hiccup? [body]
  (or (vector? body) (seq? body)))

(defn- header-present? [headers name]
  (some #(= (str/lower-case %) name) (keys headers)))

(defn- strip
  "The response keys that only the base reads must not reach the server."
  [response]
  (dissoc response :wb/height :wb/slots))

(defn- fold
  "Applies the innermost `height` layouts of `stack` around `content`."
  [content request slots stack height]
  (reduce (fn [content layout]
            (layout (merge slots {:content content :request request})))
          content
          (reverse (take-last height stack))))

(defn response
  "Renders a Hiccup `:body` through `stack`; leaves any other body untouched.
  A page request (not an htmx fragment) gets the whole stack unless the
  response names `:wb/height`; a fragment gets `:wb/height` or nothing. The
  doctype is added only when a page request rendered the whole stack."
  [request response stack]
  (let [body (:body response)]
    (if-not (hiccup? body)
      (strip response)
      (let [page?  (not (htmx/partial-request? request))
            total  (count stack)
            height (or (:wb/height response) (if page? total 0))]
        (when-not (and (nat-int? height) (<= height total))
          (throw (ex-info "response :wb/height is not within the route's layout stack"
                          {:wb/height (:wb/height response) :layouts total})))
        (let [markup   (fold body request (:wb/slots response) stack height)
              document (cond->> (html markup)
                         (and page? (= height total)) (str doctype))
              headers  (:headers response {})]
          (-> (strip response)
              (assoc :body document)
              (assoc :headers
                     (cond-> (assoc headers "Vary" htmx/vary)
                       (not (header-present? headers "content-type"))
                       (assoc "Content-Type" "text/html; charset=utf-8")))))))))

(def middleware
  "reitit middleware. Compiled per route so the layout stack is read from the
  merged route data once; a route without `:wb/layouts` still renders, with an
  empty stack."
  {:name    ::render
   :compile (fn [{:wb/keys [layouts]} _opts]
              (let [stack (vec layouts)]
                (fn [handler]
                  (fn [request]
                    (response request (handler request) stack)))))})
