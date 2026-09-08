(ns demo.routes
  "reitit route data. The layout stack is route data: the shell on every
  page, the tab section only where tabs are shown."
  (:require [demo.handlers :as handlers]
            [demo.schema :as schema]
            [demo.views :as views]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.htmx :as htmx]))

(defn routes [store]
  [["" {:wb/layouts [views/shell]}
    ["" {:wb/layouts [views/tabs]}
     ["/" {:get {:handler (partial handlers/home store)}}]
     ["/tabs/:tab" {:get {:parameters {:path [:map [:tab schema/Tab]]}
                          :handler    (partial handlers/tab store)}}]]

    ["/search" {:get {:parameters {:query schema/SearchQuery}
                      :handler    (partial handlers/search store)}}]
    ["/signup" {:post {:handler (partial handlers/signup store)}}]
    ["/todos" {:get  {:handler (partial handlers/list-todos store)}
               :post {:parameters {:form schema/NewTodo}
                      :handler    (partial handlers/create-todo store)}}]
    ["/todos/:id" {:delete {:parameters {:path [:map [:id schema/TodoId]]}
                            :handler    (partial handlers/delete-todo store)}}]
    ["/todos/:id/toggle" {:post {:parameters {:path [:map [:id schema/TodoId]]}
                                 :handler    (partial handlers/toggle-todo store)}}]

    ["/login" {:get  {:handler handlers/login-form}
               :post {:handler handlers/login}}]
    ["/logout" {:post {:handler handlers/logout}}]
    ["/lang" {:post {:handler handlers/switch-language}}]
    ["/private" {:wb/gate wb/subject-present?
                 :get {:handler handlers/private}}]
    ;; The base's classifier, not the raw header: a history restore sends
    ;; HX-Request too and must get the page back, not the deliberate error.
    ["/boom" {:get {:handler (fn [request]
                               (if (htmx/partial-request? request)
                                 (handlers/boom request)
                                 (handlers/boom-page request)))}}]
    ["/throw" {:get {:handler handlers/boom}}]
    ["/health" {:get {:handler (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})}}]]])
