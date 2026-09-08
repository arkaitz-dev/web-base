(ns dev.arkaitz.web-base.i18n-test
  "Every triple below is `[locale greeting only-en]` as one handler saw it: the
  resolved locale and Tempura's answer are pinned together so they cannot
  disagree unnoticed."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.i18n :as i18n]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

;; :zh-Hans is deliberately mixed-case: `:wb/locale` must be the dictionary's
;; own key, never a lowercased copy.
(def ^:private D {:en      {:greet "Hello %1" :only-en "en-only"}
                  :es      {:greet "Hola %1"}
                  :zh-Hans {:greet "你好 %1"}
                  :zh      {:greet "zh %1"}})

(defn- seen [request]
  [(:wb/locale request)
   ((:wb/tr request) [:greet] ["Ann"])
   ((:wb/tr request) [:only-en])])

(defn- app-with [config] (i18n/wrap seen (merge {:dict D} config)))
(def ^:private app-es (app-with {:default-locale :es}))
(def ^:private app-en (app-with {:default-locale :en}))

(defn- get* [& headers]
  (reduce (fn [request [k v]] (mock/header request k v)) (mock/request :get "/") (partition 2 headers)))

(defn- accept [app header]
  (app (if header (get* "Accept-Language" header) (get*))))

(def ^:private EN [:en "Hello Ann" "en-only"])
(def ^:private ES [:es "Hola Ann" nil])

(deftest config-is-checked-at-construction--dict-default-locale-and-locale-fn-name-their-config-key
  (let [attempt (fn [config] (try (i18n/wrap identity config) ::constructed
                                  (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        no-dict ["i18n config needs :dict, a Tempura dictionary keyed by locale" {:config-key [:i18n :dict]}]
        bad-def (fn [value] ["i18n :default-locale must be one of the dictionary's locales"
                             {:config-key [:i18n :default-locale] :value value :locales [:en :es :zh-Hans :zh]}])]
    (doseq [config [{} {:dict {} :default-locale :en} {:dict nil :default-locale :en} {:dict [[:en {}]] :default-locale :en}]]
      (is (= no-dict (attempt config)) (str "no dictionary: " (pr-str config))))
    (doseq [[config value] [[{:dict D} nil] [{:dict D :default-locale :fr} :fr]
                            [{:dict D :default-locale "en"} "en"] [{:dict D :default-locale :EN} :EN]]]
      (is (= (bad-def value) (attempt config)) (str "default not a dictionary key: " (pr-str value))))
    (is (= ["i18n :locale-fn must be a function of the request" {:config-key [:i18n :locale-fn] :value "en"}]
           (attempt {:dict D :default-locale :es :locale-fn "en"}))
        "a non-callable :locale-fn is refused")
    (let [calls (atom 0)]
      (is (= ::constructed (attempt {:dict D :default-locale :es}))
          "a valid config constructs")
      (is (fn? (i18n/wrap (fn [_] (swap! calls inc)) {:dict D :default-locale :es})) "wrap returns the handler")
      (is (= 0 @calls) "without calling the inner one"))))

(deftest every-request-gets-wb-tr-and-wb-locale-and-nothing-else-changes
  (let [bare (i18n/wrap #(dissoc % :wb/tr) {:dict D :default-locale :es})]
    (is (= {:uri "/x" :headers {"accept-language" "en-GB"} :wb/locale :en}
           (bare {:uri "/x" :headers {"accept-language" "en-GB"}}))
        "exactly :wb/locale added (plus :wb/tr), nothing else touched")
    (is (= {:uri "/" :wb/locale :es} (bare {:uri "/"})) "a request without :headers gets the default"))
  (is (fn? (:wb/tr ((i18n/wrap identity {:dict D :default-locale :es}) {}))) ":wb/tr is a function")
  (is (= ES (app-es {:uri "/"})) "and it answers, bound to the default"))

(deftest accept-language-alone--first-supported-wins--q-order-beats-written-order--ties-keep-written-order
  (doseq [[header expected] [["en"                             EN]
                             ["de"                             ES]
                             ["de, en;q=0.5"                   EN]
                             ["de;q=0.3, es;q=0.8, en;q=0.9"   EN]
                             ["es;q=0.8, en"                   EN]
                             ["en;q=0.8, es"                   [:es "Hola Ann" "en-only"]]
                             ["es;q=0.9, en;q=0.9"             [:es "Hola Ann" "en-only"]]
                             ["en;q=0.9, es;q=0.9"             EN]
                             ["fr, en-GB;q=0.5, es;q=0.7"      [:es "Hola Ann" "en-only"]]
                             ["*"                              ES]
                             ["en;q=0"                         ES]
                             ["en;q=0, es"                     ES]]]
    (testing header
      (is (= expected (accept app-es header)))))
  (is (= EN (app-es {:headers {"accept-language" "en"}})) "hand-built lowercase header, as Ring delivers it"))

(deftest parse-accept-language-orders-by-quality-and-drops-what-it-cannot-use
  (doseq [[header expected] [[nil                                    []]
                             [""                                     []]
                             ["en"                                   ["en"]]
                             ["de;q=0.3, es;q=0.8, en;q=0.9"         ["en" "es" "de"]]
                             ["es;q=0.9, en;q=0.9"                   ["es" "en"]]
                             ["en;q=0"                               []]
                             ["en;q=abc, es"                         ["es"]]
                             ["en ; q = 0.5 , es"                    ["es" "en"]]
                             [";"                                    []]
                             [";;,"                                  []]
                             [", ,"                                  []]
                             ["en;"                                  ["en"]]
                             ["en;q="                                []]
                             ["en;level=1;q=0.5, es"                 ["es" "en"]]]]
    (testing (pr-str header)
      (is (= expected (i18n/parse-accept-language header))))))

(deftest wb-locale-is-the-dictionary-s-own-key--region-and-script-narrow--case-and-underscore-normalise--tr-agrees
  (doseq [[header expected] [["en-GB"      EN]
                             ["EN"         EN]
                             ["EN-GB"      EN]
                             ["en_GB"      EN]
                             ["en-"        EN]
                             ["zh-Hans-CN" [:zh-Hans "你好 Ann" nil]]
                             ["zh-hans-cn" [:zh-Hans "你好 Ann" nil]]
                             ["zh-CN"      [:zh "zh Ann" nil]]]]
    (testing header
      (let [[locale :as triple] (accept app-es header)]
        (is (= expected triple))
        (is (contains? D locale) "the locale is a dictionary key, verbatim")))))

(deftest locale-fn-s-list-is-honoured-in-full-before-the-header--and-sees-the-request-once
  (let [with-fn (fn [f] (app-with {:default-locale :es :locale-fn f}))]
    (doseq [[choice header expected] [[["en"]     "es" EN]
                                      [["de" "zh"] "en" [:zh "zh Ann" "en-only"]]
                                      [[:EN]      "es" EN]
                                      ['("en")    "es" EN]
                                      [["de"]     "en" EN]
                                      [["fr"]     "de" ES]
                                      [nil        "en" EN]
                                      [[]         "en" EN]
                                      [["en"]     nil  EN]]]
      (testing (str (pr-str choice) " with header " (pr-str header))
        (is (= expected (accept (with-fn (constantly choice)) header)))))
    (let [calls (atom [])
          app   (with-fn (fn [request] (swap! calls conj (select-keys request [:uri :request-method :headers])) ["de" "zh"]))]
      (app (get* "Accept-Language" "en"))
      (is (= [{:uri "/" :request-method :get :headers {"host" "localhost" "accept-language" "en"}}] @calls)
          "called once, with the request as received")))
  (doseq [bad [:en [nil] ["en" nil]]]
    (is (= [":locale-fn must return nil or a sequence of locales" {:config-key [:i18n :locale-fn] :value bad}]
           (try ((app-with {:default-locale :es :locale-fn (constantly bad)}) (get*))
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str "a bad return value is named: " (pr-str bad)))))

(deftest the-default-is-last-and-never-nil--and-does-not-pre-empt-a-supported-preference
  (doseq [[header expected] [[nil  [ES EN]]
                             ["de" [ES EN]]
                             ["es" [ES [:es "Hola Ann" "en-only"]]]
                             ["en" [EN EN]]]]
    (testing (pr-str header)
      (is (= expected [(accept app-es header) (accept app-en header)])))))

(deftest a-partial-with-the-same-headers-lands-in-the-same-language--and-one-handler-answers-each-request-by-its-own-headers
  (is (= [EN EN ES EN [:zh-Hans "你好 Ann" nil] EN]
         [(app-es (get* "Accept-Language" "en-GB"))
          (app-es (get* "Accept-Language" "en-GB" "HX-Request" "true"))
          (app-es (get* "Accept-Language" "de"))
          (app-es (get* "Accept-Language" "en-GB" "HX-Request" "true" "HX-Request-Type" "full"))
          (app-es (get* "Accept-Language" "zh-Hans-CN" "HX-Request" "true"))
          (app-es (get* "Accept-Language" "en-GB"))])
      "one instance, six requests, each answered by its own headers; htmx changes nothing"))

(deftest tr-resources--missing-is-nil-not-a-throw--missing-entry-searched-through-the-list--inline-fallback--per-key-fallthrough
  (let [tr-of (fn [config header ids & [args]]
                ((i18n/wrap (fn [r] [(:wb/locale r) ((:wb/tr r) ids args)]) (merge {:dict D} config))
                 (get* "Accept-Language" header)))]
    (is (= [:en nil] (tr-of {:default-locale :es} "en" [:nope])) "a missing id is nil, no exception")
    (is (= [:en "Fallback"] (tr-of {:default-locale :es} "en" [:nope "Fallback"])) "inline fallback")
    (is (= [:en "Hello Z"] (tr-of {:default-locale :es} "en" [:nope :greet] ["Z"])) "the second id is searched")
    (is (= [:en "[es?]"] (tr-of {:dict (assoc-in D [:es :missing] "[es?]") :default-locale :es} "en" [:nope]))
        ":missing found in the default while the locale stays :en")
    (is (= [:en "[en?]"] (tr-of {:dict (-> D (assoc-in [:en :missing] "[en?]") (assoc-in [:es :missing] "[es?]")) :default-locale :es} "en" [:nope]))
        "the first locale's :missing wins")
    (is (= [:es "Hola Ann" "en-only"] (accept app-en "es")) "a key absent in :es is answered from the default; the locale is still :es")))

(deftest a-malformed-accept-language-never-throws--it-is-treated-as-no-preference
  (doseq [[header expected] [[";" ES] [";;," ES] ["" ES] [", ," ES] ["en;" EN] ["en;q=" ES] ["-en" EN]]]
    (testing (pr-str header)
      (is (= expected (try (accept app-es header) (catch Throwable t [::threw (class t)]))))))
  (is (= EN (accept (app-with {:default-locale :es :locale-fn (constantly ["en"])}) ";"))
      "the host's choice survives a broken header"))

(deftest wb-tr-takes-a-bare-id-or-tempuras-vector--with-or-without-args--and-a-missing-id-is-still-nil
  ;; D carries no :missing key on purpose: a nil here is the absence of any
  ;; fallback layer, not a fallback text.
  (let [capture (fn [{tr :wb/tr}]
                  {:bare      (tr :only-en)
                   :bare-args (tr :greet ["Ann"])
                   :vectors   [(tr [:nope :greet] ["Z"]) (tr [:greet] ["Q"])]
                   :missing   [(tr :nope) (tr [:nope])]
                   :control   (tr [:only-en])})
        seen    ((i18n/wrap capture {:dict D :default-locale :es}) (get* "Accept-Language" "en"))]
    (is (= "en-only" (:control seen)) "control: today's vector shape still answers")
    (is (= "en-only" (:bare seen)) "(tr :id) — a bare id is wrapped into Tempura's vector")
    (is (= "Hello Ann" (:bare-args seen)) "(tr :id args) — with arguments")
    (is (= ["Hello Z" "Hello Q"] (:vectors seen)) "vector ids are passed through untouched, with args")
    (is (= [nil nil] (:missing seen)) "a missing id is nil through both shapes: no fallback layer")))
