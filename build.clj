(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'dev.arkaitz/web-base)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Builds the library jar from src and resources only. The demo lives on the
  :demo alias paths and must never end up inside the artifact."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis
                  :src-dirs  ["src"]})
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file})
    (println "Built" jar-file)))
