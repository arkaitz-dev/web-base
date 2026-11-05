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
