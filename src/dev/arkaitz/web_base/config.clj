(ns dev.arkaitz.web-base.config
  "EDN configuration, read explicitly and passed in (SPEC §3, §6). The one
  reader tag the base adds, `#wb/env \"VAR\"`, is how a secret such as the
  session key reaches the config from the environment without ever being
  committed. It has no default form on purpose: a default is how a
  development key reaches production (SPEC §11).

  A development machine may name the variables in a file instead of exporting
  them on every start: `env-file-readers`. That file is asked only for what the
  environment does not have, it is never looked for on its own, and what comes
  out of it is logged."
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [clojure.lang ExceptionInfo]))

(defn- check-name! [var-name]
  (when-not (string? var-name)
    (throw (ex-info "#wb/env takes the variable name as a string"
                    {:given var-name}))))

(defn- missing! [var-name]
  (throw (ex-info (str "environment variable " var-name " is not set")
                  {:env var-name})))

(defn- env
  "Resolved at read time, so a missing variable fails while the config is
  being loaded, not on the first request that needs it."
  [var-name]
  (check-name! var-name)
  (or (System/getenv var-name)
      (missing! var-name)))

(def readers
  "Public so a host reading its EDN some other way — aero, `ig/read-string` —
  merges the same tags in."
  {'wb/env env})

(defn- check-variables!
  "A malformed fallback is named here rather than resolving to nil later: the
  variables it holds are the ones nobody notices are missing until a session
  cannot be read."
  [variables source]
  (when-not (map? variables)
    (throw (ex-info (str "variables from " source " must be a map of name to value")
                    {:source source :given variables})))
  (doseq [[var-name value] variables]
    (when-not (string? var-name)
      (throw (ex-info (str "variable name from " source " is not a string")
                      {:source source :name var-name})))
    (when-not (string? value)
      (throw (ex-info (str "value of " var-name " from " source " is not a string")
                      {:source source :name var-name :value value}))))
  variables)

(defn- resolved
  "The value for `var-name`: what the environment holds, then what
  `variables` holds, then a refusal."
  [from-environment variables source var-name]
  (or from-environment
      (when-let [value (get variables var-name)]
        (log/warnf "%s came from %s, not from the environment" var-name source)
        value)
      (missing! var-name)))

(defn env-readers
  "The base's readers, with `variables` — a map of variable name to value —
  consulted for a name the process environment does not have. The environment
  always wins, a name in neither still throws while the config is read, and a
  value taken from `variables` is logged at warn naming `source`: a
  development convenience has to be visible if it ever happens anywhere else.

  A variable the environment holds wins even when its value is empty: an
  operator who exported it named it, and reading a development file instead
  would be the silent substitution this namespace exists to prevent."
  ([variables] (env-readers variables "the variables passed in"))
  ([variables source]
   (check-variables! variables source)
   {'wb/env (fn [var-name]
              (check-name! var-name)
              (resolved (System/getenv var-name) variables source var-name))}))

(defn read-string
  "EDN from a string, with the base's readers or the ones given — the argument
  order of `clojure.edn/read-string`."
  ([s] (read-string readers s))
  ([readers s] (edn/read-string {:readers readers} s)))

(defn- reading
  "`f`, with anything the EDN reader itself refuses named after `source`: a
  hand-written file with a brace missing otherwise fails as a bare `EOF while
  reading`, naming nothing. A refusal from a reader tag — `#wb/env` on a
  variable that is not set — is already named and passes through."
  [source f]
  (try (f)
       (catch ExceptionInfo e (throw e))
       (catch RuntimeException e
         (throw (ex-info (str "config " source " is not valid EDN: " (ex-message e))
                         {:source source} e)))))

(defn read-resource
  "EDN from a classpath resource; a missing resource is named, not a NPE from
  `slurp` on nil."
  ([resource-name] (read-resource readers resource-name))
  ([readers resource-name]
   (if-let [url (io/resource resource-name)]
     (reading resource-name #(read-string readers (slurp url)))
     (throw (ex-info (str "config resource " resource-name " not found on the classpath")
                     {:resource resource-name})))))

(defn read-file
  "EDN from a file path."
  ([path] (read-file readers path))
  ([readers path]
   (let [file (io/file path)]
     (if (.isFile file)
       (reading (str path) #(read-string readers (slurp file)))
       (throw (ex-info (str "config file " path " not found")
                       {:file (str path)}))))))

(defn env-file-readers
  "`env-readers` over the EDN map in the file at `path`, which the operator
  keeps out of the repository. An absent file is not an error — it is the
  production case — and yields `readers` unchanged. The file is never looked
  for unless a host asks for it by name, and it is read with the plain
  `readers`, so a `#wb/env` inside it still means the environment."
  [path]
  (if (.isFile (io/file path))
    ;; The absolute path, so a stray file is findable from the log line alone.
    (env-readers (read-file path) (.getAbsolutePath (io/file path)))
    readers))
