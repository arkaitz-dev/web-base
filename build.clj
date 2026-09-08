(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deploy*]))

(def lib 'dev.arkaitz/web-base)
(def version "0.1.0")
(def url "https://github.com/arkaitz-dev/web-base")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def pom-file (format "%s/META-INF/maven/%s/%s/pom.xml" class-dir (namespace lib) (name lib)))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Builds the library jar from src and resources only. The demo lives on the
  :demo alias paths and must never end up inside the artifact."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (basis)
                :src-dirs  ["src"]
                :scm       {:url                 url
                            :connection          (str "scm:git:" url ".git")
                            :developerConnection (str "scm:git:" url ".git")
                            :tag                 (str "v" version)}
                :pom-data  [[:description "A super-micro-framework for server-rendered Clojure web applications: routing, sessions, errors, a page shell, htmx."]
                            [:url url]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))

(defn install
  "The jar into the local Maven repository (~/.m2), for a consumer using
  :mvn/version on this machine."
  [_]
  (jar nil)
  (b/install {:basis     (basis)
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed" lib version))

(defn deploy
  "The jar and its pom to Clojars. Credentials come from CLOJARS_USERNAME and
  CLOJARS_PASSWORD (a deploy token) in the environment; the group must be
  verified on Clojars beforehand."
  [_]
  (jar nil)
  (deploy*/deploy {:installer      :remote
                   :artifact       jar-file
                   :pom-file       pom-file
                   :sign-releases? false})
  (println "Deployed" lib version))
