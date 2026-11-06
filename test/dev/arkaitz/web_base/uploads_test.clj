(ns dev.arkaitz.web-base.uploads-test
  "`wb/uploads` through the assembled handler on a route that declares `:wb/multipart`,
  beside the raw `:multipart-params` Ring delivered, so the cut and the defaults are
  shown to be `uploads`' and not the parser's; and `response/attachment`, its header
  pinned exactly and its `filename*` decoded back by a decoder of the test's own. Every
  expected value was measured."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.testing :as wt]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayInputStream File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Arrays]))

(def ^:private KEY "AAECAwQFBgcICQoLDA0ODw==")

(defn- payload
  "Bytes no transcoding or line-ending fix leaves alone, `n` telling files apart."
  [n]
  (byte-array (concat (map unchecked-byte (range 256)) (.getBytes "\r\n--x\r" "UTF-8") [(unchecked-byte n)])))

(def ^:private kept (atom []))

(defn- echo
  "What `uploads` answered, each file's bytes compared with what was sent for its name,
  and the raw first part as Ring delivered it."
  [sent]
  (fn [r]
    (let [files (wb/uploads r "f")
          raw   (get-in r [:multipart-params "f"])]
      (reset! kept (mapv :tempfile files))
      {:status 200
       :body   (pr-str {:vector? (vector? files)
                        :files (mapv (fn [{:keys [filename content-type size ^File tempfile]}]
                                       [filename content-type size
                                        (Arrays/equals ^bytes (get sent filename (byte-array 0))
                                                       (Files/readAllBytes (.toPath tempfile)))])
                                     files)
                        :raw   (let [first-part (if (vector? raw) (first (filter map? raw)) raw)]
                                 (when (map? first-part) [(:filename first-part) (:content-type first-part)]))})})))

(defn- app [sent]
  (wb/handler {:session {:key KEY}
               :routes  [["/form" {:get (fn [r] {:status 200 :body [:form (security/csrf-field r)]})}]
                         ["/u" {:wb/multipart {:max-file-size 2048 :max-files 10} :post (echo sent)}]]}))

(defn- sent-parts
  "`[status files raw tempfiles-alive-after]` for one form with `params` and `files`."
  [params files]
  (let [sent (into {} (for [[_ {:keys [filename bytes]}] files] [filename bytes]))
        h    (app sent)
        b    (wt/visit (wt/browser h) :get "/form")
        r    (:response (wt/visit b :post "/u" params {:files files}))
        body (when (= 200 (:status r)) (edn/read-string (:body r)))]
    [(:status r) (:files body) (:raw body) (mapv #(.exists ^File %) @kept) (:vector? body)]))

(defn- part [filename content-type bytes] ["f" {:filename filename :content-type content-type :bytes bytes}])

(deftest uploads-answers-the-files-of-a-field-one-or-several-alike--the-empty-input-left-out
  (let [empty-input (part "" "application/octet-stream" (byte-array 0))]
    (doseq [[label params files expected]
            [["one" {} [(part "a.png" "image/png" (payload 1))] [["a.png" "image/png" 263 true]]]
             ["two" {} [(part "a.png" "image/png" (payload 1)) (part "b.bin" "application/octet-stream" (payload 2))]
              [["a.png" "image/png" 263 true] ["b.bin" "application/octet-stream" 263 true]]]
             ["an empty input" {} [empty-input] []]
             ["an empty input beside a file" {} [empty-input (part "a.png" "image/png" (payload 1))] [["a.png" "image/png" 263 true]]]
             ["an empty file chosen by name" {} [(part "empty.txt" "text/plain" (byte-array 0))] [["empty.txt" "text/plain" 0 true]]]
             ["a text field of the same name beside a file" [["f" "text"]] [(part "a.png" "image/png" (payload 1))]
              [["a.png" "image/png" 263 true]]]
             ["a text field beside an empty input" [["f" "text"]] [empty-input] []]
             ["no such field" {} [["g" {:filename "a.png" :content-type "image/png" :bytes (payload 1)}]] []]]]
      (testing label
        (let [[status files _ alive vector?] (sent-parts params files)]
          (is (= 200 status) "witness: the request reached the handler")
          (is (true? vector?) "a vector, empty when there is no file — never nil")
          (is (= expected files) "[filename content-type size bytes-as-sent] per file, in order")
          (is (every? false? alive) "the temporary files die with the answer"))))))

(defn- one-upload
  "`[status count upload same?]` for one form: how many files `uploads` saw, what `upload`
  answered — its temporary file replaced by whether its bytes are the ones sent — and
  whether it is `uploads`' first."
  [params files]
  (let [sent    (mapv (comp :bytes second) files)
        as-sent #(boolean (some (fn [b] (Arrays/equals ^bytes b (Files/readAllBytes (.toPath ^File %)))) sent))
        h       (wb/handler {:session {:key KEY}
                             :routes  [["/form" {:get (fn [r] {:status 200 :body [:form (security/csrf-field r)]})}]
                                       ["/u" {:wb/multipart {:max-file-size 2048}
                                              :post (fn [r]
                                                      (let [one (wb/upload r "f")]
                                                        {:status 200
                                                         :body   (pr-str [(count (wb/uploads r "f"))
                                                                          (some-> one (update :tempfile as-sent))
                                                                          (= one (first (wb/uploads r "f")))])}))}]]})
        b       (wt/visit (wt/browser h) :get "/form")
        r       (:response (wt/visit b :post "/u" params {:files files}))]
    (into [(:status r)] (when (= 200 (:status r)) (edn/read-string (:body r))))))

(deftest upload-answers-the-one-file-of-a-field-as-uploads-cuts-it--and-nil-for-none-or-several
  (let [empty-input (part "" "application/octet-stream" (byte-array 0))
        a           {:filename "a.png" :content-type "image/png" :size 263 :tempfile true}]
    (doseq [[label params files expected]
            [["one" {} [(part "a.png" "image/png" (payload 1))] [1 a true]]
             ["one sent with a whole path" {} [(part "C:\\Users\\a\\photo.png" "image/png" (payload 1))]
              [1 (assoc a :filename "photo.png") true]]
             ["one with no type" {} [(part "a.png" "" (payload 1))] [1 (assoc a :content-type "application/octet-stream") true]]
             ["an empty file chosen by name" {} [(part "empty.txt" "text/plain" (byte-array 0))]
              [1 {:filename "empty.txt" :content-type "text/plain" :size 0 :tempfile true} true]]
             ["two" {} [(part "a.png" "image/png" (payload 1)) (part "b.bin" "application/octet-stream" (payload 2))] [2 nil false]]
             ["an empty input" {} [empty-input] [0 nil true]]
             ["an empty input beside one file" {} [empty-input (part "a.png" "image/png" (payload 1))] [1 a true]]
             ["a text field of the same name beside one file" [["f" "text"]] [(part "a.png" "image/png" (payload 1))] [1 a true]]
             ["a text field alone" [["f" "text"]] [] [0 nil true]]]]
      (testing label
        (let [[status n one same?] (one-upload params files)]
          (is (= 200 status) "witness: the request reached the handler")
          (is (= (first expected) n) "witness: uploads saw the files the case sends")
          (is (= (second expected) one) "upload: the file as uploads cuts it, its bytes the ones sent — or nil")
          (is (= (nth expected 2) same?) "and it is uploads' first exactly when there is one"))))))

(deftest uploads-names-a-file-by-its-last-segment-never-a-path-and-types-an-untyped-part
  (doseq [[sent-name ct expected-name expected-ct]
          [["C:\\Users\\a\\photo.png" "image/png" "photo.png" "image/png"]
           ["dir/sub/x.txt" "text/plain" "x.txt" "text/plain"]
           ["dir/x.txt/" "text/plain" "x.txt" "text/plain"]
           ["a/ /b" "text/plain" "b" "text/plain"]
           ["a/ /" "text/plain" "a" "text/plain"]
           ["/" "text/plain" "file" "text/plain"]
           ["\\\\" "text/plain" "file" "text/plain"]
           [".." "text/plain" "file" "text/plain"]
           ["dir/." "text/plain" "file" "text/plain"]
           [".hidden" "text/plain" ".hidden" "text/plain"]
           ["..." "text/plain" "..." "text/plain"]
           ["dir/ x.txt" "text/plain" " x.txt" "text/plain"]
           ["informe ñ 日本 😀.pdf" "application/pdf" "informe ñ 日本 😀.pdf" "application/pdf"]
           ["a.png" "" "a.png" "application/octet-stream"]]]
    (testing (pr-str sent-name)
      (let [[status files raw] (sent-parts {} [(part sent-name ct (payload 1))])]
        (is (= 200 status) "witness: the request reached the handler")
        (is (= [sent-name ct] raw) "witness: Ring handed the name and type as sent — the cut and the default are uploads'")
        (is (= [[expected-name expected-ct]] (mapv #(subvec % 0 2) files)) "[last segment, type]"))))
  (testing "a part with no Content-Type header at all"
    (let [boundary "xyz"
          bytes    (.getBytes (str "--" boundary "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.png\"\r\n\r\nHELLO\r\n--"
                                   boundary "--\r\n") "UTF-8")
          h        (wb/handler {:session {:key KEY} :csrf false
                                :routes [["/u" {:wb/multipart {:max-file-size 2048}
                                                :post (fn [r] {:status 200
                                                               :body (pr-str [(get-in r [:multipart-params "f" :content-type])
                                                                              (mapv (juxt :filename :content-type :size) (wb/uploads r "f"))])})}]]})
          r        (h (-> (mock/request :post "/u")
                          (assoc :body (ByteArrayInputStream. bytes) :content-length (alength bytes)
                                 :content-type (str "multipart/form-data; boundary=" boundary))
                          (assoc-in [:headers "content-type"] (str "multipart/form-data; boundary=" boundary))))]
      (is (= [200 [nil [["a.png" "application/octet-stream" 5]]]] [(:status r) (edn/read-string (:body r))])
          "Ring gives no type, uploads gives application/octet-stream"))))

(defn- decode-5987
  "Percent-decoding by hand: `%XX` is a byte, everything else ASCII as it stands — a `+`
  stays a `+`, which java.net.URLDecoder would read as a space."
  [^String s]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop [i 0]
      (if (< i (count s))
        (if (= \% (.charAt s i))
          (do (.write out (Integer/parseInt (subs s (inc i) (+ i 3)) 16)) (recur (+ i 3)))
          (do (.write out (int (.charAt s i))) (recur (inc i))))
        (String. (.toByteArray out) StandardCharsets/UTF_8)))))

(deftest attachment-is-a-download-under-its-name-spelt-twice-as-rfc-6266-wants--the-body-untouched--no-cache-rule
  (is (= ["a b" "a+b"] [(decode-5987 "a%20b") (decode-5987 "a+b")]) "witness: the test's decoder reads + as itself")
  (doseq [[filename disposition]
          [["plain.pdf" "attachment; filename=\"plain.pdf\"; filename*=UTF-8''plain.pdf"]
           ["with space.pdf" "attachment; filename=\"with space.pdf\"; filename*=UTF-8''with%20space.pdf"]
           ["star*.txt" "attachment; filename=\"star*.txt\"; filename*=UTF-8''star%2A.txt"]
           ["quote\"q.txt" "attachment; filename=\"quote_q.txt\"; filename*=UTF-8''quote%22q.txt"]
           ["back\\slash.txt" "attachment; filename=\"back_slash.txt\"; filename*=UTF-8''back%5Cslash.txt"]
           ["ñ.txt" "attachment; filename=\"_.txt\"; filename*=UTF-8''%C3%B1.txt"]
           ["日本.txt" "attachment; filename=\"__.txt\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC.txt"]
           ["😀.png" "attachment; filename=\"_.png\"; filename*=UTF-8''%F0%9F%98%80.png"]
           ["informe ñ \"x\" *.pdf" "attachment; filename=\"informe _ _x_ *.pdf\"; filename*=UTF-8''informe%20%C3%B1%20%22x%22%20%2A.pdf"]
           ["nl\nx.txt" "attachment; filename=\"nl_x.txt\"; filename*=UTF-8''nl%0Ax.txt"]
           ["a+b.txt" "attachment; filename=\"a+b.txt\"; filename*=UTF-8''a%2Bb.txt"]
           ["del\u007Fx.txt" "attachment; filename=\"del_x.txt\"; filename*=UTF-8''del%7Fx.txt"]
           ["cr\rx.txt" "attachment; filename=\"cr_x.txt\"; filename*=UTF-8''cr%0Dx.txt"]
           ["dir/sub.pdf" "attachment; filename=\"dir_sub.pdf\"; filename*=UTF-8''dir%2Fsub.pdf"]
           ["C:\\Users\\a.pdf" "attachment; filename=\"C__Users_a.pdf\"; filename*=UTF-8''C%3A%5CUsers%5Ca.pdf"]
           ["a:b.pdf" "attachment; filename=\"a_b.pdf\"; filename*=UTF-8''a%3Ab.pdf"]
           ["/" "attachment; filename=\"_\"; filename*=UTF-8''%2F"]
           ["a%b;c,d'e=f.pdf" "attachment; filename=\"a%b;c,d'e=f.pdf\"; filename*=UTF-8''a%25b%3Bc%2Cd%27e%3Df.pdf"]
           ["a\"b\\c\r\nX-Evil: 1/é.pdf"
            "attachment; filename=\"a_b_c__X-Evil_ 1__.pdf\"; filename*=UTF-8''a%22b%5Cc%0D%0AX-Evil%3A%201%2F%C3%A9.pdf"]]]
    (let [body (byte-array 3)
          r    (response/attachment body filename)
          cd   (get-in r [:headers "Content-Disposition"])]
      (is (= disposition cd) (str (pr-str filename) ": the header, exactly — no CR or LF in it"))
      (is (= filename (decode-5987 (second (re-find #"filename\*=UTF-8''(.*)$" cd))))
          (str (pr-str filename) ": filename* decodes back to the name"))
      (is (= [[:body :headers :status] 200 ["Content-Disposition" "Content-Type"] "application/octet-stream" true]
             [(sort (keys r)) (:status r) (sort (keys (:headers r))) (get-in r [:headers "Content-Type"]) (identical? body (:body r))])
          (str (pr-str filename) ": a download, its body untouched, and no cache rule imposed"))))
  (doseq [blank [nil "" "  "]]
    (is (= ["web-base: attachment needs a file name, and was given none" {:filename blank}]
           (try (response/attachment (byte-array 0) blank) :answered
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str (pr-str blank) ": a download with no name is refused")))
  (let [bytes (payload 9)
        h     (wb/handler {:session {:key KEY}
                           :routes  [["/s" {:get (fn [_] (response/attachment (ByteArrayInputStream. bytes) "ñ.bin"))}]
                                     ["/b" {:get (fn [_] (response/attachment bytes "ñ.bin"))}]]})]
    (doseq [path ["/s" "/b"]]
      (let [r    (h (mock/request :get path))
            sent (let [b (:body r)] (if (bytes? b) b (.readAllBytes ^java.io.InputStream b)))]
        (is (= [200 "application/octet-stream" "attachment; filename=\"_.bin\"; filename*=UTF-8''%C3%B1.bin" nil true]
               [(:status r) (get-in r [:headers "Content-Type"]) (get-in r [:headers "Content-Disposition"])
                (get-in r [:headers "Set-Cookie"]) (Arrays/equals ^bytes bytes ^bytes sent)])
            (str path ": through the stack, the download as built and its bytes whole"))))))

(deftest attachment-with-options-is-sent-under-its-type--and-shown-in-place-only-for-the-five-viewable-types--anything-else-refused-at-the-call
  (let [body    (byte-array 3)
        refused (fn [f] (try (f) :answered (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        shape   (fn [r] [(sort (keys r)) (:status r) (:headers r) (identical? body (:body r))])
        inline  (fn [type] [(str "web-base: attachment shows a file in place only as application/pdf, image/png, image/jpeg,"
                                 " image/gif or image/webp, spelt exactly — not " (pr-str type) "; anything else could run a"
                                 " script in this origin, so send it as a download")
                            {:filename "r.pdf" :content-type type}])
        no-type (fn [type] [(str "web-base: attachment was given a :content-type that is no media type: " (pr-str type))
                            {:filename "r.pdf" :content-type type}])]
    (doseq [type ["application/pdf" "image/png" "image/jpeg" "image/gif" "image/webp"]]
      (is (= [[:body :headers :status] 200 {"Content-Type" type "Content-Disposition" "inline; filename=\"r.pdf\"; filename*=UTF-8''r.pdf"} true]
             (shape (response/attachment body "r.pdf" {:content-type type :inline? true})))
          (str type ": shown in place under its type, the name spelt twice")))
    (is (= [[:body :headers :status] 200 {"Content-Type" "application/pdf" "Content-Disposition" "attachment; filename=\"r.pdf\"; filename*=UTF-8''r.pdf"} true]
           (shape (response/attachment body "r.pdf" {:content-type "application/pdf"})))
        "a type without :inline? is still a download")
    (is (= {"Content-Type" "text/csv" "Content-Disposition" "attachment; filename=\"r.csv\"; filename*=UTF-8''r.csv"}
           (:headers (response/attachment body "r.csv" {:content-type "text/csv" :inline? false})))
        "any media type may be downloaded; :inline? false is a download")
    (is (= {"Content-Type" "application/octet-stream" "Content-Disposition" "attachment; filename=\"r.bin\"; filename*=UTF-8''r.bin"}
           (:headers (response/attachment body "r.bin" {})))
        "no options: the octet-stream download")
    (is (= "inline; filename=\"x_y.png\"; filename*=UTF-8''x%2Fy.png"
           (get-in (response/attachment body "x/y.png" {:content-type "image/png" :inline? true}) [:headers "Content-Disposition"]))
        "shown in place, the fallback carries no directory either")
    (doseq [type ["image/svg+xml" "text/html" "application/xhtml+xml" "application/xml" "text/xml" "text/plain" "text/javascript"
                  "application/javascript" "image/svg" "image/bmp" "application/pdf; charset=x" "Application/PDF" "image/JPEG"]]
      (is (= (inline type) (refused #(response/attachment body "r.pdf" {:content-type type :inline? true})))
          (str (pr-str type) " with :inline?: refused at the call — the five, spelt exactly, and nothing else")))
    (is (= (inline "application/octet-stream") (refused #(response/attachment body "r.pdf" {:inline? true})))
        "no type with :inline?: the octet-stream it would be sent as is refused")
    (doseq [truthy [1 "yes" :y]]
      (is (= (inline "image/svg+xml") (refused #(response/attachment body "r.pdf" {:content-type "image/svg+xml" :inline? truthy})))
          (str (pr-str truthy) " as :inline? is inline, and judged as inline")))
    (is (= ["web-base: attachment takes :content-type and :inline?, not :cache" {:filename "r.pdf" :unknown [:cache]}]
           (refused #(response/attachment body "r.pdf" {:content-type "application/pdf" :inline? true :cache "x"})))
        "an option it does not take is refused, named")
    (is (= ["web-base: attachment takes :content-type and :inline?, not \"b\" :a" {:filename "r.pdf" :unknown ["b" :a]}]
           (refused #(response/attachment body "r.pdf" {:a 1 "b" 2})))
        "several, of mixed kinds, named in a stable order — never a ClassCastException")
    (doseq [bad ["" "  " nil "pdf" :application/pdf "text/plain\r\nX-Evil: 1" "text/\u0000plain" "/pdf"]]
      (is (= (no-type bad) (refused #(response/attachment body "r.pdf" {:content-type bad})))
          (str (pr-str bad) ": no media type, refused, never sent as a header")))
    (testing "the order the refusals are judged in: the name, an option, the type, then showing it in place"
      (is (= ["web-base: attachment needs a file name, and was given none" {:filename ""}]
             (refused #(response/attachment body "" {:content-type "" :inline? true :cache 1}))))
      (is (= ["web-base: attachment takes :content-type and :inline?, not :cache" {:filename "r.pdf" :unknown [:cache]}]
             (refused #(response/attachment body "r.pdf" {:content-type "" :inline? true :cache 1}))))
      (is (= (no-type "") (refused #(response/attachment body "r.pdf" {:content-type "" :inline? true})))))
    (doseq [[label filename opts expected]
            [["a blank name" "" {} ["web-base: attachment needs a file name, and was given none" {:filename ""}]]
             ["an unknown option" "r.pdf" {:cache 1} ["web-base: attachment takes :content-type and :inline?, not :cache" {:filename "r.pdf" :unknown [:cache]}]]
             ["no media type" "r.pdf" {:content-type "pdf"} (no-type "pdf")]
             ["svg in place" "r.pdf" {:content-type "image/svg+xml" :inline? true} (inline "image/svg+xml")]]]
      (let [closed (atom false)
            stream (proxy [java.io.ByteArrayInputStream] [(byte-array 3)] (close [] (reset! closed true)))]
        (is (= expected (refused #(response/attachment stream filename opts))) (str label ": witness: refused"))
        (is (true? @closed) (str label ": and the stream was closed, not left open for the collector"))))
    (let [stream (proxy [java.io.ByteArrayInputStream] [(byte-array 3)] (close [] (throw (java.io.IOException. "close failed"))))
          e      (try (response/attachment stream "" {}) nil (catch Exception e e))]
      (is (= ["web-base: attachment needs a file name, and was given none" {:filename ""} [["close failed"]]]
             [(ex-message e) (ex-data e) (mapv (juxt ex-message) (.getSuppressed ^Throwable e))])
          "a stream whose close fails: the refusal still names the mistake, the close's failure suppressed on it"))
    (let [h      (wb/handler {:session    {:key KEY}
                              :subject-fn #(get-in % [:session :user])
                              :routes     [["/i" {:get (fn [_] (response/attachment body "r.pdf" {:content-type "application/pdf" :inline? true}))}]
                                           ["/in" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/i"} :body ""} {:user "ann"}))}]]})
          anon   (h (mock/request :get "/i"))
          login  (h (mock/header (mock/request :post "/in") "Sec-Fetch-Site" "same-origin"))
          signed (h (wt/with-cookies (mock/request :get "/i") login))
          seen   (fn [r] [(:status r) (get-in r [:headers "Content-Type"]) (get-in r [:headers "Content-Disposition"])
                          (get-in r [:headers "X-Content-Type-Options"]) (get-in r [:headers "Cache-Control"])])]
      (is (= 303 (:status login)) "witness: signed in")
      (is (= [200 "application/pdf" "inline; filename=\"r.pdf\"; filename*=UTF-8''r.pdf" "nosniff" nil] (seen anon))
          "through the stack, anonymous: the type and the disposition as built, the browser told not to sniff, and cacheable")
      (is (= [200 "application/pdf" "inline; filename=\"r.pdf\"; filename*=UTF-8''r.pdf" "nosniff" "no-store"] (seen signed))
          "the same file to somebody signed in is no-store without a word from the host"))))
