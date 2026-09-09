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
