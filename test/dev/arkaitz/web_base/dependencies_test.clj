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
  "Every integrant reference inside one form: symbols and keywords naming the
  library or the exempt namespace, and tags in integrant's `ig` namespace.
  Walks quoted forms, reader conditionals (both branches) and tagged literals."
  [form]
  (let [found (atom [])]
    (letfn [(walk [x]
              (cond
                (instance? ReaderConditional x) (walk (.form ^ReaderConditional x))
                (tagged-literal? x) (do (when (= "ig" (namespace (:tag x)))
                                          (swap! found conj (symbol (str "#" (:tag x)))))
                                        (walk (:form x)))
                (map? x) (doseq [[k v] x] (walk k) (walk v))
                (coll? x) (doseq [y x] (walk y))
                (integrant-name? x) (swap! found conj x)))]
      (walk form))
    @found))

(defn- read-all-forms
  "Top-level forms of a file with their line numbers. Reader conditionals are
  preserved and data readers are disabled so `#ig/ref` stays inspectable data
  instead of resolving through integrant's own data_readers."
  [^File file]
  (with-open [rdr (LineNumberingPushbackReader. (io/reader file))]
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

(defn- scan [files]
  (reduce (fn [acc [path ^File file]]
            (let [{:keys [forms error]} (try {:forms (read-all-forms file)}
                                             (catch Exception e {:error (.getMessage e)}))]
              (cond
                error (update acc :read-errors conj {:file path :error error})
                :else (update acc :violations into
                              (for [{:keys [line form]} forms
                                    offender (offenders form)]
                                {:file path :line line
                                 :top-form (when (seq? form) (first form))
                                 :offender offender})))))
          {:read-errors [] :violations []}
          files))

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
        (let [{:keys [read-errors violations]} (scan (dissoc files exempt-path))]
          (is (= [] read-errors)
              (str "could not read: " (pr-str read-errors)))
          (is (= [] violations)
              (str "integrant referenced outside " exempt-ns ": " (pr-str violations))))))))
