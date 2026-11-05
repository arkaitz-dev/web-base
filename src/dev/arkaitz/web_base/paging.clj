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
  zero, sent twice — is a 404."
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

(defn path
  "The path of page `n` of the list `request` asked for: the path the router matched, so
  only one of the host's routes can become a Location, with every query parameter but
  `page` kept — a filter survives. Page 1 is spelt without `page`, so a link and a
  redirect to it agree. A request the router did not answer has no such path, and is
  refused."
  [request n]
  (let [match (or (::r/match request)
                  (throw (ex-info "web-base: paging/path: this request was not answered by the router" {})))]
    (r/match->path match (cond-> (dissoc (:query-params request) "page")
                           (> n 1) (assoc "page" (str n))))))

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
  (when-not (contains? request :query-params)
    (throw (ex-info "web-base: paging/paged: this request carries no parsed query — a :sessionless route is answered before it is parsed" {})))
  (let [pages (max 1 (quot (+ total (dec size)) size))
        page  (page-asked request)]
    (if (> page pages)
      (response/see-other (path request pages))
      (f {:page page :pages pages :offset (* size (dec page)) :limit size}))))
