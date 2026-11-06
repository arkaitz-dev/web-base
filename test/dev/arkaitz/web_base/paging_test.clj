(ns dev.arkaitz.web-base.paging-test
  "A list in numbered pages, through the whole stack: `wrap-params` is what turns a
  repeated `page` into a vector and a form field into `:form-params`, and the router is
  what gives `path` its match. `calls` records every page `f` was asked for, so a page
  that is redirected or refused is seen not to have been served."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.paging :as paging]
            [reitit.core :as r]
            [ring.middleware.session.memory :as memory]
            [ring.mock.request :as mock]))

(defn- app
  "A list of `total` rows, `size` to a page (20 unless given), at `/items`; `calls`
  collects what `f` received."
  [total calls & [size]]
  (wb/handler {:session {:store (memory/memory-store)}
               :csrf    false
               :routes  [["/items" {:handler (fn [r] (paging/paged r total (or size 20)
                                                                   (fn [p] (swap! calls conj p)
                                                                     {:status 200 :body (pr-str p)})))}]]}))

(defn- get-page [total query & [size]]
  (let [calls (atom [])
        r     ((app total calls size) (mock/request :get (str "/items" query)))]
    [r @calls]))

(deftest a-page-asked-of-a-list-is-answered-through-f-with-its-exact-window--the-last-page-too
  (doseq [[total query expected] [[41 ""        {:page 1 :pages 3 :offset 0 :limit 20}]
                                  [41 "?page=2" {:page 2 :pages 3 :offset 20 :limit 20}]
                                  [41 "?page=3" {:page 3 :pages 3 :offset 40 :limit 20}]
                                  [40 "?page=2" {:page 2 :pages 2 :offset 20 :limit 20}]
                                  [1  ""        {:page 1 :pages 1 :offset 0 :limit 20}]
                                  [0  ""        {:page 1 :pages 1 :offset 0 :limit 20}]]]
    (let [[r calls] (get-page total query)]
      (is (= 200 (:status r)) (str total " rows, " (pr-str query) ": answered, not redirected — the last page is a page"))
      (is (= [expected] calls) (str total " rows, " (pr-str query) ": f asked once, for exactly this window"))))
  (let [[r calls] (get-page 41 "?page=4" 10)]
    (is (= [200 [{:page 4 :pages 5 :offset 30 :limit 10}]] [(:status r) calls])
        "the size given, not 20, draws the window"))
  (let [[r calls] (get-page 41 "?page=1")]
    (is (= [200 [{:page 1 :pages 3 :offset 0 :limit 20}]] [(:status r) calls])
        "page 1 spelt out is served, like no page at all")))

(deftest a-well-formed-page-past-the-end-is-a-303-to-the-last-page-with-the-query-kept--and-f-never-runs
  (let [[r calls] (get-page 41 "?page=4&status=a%20b&tag=x&tag=y")]
    (is (= 303 (:status r)) "a page past the end is a 303, not a 404")
    ;; wrap-params builds an array map up to eight keys, so this query keeps the order it
    ;; arrived in; past eight the Location is as right and in no particular order.
    (is (= "/items?status=a+b&tag=x&tag=y&page=3" (get-in r [:headers "Location"]))
        "to the last page, with the filter and both tags kept")
    (is (= [] calls) "f is not asked for a page past the end"))
  (let [[r calls] (get-page 41 "?page=999999")]
    (is (= [303 "/items?page=3" []] [(:status r) (get-in r [:headers "Location"]) calls])
        "six digits are well formed, and clamp")))

(deftest an-empty-lists-page-2-lands-on-the-bare-path
  (let [[r calls] (get-page 0 "?page=2")]
    (is (= [303 "/items" []] [(:status r) (get-in r [:headers "Location"]) calls])
        "page 1 is spelt without page"))
  (let [[r _] (get-page 0 "?page=2&q=z")]
    (is (= "/items?q=z" (get-in r [:headers "Location"])) "and the rest of the query survives")))

(deftest a-page-nobody-could-have-linked-to-is-a-404--never-clamped-and-never-served
  (doseq [query ["?page=0" "?page=02" "?page=2&page=3" "?page=x" "?page=1000000" "?page=" "?page=-1" "?page=2x"]]
    (let [[r calls] (get-page 41 query)]
      (is (= [404 []] [(:status r) calls]) (str query " is a 404, neither clamped nor served")))))

(deftest the-page-is-read-from-the-query--a-form-field-named-page-does-not-choose-it
  (doseq [[uri expected] [["/items" 1] ["/items?page=2" 2]]]
    (let [calls (atom [])
          r     ((app 41 calls) (-> (mock/request :post uri)
                                    (mock/content-type "application/x-www-form-urlencoded")
                                    (mock/body "page=3")))]
      (is (= [200 [expected]] [(:status r) (mapv :page @calls)])
          (str "POST " uri " with a form field page=3: the query's page, " expected)))))

(deftest path-is-the-routes-own-path-with-the-query-kept-and-page-1-bare
  (let [seen    (atom nil)
        handler (wb/handler {:session {:store (memory/memory-store)}
                             :csrf    false
                             :routes  [["/lists/:id/items"
                                        {:get (fn [r] (reset! seen (mapv #(paging/path r %) [1 2 3])) {:status 200 :body ""})}]]})]
    (handler (mock/request :get "/lists/7/items?q=a&page=2"))
    (is (= ["/lists/7/items?q=a" "/lists/7/items?q=a&page=2" "/lists/7/items?q=a&page=3"] @seen)
        "the path parameter in place, the query kept, page 1 without page")
    (testing "the path is the match's, not the request's uri — called directly, since through the stack they are one string"
      (let [match (r/match-by-path (::r/router (meta handler)) "/lists/7/items")]
        (is (= "/lists/7/items?page=2"
               (paging/path {:uri "/elsewhere" :query-params {} ::r/match match} 2)))))
    (is (= "web-base: paging/path: this request was not answered by the router"
           (try (paging/path {:uri "/lists/7/items" :query-params {}} 2) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e))))
        "a request the router did not answer is refused, naming why")))

(deftest a-sessionless-route-is-refused-rather-than-served-page-1-for-every-page
  (let [handler (wb/handler {:session     {:store (memory/memory-store)}
                             :sessionless {"/s" (fn [r] (paging/paged r 41 20 (fn [_] {:status 200 :body "served"})))}
                             :routes      []})
        r       (handler (mock/request :get "/s?page=2"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a :sessionless route is answered before it is parsed"
                          (paging/paged {:uri "/s"} 41 20 identity))
        "witness: a request with no parsed query is refused, naming why")
    (is (= 500 (:status r)) (str "so the stack answers the error, never page 1: " (:status r) " " (pr-str (:body r))))))

(deftest a-host-bug-in-total-or-size-is-refused
  (doseq [[total size] [[-1 20] [10 0] [10 -5] [nil 20] [10 nil]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"paging/paged takes a total from 0 and a size from 1"
                          (paging/paged {:query-params {}} total size identity))
        (str "total " (pr-str total) ", size " (pr-str size)))))

;; --- keyset ------------------------------------------------------------------------

(def ^:private ids (mapv #(format "a%02d" %) (range 1 26)))

(defn- keyset-app
  "A list of `ids` (the 25 unless given), ten to a page, read by position at `/items`.
  `calls` records each fetch; `served` what `f` was given. A fetch asked with no key after
  or before a cursor throws, as a host's query would: a missing clamp is a 500, never a
  lucky redirect."
  [calls served & [ids]]
  (let [ids   (or ids (var-get #'ids))
        fetch (fn [{:keys [direction key limit] :as asked}]
                (swap! calls conj asked)
                (when (and (not= :first direction) (nil? key))
                  (throw (ex-info "fetch asked with no key" asked)))
                (case direction
                  :first  (vec (take limit ids))
                  :after  (vec (take limit (rest (drop-while #(not= key %) ids))))
                  :before (vec (take limit (reverse (take-while #(not= key %) ids))))))]
    (wb/handler {:session {:store (memory/memory-store)}
                 :csrf    false
                 :routes  [["/items" {:handler (fn [r]
                                                 (paging/keyset r {:size 10 :cursor identity
                                                                   :decode (fn [c] (some #{c} ids)) :fetch fetch}
                                                                (fn [page] (swap! served conj page)
                                                                  {:status 200 :body "page"})))}]]})))

(defn- keyset-get [query & [ids]]
  (let [calls (atom []) served (atom [])
        r     ((keyset-app calls served ids) (mock/request :get (str "/items" query)))]
    [r @calls @served]))

(defn- span [from to] (subvec ids (dec from) to))

(deftest keyset-serves-each-page-by-position-with-its-neighbours-and-asks-one-row-more
  (doseq [[query rows next previous asked]
          [["" (span 1 10) "/items?after=a10" nil {:direction :first :key nil :limit 11}]
           ["?after=a10" (span 11 20) "/items?after=a20" "/items?before=a11" {:direction :after :key "a10" :limit 11}]
           ["?after=a15" (span 16 25) nil "/items?before=a16" {:direction :after :key "a15" :limit 11}]
           ["?after=a20" (span 21 25) nil "/items?before=a21" {:direction :after :key "a20" :limit 11}]
           ["?before=a21" (span 11 20) "/items?after=a20" "/items?before=a11" {:direction :before :key "a21" :limit 11}]]]
    (let [[r calls served] (keyset-get query)]
      (is (= [200 [asked]] [(:status r) calls]) (str (pr-str query) ": served, the fetch asked once for a page and one row more"))
      (is (= [{:rows rows :next next :previous previous}] served)
          (str (pr-str query) ": the rows in the list's order, and the neighbours that exist")))))

(deftest keyset-serves-the-first-page-of-a-short-list-whole-with-no-neighbours--never-a-redirect
  (doseq [[label list] [["empty" []] ["three rows" ["a01" "a02" "a03"]] ["exactly a page" (span 1 10)]]]
    (let [[r calls served] (keyset-get "" list)]
      (is (= [200 1 [{:rows list :next nil :previous nil}]] [(:status r) (count calls) served])
          (str label ": the first page is served as it is — a redirect here would be to itself")))))

(deftest keyset-lands-on-the-first-page-for-a-cursor-that-no-longer-leads-anywhere
  (doseq [[query why fetched?]
          [["?before=a11" "a before that reaches the start: the first page has one spelling" true]
           ["?after=a25" "after the last row: nothing to show" true]
           ["?before=a01" "before the first row: nothing to show" true]
           ["?after=a99" "a cursor whose row is gone: never fetched" false]]]
    (let [[r calls served] (keyset-get query)]
      (is (= [303 "/items" [] fetched?] [(:status r) (get-in r [:headers "Location"]) served (boolean (seq calls))])
          (str (pr-str query) ": " why))))
  (let [[r _ _] (keyset-get "?after=a99&status=x")]
    (is (= [303 "/items?status=x"] [(:status r) (get-in r [:headers "Location"])]) "the rest of the query kept"))
  (let [[_ _ served] (keyset-get "?after=a10&status=x&tag=y")]
    (is (= ["/items?status=x&tag=y&after=a20" "/items?status=x&tag=y&before=a11"] ((juxt :next :previous) (first served)))
        "the neighbours keep the rest of the query, and only one cursor")))

(deftest keyset-refuses-a-cursor-nobody-built-with-a-404-and-reads-nothing
  (doseq [query ["?after=a10&before=a11" "?after=a10&after=a11" "?after=a%20b" "?after="
                 (str "?after=" (apply str (repeat 129 "a"))) "?before=a/b"]]
    (let [[r calls served] (keyset-get query)]
      (is (= [404 [] []] [(:status r) calls served]) (str (pr-str (subs query 0 (min 30 (count query)))) " is a 404, nothing fetched"))))
  (let [[r _ served] (keyset-get (str "?after=" (apply str (repeat 128 "a"))))]
    (is (= [303 []] [(:status r) served]) "128 characters is a cursor's shape: read, and unknown, so the first page")))

(deftest keyset-is-refused-on-a-sessionless-route-and-for-a-host-bug-in-its-options
  (let [handler (wb/handler {:session     {:store (memory/memory-store)}
                             :sessionless {"/s" (fn [r] (paging/keyset r {:size 10 :cursor identity :decode identity
                                                                          :fetch (constantly [])}
                                                                       (fn [_] {:status 200 :body "served"})))}
                             :routes      []})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a :sessionless route is answered before it is parsed"
                          (paging/keyset {:uri "/s"} {:size 10 :cursor identity :decode identity :fetch (constantly [])} identity))
        "witness: a request with no parsed query is refused, naming why")
    (is (= 500 (:status (handler (mock/request :get "/s?after=a10")))) "so the stack answers the error, never the first page"))
  (doseq [opts [{:size 0 :cursor identity :decode identity :fetch identity}
                {:size nil :cursor identity :decode identity :fetch identity}
                {:size 10 :cursor identity :decode identity}
                {:size 10 :cursor "x" :decode identity :fetch identity}
                {:size 10 :cursor identity :decode "x" :fetch identity}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"paging/keyset takes a :size from 1"
                          (paging/keyset {:query-params {}} opts identity))
        (pr-str opts))))
