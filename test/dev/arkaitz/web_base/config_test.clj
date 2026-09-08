(ns dev.arkaitz.web-base.config-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base.config :as config])
  (:import [clojure.lang ExceptionInfo]))

(defn- attempt [f]
  (try (f) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(deftest wb-env-resolves-at-read-time-from-the-environment--and-names-a-missing-variable
  (let [home (System/getenv "HOME")]
    (is (not (str/blank? home)) "precondition: HOME is set in this JVM")
    (is (= {:home home} (config/read-string "{:home #wb/env \"HOME\"}")) "a present variable yields its value")
    (is (= {:home home :n 1} (config/read-resource "probe-config.edn")) "through a classpath resource")
    (is (= {:home home :n 1} (config/read-file (io/file (io/resource "probe-config.edn")))) "through a file")
    (is (= ["environment variable WB_SURELY_UNSET_XYZ is not set" {:env "WB_SURELY_UNSET_XYZ"}]
           (attempt #(config/read-string "{:x #wb/env \"WB_SURELY_UNSET_XYZ\"}")))
        "a missing variable is named — no default, ever")
    (is (= ["#wb/env takes the variable name as a string" {:given 'HOME}]
           (attempt #(config/read-string "{:x #wb/env HOME}")))
        "a symbol is refused")
    (is (= {:home home} (edn/read-string {:readers config/readers} "{:home #wb/env \"HOME\"}"))
        "readers is public: a host reading its EDN with clojure.edn merges the same tag in")))

(deftest missing-resource-and-file-are-named-not-a-nil-slurp
  (is (= ["config resource nope/none.edn not found on the classpath" {:resource "nope/none.edn"}]
         (attempt #(config/read-resource "nope/none.edn"))))
  (is (= ["config file /nope/none.edn not found" {:file "/nope/none.edn"}]
         (attempt #(config/read-file "/nope/none.edn")))))
