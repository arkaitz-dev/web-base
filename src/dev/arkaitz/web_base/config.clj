(ns dev.arkaitz.web-base.config
  "EDN configuration, read explicitly and passed in (SPEC §3, §6). The one
  reader tag the base adds, `#wb/env \"VAR\"`, is how a secret such as the
  session key reaches the config from the environment without ever being
  committed. It has no default form on purpose: a default is how a
  development key reaches production (SPEC §11)."
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- env
  "Resolved at read time, so a missing variable fails while the config is
  being loaded, not on the first request that needs it."
  [var-name]
  (when-not (string? var-name)
    (throw (ex-info "#wb/env takes the variable name as a string"
                    {:given var-name})))
  (or (System/getenv var-name)
      (throw (ex-info (str "environment variable " var-name " is not set")
                      {:env var-name}))))

(def readers
  "Public so a host reading its EDN some other way — aero, `ig/read-string` —
  merges the same tags in."
  {'wb/env env})

(defn read-string
  "EDN from a string, with the base's readers."
  [s]
  (edn/read-string {:readers readers} s))

(defn read-resource
  "EDN from a classpath resource; a missing resource is named, not a NPE from
  `slurp` on nil."
  [resource-name]
  (if-let [url (io/resource resource-name)]
    (read-string (slurp url))
    (throw (ex-info (str "config resource " resource-name " not found on the classpath")
                    {:resource resource-name}))))

(defn read-file
  "EDN from a file path."
  [path]
  (let [file (io/file path)]
    (if (.isFile file)
      (read-string (slurp file))
      (throw (ex-info (str "config file " path " not found")
                      {:file (str path)})))))
