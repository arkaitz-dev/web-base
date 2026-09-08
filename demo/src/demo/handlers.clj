(ns demo.handlers
  "Handlers return Ring responses whose body is Hiccup; web-base renders
  them through the route's layouts. A handler names `:wb/height` only where
  the ordinary default — whole stack for a page, bare markup for a swap — is
  not what it wants."
  (:require [demo.schema :as schema]
            [demo.store :as store]
            [demo.views :as views]
            [dev.arkaitz.web-base.session :as session]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

(defn- tr [request] (:wb/tr request))

(defn- tr* [request k] ((tr request) [k]))

(defn- humanize
  "malli's messages in the request's language, `::m/missing-key` included."
  [request explanation]
  (me/humanize explanation {:locale (:wb/locale request)
                            :errors (assoc me/default-errors ::m/missing-key
                                           {:error/message {:en "is required" :es "es obligatorio"}})}))

(defn- panel-data [tab store]
  (case tab
    "todos"  (store/list-todos store)
    "search" {:query "" :results []}
    "signup" {:values {} :errors nil}))

(defn home [store request]
  {:status 200
   :body   (views/panel (tr request) "todos" (panel-data "todos" store))
   :wb/slots {:tab "todos"}})

(defn tab
  "An htmx swap of the whole `#app`: the section layout, not the shell —
  the one intermediate case, named."
  [store request]
  (let [tab (get-in request [:parameters :path :tab])]
    {:status    200
     :body      (views/panel (tr request) tab (panel-data tab store))
     :wb/slots  {:tab tab}
     :wb/height 1}))

(defn search [_store request]
  (let [query (or (get-in request [:parameters :query :q]) "")]
    {:status 200 :body (views/search-results (tr request) query (store/search-languages query))}))

(defn- form-values [request keys*]
  (-> (:form-params request) (select-keys keys*) (update-keys keyword)))

(defn signup
  "Validated by hand rather than by reitit's coercion: a rejected form comes
  back as a 200 carrying the re-rendered form."
  [_store request]
  (let [values (form-values request ["name" "email" "age"])
        parsed (m/decode schema/Signup values (mt/string-transformer))
        state  (if-let [explanation (m/explain schema/Signup parsed)]
                 {:values values :errors (humanize request explanation)}
                 {:values {} :saved parsed})]
    {:status 200 :body (views/signup-form (tr request) state)}))

(defn list-todos [store request]
  {:status 200 :body (views/todos (tr request) (store/list-todos store))})

(defn create-todo [store request]
  {:status 200 :body (views/todos (tr request) (store/add-todo store (get-in request [:parameters :form :title])))})

(defn toggle-todo [store request]
  {:status 200 :body (views/todos (tr request) (store/toggle-todo store (get-in request [:parameters :path :id])))})

(defn delete-todo [store request]
  {:status 200 :body (views/todos (tr request) (store/remove-todo store (get-in request [:parameters :path :id])))})

;; --- The demo's own login: web-base only learns that a subject exists -----

(defn login-form [request]
  {:status 200 :body (views/login-form (tr request) request {:values {} :errors nil}) :wb/slots {:title (tr* request :login/title)}})

(defn login
  "Stores an opaque subject in the session and rotates the session id: the
  fixation defence web-base exposes and the host must call (SPEC §11)."
  [request]
  (let [values (form-values request ["name"])]
    (if-let [explanation (m/explain schema/Login values)]
      {:status 200 :body (views/login-form (tr request) request {:values values :errors (humanize request explanation)})}
      (session/rotate {:status 303 :headers {"Location" "/private"} :body ""}
                      (assoc (:session request) :subject (:name values))))))

(defn logout [_request]
  {:status 303 :headers {"Location" "/"} :body "" :session nil})

(defn switch-language
  "A classic form: the choice lives in the session, where the demo's
  `:locale-fn` reads it."
  [request]
  (let [locale (get-in request [:form-params "locale"])]
    {:status  303
     :headers {"Location" "/"}
     :body    ""
     :session (assoc (:session request) :locale (when (#{"es" "en"} locale) locale))}))

(defn private [request]
  {:status 200 :body (views/private-page (tr request) (:wb/subject request)) :wb/slots {:title (tr* request :private/title)}})

(defn boom-page [request]
  {:status 200 :body (views/boom-page (tr request)) :wb/slots {:title (tr* request :boom/title)}})

(defn boom
  "The deliberate error: as a page you get the error page, from htmx the
  fragment lands inside its target."
  [_request]
  (throw (ex-info "the demo's deliberate failure" {:demo/boom true})))
