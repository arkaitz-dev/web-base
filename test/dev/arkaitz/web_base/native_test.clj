(ns dev.arkaitz.web-base.native-test
  "What makes the base compilable to a GraalVM native image, in one place.
  None of it is observable through behaviour — a request id is random whether
  its generator was created at load or on first use, and the resource method
  is inert on a JVM — so each test observes the structural property the image
  builder itself checks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            ;; required for the compile-time var references below; the scan
            ;; itself loads every namespace of the base from the sources
            [dev.arkaitz.web-base.log]
            [dev.arkaitz.web-base.native]
            [dev.arkaitz.web-base.security]
            [ring.util.response :as response])
  (:import [java.io ByteArrayInputStream File InputStream]
           [java.lang.reflect Field Modifier]
           [java.net URI URL URLConnection URLStreamHandler]
           [java.security SecureRandom]
           [java.util Date IdentityHashMap]))

;; --- the image heap -------------------------------------------------------

(defn- base-namespaces
  "Every namespace of the base, from the sources on disk rather than from
  what happens to be loaded: a filtered test run loads three of them, and a
  scan over `all-ns` would then miss the other twelve."
  []
  (let [anchor (io/file (io/resource "dev/arkaitz/web_base.clj"))
        src    (.getParentFile (.getParentFile (.getParentFile anchor)))]
    (for [^File f (file-seq src)
          :when   (str/ends-with? (.getName f) ".clj")
          :let    [path (subs (.getPath f) (inc (count (.getPath src))))]]
      (symbol (-> path
                  (str/replace #"\.clj$" "")
                  (str/replace "/" ".")
                  (str/replace "_" "-"))))))

(defn- children
  "What `x` holds, one hop out. Containers are opened through their public
  API — reflecting into the JDK's own classes throws under strong
  encapsulation, and a vector keeps its elements in an array, which declares
  no fields at all. Only boxes that cannot block are dereferenced: a promise
  or a future would hang, and forcing a delay would realize the very thing
  the caller is about to measure."
  [x]
  (cond
    (.isArray (class x))               (seq x)
    (instance? java.util.Map x)        (concat (keys x) (vals x))
    (instance? java.util.Collection x) (seq x)
    (or (instance? clojure.lang.Atom x)
        (instance? clojure.lang.Ref x)
        (instance? clojure.lang.Volatile x)) [@x]
    (re-find #"^(java|javax|jdk|sun|com\.sun)\." (.getName (class x))) nil
    :else (keep (fn [^Field f]
                  (when-not (Modifier/isStatic (.getModifiers f))
                    (.setAccessible f true)
                    (.get f x)))
                (.getDeclaredFields (class x)))))

(defn- reaches-a-secure-random?
  "Whether a SecureRandom is reachable from `value`, `depth` hops deep.
  native-image walks the whole heap; this walks the shapes a var root can
  hold — the generator itself, a closure's captured field, a delay's thunk,
  a collection, an atom's contents."
  [value depth]
  (let [seen (IdentityHashMap.)]
    (letfn [(walk [x d]
              (cond
                (nil? x)                   false
                (instance? SecureRandom x) true
                (neg? d)                   false
                (.containsKey seen x)      false
                :else (do (.put seen x true)
                          (boolean (some #(walk % (dec d)) (children x))))))]
      (walk value depth))))

(defn- violations
  "Every var of the base whose root would put a random generator in the image
  heap, or whose delay was already forced while the namespace loaded."
  []
  (vec (for [n     (base-namespaces)
             [_ v] (ns-interns (find-ns n))
             :let  [root (when (.hasRoot v) (var-get v))
                    why  (cond
                           (reaches-a-secure-random? root 4)              :reaches-a-secure-random
                           (and (instance? clojure.lang.Delay root)
                                (realized? root))                        :delay-realized-at-load)]
             :when why]
         [v why])))

(deftest no-var-root-of-the-base-reaches-a-secure-random-at-load--and-the-delays-yield-one-when-forced
  ;; Reloaded, not merely required: another test may have forced these
  ;; delays already, and the property is about the state a fresh load leaves.
  (let [namespaces (base-namespaces)]
    (is (<= 15 (count namespaces))
        (str "precondition: every source of the base was found, not a subset: " (pr-str namespaces)))
    (is (some #{'dev.arkaitz.web-base.session} namespaces)
        "precondition: including the ones a filtered run never loads")
    (doseq [n namespaces] (require n :reload)))
  (is (= [] (violations))
      "no var root reaches a SecureRandom, and no delay is forced while loading")
  (is (= [clojure.lang.Delay clojure.lang.Delay]
         [(class (var-get #'dev.arkaitz.web-base.log/random))
          (class (var-get #'dev.arkaitz.web-base.security/random))])
      "the two roots are delays, so the image holds the box and not the generator")
  (is (= [SecureRandom SecureRandom]
         [(class @@#'dev.arkaitz.web-base.log/random)
          (class @@#'dev.arkaitz.web-base.security/random)])
      "and forcing them yields a SecureRandom"))

;; --- the resource protocol ------------------------------------------------

(deftest requiring-native-adds-exactly-the-resource-method-and-leaves-file-and-jar-to-ring
  (is (= #{:file :jar :resource} (set (keys (methods response/resource-data))))
      "exactly one method added, and no :default that would answer for any protocol")
  (is (str/starts-with? (.getName (class (get-method response/resource-data :resource)))
                        "dev.arkaitz.web_base.native$")
      "the :resource method is this namespace's")
  (is (= [true true]
         (mapv #(str/starts-with? (.getName (class (get-method response/resource-data %)))
                                  "ring.util.response$")
               [:file :jar]))
      ":file and :jar are still Ring's own, not redefined here")
  ;; The day ring-core ships its own :resource method, this namespace stops
  ;; being an addition and silently overrides it. This one file is the whole
  ;; signal: a method added from anywhere else in ring-core is not seen.
  (let [source (io/resource "ring/util/response.clj")]
    (is (some? source) "ring-core still ships its source, so the tripwire can read it")
    (is (nil? (re-find #"defmethod\s+resource-data\s+:resource" (slurp source)))
        "ring-core does not define :resource itself yet — when it does, delete this namespace")))

(defn- resource-url
  "A URL whose protocol is `resource`, carrying its own handler so nothing is
  registered globally. The multimethod's real dispatch is what is under test,
  not the method function."
  [path connection-of]
  (URL/of (URI. (str "resource:" path))
          (proxy [URLStreamHandler] [] (openConnection [u] (connection-of u)))))

(defn- unknowing-connection
  "A connection answering what URLConnection itself answers when it does not
  know: -1 for the length, 0 for the time. The base class says it, not this
  test."
  [u]
  (proxy [URLConnection] [u]
    (connect [] nil)
    (getInputStream [] (ByteArrayInputStream. (byte-array 0)))))

(deftest resource-data-maps-a-resource-connection-to-rings-jar-shape
  (let [file (File/createTempFile "wb-native-probe" ".txt")]
    (try
      (spit file "abc")
      (is (true? (.setLastModified file 1700000000000)) "precondition: the fixture's time was set")
      (let [data (response/resource-data
                  (resource-url "/probe" (fn [_] (.openConnection (.toURL (.toURI file))))))]
        (is (= {:content-length 3 :last-modified (Date. 1700000000000)} (dissoc data :content))
            "a known length and time come through as Ring reports them, and no other key")
        (with-open [content ^InputStream (:content data)]
          (is (= "abc" (String. (.readAllBytes content) "UTF-8")) "and :content is the resource's bytes")))
      (finally (.delete file))))
  (let [data (response/resource-data (resource-url "/probe" unknowing-connection))]
    (with-open [_ ^InputStream (:content data)]
      (is (= {:content-length nil :last-modified nil} (dissoc data :content))
          "a connection that does not know answers nil, never -1 or the epoch")))
  (let [data (response/resource-data
              (resource-url "/probe" (fn [u] (proxy [URLConnection] [u]
                                               (connect [] nil)
                                               (getContentLengthLong [] 0)
                                               (getInputStream [] (ByteArrayInputStream. (byte-array 0)))))))]
    (with-open [_ ^InputStream (:content data)]
      (is (= {:content-length 0 :last-modified nil} (dissoc data :content))
          "a length known to be zero stays 0, as Ring's :jar does"))))

(deftest resource-data-refuses-a-path-spelled-as-a-directory
  ;; The paths are the ones a native image really produced, read from inside
  ;; a binary: `resource:/0!/dev/arkaitz/web_base/public/` for the base's own
  ;; assets and `resource:/1!/public/css/` for a host's static root. Measured
  ;; there too: with this guard `/wb/` and `/css/` answer 404 where they
  ;; served a listing before.
  (doseq [path ["/0!/dev/arkaitz/web_base/public/" "/1!/public/css/" "/anything/at/all/"]]
    (is (nil? (response/resource-data (resource-url path unknowing-connection)))
        (str "a path spelled as a directory is nil: " path)))
  (let [data (response/resource-data (resource-url "/1!/public/css/site.css" unknowing-connection))]
    (with-open [_ ^InputStream (:content data)]
      (is (some? data) "control: a file under the same root is still served"))))
