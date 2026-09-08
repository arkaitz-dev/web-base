(ns demo.i18n
  "The demo's dictionary, in Tempura's shape. Two languages so the seam is
  exercised: the base negotiates, the host words.")

(def dictionary
  {:en {:missing "[missing text]"
        :app     {:title "web-base demo"
                  :tagline "Clojure · reitit · Hiccup · htmx 4 · web-base"}
        :nav     {:home "Home" :private "Private" :login "Sign in" :logout "Sign out"
                  :signed-in "signed in as %1" :anonymous "anonymous"}
        :tabs    {:todos "Tasks" :search "Search" :signup "Sign up"}
        :todos   {:hint "Add, toggle and delete without a reload: every button posts and the server answers with the fragment htmx swaps in."
                  :placeholder "New task…" :add "Add" :empty "No tasks yet." :toggle "Toggle" :delete "Delete"}
        :search  {:hint "The input fires a request after a 300 ms pause; the parameter is coerced with malli and only the results are swapped."
                  :placeholder "clojure, lisp, systems…" :prompt "Type to search by name or tag."
                  :none "No results for “%1”."}
        :signup  {:hint "The form is validated with malli on the server: errors come back humanized inside the form, so htmx swaps it with a 200 — a 4xx would swap too under htmx 4, but a rejected form is not an error."
                  :name "Name" :email "Email" :age "Age" :submit "Sign up" :saved "✓ %1 signed up"}
        :login   {:title "Who are you?"
                  :hint "This login is the demo's own, not web-base's: the base only learns that a subject exists. Any name will do."
                  :name "Name" :submit "Sign in"}
        :private {:title "Private page" :body "Only a subject gets here. You are %1."}
        :boom    {:title "Deliberate error" :body "This handler throws. As a page you see the error page; from an htmx button the error lands inside its target."
                  :button "Throw from htmx"}
        :error   {:404 "Nothing here." :403 "You may not see that." :500 "Something broke on our side; it is logged with the request id."
                  :400 "The request was not understood." :405 "That method is not allowed here."}
        :lang    {:switch "Language" :es "Español" :en "English"}}
   :es {:missing "[texto ausente]"
        :app     {:title "demo de web-base"
                  :tagline "Clojure · reitit · Hiccup · htmx 4 · web-base"}
        :nav     {:home "Inicio" :private "Privado" :login "Entrar" :logout "Salir"
                  :signed-in "sesión de %1" :anonymous "anónimo"}
        :tabs    {:todos "Tareas" :search "Búsqueda" :signup "Alta"}
        :todos   {:hint "Alta, cambio de estado y borrado sin recargar: cada botón hace su petición y el servidor devuelve el fragmento que htmx sustituye."
                  :placeholder "Nueva tarea…" :add "Añadir" :empty "No hay tareas todavía." :toggle "Cambiar estado" :delete "Borrar"}
        :search  {:hint "El input dispara una petición tras 300 ms de pausa; el parámetro se valida con malli y solo se sustituyen los resultados."
                  :placeholder "clojure, lisp, sistemas…" :prompt "Escribe para buscar por nombre o etiqueta."
                  :none "Sin resultados para «%1»."}
        :signup  {:hint "El formulario se valida con malli en el servidor: los errores vuelven humanizados dentro del propio formulario, así que htmx lo sustituye con un 200 — con htmx 4 un 4xx también se sustituiría, pero un formulario rechazado no es un error."
                  :name "Nombre" :email "Correo" :age "Edad" :submit "Dar de alta" :saved "✓ %1 dado de alta"}
        :login   {:title "¿Quién eres?"
                  :hint "Este login es de la demo, no de web-base: la base solo sabe que existe un sujeto. Vale cualquier nombre."
                  :name "Nombre" :submit "Entrar"}
        :private {:title "Página privada" :body "Aquí solo llega un sujeto. Eres %1."}
        :boom    {:title "Error deliberado" :body "Este handler lanza. Como página ves la página de error; desde un botón htmx el error aterriza dentro de su target."
                  :button "Lanzar desde htmx"}
        :error   {:404 "Aquí no hay nada." :403 "No puedes ver eso." :500 "Algo se ha roto de nuestro lado; queda registrado con el id de la petición."
                  :400 "No se ha entendido la petición." :405 "Ese método no está permitido aquí."}
        :lang    {:switch "Idioma" :es "Español" :en "English"}}})
