(ns dev.arkaitz.web-base.integrant
  "Optional: Integrant methods for a host that uses it (SPEC §10). The only
  namespace of the base that requires integrant; nothing else depends on it,
  and a host without Integrant calls `dev.arkaitz.web-base/handler` and
  `start` itself.

  A config for `::handler` holds functions — routes, the subject function,
  layouts — which EDN cannot express, so a host writes it as an Integrant
  component of its own and refers to it, or builds the map in code:

      {::wb/handler {:routes #ig/ref :my/routes :session {...} ...}
       ::wb/server  {:handler #ig/ref ::wb/handler :port 3000}}

  `config/readers` is merged in so `#wb/env` works in the same EDN, and the
  two-argument form takes readers of the host's own."
  (:refer-clojure :exclude [read-string run!])
  (:require [clojure.java.io :as io]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.config :as config]
            [integrant.core :as ig]))

(defn read-string
  "Integrant's reader with the base's tags added, or with the readers given —
  `config/env-file-readers` for a development machine, say."
  ([s] (read-string config/readers s))
  ([readers s] (ig/read-string {:readers readers} s)))

(defmethod ig/init-key ::wb/handler [_ config]
  (wb/handler config))

(defmethod ig/init-key ::wb/server [_ {:keys [handler] :as options}]
  (wb/start handler (dissoc options :handler)))

(defmethod ig/halt-key! ::wb/server [_ handle]
  (wb/stop handle))

;; --- running a host ---------------------------------------------------------

(defn- port-of
  "`arg` as a port number, or nil when it is not one from 1 to 65535."
  [arg]
  (when-let [n (some-> arg parse-long)]
    (when (<= 1 n 65535) n)))

(defn- system-config
  "`[:ok config]` for `args`, or `[:error message]` naming what is wrong. The resource is
  read with the readers of `env-file` when the host named one — never a file it did not
  name — and a port given as the first argument is put at `port-path`."
  [{:keys [config env-file port-path]} args]
  (let [port (first args)]
    (cond
      (not (string? config))
      [:error (str ":config must name a classpath resource, not " (pr-str config))]

      (not (or (nil? port-path) (and (vector? port-path) (seq port-path))))
      [:error (str ":port-path must be a vector of keys, not " (pr-str port-path))]

      (and port (nil? port-path))
      [:error (str "this host takes no port, and was given " (pr-str port))]

      (and port (nil? (port-of port)))
      [:error (str "the port must be a number from 1 to 65535, not " (pr-str port))]

      (nil? (io/resource config))
      [:error (str "config resource " config " not found on the classpath")]

      :else
      ;; A reader's refusal — a variable nobody set, a malformed file — is the host's
      ;; to read as a sentence, not as a stack trace. The EDN reader throws a plain
      ;; RuntimeException for bad syntax, the base's own readers an ExceptionInfo.
      (try
        [:ok (cond-> (read-string (if env-file (config/env-file-readers env-file) config/readers)
                                  (slurp (io/resource config)))
               port (assoc-in port-path (port-of port)))]
        (catch clojure.lang.ExceptionInfo e
          [:error (ex-message e)])
        (catch RuntimeException e
          [:error (str "config resource " config " is not valid EDN: " (ex-message e))])))))

(defn- message-of
  "What a failure says, or its class when it says nothing."
  [^Throwable t]
  (or (ex-message t) (.getName (class t))))

(defn- key-failure
  "The message of the key's own exception under Integrant's wrapper, which Integrant
  puts around a failure to init and a failure to halt alike."
  [^Throwable t]
  (message-of (or (ex-cause t) t)))

(defn- start
  "`[:ok system]`, or `[:error message]` with whatever had started halted — `ig/init`
  leaves a partial system behind when a key throws, and nobody else holds it.

  The message is the failing key's own exception, just under Integrant's wrapper: never
  Integrant's data, which carries the whole resolved configuration of that key, and not
  the innermost cause either, which is a driver's and says whatever the driver says."
  [config]
  (try
    [:ok (ig/init config)]
    (catch clojure.lang.ExceptionInfo e
      (let [partial (:system (ex-data e))
            halted  (when partial
                      (try (ig/halt! partial) nil
                           (catch Throwable t
                             (str "; halting what had started also failed: " (key-failure t)))))]
        [:error (str "failed to start: " (key-failure e) halted)]))))

(defn run!
  "The whole of a host's `-main`: reads `config` (a classpath resource), with the readers
  of `env-file` when named, puts a port given as the first of `args` at `port-path`,
  starts the system, prints `(banner system)`, halts it on shutdown, and blocks the
  calling thread — the server's threads do not keep a JVM alive on their own.

    (defn -main [& args]
      (wbi/run! {:config    \"config.edn\"
                 :env-file  \"env.local.edn\"
                 :port-path [:my/port]
                 :banner    #(str \"serving on \" (get-in % [::wb/server :port]))}
                args))

  A bad port, a missing or malformed resource, a variable nobody set, a system that
  fails to start or a banner that throws prints one line to `*err*` — the failure's
  message, never the configuration — and exits with status 1, whatever had started
  halted first."
  [{:keys [banner] :as options} args]
  (let [fail!           (fn [message]
                          (binding [*out* *err*] (println message))
                          (System/exit 1))
        [outcome value] (let [[ok config] (system-config options args)]
                          (if (= :ok ok) (start config) [ok config]))]
    (if (= :error outcome)
      (fail! value)
      (let [system value]
        ;; Before the shutdown hook: a banner that throws would otherwise leave the
        ;; server's threads serving with nothing left to halt them.
        (when banner
          (try (println (banner system))
               (catch Throwable t
                 (ig/halt! system)
                 (fail! (str "the banner failed: " (message-of t))))))
        (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(ig/halt! system)))
        @(promise)))))
