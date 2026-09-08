(ns dev.arkaitz.web-base.native
  "Optional, like the Integrant namespace: requiring it is the opt-in. It
  teaches Ring to read a classpath resource from inside a GraalVM native
  image, where resources are served under the `resource:` URL protocol that
  `ring.util.response/resource-data` knows nothing about — so without this
  every asset the base serves, its own CSS and htmx included, answers 500.

  The method is inert outside an image: no JVM produces that protocol, and
  only the tests reach the body, through a URL carrying a handler of their
  own. The build flags an image needs are in the README, not here: they move
  between GraalVM releases, and a stale flag in a README is visible while a
  stale flag compiled into a library is a build that breaks for a reason its
  user cannot see.

  Two things this is not, both unlike the Integrant namespace it otherwise
  mirrors. `:resource` is a bare key on a multimethod that belongs to Ring,
  not a namespaced key of ours on a multimethod of ours, so whoever loads
  last wins if Ring or another library ever defines it — which is what the
  tripwire in the tests watches for. And requiring is the opt-in only for
  whoever requires: a host library that requires this hands the method to
  its own consumers, who never asked."
  (:require [clojure.string :as str]
            [ring.util.response :as response])
  (:import [java.net URL URLConnection]
           [java.util Date]))

(defmethod response/resource-data :resource
  [^URL url]
  ;; The shape and the nil rules are Ring's own `:jar` method's, so a resource
  ;; read out of an image answers exactly as one read out of a jar: a length
  ;; the connection does not know is nil rather than -1, and an unknown
  ;; modification time is nil rather than the epoch.
  ;;
  ;; A directory is nil, or an image answers a listing of the host's static
  ;; root where a jar answers 404 — measured: without this, `GET /wb/` came
  ;; back `htmx.min.js\nwb.css` with 200. A `resource:` connection cannot be
  ;; asked outright the way Ring asks a jar entry, so the trailing slash the
  ;; resource handler builds for a root is what says so. Measured inside an
  ;; image, that covers `/wb/` and `/css/`; a subdirectory asked for without
  ;; the slash, `/css`, is refused only when the build did not register the
  ;; directory itself — which is what the README's include patterns do.
  (when-not (str/ends-with? (.getPath url) "/")
    (let [connection (.openConnection url)
          length     (.getContentLengthLong ^URLConnection connection)
          modified   (.getLastModified ^URLConnection connection)]
      {:content        (.getInputStream ^URLConnection connection)
       :content-length (when (<= 0 length) length)
       :last-modified  (when-not (zero? modified) (Date. modified))})))
