(ns dev.arkaitz.web-base.csv
  "Comma-separated values a spreadsheet opens with every character intact (since 0.18.0).
  The separator is the comma; a spreadsheet set to another list separator — Excel under
  a Spanish locale — asks for it on import.

  - The bytes are UTF-8 with a byte order mark, so Excel reads a ñ as one character.
  - Every field is quoted, with its quotes doubled, and every line ends in CRLF.
  - A field a spreadsheet would read as a formula is prefixed with an apostrophe, so it
    is shown and never run. That is any field beginning with one of OWASP's CSV-injection
    characters (`=`, `+`, `-`, `@`, tab, carriage return, line feed) or their full-width
    forms (`＝`, `＋`, `－`, `＠`). A product named `=HYPERLINK(…)` is text. A negative
    number becomes the text `'-5` as well, which is the price OWASP's own alternatives
    pay too.

  Every host that exports a list writes this. The guard is the part each copy got
  wrong, measured on the first one, which missed the line feed and the full-width
  forms."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base.response :as response]))

(def ^:private formula-start #"^[=+\-@\t\r\n＝＋－＠]")

(defn- field [v]
  (let [text (str v)
        text (if (re-find formula-start text) (str "'" text) text)]
    (str "\"" (str/replace text "\"" "\"\"") "\"")))

(defn write
  "`rows`, each a sequence of values (`str` of each, nil as empty), as the bytes of a
  CSV file."
  [rows]
  (.getBytes (str "\uFEFF" (apply str (for [row rows] (str (str/join "," (map field row)) "\r\n"))))
             "UTF-8"))

(defn attachment
  "`rows` as a CSV download saved under `filename`: `write` inside
  `response/attachment`, typed `text/csv; charset=utf-8`. The no-caching rule is still
  the host's, as `response/attachment` says."
  [rows filename]
  (assoc-in (response/attachment (write rows) filename)
            [:headers "Content-Type"] "text/csv; charset=utf-8"))
