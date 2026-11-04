(ns dev.arkaitz.web-base.csv-test
  "Every special character here is built from an integer and the file stays ASCII:
  the tool these tests were written with turns an escape into the glyph, and a BOM or a
  full-width sign that cannot be seen cannot be reviewed. `(.getBytes s)` with no
  charset is an equivalent mutant of `write` on every JDK from 18 on (UTF-8 is the
  default since JEP 400), so these bytes cannot tell it from the explicit charset."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.web-base.csv :as csv])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private BOM [0xEF 0xBB 0xBF])

(defn- unsigned [bytes] (mapv #(bit-and 0xFF %) bytes))

(defn- ascii [s] (map int s))

(defn- first-difference
  "Where two byte vectors part, in hex, so a red names the offset."
  [expected actual]
  (let [n (or (first (keep-indexed (fn [i [e a]] (when (not= e a) i)) (map vector expected actual)))
              (min (count expected) (count actual)))]
    (format "first difference at byte %d: expected %s, got %s (lengths %d and %d)"
            n (some->> (get expected n) (format "%02X")) (some->> (get actual n) (format "%02X"))
            (count expected) (count actual))))

(deftest the-test-file-is-ascii--so-every-special-character-in-it-is-visible
  (let [source (slurp (io/resource "dev/arkaitz/web_base/csv_test.clj"))]
    (is (every? #(< (int %) 128) source) "a pasted glyph in this file hides from review")))

(deftest the-bytes-of-a-small-table--bom--every-field-quoted--quotes-doubled--crlf--nil-empty
  (let [rows     [["id" "name" "qty"]
                  [1 (str "Comma, \"quoted\"\nline " (char 0xF1)) nil]
                  [2.5 "two\r\nlines" ""]]
        expected (vec (concat BOM
                              (ascii "\"id\",\"name\",\"qty\"\r\n\"1\",\"Comma, \"\"quoted\"\"\nline ")
                              [0xC3 0xB1]
                              (ascii "\",\"\"\r\n\"2.5\",\"two\r\nlines\",\"\"\r\n")))
        actual   (unsigned (csv/write rows))]
    (is (= 81 (count expected)) "witness: the expectation is the 81 bytes measured")
    (is (= expected actual) (str "the file, byte for byte - " (first-difference expected actual))))
  (is (= BOM (unsigned (csv/write []))) "no rows: the byte order mark alone"))

(defn- line-of
  "The one line `v` alone writes, BOM witnessed and taken off."
  [v]
  (let [bytes (unsigned (csv/write [[v]]))]
    (is (= BOM (vec (take 3 bytes))) (str (pr-str v) ": the file opens with the byte order mark"))
    (String. (byte-array (drop 3 bytes)) "UTF-8")))

(deftest a-field-beginning-with-one-of-owasps-characters-is-prefixed-with-an-apostrophe--and-nothing-else-is
  (doseq [[n name] [[0x3D "equals"] [0x2B "plus"] [0x2D "minus"] [0x40 "at"]
                    [0x09 "tab"] [0x0D "carriage return"] [0x0A "line feed"]
                    [0xFF1D "full-width equals"] [0xFF0B "full-width plus"]
                    [0xFF0D "full-width minus"] [0xFF20 "full-width at"]]]
    (is (= (str "\"'" (char n) "x\"\r\n") (line-of (str (char n) "x")))
        (format "U+%04X (%s): a field beginning with it is shown, never run" n name)))
  (testing "every field of every row is guarded, not the first alone"
    (is (= (vec (concat BOM (ascii "\"a\",\"b\"\r\n\"c\",\"'=d\"\r\n")))
           (unsigned (csv/write [["a" "b"] ["c" "=d"]])))
        "a formula in the second column of the second row is shown, never run"))
  (testing "a number is written through str, and guarded like text"
    (is (= "\"'-5\"\r\n" (line-of -5)))
    (is (= "\"'-0.5\"\r\n" (line-of -0.5))))
  (doseq [[v expected] [["x" "\"x\"\r\n"] ["1" "\"1\"\r\n"] ["0" "\"0\"\r\n"]
                        [" =x" "\" =x\"\r\n"] ["'x" "\"'x\"\r\n"] ["a=b" "\"a=b\"\r\n"]
                        ["" "\"\"\r\n"] [nil "\"\"\r\n"] ["|x" "\"|x\"\r\n"] ["%x" "\"%x\"\r\n"]
                        [";x" "\";x\"\r\n"] ["(x" "\"(x\"\r\n"] ["." "\".\"\r\n"] ["/" "\"/\"\r\n"]
                        ["\"x" "\"\"\"x\"\r\n"] [7 "\"7\"\r\n"] [2.5 "\"2.5\"\r\n"]]]
    (is (= expected (line-of v)) (str (pr-str v) ": not a formula start, left alone"))))

(deftest attachment-is-text-csv-utf-8-under-its-name-as-response-attachment-spells-it--the-table-as-its-body--and-a-blank-name-is-refused
  (let [r (csv/attachment [["a" "b"]] (str "pedidos " (char 0xF1) ".csv"))]
    (is (= {:status  200
            :headers {"Content-Type"        "text/csv; charset=utf-8"
                      "Content-Disposition" "attachment; filename=\"pedidos _.csv\"; filename*=UTF-8''pedidos%20%C3%B1.csv"}}
           (dissoc r :body))
        "a CSV download: 200, text/csv; charset=utf-8, the name as RFC 6266 spells it, and nothing else")
    (is (bytes? (:body r)) "the body is bytes")
    (is (= (vec (concat BOM (ascii "\"a\",\"b\"\r\n"))) (unsigned (:body r))) "the body is the table's bytes"))
  (doseq [blank [nil "" "  "]]
    (is (= ["web-base: attachment needs a file name, and was given none" {:filename blank}]
           (try (csv/attachment [["a"]] blank) :answered
                (catch ExceptionInfo e [(ex-message e) (ex-data e)])))
        (str (pr-str blank) ": a download with no name is refused, as response/attachment refuses it"))))
