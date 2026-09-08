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
  (:refer-clojure :exclude [read-string])
  (:require [dev.arkaitz.web-base :as wb]
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
