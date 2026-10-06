(ns dev.arkaitz.web-base.release-test
  "The release guards, exercised against **real git**: every test builds a
  throwaway repository with its own bare origin, puts it in the state it wants,
  and runs the guard against that. A guard tested against a mock of git is a
  guard tested against my idea of git, and the defect it exists to prevent —
  publishing from a state nobody can reproduce — is one only git can confirm.

  The scratch repositories are left in the system temp directory rather than
  deleted. Recursive deletion driven by a variable inside a loop is precisely
  the shape this project forbids, and the alternative — a handful of 50 kB
  directories the operating system reclaims — is cheaper than the exception."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [release])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- scratch
  "A working repository with one commit, pushed to its own bare origin."
  []
  (let [root   (str (Files/createTempDirectory "auth-base-release" (into-array FileAttribute [])))
        origin (str root "/origin.git")
        work   (str root "/work")]
    (release/git nil "init" "--bare" "-b" "main" origin)
    (release/git nil "clone" "-q" origin work)
    (release/git work "config" "user.email" "test@example.test")
    (release/git work "config" "user.name" "Test")
    (spit (io/file work "README") "hello\n")
    (release/git work "add" "README")
    (release/git work "commit" "-qm" "first")
    (release/git work "push" "-q" "-u" "origin" "main")
    {:root root :origin origin :dir work}))

(defn- refusal
  "The `:check` of the refusal `check-releasable!` raises, or `:accepted`."
  [dir tag]
  (try (do (release/check-releasable! dir tag) :accepted)
       (catch ExceptionInfo e (:check (ex-data e)))))

;; --- the finding this whole namespace exists for --------------------------

(deftest git-fails-loudly-rather-than-answering-nothing
  ;; `b/git-process` returns nil for a command that failed AND for one that
  ;; succeeded with no output, so a guard built on it cannot tell a clean tree
  ;; from a broken git: it fails open, publishing in the case it was written to
  ;; stop. Both halves below are the assertion; either alone is satisfied by
  ;; the very implementation being ruled out.
  (let [{:keys [dir]} (scratch)]
    (is (= "" (release/git dir "status" "--porcelain"))
        "a command that succeeds with no output answers the empty string, not nil")
    (is (not (str/blank? (release/git dir "rev-parse" "HEAD")))
        "and one with output answers it")
    (let [thrown (try (release/git dir "no-such-subcommand") :did-not-throw
                      (catch ExceptionInfo e e))]
      (is (instance? ExceptionInfo thrown)
          "a command that fails throws — it does not answer nothing")
      (when (instance? ExceptionInfo thrown)
        (is (= ["no-such-subcommand"] (:git (ex-data thrown)))
            "and the failure names the command that failed")
        (is (= 1 (:exit (ex-data thrown)))
            "carrying its exit code")
        (is (str/includes? (str (:err (ex-data thrown))) "no-such-subcommand")
            "and git's own words, so nobody has to reproduce it to know what happened")))))

;; --- the guard ------------------------------------------------------------

(deftest check-releasable!-accepts-a-clean-pushed-repository-without-the-tag
  (let [{:keys [dir]} (scratch)]
    (is (= :releasable (release/check-releasable! dir "v1.0.0"))
        "and says so with a value: a guard that returned nil would be indistinguishable
         from one whose body had been deleted")))

(deftest check-releasable!-refuses-each-unreproducible-state-and-names-it
  (testing "a tracked file with uncommitted changes"
    (let [{:keys [dir]} (scratch)]
      (spit (io/file dir "README") "changed\n")
      (is (= :dirty-tree (refusal dir "v1.0.0")))))

  (testing "a file nobody is tracking"
    (let [{:keys [dir]} (scratch)]
      (spit (io/file dir "stray.clj") "(ns stray)\n")
      (is (= :dirty-tree (refusal dir "v1.0.0")))))

  (testing "a commit that has not been pushed"
    (let [{:keys [dir]} (scratch)]
      (spit (io/file dir "README") "changed\n")
      (release/git dir "commit" "-qam" "second")
      (is (= :unpushed (refusal dir "v1.0.0")))))

  (testing "a branch with no upstream at all"
    (let [{:keys [dir]} (scratch)]
      (release/git dir "checkout" "-q" "-b" "elsewhere")
      (is (= :no-upstream (refusal dir "v1.0.0")))))

  (testing "the tag already here — the version was not bumped"
    (let [{:keys [dir]} (scratch)]
      (release/git dir "tag" "-a" "v1.0.0" "-m" "already")
      (is (= :tag-exists (refusal dir "v1.0.0")))))

  (testing "the tag already on the remote, released from somewhere else"
    (let [{:keys [dir]} (scratch)]
      (release/git dir "tag" "-a" "v1.0.0" "-m" "released elsewhere")
      (release/git dir "push" "-q" "origin" "v1.0.0")
      (release/git dir "tag" "-d" "v1.0.0")
      (is (= "" (release/git dir "tag" "--list" "v1.0.0"))
          "precondition: the tag is genuinely gone from here, so the refusal is the remote's")
      (is (= :tag-on-remote (refusal dir "v1.0.0")))))

  (testing "and a repository in none of those states is still accepted"
    ;; Without this the six above are what a guard refusing everything would say.
    (let [{:keys [dir]} (scratch)]
      (is (= :accepted (refusal dir "v1.0.0"))))))

;; --- the tag --------------------------------------------------------------

(deftest tag-release!-lands-an-annotated-tag-on-the-remote-at-head
  (let [{:keys [dir origin]} (scratch)
        head (release/git dir "rev-parse" "HEAD")]
    (is (= "v2.0.0" (release/tag-release! dir "v2.0.0" "the message")))
    ;; Observed in the bare origin, not in the working copy and not from the
    ;; command's own output: what matters is that it arrived.
    ;; No pattern: `ls-remote --tags <url> v2.0.0` matches the tag ref but not
    ;; its peeled `^{}` companion, and the peeled ref is how annotation is
    ;; observed at all.
    (let [remote (release/git dir "ls-remote" "--tags" origin)
          lines  (into {} (map (fn [l] (let [[sha ref] (str/split l #"\t")] [ref sha])))
                       (str/split-lines remote))]
      (is (contains? lines "refs/tags/v2.0.0")
          "the tag is on the remote")
      (is (= head (get lines "refs/tags/v2.0.0^{}"))
          "it is annotated — a peeled ref exists — and it dereferences to the commit
           that was published, which is the whole point of tagging at all")
      (is (not= head (get lines "refs/tags/v2.0.0"))
          "the ref itself is the tag object, not the commit: a lightweight tag would
           carry neither message nor date"))
    (is (= "the message" (release/git dir "tag" "-l" "--format=%(contents:subject)" "v2.0.0"))
        "and it carries the message it was given")))

;; --- verify-release! --------------------------------------------------------

(def ^:private binary-bytes
  "Every byte value once: a file a text round trip through git's output would change."
  (byte-array (map unchecked-byte (range 256))))

(defn- tagged
  "A scratch repository with a source file, a text resource and a binary one, tagged
  v1.0.0 and pushed with its tag."
  []
  (let [{:keys [dir] :as s} (scratch)]
    (io/make-parents (io/file dir "src/a/core.clj"))
    (spit (io/file dir "src/a/core.clj") "(ns a.core)\n")
    (io/make-parents (io/file dir "resources/a/public/x.css"))
    (spit (io/file dir "resources/a/public/x.css") "body{}\n")
    (with-open [o (io/output-stream (io/file dir "resources/a/blob.bin"))] (.write o ^bytes binary-bytes))
    (release/git dir "add" ".")
    (release/git dir "commit" "-qm" "sources")
    (release/git dir "push" "-q")
    (release/git dir "tag" "-a" "v1.0.0" "-m" "1.0.0")
    (release/git dir "push" "-q" "origin" "v1.0.0")
    s))

(defn- publish!
  "A file:// Maven repository under `root` holding test.example/lib `version`: a jar of
  `entries` ({path bytes-or-string}) with a pom naming `pom-version` and `pom-tag`.
  Answers the repository's URL."
  [root version entries {:keys [pom-version pom-tag] :or {pom-version version pom-tag "v1.0.0"}}]
  (let [repo (str root "/maven")
        base (io/file repo "test/example/lib" version)
        pom  (str "<project><modelVersion>4.0.0</modelVersion><groupId>test.example</groupId>"
                  "<artifactId>lib</artifactId><version>" pom-version "</version>"
                  "<scm><tag>" pom-tag "</tag></scm></project>")]
    (.mkdirs base)
    (spit (io/file base (str "lib-" version ".pom"))
          (str/replace pom (str "<version>" pom-version "</version>") (str "<version>" version "</version>")))
    (with-open [jo (java.util.jar.JarOutputStream. (io/output-stream (io/file base (str "lib-" version ".jar"))))]
      (doseq [[path content] (assoc entries "META-INF/maven/test.example/lib/pom.xml" pom)]
        (.putNextEntry jo (java.util.jar.JarEntry. ^String path))
        (.write jo ^bytes (if (string? content) (.getBytes ^String content "UTF-8") content))
        (.closeEntry jo)))
    (str "file://" repo)))

(def ^:private exact
  {"a/core.clj" "(ns a.core)\n" "a/public/x.css" "body{}\n" "a/blob.bin" binary-bytes})

(defn- verdict
  "`:verified`, or the `:check` of the refusal, for test.example/lib `version` from `url`,
  fetched into a directory of its own each time."
  [dir root url version]
  (try (release/verify-release! dir {:lib 'test.example/lib :version version :tag "v1.0.0"
                                     :local-dir (str root "/fetched-" (random-uuid)) :repo-url url})
       (catch ExceptionInfo e (:check (ex-data e)))))

(deftest verify-release!-accepts-a-jar-that-is-the-tag-byte-for-byte--and-names-each-way-one-is-not
  (let [{:keys [dir root]} (tagged)]
    (is (= :verified (verdict dir root (publish! root "1.0.0" exact {}) "1.0.0"))
        "control: the jar is the tag, a binary file included, and it says so with a value")
    (is (= :differs (verdict dir (str root "/d") (publish! (str root "/d") "1.0.0" (assoc exact "a/public/x.css" "body{x}\n") {}) "1.0.0"))
        "a file whose bytes differ from the tag's")
    (is (= :differs (verdict dir (str root "/b") (publish! (str root "/b") "1.0.0" (assoc exact "a/blob.bin" (byte-array (reverse binary-bytes))) {}) "1.0.0"))
        "a binary file whose bytes differ")
    (is (= :missing-from-jar (verdict dir (str root "/m") (publish! (str root "/m") "1.0.0" (dissoc exact "a/public/x.css") {}) "1.0.0"))
        "a file of the tag the jar does not carry")
    (is (= :not-in-tag (verdict dir (str root "/n") (publish! (str root "/n") "1.0.0" (assoc exact "a/extra.clj" "(ns a.extra)\n") {}) "1.0.0"))
        "a file in the jar that is in neither src nor resources at the tag")
    (is (= :pom (verdict dir (str root "/p") (publish! (str root "/p") "1.0.0" exact {:pom-tag "v0.9.9"}) "1.0.0"))
        "a pom naming another tag")
    (is (= :unresolvable (verdict dir (str root "/u") (publish! (str root "/u") "1.0.0" exact {}) "1.0.1"))
        "a version nobody published")))

(deftest verify-release!-refuses-a-tag-the-remote-does-not-have
  (let [{:keys [dir root]} (tagged)]
    (release/git dir "push" "-q" "origin" ":refs/tags/v1.0.0")
    (is (= "" (release/git dir "ls-remote" "--tags" "origin" "v1.0.0")) "precondition: the tag is only here")
    (is (= :tag-not-on-remote (verdict dir root (publish! root "1.0.0" exact {}) "1.0.0")))))
