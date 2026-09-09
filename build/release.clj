(ns release
  "The guards that stand between `deploy` and Clojars, and the tag it leaves
  behind.

  They exist because three artifacts were published whose poms announced a
  `<scm><tag>` that did not exist, and finding out afterwards which commit each
  one had been built from took cross-referencing the jar's `Last-Modified` on
  `repo.clojars.org` against commit timestamps in UTC. Nothing was careless: the
  deploy simply had no opinion about it, so remembering was a person's job.

  **`b/git-process` is not used here, and that is the whole design.** It returns
  `nil` when a command fails *and* when a command succeeds with no output:

      (b/git-process {:git-args \"status --porcelain\"})  ; => nil, clean tree
      (b/git-process {:git-args \"no-such-subcommand\"})  ; => nil, git never ran

  A guard built on that cannot tell \"the tree is clean\" from \"git is broken\",
  so it fails **open** — publishing in precisely the case it was written to
  stop. `b/process` returns `{:exit :out :err}`, and every call below reads the
  exit code."
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(defn git
  "Runs git in `dir` (nil for the working directory) and returns its trimmed
  stdout. A git that did not succeed is an `ex-info` naming the command and
  carrying its stderr — never an empty answer."
  [dir & args]
  (let [{:keys [exit out err]} (b/process (cond-> {:command-args (into ["git"] args)
                                                   :out :capture
                                                   :err :capture}
                                            dir (assoc :dir dir)))]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed with exit " exit)
                      {:git (vec args) :exit exit :dir dir
                       :err (some-> err str/trim)})))
    (str/trim (or out ""))))

(defn- refuse! [message data]
  (throw (ex-info (str "deploy refused: " message) data)))

(defn check-releasable!
  "Throws unless `dir` is in a state whose artifact could be found again later.
  Returns `:releasable` when it is, so a caller cannot mistake a guard that did
  nothing for one that passed."
  [dir tag]
  (let [dirty (git dir "status" "--porcelain")]
    (when (seq dirty)
      (refuse! "the working tree is not clean, so this jar could not be rebuilt from any commit"
               {:check :dirty-tree :changes (str/split-lines dirty)})))
  (let [head     (git dir "rev-parse" "HEAD")
        upstream (try
                   (git dir "rev-parse" "@{u}")
                   (catch clojure.lang.ExceptionInfo e
                     (refuse! "this branch has no upstream, so nothing here has been pushed anywhere"
                              {:check :no-upstream :err (:err (ex-data e))})))]
    (when-not (= head upstream)
      (refuse! "HEAD is not what the remote has; push first, or the tag names a commit nobody else can fetch"
               {:check :unpushed :head head :upstream upstream})))
  (when (seq (git dir "tag" "--list" tag))
    (refuse! (str "the tag " tag " already exists here — the version was not bumped")
             {:check :tag-exists :tag tag}))
  (when (seq (git dir "ls-remote" "--tags" "origin" tag))
    (refuse! (str "the tag " tag " already exists on the remote — that version has been released")
             {:check :tag-on-remote :tag tag}))
  :releasable)

(defn tag-release!
  "Annotates `tag` on HEAD and pushes it. Returns the tag."
  [dir tag message]
  (git dir "tag" "-a" tag "-m" message)
  (git dir "push" "origin" tag)
  tag)
