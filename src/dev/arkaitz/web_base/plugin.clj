(ns dev.arkaitz.web-base.plugin
  "Plugins: values the host passes under `:plugins`, merged into its config as data. The
  base discovers nothing and calls no plugin on its own — a plugin is the same keys a
  host writes, prepared by a library: routes, sessionless routes, a dictionary, an asset
  root and its stylesheets, and at most one subject function, login path and session.
  `expand` answers the plain config they stand for, and that is all `handler` builds
  from, so what a plugin does is inspectable at the REPL.

  The rules, each refused at construction and named:
  - `:routes` are the host's, then each plugin's, in order; a path two of them claim is
    reitit's conflict, which names both.
  - `:sessionless`, `:assets` and `:stylesheets` are unions; the same path twice is
    refused (the last two by `handler`, which checks the host's own too). Stylesheets come
    in plugin order and the host's last, so the host's cascade wins. A plugin names one
    asset root, `{:path … :root …}`; the host's `:assets` is a vector of them.
  - `:i18n :dict`: each plugin owns the keys it brings under each locale, and two plugins
    bringing one are refused; the host's dictionary is merged over the result, key by
    key, so any string a plugin ships can be replaced. `:default-locale` and `:locale-fn`
    are the host's alone, and a plugin dictionary without a host `:default-locale` is
    refused.
  - `:subject-fn`, `:login-path`, `:session`: the host's value wins — an explicit nil is
    no value — and with none, one plugin may supply it; two are refused.
  - A plugin's routes are top-level siblings of the host's: they carry their own
    `:wb/layouts` and `:wb/gate`, and inherit none of the host's."
  (:require [clojure.string :as str]))

(def ^:private plugin-keys
  #{:wb.plugin/name :routes :sessionless :assets :stylesheets :i18n :subject-fn :login-path :session})

(def ^:private scalar-keys [:subject-fn :login-path :session])

(defn- fail!
  ([message config-key] (fail! message config-key {}))
  ([message config-key data]
   (throw (ex-info (str "web-base: " message) (assoc data :config-key config-key)))))

(defn- route-seq
  "Route data as a sequence of routes: reitit takes one route or a vector of them."
  [routes]
  (cond (nil? routes)             []
        (string? (first routes))  [routes]
        :else                     (vec routes)))

(defn- deep-merge
  "`b` over `a`, map by map. A nil `b` is no value and keeps `a`: a plugin with no
  dictionary, or a host that chose the locale and brought none, must erase nothing."
  [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        (nil? b)                a
        :else                   b))

(defn check-asset!
  "An asset root `{:path \"/name/\" :root \"classpath/prefix\"}`, refused naming `where`:
  a path of one lower-case segment, never the base's `/wb/`."
  [asset where]
  (when-not (and (map? asset) (= #{:path :root} (set (keys asset))))
    (fail! (str (pr-str where) " must be {:path \"/name/\" :root \"classpath/prefix\"}") where {:value asset}))
  (let [{:keys [path root]} asset]
    (when-not (and (string? path) (re-matches #"/[a-z0-9][a-z0-9-]*/" path))
      (fail! (str "an asset :path is one lower-case segment between slashes, like \"/ab/\": " (pr-str path))
             (conj where :path)))
    (when (= "/wb/" path)
      (fail! "an asset :path may not be /wb/, which is the base's own" (conj where :path)))
    (when-not (and (string? root) (not (str/blank? root)))
      (fail! "an asset :root is a classpath prefix" (conj where :root)))))

(defn- check-plugin! [i p]
  (when-not (map? p)
    (fail! (str "plugin " i " is not a map") [:plugins i]))
  (let [named (:wb.plugin/name p)]
    (when-not (keyword? named)
      (fail! (str "plugin " i " needs :wb.plugin/name, a keyword") [:plugins i :wb.plugin/name]))
    (when-let [unknown (not-empty (sort-by pr-str (remove plugin-keys (keys p))))]
      (fail! (str "plugin " named " has unknown key" (when (next unknown) "s") " " (pr-str (vec unknown))
                  " — it takes " (pr-str (vec (sort-by pr-str plugin-keys))))
             [:plugins i (first unknown)]))
    (when-let [assets (:assets p)]
      (check-asset! assets [:plugins i :assets]))
    (when-let [sheets (:stylesheets p)]
      (let [prefix (get-in p [:assets :path])]
        (doseq [href sheets]
          (when-not (and prefix (string? href) (str/starts-with? href prefix))
            (fail! (str "plugin " named " lists stylesheet " (pr-str href) " outside its own :assets path "
                        (pr-str prefix))
                   [:plugins i :stylesheets])))))
    (when-let [i18n (:i18n p)]
      (when-not (and (= #{:dict} (set (keys i18n))) (map? (:dict i18n)) (every? map? (vals (:dict i18n))))
        (fail! (str "plugin " named " brings :i18n as {:dict …}; the locale is the host's to choose")
               [:plugins i :i18n])))
    (when-not (or (nil? (:sessionless p)) (map? (:sessionless p)))
      (fail! (str "plugin " named " :sessionless must be a map of path to handler") [:plugins i :sessionless]))))

(defn- union-refusing
  "`(merge a b)`, refusing a key both hold, naming it and `owner`."
  [a b owner what]
  (reduce-kv (fn [acc k v]
               (when (contains? acc k)
                 (fail! (str owner " brings " what " " (pr-str k) ", which is already taken") [:plugins what k]))
               (assoc acc k v))
             (or a {})
             (or b {})))

(defn- merged-dict
  "The plugins' dictionaries, each owning its keys per locale, with the host's over them."
  [plugins host-dict]
  (let [owned (reduce (fn [acc p]
                        (reduce-kv (fn [acc locale entries]
                                     (reduce (fn [acc k]
                                               (when-let [other (get-in acc [locale k])]
                                                 (fail! (str "plugins " other " and " (:wb.plugin/name p)
                                                             " both bring i18n key " (pr-str k) " under " locale)
                                                        [:plugins :i18n locale k]))
                                               (assoc-in acc [locale k] (:wb.plugin/name p)))
                                             acc (keys entries)))
                                   acc (get-in p [:i18n :dict])))
                      {} plugins)
        dicts (reduce (fn [acc p] (deep-merge acc (get-in p [:i18n :dict]))) {} plugins)]
    (when (seq owned) (deep-merge dicts host-dict))))

(defn expand
  "The plain config `config` stands for, its `:plugins` merged in by the rules of this
  namespace and removed. A config without plugins is answered as it came, less the key."
  [{:keys [plugins] :as config}]
  (when-not (or (nil? (:assets config)) (sequential? (:assets config)))
    (fail! "config :assets is a vector of {:path \"/name/\" :root \"classpath/prefix\"}" [:assets]))
  (if (empty? plugins)
    (dissoc config :plugins)
    (do
      (when-not (sequential? plugins)
        (fail! ":plugins must be a vector of plugin maps" [:plugins]))
      (doseq [[i p] (map-indexed vector plugins)] (check-plugin! i p))
      (let [names (map :wb.plugin/name plugins)]
        (when-let [twice (first (for [[n c] (frequencies names) :when (< 1 c)] n))]
          (fail! (str "plugin " twice " is given twice") [:plugins])))
      (when-not (or (nil? (:sessionless config)) (map? (:sessionless config)))
        (fail! "config :sessionless must be a map of path to handler" [:sessionless]))
      (let [dict       (merged-dict plugins (get-in config [:i18n :dict]))
            routes     (into (route-seq (:routes config)) (mapcat (comp route-seq :routes) plugins))
            sessionless (reduce (fn [acc p] (union-refusing acc (:sessionless p) (str "plugin " (:wb.plugin/name p)) :sessionless))
                                (:sessionless config) plugins)]
        (when (and dict (nil? (get-in config [:i18n :default-locale])))
          (fail! (str "plugins bring dictionaries for " (pr-str (vec (sort (keys dict))))
                      "; the host chooses the locale with :i18n :default-locale")
                 [:i18n :default-locale]))
        (as-> (dissoc config :plugins) cfg
          (cond-> cfg
            (seq routes)      (assoc :routes routes)
            (seq sessionless) (assoc :sessionless sessionless)
            dict              (assoc-in [:i18n :dict] dict))
          (let [assets (into (vec (keep :assets plugins)) (:assets config))
                sheets (into (vec (mapcat :stylesheets plugins)) (:stylesheets config))]
            (cond-> cfg
              (seq assets) (assoc :assets assets)
              (seq sheets) (assoc :stylesheets sheets)))
          (reduce (fn [cfg k]
                    (let [providers (filter #(some? (get % k)) plugins)]
                      (cond
                        (some? (get config k)) cfg
                        (next providers)       (fail! (str "plugins " (pr-str (mapv :wb.plugin/name providers))
                                                           " both supply " k "; give it in the config to choose")
                                                      [:plugins k])
                        (seq providers)        (assoc cfg k (get (first providers) k))
                        :else                  cfg)))
                  cfg scalar-keys))))))
