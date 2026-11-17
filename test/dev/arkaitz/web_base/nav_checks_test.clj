(ns dev.arkaitz.web-base.nav-checks-test
  "What a browser saw of `/wb/nav.js`, held to the bytes it saw. test/browser/nav-check.mjs
  runs `clojure -M:nav-probe` in real engines and test/resources/nav-checks.edn records each
  check; this holds that every recorded check passed and that nav.js, the check and the probe
  are still the bytes they ran against — a change to any reds here until the checks run
  again, since no JVM test can see a swap."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.security MessageDigest)))

(def ^:private record (edn/read-string (slurp (io/resource "nav-checks.edn"))))

(def ^:private files
  {"nav.js"        "resources/dev/arkaitz/web_base/public/nav.js"
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

(deftest every-recorded-nav-check-passed
  (let [runs (:runs record)]
    (is (= #{"chromium" "webkit"} (set (map :engine runs))) "witness: both engines were run")
    (doseq [{:keys [engine checks]} runs]
      (is (<= 6 (count checks)) (str "witness: " engine " recorded its checks, " (count checks)))
      (is (= [] (sort (keep (fn [[check pass]] (when-not (true? pass) check)) checks)))
          (str "checks that failed in " engine)))))
