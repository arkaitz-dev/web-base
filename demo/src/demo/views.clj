(ns demo.views
  "Hiccup markup. Every function returns plain Hiccup data; turning it into a
  string is web-base's job. Views receive `tr`, the request's translate
  function, never the request itself — the layouts get that.

  No `hx-on`, no `hx-vals js:`: the demo runs under a strict Content
  Security Policy without `unsafe-eval`, so every behaviour is a request the
  server answers with markup."
  (:require [clojure.string :as str]
            [demo.schema :as schema]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]))

;; --- Layouts ---------------------------------------------------------------

(defn- language-switch [tr request]
  (let [current (:wb/locale request)]
    [:form.lang {:method "post" :action "/lang"}
     (security/csrf-field request)
     [:label (tr [:lang/switch])
      [:select {:name "locale" :onchange nil}
       (for [locale [:es :en]]
         [:option {:value (name locale) :selected (= locale current)} (tr [(keyword "lang" (name locale))])])]]
     [:button {:type "submit"} "→"]]))

(defn- nav [tr request]
  (let [subject (:wb/subject request)]
    (list
     [:a {:href "/"} (tr [:nav/home])]
     [:a {:href "/private"} (tr [:nav/private])]
     [:a {:href "/boom"} (tr [:boom/title])]
     (if subject
       [:form.inline {:method "post" :action "/logout"}
        (security/csrf-field request)
        [:button {:type "submit"} (tr [:nav/logout])]]
       [:a {:href "/login"} (tr [:nav/login])]))))

(defn shell
  "The outermost layout: web-base's shell with the demo's slots filled."
  [{:keys [content request title]}]
  (let [tr      (:wb/tr request)
        subject (:wb/subject request)]
    (shell/page
     {:request  request
      :title    (str (when title (str title " · ")) (tr [:app/title]))
      :head     (list [:link {:rel "icon" :href "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'><text x='1' y='13' font-size='13'>λ</text></svg>"}]
                      [:link {:rel "stylesheet" :href "/css/demo.css"}])
      :header   (list [:h1 (tr [:app/title])] [:p.tagline (tr [:app/tagline])])
      :nav      (nav tr request)
      :identity (list (if subject (tr [:nav/signed-in] [subject]) (tr [:nav/anonymous]))
                      (language-switch tr request))
      :content  content
      :footer   [:span "web-base"]})))

(defn- tab-nav [tr active]
  [:nav.tabs
   (for [id schema/tabs]
     ;; outerHTML: the section layout answers with the `#app` wrapper itself.
     [:button.tab {:class     (when (= id active) "is-active")
                   :hx-get    (str "/tabs/" id)
                   :hx-target "#app"
                   :hx-swap   "outerHTML"}
      (tr [(keyword "tabs" id)])])])

(defn tabs
  "The section layout: the tab bar around whichever panel is active. As a
  page it sits inside the shell; as an htmx swap it replaces `#app` alone."
  [{:keys [content request tab]}]
  [:div#app
   (tab-nav (:wb/tr request) tab)
   [:main#panel content]])

(defn error-page
  "The error page in the demo's words. `:wb/coercion` arrives as data."
  [{:keys [content request error]}]
  (let [tr (:wb/tr request)]
    (shell {:request request
            :title   (str (:status error))
            :content [:section.panel
                      content
                      [:p (tr [(keyword "error" (str (:status error)))])]
                      (when-let [explanation (:wb/coercion error)]
                        [:pre (pr-str explanation)])]})))

;; --- Tasks -----------------------------------------------------------------

(defn todo-item [tr {:keys [id title done?]}]
  [:li.todo {:class (when done? "is-done")}
   [:button.icon {:hx-post    (str "/todos/" id "/toggle")
                  :hx-target  "#todos"
                  :hx-swap    "outerHTML"
                  :title      (tr [:todos/toggle])
                  :aria-label (tr [:todos/toggle])}
    (if done? "☑" "☐")]
   [:span.todo-title title]
   [:button.icon.danger {:hx-delete  (str "/todos/" id)
                         :hx-target  "#todos"
                         :hx-swap    "outerHTML"
                         :title      (tr [:todos/delete])
                         :aria-label (tr [:todos/delete])}
    "✕"]])

(defn todos
  "Form and list together, so a swap after adding also yields a fresh, empty
  form — the reset htmx 2 did with `hx-on` needs no script."
  [tr todos]
  [:div#todos
   [:form.row {:hx-post "/todos" :hx-target "#todos" :hx-swap "outerHTML"}
    [:input {:type "text" :name "title" :placeholder (tr [:todos/placeholder])
             :autocomplete "off" :required true :maxlength 120}]
    [:button.primary {:type "submit"} (tr [:todos/add])]]
   [:ul.todos
    (if (seq todos)
      (map #(todo-item tr %) todos)
      [:li.empty (tr [:todos/empty])])]])

(defn- todos-panel [tr items]
  [:section.panel
   [:p.hint (tr [:todos/hint])]
   (todos tr items)])

;; --- Live search -----------------------------------------------------------

(defn search-results [tr query languages]
  [:div#search-results
   (cond
     (str/blank? query) [:p.empty (tr [:search/prompt])]
     (empty? languages) [:p.empty (tr [:search/none] [query])]
     :else
     [:ul.cards
      (for [{:keys [name year tags]} languages]
        [:li.card
         [:span.card-title name]
         [:span.card-year year]
         [:ul.tags (for [tag tags] [:li.tag tag])]])])])

(defn- search-panel [tr {:keys [query results]}]
  [:section.panel
   [:p.hint (tr [:search/hint])]
   [:form.row {:hx-get "/search" :hx-target "#search-results" :hx-swap "outerHTML"
               :hx-trigger "submit"}
    [:input {:type "search" :name "q" :value query
             :placeholder (tr [:search/placeholder])
             :autocomplete "off" :maxlength 60
             :hx-get "/search"
             :hx-trigger "keyup changed delay:300ms, search"
             :hx-target "#search-results"
             :hx-swap "outerHTML"}]]
   (search-results tr query results)])

;; --- Signup, validated with malli -----------------------------------------

(def ^:private signup-fields
  [{:name "name"  :label :signup/name  :type "text"  :placeholder "Ada Lovelace"}
   {:name "email" :label :signup/email :type "email" :placeholder "ada@example.com"}
   {:name "age"   :label :signup/age   :type "text"  :placeholder "42"}])

(defn signup-form
  "`values` are the raw form params echoed back; `errors` is the humanized
  malli explanation keyed by field."
  [tr {:keys [values errors saved]}]
  [:form#signup-form {:hx-post "/signup" :hx-target "#signup-form" :hx-swap "outerHTML"
                      ;; malli on the server is the only validator here; the
                      ;; browser's native checks would swallow the submit.
                      :novalidate true}
   (for [{:keys [name label type placeholder]} signup-fields]
     (let [field-errors (get errors (keyword name))]
       [:div.field {:class (when field-errors "has-error")}
        [:label {:for name} (tr [label])]
        [:input {:id name :name name :type type :placeholder placeholder
                 :autocomplete "off"
                 :value (get values (keyword name) "")
                 :aria-invalid (when field-errors "true")}]
        (when field-errors
          [:p.error (str/join ", " field-errors)])]))
   [:div.form-actions
    [:button.primary {:type "submit"} (tr [:signup/submit])]
    (when saved [:span.saved (tr [:signup/saved] [(:name saved)])])]])

(defn- signup-panel [tr state]
  [:section.panel
   [:p.hint (tr [:signup/hint])]
   (signup-form tr state)])

;; --- Panels, login, private, boom -----------------------------------------

(defn panel [tr tab data]
  (case tab
    "todos"  (todos-panel tr data)
    "search" (search-panel tr data)
    "signup" (signup-panel tr data)))

(defn login-form [tr request {:keys [values errors]}]
  [:section.panel
   [:h2 (tr [:login/title])]
   [:p.hint (tr [:login/hint])]
   [:form {:method "post" :action "/login" :novalidate true}
    (security/csrf-field request)
    [:div.field {:class (when (:name errors) "has-error")}
     [:label {:for "name"} (tr [:login/name])]
     [:input {:id "name" :name "name" :type "text" :autocomplete "off" :value (get values :name "")}]
     (when-let [e (:name errors)] [:p.error (str/join ", " e)])]
    [:button.primary {:type "submit"} (tr [:login/submit])]]])

(defn private-page [tr subject]
  [:section.panel
   [:h2 (tr [:private/title])]
   [:p (tr [:private/body] [subject])]])

(defn boom-page [tr]
  [:section.panel
   [:h2 (tr [:boom/title])]
   [:p (tr [:boom/body])]
   [:div#boom-target
    [:button.primary {:hx-get "/boom" :hx-target "#boom-target" :hx-swap "innerHTML"} (tr [:boom/button])]]])
