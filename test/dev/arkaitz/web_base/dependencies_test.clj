(ns dev.arkaitz.web-base.dependencies-test
  "Guards SPEC §10: integrant is used, not imposed. Only the optional namespace
  may reference it. Because integrant sits on the base classpath, an illegal
  require compiles, loads and passes every other test — this scan is the only
  signal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang LineNumberingPushbackReader ReaderConditional]
           [java.io File PushbackReader]))

(def ^:private anchor-path "dev/arkaitz/web_base.clj")
(def ^:private anchor-ns 'dev.arkaitz.web-base)
(def ^:private exempt-path "dev/arkaitz/web_base/integrant.clj")
(def ^:private exempt-ns 'dev.arkaitz.web-base.integrant)

(defn- src-root
  "The src directory, located through the classpath so a different cwd cannot
  turn the scan into a vacuous walk over nothing."
  []
  (let [url (io/resource anchor-path)]
    (when (and url (= "file" (.getProtocol url)))
      (let [anchor (io/file url)
            depth  (count (str/split anchor-path #"/"))]
        (nth (iterate #(.getParentFile ^File %) anchor) depth)))))

(defn- source-files
  "Every Clojure source under root, keyed by its /-separated relative path."
  [^File root]
  (let [prefix (inc (count (.getPath root)))]
    (into {}
          (for [^File f (file-seq root)
                :when (and (.isFile f)
                           (re-find #"\.clj[cs]?$" (.getName f)))]
            [(str/replace (subs (.getPath f) prefix) File/separator "/") f]))))

(defn- integrant-name? [x]
  (let [named? (or (symbol? x) (keyword? x))
        hit?   (fn [^String s] (and s (or (= s "integrant") (str/starts-with? s "integrant."))))]
    (and named?
         (or (hit? (namespace x))
             (hit? (name x))
             (= (str exempt-ns) (namespace x))
             (= (str exempt-ns) (str x))))))

(defn- offenders
  "Every reference `name?` accepts inside one form, plus tags in integrant's
  `ig` namespace. Walks quoted forms, reader conditionals (both branches) and
  tagged literals."
  [name? form]
  (let [found (atom [])]
    (letfn [(walk [x]
              (cond
                (instance? ReaderConditional x) (walk (.form ^ReaderConditional x))
                (tagged-literal? x) (do (when (= "ig" (namespace (:tag x)))
                                          (swap! found conj (symbol (str "#" (:tag x)))))
                                        (walk (:form x)))
                (map? x) (doseq [[k v] x] (walk k) (walk v))
                (coll? x) (doseq [y x] (walk y))
                (name? x) (swap! found conj x)))]
      (walk form))
    @found))

(defn- read-all-forms
  "Top-level forms of a file with their line numbers. Reader conditionals are
  preserved and data readers are disabled so `#ig/ref` stays inspectable data
  instead of resolving through integrant's own data_readers."
  [^File file]
  ;; `::alias/kw` only reads inside its own namespace; folding `::` to `:`
  ;; keeps every symbol and keyword name intact, which is all the scan looks at.
  (with-open [rdr (LineNumberingPushbackReader.
                   (java.io.StringReader. (str/replace (slurp file) "::" ":")))]
    (binding [*read-eval*               false
              *data-readers*            {}
              *default-data-reader-fn*  tagged-literal]
      (loop [forms []]
        (let [line (.getLineNumber rdr)
              form (read {:read-cond :preserve :eof ::eof} ^PushbackReader rdr)]
          (if (= ::eof form)
            forms
            ;; The reader stamps :line on collections; the pre-read line is only
            ;; a fallback for scalar top-level forms, which carry no metadata.
            (recur (conj forms {:line (or (:line (meta form)) line) :form form}))))))))

(defn- scan [name? files]
  (reduce (fn [acc [path ^File file]]
            (let [{:keys [forms error]} (try {:forms (read-all-forms file)}
                                             (catch Exception e {:error (.getMessage e)}))]
              (cond
                error (update acc :read-errors conj {:file path :error error})
                :else (update acc :violations into
                              (for [{:keys [line form]} forms
                                    offender (offenders name? form)]
                                {:file path :line line
                                 :top-form (when (seq? form) (first form))
                                 :offender offender})))))
          {:read-errors [] :violations []}
          files))

(defn- edn-file-literals
  "Every string literal under `root` that names an EDN file."
  [files]
  (vec (sort (for [[path file] files
                   {:keys [form]} (read-all-forms file)
                   s (filter string? (tree-seq coll? seq form))
                   :when (str/ends-with? s ".edn")]
               [path s]))))

(deftest the-base-names-no-edn-file-of-its-own
  ;; SPEC §3 and §11's second trap: config is passed in, and a base that knew
  ;; a file name could look for it, letting the working directory decide the
  ;; session signing key. This scan is narrower than that condition — it
  ;; catches the shape the demo uses, `(def env-file "env.local.edn")`, not
  ;; every way a path could be built. The condition itself rests on rule 1
  ;; and on review.
  (let [root (src-root)]
    (is (some? root) "precondition: the sources were located through the classpath")
    (when root
      (let [files (source-files root)]
        (is (contains? files anchor-path) "precondition: the scan reached the real sources")
        (is (= [] (edn-file-literals files))
            "an EDN file name in the base is how auto-detection starts: pass the path in instead")))))

(deftest only-the-integrant-namespace-references-integrant
  (let [root (src-root)]
    (is (some? root)
        (str anchor-path " not on classpath as a file: " (pr-str (io/resource anchor-path))))
    (when root
      (let [files (source-files root)]
        (testing "the scan reached the real sources"
          (is (contains? files anchor-path)
              (str "anchor " anchor-path " not found among scanned sources under "
                   root ": " (pr-str (keys files))))
          (when-let [anchor (get files anchor-path)]
            (let [{:keys [form]} (first (read-all-forms anchor))]
              (is (and (seq? form) (= 'ns (first form)) (= anchor-ns (second form)))
                  (str "first form of " anchor-path " is not (ns " anchor-ns " …): "
                       (pr-str form))))))
        (let [{:keys [read-errors violations]} (scan integrant-name? (dissoc files exempt-path))]
          (is (= [] read-errors)
              (str "could not read: " (pr-str read-errors)))
          (is (= [] violations)
              (str "integrant referenced outside " exempt-ns ": " (pr-str violations))))))))

(def ^:private native-path "dev/arkaitz/web_base/native.clj")
(def ^:private native-ns 'dev.arkaitz.web-base.native)

(defn- native-name?
  "The namespace named in full, and also the bare segment `native`, because
  `(:require [dev.arkaitz.web-base [native :as n]])` loads it just as well
  and spells no full name anywhere. The integrant guard catches its own
  prefix-list form only because its library root is a single segment."
  [x]
  (and (or (symbol? x) (keyword? x))
       (or (= (str native-ns) (str x))
           (= (str native-ns) (namespace x))
           (= "native" (name x)))))

(deftest nothing-in-the-base-references-the-native-namespace
  ;; Requiring it is the opt-in, exactly as for integrant: it installs a
  ;; method on a multimethod that belongs to Ring, so a consumer who never
  ;; asked must never get it. On a JVM the whole suite passes either way,
  ;; which is why this scan is the only signal.
  (let [root (src-root)]
    (is (some? root) "precondition: the sources were located through the classpath")
    (when root
      (let [files (source-files root)]
        (is (contains? files native-path)
            (str "precondition: " native-path " is among the scanned sources"))
        ;; The walk was parameterised so two guards share it, which is the
        ;; edit that can neuter one while the other stays green.
        (is (seq (offenders native-name? '(ns x (:require [dev.arkaitz.web-base.native]))))
            "positive control: the guard fires on the plain require")
        (is (seq (offenders native-name? '(ns x (:require [dev.arkaitz.web-base [native :as n]]))))
            "positive control: and on the prefix list, which loads it just the same")
        (let [{:keys [read-errors violations]} (scan native-name? (dissoc files native-path))]
          (is (= [] read-errors) (str "could not read: " (pr-str read-errors)))
          (is (= [] violations)
              (str native-ns " referenced outside itself: " (pr-str violations))))))))
