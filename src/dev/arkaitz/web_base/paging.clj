(ns dev.arkaitz.web-base.paging
  "A list in numbered pages, read from the query's `page` (since 0.18.0). The host counts
  its rows and reads one page of them; this namespace decides which page that is, and
  what a page that no longer exists answers. No SQL and no markup: the statement and the
  pager's words are the host's.

  A page goes past the end without anyone typing it: the last row of the last page moves
  out of a filter, a colleague's move does it, a tab sits open. A well-formed page past
  the end therefore answers a 303 to the last page, with the rest of the query kept —
  never a 404 for a link that worked a second ago, and never the last page rendered under
  a URL that names another. A `page` nobody could have linked to — junk, zero, a leading
  zero, sent twice — is a 404.

  `keyset` (since 0.21.0) pages by position in the order instead of by count: the next page
  begins after the last row shown, so a row that leaves the list — a work queue filtered by
  a state the work changes — never makes the next page skip one. It gives up \"page N of M\"
  and jumping to a page."
  (:require [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.response :as response]
            [reitit.core :as r]))

(defn- page-asked
  "The query's `page` as a number, 1 when absent. Read raw rather than through
  `wb/param`, which answers nil for a field sent twice as for one never sent —
  `?page=2&page=3` would be page 1."
  [request]
  (let [typed (get-in request [:query-params "page"])]
    (cond (nil? typed)                                             1
          (and (string? typed) (re-matches #"[1-9]\d{0,5}" typed)) (parse-long typed)
          :else                                                    (error/throw! {:status 404}))))

(defn- match-of [request fn-name]
  (or (::r/match request)
      (throw (ex-info (str "web-base: paging/" fn-name ": this request was not answered by the router") {}))))

(defn- query-parsed! [request fn-name]
  (when-not (contains? request :query-params)
    (throw (ex-info (str "web-base: paging/" fn-name ": this request carries no parsed query — a :sessionless route is answered before it is parsed") {}))))

(defn path
  "The path of page `n` of the list `request` asked for: the path the router matched, so
  only one of the host's routes can become a Location, with every query parameter but
  `page` kept — a filter survives. Page 1 is spelt without `page`, so a link and a
  redirect to it agree. A request the router did not answer has no such path, and is
  refused."
  [request n]
  (r/match->path (match-of request "path")
                 (cond-> (dissoc (:query-params request) "page")
                   (> n 1) (assoc "page" (str n)))))

(defn paged
  "`(f {:page p :pages m :offset o :limit size})` for the page the query asks of a list
  of `total` rows, `size` to a page, or a 303 to the last page when the one asked is
  past it. An empty list has one page, empty. A `page` nobody could have linked to is a
  404. The count and the page are the host's two reads: a row that leaves between them
  makes the last page short, never a redirect.

  A `:sessionless` route is refused: it is answered before the query is parsed, so every
  `page` would read as page 1, in silence."
  [request total size f]
  (when-not (and (nat-int? total) (pos-int? size))
    (throw (ex-info "web-base: paging/paged takes a total from 0 and a size from 1" {:total total :size size})))
  (query-parsed! request "paged")
  (let [pages (max 1 (quot (+ total (dec size)) size))
        page  (page-asked request)]
    (if (> page pages)
      (response/see-other (path request pages))
      (f {:page page :pages pages :offset (* size (dec page)) :limit size}))))

(def ^:private cursor-shape
  "What a cursor may be: the characters a URL carries unescaped, a length a key needs. The
  host's own `decode` says what it means."
  #"[A-Za-z0-9._~-]{1,128}")

(defn- cursor-asked
  "`[direction cursor]` the query asks for: `[:first nil]` with neither `after` nor
  `before`, else the one sent. Both, either sent twice, or a cursor of another shape is a
  link nobody built: a 404."
  [request]
  (let [{after "after" before "before"} (:query-params request)]
    (cond (and (nil? after) (nil? before)) [:first nil]
          (and after before)               (error/throw! {:status 404})
          :else (let [[direction cursor] (if after [:after after] [:before before])]
                  (if (and (string? cursor) (re-matches cursor-shape cursor))
                    [direction cursor]
                    (error/throw! {:status 404}))))))

(defn cursor-path
  "The path of the same list from a cursor: `direction` `:after` or `:before`, or nil for
  its first page. The router's path, with every query parameter but the cursors kept."
  [request direction cursor]
  (r/match->path (match-of request "cursor-path")
                 (cond-> (dissoc (:query-params request) "after" "before")
                   direction (assoc (name direction) cursor))))

(defn keyset
  "`(f {:rows … :next path-or-nil :previous path-or-nil})` for the page the query asks of
  a list read by position. The host gives
  - `:size`, rows to a page;
  - `:cursor`, a row's cursor — a string of URL-safe characters, its key in the order;
  - `:decode`, a cursor back to the key it names, or nil for any cursor it cannot read
    or whose row is gone — never a throw, which would answer a crafted link with a 500;
  - `:fetch`, called with `{:direction :first|:after|:before :key k :limit l}`, answering up
    to `l` rows in the list's order from the start, after `k`, or — for `:before` — before
    `k` nearest first, so in the reverse order: `ORDER BY k DESC` for a list read
    `ORDER BY k`.
  It asks for one row more than a page to know whether there is another, and gives the
  `:before` rows back in the list's order.

  A cursor whose row is gone, a page with no rows under a cursor, and a `before` that
  reaches the start each answer a 303 to the first page: the start has one spelling, and a
  link that worked a second ago is never a 404. A cursor of a shape nobody builds is a
  404. A `:sessionless` route is refused, as in `paged`."
  [request {:keys [size cursor decode fetch]} f]
  (when-not (and (pos-int? size) (ifn? cursor) (ifn? decode) (ifn? fetch))
    (throw (ex-info "web-base: paging/keyset takes a :size from 1 and :cursor, :decode and :fetch functions" {:size size})))
  (query-parsed! request "keyset")
  (let [[direction asked] (cursor-asked request)
        start             (fn [] (response/see-other (cursor-path request nil nil)))
        key               (when asked (decode asked))]
    (if (and asked (nil? key))
      (start)
      (let [fetched (fetch {:direction direction :key key :limit (inc size)})
            more?   (> (count fetched) size)
            shown   (vec (take size fetched))
            rows    (if (= :before direction) (vec (rseq shown)) shown)]
        (cond
          (and (not= :first direction) (empty? rows)) (start)
          (and (= :before direction) (not more?))     (start)
          :else
          (f {:rows     rows
              :next     (when (or more? (= :before direction))
                          (cursor-path request :after (cursor (peek rows))))
              :previous (when (not= :first direction)
                          (cursor-path request :before (cursor (first rows))))}))))))
