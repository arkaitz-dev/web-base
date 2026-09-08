(ns dev.arkaitz.web-base.testing
  "Helpers for a host's own test suite, over plain Ring maps and rendered
  bodies. Nothing here needs ring-mock or any other library, so it ships in
  the jar. The shapes parsed are the base's own — the cookies Ring's session
  middleware sets, the CSRF token the shell and `security/csrf-field` emit —
  which is why the base owns these readers rather than every host's tests."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.htmx :as htmx]
            [dev.arkaitz.web-base.security :as security]))

(defn- header-values
  "The values of header `name` in `headers`, whatever the case of the key and
  whether Ring holds one string or several."
  [headers name]
  (let [wanted (str/lower-case name)
        values (some (fn [[k v]] (when (= wanted (str/lower-case (str k))) v)) headers)]
    (cond (nil? values)    []
          (string? values) [values]
          :else            (vec values))))

(defn- pair
  "`[name value]` of one `name=value` fragment; nil without a name."
  [fragment]
  (let [[name value] (str/split (str/trim fragment) #"=" 2)]
    (when (seq name) [name (or value "")])))

(defn- deletion?
  "A `Max-Age` of zero or less among a cookie's attributes: the browser
  forgets the cookie instead of keeping it."
  [attributes]
  (boolean (some (fn [attribute]
                   (when-let [[name value] (pair attribute)]
                     (when (= "max-age" (str/lower-case name))
                       (try (<= (Long/parseLong value) 0)
                            (catch NumberFormatException _ false)))))
                 attributes)))

(defn cookies
  "Every cookie `response` sets, name to value, the way a browser keeps
  them: a deletion (`Max-Age` of zero or less) is nil, a cookie to forget."
  [response]
  (into {}
        (keep (fn [header]
                (let [[cookie & attributes] (str/split header #";")]
                  (when-let [[name value] (pair cookie)]
                    [name (when-not (deletion? attributes) value)]))))
        (header-values (:headers response) "Set-Cookie")))

(defn- request-cookies [request]
  (into {} (keep pair) (str/split (str (first (header-values (:headers request) "cookie"))) #";")))

(defn with-cookies
  "`request` carrying the cookies it already had and every one `response`
  set, the way a browser follows a redirect: a cookie set again replaces the
  old value, a deletion forgets it. The second request of a login test, the
  swap after a page. Unchanged when the jar does not change."
  [request response]
  (let [before (into (sorted-map) (request-cookies request))
        after  (into (sorted-map) (remove (comp nil? val)) (merge before (cookies response)))]
    (cond (= before after) request
          (empty? after)   (update request :headers dissoc "cookie")
          :else            (assoc-in request [:headers "cookie"]
                                     (str/join "; " (map (fn [[name value]] (str name "=" value)) after))))))

(def ^:private hidden-field
  "The input `security/csrf-field` renders, in any attribute order."
  #"<input[^>]*__anti-forgery-token[^>]*>")

(def ^:private field-value #"\bvalue=\"([^\"]*)\"")

(def ^:private header-attribute
  "The token inside the shell's `hx-headers:inherited` JSON, as Hiccup
  escapes it into the attribute."
  (re-pattern (str (java.util.regex.Pattern/quote security/csrf-header)
                   "&quot;:&quot;([^&]+)&quot;")))

(defn csrf-token
  "The CSRF token a rendered `response` carries, from the hidden field of a
  classic form or from the shell's `<body>` attribute; nil when the body is
  not a string or holds neither."
  [response]
  (let [body (:body response)]
    (when (string? body)
      (or (some->> (re-find hidden-field body) (re-find field-value) second)
          (second (re-find header-attribute body))))))

(defn fragment
  "`request` as htmx sends it for a swap, so the base renders a fragment —
  even a request already marked as a whole-document one."
  [request]
  (update request :headers merge htmx/fragment-headers))
