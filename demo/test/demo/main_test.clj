(ns demo.main-test
  "The demo's entry point reads its variables from the file it is given. The
  zero-argument form depends on the developer's working tree, so what is
  pinned here is the path it is handed and the default's shape."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.tools.logging.test :as lt]
            [demo.main :as main]
            [integrant.core :as ig])
  (:import [clojure.lang ExceptionInfo]))

(defn- entries []
  (mapv (fn [{:keys [logger-ns level throwable message]}] [(ns-name logger-ns) level throwable message])
        (lt/the-log)))

(deftest read-config-takes-its-variables-from-the-file-it-is-given--and-without-one-only-from-the-environment
  (is (nil? (System/getenv "WB_SESSION_KEY"))
      "precondition: WB_SESSION_KEY is not exported — unset it in this shell and run again")
  (let [path (str (io/file (io/resource "env-fallback.edn")))]
    (lt/with-log
      (let [config (main/read-config path)]
        (is (= "AAECAwQFBgcICQoLDA0ODw==" (get-in config [:demo/web-config :session-key]))
            "the session key is the file's")
        (is (ig/ref? (get-in config [:demo/web-config :store]))
            "read by integrant's reader, so #ig/ref survived"))
      (is (= ["environment variable WB_SESSION_KEY is not set" {:env "WB_SESSION_KEY"}]
             (try (main/read-config "/nope/none.local.edn") ::no-throw
                  (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
          "an absent file: the environment is the only source, and it has no key")
      (is (= [['dev.arkaitz.web-base.config :warn nil
               (str "WB_SESSION_KEY came from " path ", not from the environment")]]
             (entries))
          "one warn, for the file case only"))))

(deftest the-default-file-name-is-one-the-repository-ignores
  ;; The whole design rests on this file staying out of the repository; a
  ;; default renamed to something committable is the SPEC §11 leak in one line.
  ;; git itself is the oracle: a pattern in .gitignore proves nothing if a
  ;; later negation exempts this very name.
  (let [git     (fn [& args] (apply shell/sh (concat (cons "git" args) [:dir (System/getProperty "user.dir")])))
        tree    (git "rev-parse" "--is-inside-work-tree")
        ignored (git "check-ignore" "-v" main/env-file)
        control (git "check-ignore" "-q" "demo/src/demo/main.clj")]
    (is (= 0 (:exit tree)) (str "precondition: the suite runs inside a git work tree: " (:err tree)))
    (is (= 1 (:exit control)) "control: git answers 1 for a file it does not ignore, so the oracle is live")
    (is (= 0 (:exit ignored)) (str "git ignores " main/env-file ": " (:err ignored)))
    (is (str/starts-with? (:out ignored) ".gitignore:")
        (str "and the rule is in .gitignore, not in a local exclude a clone would not have: " (:out ignored)))
    (is (str/ends-with? main/env-file ".local.edn") "the name follows the .local.edn convention")))
