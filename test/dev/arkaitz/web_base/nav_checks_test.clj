(ns dev.arkaitz.web-base.nav-checks-test
  "What a browser saw of `/wb/nav.js` and of the header `/wb/wb.css` lays out, held to the
  bytes it saw. test/browser/nav-check.mjs runs `clojure -M:nav-probe` in real engines and
  test/resources/nav-checks.edn records each check; this holds that every check the script
  declares was recorded and passed, and that nav.js, wb.css, the check and the probe are still
  the bytes they ran against — a change to any reds here until the checks run again, since no
  JVM test can see a swap or a layout."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.security MessageDigest)))

(def ^:private record (edn/read-string (slurp (io/resource "nav-checks.edn"))))

(def ^:private files
  {"nav.js"        "resources/dev/arkaitz/web_base/public/nav.js"
   "wb.css"        "resources/dev/arkaitz/web_base/public/wb.css"
   "nav-check.mjs" "test/browser/nav-check.mjs"
   "nav_probe.clj" "test/dev/arkaitz/web_base/nav_probe.clj"})

(defn- sha256 [path]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.readAllBytes (io/input-stream (io/file path))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(deftest the-nav-checks-were-run-against-these-bytes
  (is (= (set (keys files)) (set (keys (:files record)))) "witness: the record names the files it was run against")
  (doseq [[f path] files]
    (is (= (get-in record [:files f]) (sha256 path))
        (str f " changed since the nav checks ran: run test/browser/nav-check.mjs and regenerate nav-checks.edn"))))

(defn- declared-checks
  "The names nav-check.mjs gives its checks, read from its source: every `check(` takes a
  literal name, which the parity witness below holds."
  [src]
  (map #(str/replace (second %) "\\'" "'") (re-seq #"(?:check\(|checks\[)'((?:[^'\\]|\\.)*)'" src)))

(deftest every-recorded-nav-check-passed
  (let [runs (:runs record)
        src  (slurp (get files "nav-check.mjs"))
        names    (declared-checks src)
        declared (set names)]
    (is (= (count names) (count declared)) "witness: no two checks share a name, which would record one over the other")
    (is (= (count (re-seq #"\bcheck\s*\(" src)) (count (re-seq #"\bcheck\('" src)))
        "witness: every check( in nav-check.mjs names its check with a literal, so the declared set is whole")
    (is (= #{"chromium" "webkit"} (set (map :engine runs))) "witness: both engines were run")
    (doseq [{:keys [engine checks]} runs]
      (is (= declared (set (keys checks))) (str engine " recorded exactly the checks nav-check.mjs declares"))
      (is (= [] (sort (keep (fn [[check pass]] (when-not (true? pass) check)) checks)))
          (str "checks that failed in " engine)))))
