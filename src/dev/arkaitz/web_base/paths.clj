(ns dev.arkaitz.web-base.paths
  "Paths built from a route's `:name`, never spelt by hand: a route moved moves every
  link, redirect and mail with it. The router is the one the base built, read from a
  request it answered (`:reitit.core/router`), from the handler `handler` returned (its
  metadata), or given as a router (`wb/router`)."
  (:require [reitit.core :as r]))

(defn- router-of [source route-name]
  (cond
    (map? source)                (or (::r/router source)
                                     (throw (ex-info (str "web-base: path-for " route-name ": this request was not"
                                                          " answered by the router — a sessionless route or an asset —"
                                                          " so it carries none; pass the handler `wb/handler` returned, or `wb/router`'s router")
                                                     {:route route-name})))
    (fn? source)                 (or (::r/router (meta source))
                                     (throw (ex-info (str "web-base: path-for " route-name ": this function carries no"
                                                          " router; pass the handler `wb/handler` returned")
                                                     {:route route-name})))
    (satisfies? r/Router source) source
    :else (throw (ex-info (str "web-base: path-for " route-name ": the source must be a request, the handler or a router")
                          {:route route-name}))))

(defn- given?
  "Whether `v` fills a path parameter: an empty string would build a path whose segment
  is gone, which no route answers."
  [v]
  (and (some? v) (not= "" v)))

(defn path-for
  "The path of the route named `route-name`, its path `params` in place and `query`
  encoded after it. `source` is a request the router answered, the handler `handler`
  returned, or a router. A name the router does not know, or a path parameter it needs
  and was not given — nil, or an empty string — is refused naming the route: never a
  link to nowhere."
  ([source route-name] (path-for source route-name nil nil))
  ([source route-name params] (path-for source route-name params nil))
  ([source route-name params query]
   (let [match   (r/match-by-name (router-of source route-name) route-name params)
         ;; A partial match names what it lacks; a full one may still carry an empty
         ;; string reitit took as given.
         missing (some->> (if (r/partial-match? match)
                            (:required match)
                            (keys (:path-params match)))
                          (remove #(given? (get params %)))
                          seq set)]
     (when (or (nil? match) missing)
       (throw (ex-info (str "web-base: no path for route " route-name) {:route route-name :missing missing})))
     (r/match->path match query))))
