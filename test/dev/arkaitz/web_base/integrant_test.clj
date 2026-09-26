(ns dev.arkaitz.web-base.integrant-test
  "A real Jetty through Integrant. Every HTTP call has a timeout and halt!
  runs in a finally: a hang is never a result."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.config :as config]
            [dev.arkaitz.web-base.integrant :as wbi]
            [integrant.core :as ig]
            [web-base-test.run-keys :as keys])
  (:import [clojure.lang ExceptionInfo]
           [java.net ConnectException ServerSocket URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server]))

(def ^:private client
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) (.connectTimeout (Duration/ofSeconds 2)) (.build)))

(defn- http [port path]
  (let [request  (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path))) (.timeout (Duration/ofSeconds 5)) (.GET) (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)
     :headers (into {} (map (fn [[k v]] [k (first v)])) (.map (.headers response)))}))

(defn- config [port]
  {::wb/handler {:routes [["/" {:get (fn [_] {:status 200 :body [:p "hi"]})}]] :session {:key "AAAAAAAAAAAAAAAAAAAAAA=="}}
   ::wb/server  {:handler (ig/ref ::wb/handler) :port port}})

(deftest init-starts-jetty-on-the-bound-port-and-halt-stops-it
  (let [sys (deref (future (ig/init (config 0))) 10000 ::timeout)]
    (is (not= ::timeout sys) "init returned: the server does not join")
    (when (map? sys)
      (let [handle (::wb/server sys) port (:port handle)]
        (try
          (is (= #{::wb/handler ::wb/server} (set (keys sys))))
          (is (fn? (::wb/handler sys)))
          (is (= #{:server :port} (set (keys handle))))
          (is (instance? Server (:server handle)))
          (is (pos-int? port))
          (let [r (http port "/")]
            (is (= [200 "<!DOCTYPE html>\n<p>hi</p>" "text/html;charset=utf-8"] [(:status r) (:body r) (get-in r [:headers "content-type"])])
                "Jetty normalises the content-type; the body is the page")
            (is (re-matches #"[A-Za-z0-9_-]{16}" (str (get-in r [:headers "x-request-id"])))))
          (let [r (http port "/wb/wb.css")]
            (is (= [200 "text/css"] [(:status r) (get-in r [:headers "content-type"])]))
            (is (str/starts-with? (:body r) "/* web-base structural stylesheet")))
          (finally
            (ig/halt! sys)))
        (is (= :refused (try (http port "/") (catch ConnectException _ :refused))) "after halt! the port refuses connections")
        (is (.isStopped ^Server (:server handle)))))))

(deftest init-key-server-honours-the-port-option
  ;; The port is free when the socket closes and taken by Jetty a moment
  ;; later; another process grabbing it in between would red this without a
  ;; defect, which is the price of proving the requested port is honoured.
  (let [free (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        sys  (deref (future (ig/init (config free))) 10000 ::timeout)]
    (is (map? sys) "init returned within the deadline")
    (when (map? sys)
      (try
        (is (= free (:port (::wb/server sys))) "the requested port is the bound one")
        (is (= [200 "<!DOCTYPE html>\n<p>hi</p>"] (let [r (http free "/")] [(:status r) (:body r)])))
        (finally
          (ig/halt! sys))))))

(deftest server-without-a-port-is-refused-rather-than-taking-80
  (is (= ["server options need a non-negative integer :port (0 for an ephemeral one)" {:config-key [:port] :value nil}]
         (try (wb/start identity {}) (catch ExceptionInfo e [(ex-message e) (ex-data e)])))))

(deftest read-string-reads-ig-ref-and-wb-env-in-the-same-edn
  (let [path (System/getenv "PATH")]
    (is (string? path) "precondition: PATH is set")
    (let [m (wbi/read-string "{:a #ig/ref :x/b :h #wb/env \"PATH\"}")]
      (is (= {:a (ig/ref :x/b) :h path} m))
      (is (ig/ref? (:a m))))
    (is (= ["environment variable WB_SURELY_UNSET_123 is not set" {:env "WB_SURELY_UNSET_123"}]
           (try (wbi/read-string "#wb/env \"WB_SURELY_UNSET_123\"") (catch ExceptionInfo e [(ex-message e) (ex-data e)]))))
    (is (thrown-with-msg? RuntimeException #"No reader function for tag wb/env" (ig/read-string "#wb/env \"PATH\""))
        "control: integrant alone does not know the tag — the merge is what adds it")))

(deftest read-string-with-readers-given-uses-them-and-keeps-integrant-s-own-tags
  (is (nil? (System/getenv "WB_TEST_FALLBACK"))
      "precondition: WB_TEST_FALLBACK is not exported — unset it in this shell and run again")
  (let [read (wbi/read-string (config/env-readers {"WB_TEST_FALLBACK" "from-the-map"} "probe")
                              "{:a #ig/ref :x/b :v #wb/env \"WB_TEST_FALLBACK\"}")]
    (is (= {:a (ig/ref :x/b) :v "from-the-map"} read)
        "the readers given resolve #wb/env, and #ig/ref is still integrant's")
    (is (ig/ref? (:a read))
        "as a Ref: the readers given are merged with integrant's, not put in their place")))

;; --- run! -----------------------------------------------------------------------

(def ^:private system-config #'wbi/system-config)
(def ^:private start #'wbi/start)

(def ^:private halted keys/halted)

(deftest run-reads-the-named-config-and-puts-a-given-port-where-the-host-said
  (let [options {:config "run-config.edn" :port-path [:test.run/port]}]
    (is (= [:ok 3000] (update (system-config options []) 1 :test.run/port)) "no argument: the resource's own port")
    (is (= [:ok 4567] (update (system-config options ["4567"]) 1 :test.run/port))
        "a port argument lands at the host's path, as a number")
    (doseq [bad ["abc" "0" "65536" "-1" ""]]
      (is (= [:error (str "the port must be a number from 1 to 65535, not " (pr-str bad))]
             (system-config options [bad]))
          (pr-str bad)))
    (is (= [:error "this host takes no port, and was given \"4567\""]
           (system-config (dissoc options :port-path) ["4567"])))
    (is (= [:error "config resource nowhere.edn not found on the classpath"]
           (system-config (assoc options :config "nowhere.edn") [])))
    (is (= [:error ":config must name a classpath resource, not nil"]
           (system-config (dissoc options :config) [])))))

(deftest run-reads-an-env-file-only-when-the-host-names-it
  (is (= "from-the-file"
         (:test.run/fallback (second (system-config {:config "run-env-config.edn"
                                                     :env-file "test/resources/env-fallback.edn"} [])))))
  (is (= [:error "environment variable WB_TEST_FALLBACK is not set"]
         (system-config {:config "run-env-config.edn"} []))
      "without the file named, the variable nobody set is not found — no file is looked for on the host's behalf — and it is a sentence, not a stack trace"))

(deftest a-system-that-fails-to-start-is-halted-and-reported-without-its-configuration
  (reset! halted [])
  (let [config (wbi/read-string (slurp (clojure.java.io/resource "run-failing-config.edn")))
        [outcome message] (start config)]
    (is (= [:error "failed to start: the database refused the login"] [outcome message])
        "the cause's message, and only that")
    (is (not (str/includes? message "S3CRET")) "never the configuration Integrant attaches to its own failure")
    (is (= [:first-started] @halted) "and the key that had started was halted, not leaked")))

(deftest a-malformed-config-or-port-path-is-a-sentence-not-a-stack-trace
  (let [[outcome message] (system-config {:config "malformed-edn.txt"} [])]
    (is (= :error outcome) "a resource the EDN reader refuses")
    (is (str/starts-with? (str message) "config resource malformed-edn.txt is not valid EDN: ")
        (str "named, with the reader's reason: " message)))
  (is (= [:error ":port-path must be a vector of keys, not :test.run/port"]
         (system-config {:config "run-config.edn" :port-path :test.run/port} ["4567"]))))

(deftest a-failure-names-the-keys-own-message-never-its-driver-cause-or-nothing
  (let [read #(wbi/read-string (slurp (clojure.java.io/resource %)))]
    (let [[_ message] (start (read "run-wrapping-config.edn"))]
      (is (= "failed to start: db-base: the database refused the login" message)
          "the failing key's own sentence, one link under Integrant's wrapper")
      (is (not (str/includes? message "S3CRET")) "not the driver's cause beneath it"))
    (is (= [:error "failed to start: java.lang.NullPointerException"] (start (read "run-silent-config.edn")))
        "an exception with no message is named by its class, not by an empty line")
    (is (= [:error (str "failed to start: the database refused the login;"
                        " halting what had started also failed: could not close the pool")]
           (start (read "run-bad-halt-config.edn")))
        "a partial system that cannot be halted says so, instead of escaping as a second exception")))

(defn- java-process
  "`main` in a JVM of its own, on this test's classpath, with `env` added."
  [main env]
  (let [pb (ProcessBuilder. ^java.util.List [(str (System/getProperty "java.home") "/bin/java")
                                             "-cp" (System/getProperty "java.class.path")
                                             "clojure.main" "-m" main])]
    (.putAll (.environment pb) env)
    (.start pb)))

(deftest run-exits-1-with-one-line-on-stderr-when-the-system-fails-to-start
  (let [p (java-process "web-base-test.run-failing" {})]
    (is (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS) "the process ended")
    (let [out (slurp (.getInputStream p))
          err (slurp (.getErrorStream p))]
      (is (= 1 (.exitValue p)) "with status 1")
      (is (= "failed to start: the database refused the login\n" err) (str "one line on stderr: " (pr-str err)))
      (is (= "" out) (str "nothing on stdout, and no banner: " (pr-str out)))
      (is (not (str/includes? (str out err) "S3CRET")) "and the configuration nowhere"))))

(deftest run-halts-the-system-on-sigterm-after-printing-the-banner
  (let [marker (.toFile (java.nio.file.Files/createTempFile "web-base-run-" ".marker"
                                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        p      (java-process "web-base-test.run-halting" {"WB_TEST_MARKER" (str marker)})
        reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))
        banner (deref (future (.readLine reader)) 120000 ::no-banner)]
    (is (= "up" banner) "the banner is printed once the system has started")
    (is (= "" (slurp marker)) "precondition: nothing halted yet")
    (.destroy p)
    (is (.waitFor p 60 java.util.concurrent.TimeUnit/SECONDS) "SIGTERM ends it")
    (is (= "halted" (slurp marker)) "and the shutdown hook halted the system on the way")))

(deftest a-banner-that-throws-halts-the-system-and-exits-1
  (let [marker (.toFile (java.nio.file.Files/createTempFile "web-base-run-" ".marker"
                                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        p      (java-process "web-base-test.run-bad-banner" {"WB_TEST_MARKER" (str marker)})]
    (is (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS) "the process ended by itself — no server left running")
    (is (= [1 "the banner failed: the banner could not format the port\n"]
           [(.exitValue p) (slurp (.getErrorStream p))])
        "with status 1 and one line on stderr")
    (is (= "halted" (slurp marker)) "and the system that had started was halted first")))
