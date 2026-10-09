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
                         ["/u" {:wb/multipart {:max-file-size 2048 :max-file-count 10} :post (echo sent)}]]}))

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
           ["cr\rx.txt" "attachment; filename=\"cr_x.txt\"; filename*=UTF-8''cr%0Dx.txt"]]]
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
