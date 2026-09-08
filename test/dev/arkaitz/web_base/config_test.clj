(ns dev.arkaitz.web-base.config-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.tools.logging.test :as lt]
            [dev.arkaitz.web-base.config :as config])
  (:import [clojure.lang ExceptionInfo]))

(defn- attempt [f]
  (try (f) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(def ^:private fallback-file (io/file (io/resource "env-fallback.edn")))

(defn- entries []
  (mapv (fn [{:keys [logger-ns level throwable message]}] [(ns-name logger-ns) level throwable message])
        (lt/the-log)))

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

(deftest missing-resource-and-file-are-named-not-a-nil-slurp--and-so-is-edn-that-does-not-parse
  (is (= ["config resource nope/none.edn not found on the classpath" {:resource "nope/none.edn"}]
         (attempt #(config/read-resource "nope/none.edn"))))
  (is (= ["config file /nope/none.edn not found" {:file "/nope/none.edn"}]
         (attempt #(config/read-file "/nope/none.edn"))))
  ;; A file the operator writes by hand: a brace missing is the likely
  ;; failure, and the reader's own message names nothing.
  (is (= ["config test/resources/malformed-edn.txt is not valid EDN: EOF while reading string"
          {:source "test/resources/malformed-edn.txt"}]
         (attempt #(config/read-file "test/resources/malformed-edn.txt")))
      "the reader's refusal is named after the file")
  (is (= "EOF while reading string"
         (ex-message (ex-cause (try (config/read-file "test/resources/malformed-edn.txt")
                                    (catch ExceptionInfo e e)))))
      "with the reader's exception kept as the cause")
  ;; A tag's own refusal is already named: it must pass through rather than
  ;; be reported as unparseable EDN.
  (is (= ["from the tag" {:k 1}]
         (attempt #(config/read-resource {'wb/env (fn [_] (throw (ex-info "from the tag" {:k 1})))}
                                         "probe-config.edn")))
      "a reader tag's refusal passes through untouched"))

(deftest env-file-readers-asks-the-file-only-for-what-the-environment-lacks--and-says-so-at-warn
  (let [home (System/getenv "HOME")]
    (is (not (str/blank? home)) "precondition: HOME is set in this JVM")
    (is (nil? (System/getenv "WB_TEST_FALLBACK"))
        "precondition: WB_TEST_FALLBACK is not exported — unset it in this shell and run again")
    (is (.isFile fallback-file) "precondition: the fixture is on the test classpath")
    (lt/with-log
      (is (= {:h home :v "from-the-file"}
             (config/read-string (config/env-file-readers fallback-file)
                                 "{:h #wb/env \"HOME\" :v #wb/env \"WB_TEST_FALLBACK\"}"))
          "HOME is the environment's, not the file's \"not-this-one\"; WB_TEST_FALLBACK is the file's")
      (is (= [['dev.arkaitz.web-base.config :warn nil
               (str "WB_TEST_FALLBACK came from " fallback-file ", not from the environment")]]
             (entries))
          "exactly one warn: the fallback hit names the variable and the file, the environment hit says nothing")))
  ;; Named relatively, logged absolutely: a stray file has to be findable
  ;; from the line alone.
  (lt/with-log
    (let [relative "test/resources/env-fallback.edn"]
      (is (= {:v "from-the-file"}
             (config/read-string (config/env-file-readers relative) "{:v #wb/env \"WB_TEST_FALLBACK\"}")))
      (is (= [['dev.arkaitz.web-base.config :warn nil
               (str "WB_TEST_FALLBACK came from " (.getAbsolutePath (io/file relative))
                    ", not from the environment")]]
             (entries))
          "the warn names the absolute path even when the host named a relative one"))))

(def ^:private through-the-public-path
  "Resolves WB_TEST_FALLBACK through env-file-readers and prints it, so the
  child's own `System/getenv` is the one consulted."
  (str "(require 'dev.arkaitz.web-base.config)"
       "(print (pr-str ((get (dev.arkaitz.web-base.config/env-file-readers"
       " \"test/resources/env-fallback.edn\") 'wb/env) \"WB_TEST_FALLBACK\")))"))

(defn- in-a-child-jvm
  "`expr` in a JVM on this classpath, with `environment` added to this
  process's own, and its stdout. A JVM cannot export a variable to itself, so
  a child is the only place where the real `System/getenv` can be seen
  deciding."
  [environment expr]
  (let [answer (deref (future (shell/sh "java" "-cp" (System/getProperty "java.class.path")
                                        "clojure.main" "-e" expr
                                        :env (merge (into {} (System/getenv)) environment)))
                      120000 ::timeout)]
    (is (not= ::timeout answer) "the child JVM answered within the deadline")
    (when (map? answer)
      (is (= 0 (:exit answer)) (str "the child JVM exited cleanly: " (:err answer)))
      (:out answer))))

(deftest an-environment-variable-that-is-set-but-empty-still-wins-over-the-file
  ;; Reversing this — treating "" as absent — would let a development file
  ;; sign sessions for an operator who did export the variable, which is the
  ;; substitution SPEC §11 is about. It is observed through the public path,
  ;; in a child, because the decision sits at the `System/getenv` call.
  (is (= "\"from-the-file\"" (in-a-child-jvm {} through-the-public-path))
      "control: unset in the child, so the file supplies it — the probe works")
  (is (= "\"\"" (in-a-child-jvm {"WB_TEST_FALLBACK" ""} through-the-public-path))
      "exported empty: the environment's own value, and the file is never consulted")
  ;; The decision itself, so a failure above says which half broke.
  (lt/with-log
    (is (= "" (#'config/resolved "" {"X" "from-the-file"} "src" "X")) "the rule, at the point it is applied")
    (is (= [] (entries)) "and nothing is logged, because the file was never consulted"))
  (is (= ["environment variable X is not set" {:env "X"}]
         (attempt #(#'config/resolved nil {} "src" "X")))
      "in neither: named, never a nil"))

(deftest through-the-file-readers-a-variable-in-neither-place-is-still-named-and-a-symbol-still-refused
  (is (.isFile fallback-file) "precondition: the fixture is on the test classpath")
  (lt/with-log
    (let [rs (config/env-file-readers fallback-file)]
      (is (= ["environment variable WB_SURELY_UNSET_XYZ is not set" {:env "WB_SURELY_UNSET_XYZ"}]
             (attempt #(config/read-string rs "{:x #wb/env \"WB_SURELY_UNSET_XYZ\"}")))
          "a name in neither the environment nor the file is named — still no default, ever")
      (is (= ["#wb/env takes the variable name as a string" {:given 'HOME}]
             (attempt #(config/read-string rs "{:x #wb/env HOME}")))
          "a symbol is refused through the fallback readers too")
      (is (= [] (entries)) "neither refusal logs: there was no fallback hit"))))

(deftest env-file-readers-on-an-absent-path-is-the-plain-readers-themselves
  (let [absent "/nope/none.local.edn"]
    (is (not (.exists (io/file absent))) "precondition: the path really is absent")
    (is (identical? config/readers (config/env-file-readers absent))
        "an absent file is the production case, not an error: config/readers itself, from a string path")
    (is (identical? config/readers (config/env-file-readers (io/file absent))) "and from a File"))
  ;; Not only an absent path: anything that is not a regular file. A directory
  ;; named where the file should be must not become an error either.
  (let [directory (io/file "test/resources")]
    (is (.isDirectory directory) "precondition: the path is a directory that exists")
    (is (identical? config/readers (config/env-file-readers directory))
        "a directory is not a file to read: still config/readers, never an error")))

(deftest a-malformed-variables-map-is-refused-when-the-readers-are-built--naming-the-source
  (is (= ["variables from probe must be a map of name to value" {:source "probe" :given [1 2]}]
         (attempt #(config/env-readers [1 2] "probe")))
      "not a map: refused when the readers are built, not on the first tag")
  (is (= ["variable name from probe is not a string" {:source "probe" :name :HOME}]
         (attempt #(config/env-readers {:HOME "x"} "probe")))
      "a name that is not a string")
  (is (= ["value of HOME from probe is not a string" {:source "probe" :name "HOME" :value 1}]
         (attempt #(config/env-readers {"HOME" 1} "probe")))
      "a value that is not a string")
  ;; Every entry, not only the first: a good pair before a bad one is the
  ;; shape a real file has.
  (is (= ["value of SECOND from probe is not a string" {:source "probe" :name "SECOND" :value 2}]
         (attempt #(config/env-readers (array-map "FIRST" "fine" "SECOND" 2) "probe")))
      "a bad entry after a good one is still refused")
  (is (= ["variable name from probe is not a string" {:source "probe" :name :second}]
         (attempt #(config/env-readers (array-map "FIRST" "fine" :second "x") "probe")))
      "and so is a bad name after a good one")
  (is (= ["variables from the variables passed in must be a map of name to value"
          {:source "the variables passed in" :given nil}]
         (attempt #(config/env-readers nil)))
      "the one-argument form names its default source")
  (is (= #{'wb/env} (set (keys (config/env-readers {} "probe"))))
      "control: an empty map is a map — the refusal is of shape, not of emptiness"))

(deftest readers-first-arities-read-with-the-readers-they-are-given
  ;; A marker no environment and no production reader can produce.
  (let [marker {'wb/env (fn [var-name] (str "seen:" var-name))}]
    (is (= {:home "seen:HOME"} (config/read-string marker "{:home #wb/env \"HOME\"}"))
        "read-string uses the readers given")
    (is (= {:home "seen:HOME" :n 1} (config/read-resource marker "probe-config.edn"))
        "read-resource passes them through")
    (is (= {:home "seen:HOME" :n 1} (config/read-file marker (io/file (io/resource "probe-config.edn"))))
        "read-file passes them through")
    (is (= "No reader function for tag wb/env"
           (try (config/read-string {} "{:x #wb/env \"HOME\"}") ::no-throw
                (catch RuntimeException e (ex-message e))))
        "the readers given replace the base's, they are not merged with them")))
