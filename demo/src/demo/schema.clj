(ns demo.schema
  "Malli schemas shared by the routes (request coercion) and the handlers
  (explicit validation of the signup form). Error messages carry both
  languages; malli picks one by `:locale` when humanizing.")

(def tabs
  "Tab ids in navigation order. Single source of truth: the views render
  these and the router only accepts these."
  ["todos" "search" "signup"])

(def Tab (into [:enum] tabs))

(def TodoId [:int {:min 1}])

(def NewTodo
  [:map
   [:title [:string {:min 1 :max 120
                     :error/message {:en "a task cannot be empty"
                                     :es "la tarea no puede estar vacía"}}]]])

(def SearchQuery
  [:map
   [:q {:optional true} [:maybe [:string {:max 60}]]]])

(def Signup
  [:map
   [:name [:string {:min 3 :max 40
                    :error/message {:en "must be between 3 and 40 characters"
                                    :es "debe tener entre 3 y 40 caracteres"}}]]
   [:email [:re {:error/message {:en "does not look like an email address"
                                 :es "no parece una dirección de correo"}}
            #"^[^@\s]+@[^@\s.]+\.[^@\s]+$"]]
   [:age [:int {:min 18 :max 120
                :error/message {:en "must be a number between 18 and 120"
                                :es "debe ser un número entre 18 y 120"}}]]])

(def Login
  [:map
   [:name [:string {:min 2 :max 40
                    :error/message {:en "tell us who you are, 2 to 40 characters"
                                    :es "dinos quién eres, de 2 a 40 caracteres"}}]]])
