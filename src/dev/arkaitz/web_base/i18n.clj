(ns dev.arkaitz.web-base.i18n
  "Which language to render, per request (SPEC §14). The host gives a Tempura
  dictionary — its top-level keys are the supported locales — a default
  locale, and optionally a function returning the request's own preference
  list (a choice kept in the session, a cookie, a URL prefix). The base puts
  two things on the request: `:wb/tr`, Tempura's translate function bound to
  the preferences, and `:wb/locale`, the first preference the dictionary
  actually supports, so `<html lang>` never names a language the page is not
  rendered in.

  Read from the request every time, never cached per session: the htmx
  fragment that arrives a second later carries the same headers and must land
  in the same language."
  (:require [clojure.string :as str]
            [taoensso.tempura :as tempura]))

(defn- normalise
  "`EN_gb` → `:en-gb`, empty parts dropped, nothing left → nil. Tempura only
  lowercases a locale while expanding its subtags, so the base normalises
  before handing anything over and `:wb/locale` and `tr` cannot disagree."
  [locale]
  (let [parts (remove str/blank? (str/split (str/lower-case (name locale)) #"[_-]"))]
    (when (seq parts)
      (keyword (str/join "-" parts)))))

(defn- candidates
  "`:en-gb` → (\"en-gb\" \"en\"): the narrowing Tempura applies on lookup."
  [locale]
  (let [parts (str/split (name locale) #"-")]
    (map #(str/join "-" (take % parts)) (range (count parts) 0 -1))))

(defn- dictionary-index
  "Lowercased locale name → the dictionary's own key."
  [dict]
  (into {} (map (fn [k] [(str/lower-case (name k)) k])) (keys dict)))

(defn- resolve-locale [index preferences]
  (some (fn [preference] (some index (candidates preference))) preferences))

(defn- quality
  "1.0 without a q parameter; nil — the entry is unusable — when q is there
  but is not a number."
  [parameters]
  (if-let [q (some #(second (re-matches #"\s*q\s*=(.*)" %)) parameters)]
    (parse-double (str/trim q))
    1.0))

(defn parse-accept-language
  "The header's language tags ordered by descending quality, written order
  among equals; entries with `q=0` or an unparseable q are dropped. A
  malformed header is a client's business, never an exception: it yields no
  preference."
  [header]
  (->> (str/split (str header) #",")
       (map-indexed (fn [index entry]
                      (let [[tag & parameters] (map str/trim (str/split entry #";"))]
                        (when-not (str/blank? tag)
                          (let [q (quality parameters)]
                            (when (and q (pos? q))
                              {:tag tag :q q :index index}))))))
       (remove nil?)
       (sort-by (juxt (comp - :q) :index))
       (mapv :tag)))

(defn- chosen-preferences [locale-fn request]
  (when locale-fn
    (let [chosen (locale-fn request)]
      (when-not (or (nil? chosen) (and (sequential? chosen) (every? some? chosen)))
        (throw (ex-info ":locale-fn must return nil or a sequence of locales"
                        {:config-key [:i18n :locale-fn] :value chosen})))
      chosen)))

(defn- preferences
  [{:keys [locale-fn default-locale]} request]
  (into []
        (keep normalise)
        (concat (chosen-preferences locale-fn request)
                (parse-accept-language (get-in request [:headers "accept-language"]))
                [default-locale])))

(defn- check-config! [{:keys [dict default-locale locale-fn]}]
  (when-not (and (map? dict) (seq dict))
    (throw (ex-info "i18n config needs :dict, a Tempura dictionary keyed by locale"
                    {:config-key [:i18n :dict]})))
  (when-not (contains? dict default-locale)
    (throw (ex-info "i18n :default-locale must be one of the dictionary's locales"
                    {:config-key [:i18n :default-locale]
                     :value      default-locale
                     :locales    (vec (keys dict))})))
  (when (and (some? locale-fn) (not (ifn? locale-fn)))
    (throw (ex-info "i18n :locale-fn must be a function of the request"
                    {:config-key [:i18n :locale-fn] :value locale-fn}))))

(defn- translator
  "`:wb/tr` for one request: `(tr :id)`, `(tr :id args)`, or Tempura's own
  vector of ids with fallbacks, `(tr [:id :other \"literal\"] args)`. A bare id
  is the common call; Tempura only accepts the vector, and its refusal is an
  internal invariant error that names nothing the caller wrote. An id the
  dictionary lacks answers nil."
  [tr prefs]
  (let [ids (fn [id] (if (vector? id) id [id]))]
    (fn
      ([id]      (tr prefs (ids id)))
      ([id args] (tr prefs (ids id) args)))))

(defn wrap
  "Middleware adding `:wb/tr` and `:wb/locale` to every request. Tempura's
  locale cache is off: it is a global memo keyed by the preference list, and
  that list comes from the client."
  [handler {:keys [dict default-locale] :as config}]
  (check-config! config)
  (let [tr    (tempura/new-tr-fn {:dict           dict
                                  :default-locale default-locale
                                  :cache-dict?    true
                                  :cache-locales? false})
        index (dictionary-index dict)]
    (fn [request]
      (let [prefs (preferences config request)]
        (handler (assoc request
                        :wb/tr     (translator tr prefs)
                        :wb/locale (resolve-locale index prefs)))))))
