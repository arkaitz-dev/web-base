(ns demo.store
  "In-memory application state. The atom is the component; every read and
  write goes through the functions below so handlers never touch the
  reference type directly."
  (:require [clojure.string :as str]))

(def languages
  "Static dataset behind the live-search tab."
  [{:name "Clojure"     :year 2007 :tags ["lisp" "jvm" "functional"]}
   {:name "Erlang"      :year 1986 :tags ["beam" "actors" "functional"]}
   {:name "Elixir"      :year 2011 :tags ["beam" "actors" "functional"]}
   {:name "Haskell"     :year 1990 :tags ["lazy" "types" "functional"]}
   {:name "OCaml"       :year 1996 :tags ["types" "functional"]}
   {:name "Rust"        :year 2010 :tags ["systems" "types" "no gc"]}
   {:name "Zig"         :year 2016 :tags ["systems" "no gc"]}
   {:name "Go"          :year 2009 :tags ["systems" "csp"]}
   {:name "Scheme"      :year 1975 :tags ["lisp" "macros"]}
   {:name "Common Lisp" :year 1984 :tags ["lisp" "macros"]}
   {:name "Smalltalk"   :year 1972 :tags ["objects" "image"]}
   {:name "Prolog"      :year 1972 :tags ["logic" "declarative"]}])

(defn new-store [todos]
  (atom {:todos (vec todos)}))

(defn- next-id [todos]
  (inc (reduce max 0 (map :id todos))))

(defn list-todos [store]
  (:todos @store))

(defn add-todo
  "Appends a todo and returns the resulting collection."
  [store title]
  (:todos (swap! store update :todos
                 (fn [todos] (conj todos {:id (next-id todos) :title title :done? false})))))

(defn toggle-todo [store id]
  (:todos (swap! store update :todos
                 (fn [todos] (mapv #(cond-> % (= id (:id %)) (update :done? not)) todos)))))

(defn remove-todo [store id]
  (:todos (swap! store update :todos
                 (fn [todos] (filterv #(not= id (:id %)) todos)))))

(defn search-languages
  "Case-insensitive match over name and tags. A blank query matches nothing,
  so the results stay empty until the user actually types."
  [query]
  (let [q (str/lower-case (str/trim (or query "")))]
    (if (str/blank? q)
      []
      (filterv (fn [{:keys [name tags]}]
                 (some #(str/includes? (str/lower-case %) q) (cons name tags)))
               languages))))
