(ns web-base-test.run-keys
  "Integrant keys for `run!`'s tests — shared by integrant_test and the `-main`s it runs in
  a subprocess. Beside the tests, because these methods hold atoms and write files."
  (:require [integrant.core :as ig]))

(def halted (atom []))

(defmethod ig/init-key :test.run/port [_ v] v)
(defmethod ig/init-key :test.run/fallback [_ v] v)

(defmethod ig/init-key :test.run/first [_ _] :first-started)
(defmethod ig/halt-key! :test.run/first [_ v] (swap! halted conj v))

(defmethod ig/init-key :test.run/secretive [_ {:keys [password]}]
  (throw (ex-info "the database refused the login" {:password password})))

(defmethod ig/init-key :test.run/wrapping [_ _]
  (throw (ex-info "db-base: the database refused the login" {:password "S3CRET-7f3a"}
                  (Exception. "jdbc:driver said password=S3CRET-7f3a"))))

(defmethod ig/init-key :test.run/silent [_ _] (throw (NullPointerException.)))

(defmethod ig/init-key :test.run/bad-halter [_ _] :bad-halter-started)
(defmethod ig/halt-key! :test.run/bad-halter [_ _] (throw (ex-info "could not close the pool" {})))

(defmethod ig/init-key :test.run/marker [_ path] path)
(defmethod ig/halt-key! :test.run/marker [_ path] (spit path "halted"))

;; Halted before :test.run/bad-halter, which it refers to: the marker says the hook ran
;; `ig/halt!`, and the halt then fails on the key after it.
(defmethod ig/init-key :test.run/marker-after [_ {:keys [path]}] path)
(defmethod ig/halt-key! :test.run/marker-after [_ path] (spit path "halted"))

;; A refusal of the web-base and db-base kind: a :config-key to pass on, beside the value
;; it refused, which must not be.
(defmethod ig/init-key :test.run/refusing [_ {:keys [password]}]
  (throw (ex-info "web-base: session :key must be 16 bytes" {:config-key [:session :key] :value password})))
