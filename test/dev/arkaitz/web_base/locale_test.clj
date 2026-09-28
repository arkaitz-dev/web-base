(ns dev.arkaitz.web-base.locale-test
  "The JVM's default locale must not reach anything the base compares or emits. Under
  a Turkish default `i` upper-cases to `İ` and `I` lower-cases to `ı`, so every test
  here runs under tr-TR and first proves that the switch took effect — without that
  witness a machine whose conversions ignore the default would pass vacuously."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.testing :as testing]
            [ring.mock.request :as mock])
  (:import [java.util Locale]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- under-turkish
  "`f` with tr-TR as the JVM default locale, restored whatever happens."
  [f]
  (let [before (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
      (is (= ["OPTİONS" "en-ıe"] [(str/upper-case "options") (str/lower-case "en-IE")])
          "precondition: the default locale is Turkish, so a default-locale conversion would show")
      (f)
      (finally (Locale/setDefault before)))))

(def ^:private app
  (wb/handler {:routes  [["/page" {:get (fn [request] {:status 200 :body [:p (pr-str (:wb/locale request))]})}]
                         ["/framed" {:get (fn [_] {:status 200 :headers {"X-FRAME-OPTIONS" "SAMEORIGIN"} :body "framed"})}]]
               :session {:key KEY}
               :csrf    false
               :i18n    {:dict {:en {} :en-ie {}} :default-locale :en}}))

(deftest a-405-lists-its-methods-in-ascii-under-a-turkish-default-locale
  (under-turkish
   #(let [r (app (mock/request :put "/page"))]
      (is (= 405 (:status r)) "precondition: a method the route lacks")
      (is (= "GET, OPTIONS" (get-in r [:headers "Allow"]))))))

(deftest accept-language-with-a-capital-i-resolves-its-own-locale-under-a-turkish-default-locale
  (under-turkish
   #(let [r (app (-> (mock/request :get "/page") (mock/header "Accept-Language" "en-IE")))]
      (is (= 200 (:status r)) "precondition: the page renders")
      (is (= "<!DOCTYPE html>\n<p>:en-ie</p>" (:body r))
          "en-IE is the dictionary's :en-ie, not narrowed to :en"))))

(deftest a-handler-s-upper-case-security-header-is-not-doubled-under-a-turkish-default-locale
  (under-turkish
   #(let [headers (:headers (app (mock/request :get "/framed")))]
      (is (= {"X-FRAME-OPTIONS" "SAMEORIGIN"}
             (into {} (filter (fn [[k _]] (= "x-frame-options" (.toLowerCase ^String k Locale/ROOT)))) headers))
          "the handler's header is kept and the base adds no second one"))))

(deftest testing-cookies-reads-an-upper-case-set-cookie-under-a-turkish-default-locale
  (under-turkish
   #(is (= {"a" "1"} (testing/cookies {:status 200 :headers {"SET-COOKIE" ["a=1; Path=/"]}})))))
