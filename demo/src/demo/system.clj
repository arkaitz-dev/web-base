(ns demo.system
  "The host's explicit wiring (SPEC §3): the demo's components and the map
  web-base receives. Functions cannot live in EDN, so `config.edn` refers to
  `:demo/web-config`, which builds them here."
  (:require [demo.i18n :as i18n]
            [demo.routes :as routes]
            [demo.store :as store]
            [demo.views :as views]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]
            [reitit.coercion.malli :as malli-coercion]))

(defmethod ig/init-key :demo/store [_ {:keys [todos]}]
  (store/new-store todos))

(defmethod ig/init-key :demo/web-config [_ {:keys [store session-key secure?]}]
  {:routes       (routes/routes store)
   :coercion     malli-coercion/coercion
   :subject-fn   #(get-in % [:session :subject])
   :login-path   "/login"
   :session      {:key session-key :cookie-attrs {:secure secure?}}
   :static       {:root "public"}
   :error-layout views/error-page
   :i18n         {:dict           i18n/dictionary
                  :default-locale :es
                  :locale-fn      #(some-> (get-in % [:session :locale]) vector)}
   :security     {:csp (str "default-src 'self'; script-src 'nonce-{nonce}'; style-src 'self'; "
                            "img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")}})
