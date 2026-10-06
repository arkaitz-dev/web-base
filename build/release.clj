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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

;; --- after the deploy ---------------------------------------------------------

(defn- git-bytes
  "The bytes of `path` at `rev` in `dir`, exactly, or an `ex-info`. `git` answers
  text, which would quietly re-encode a binary file; this reads the stream as it
  comes. Bounded: a git that has not finished in a minute is an error."
  ^bytes [dir rev path]
  (let [pb (cond-> (ProcessBuilder. ^java.util.List ["git" "show" (str rev ":" path)])
             dir (.directory (java.io.File. ^String dir)))
        p  (.start (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD))
        out (.readAllBytes (.getInputStream p))]
    (when-not (.waitFor p 60 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly p)
      (throw (ex-info (str "git show " rev ":" path " did not finish") {:path path})))
    (when-not (zero? (.exitValue p))
      (throw (ex-info (str "git show " rev ":" path " failed with exit " (.exitValue p)) {:path path})))
    out))

(defn- shipped-at
  "`{jar-path tree-path}` for every file under src/ and resources/ at `tag`: what the
  jar must hold, and where each entry came from."
  [dir tag]
  (let [files (str/split-lines (git dir "ls-tree" "-r" "--name-only" tag "--" "src" "resources"))
        pairs (for [f files :when (seq f)] [(str/replace-first f #"^(src|resources)/" "") f])
        twice (->> pairs (group-by first) (filter #(< 1 (count (val %)))) keys sort)]
    (when (seq twice)
      (throw (ex-info (str "release: " (first twice) " is in both src and resources, so the jar cannot say which")
                      {:check :ambiguous :paths (vec twice)})))
    (into {} pairs)))

(defn- jar-entries
  "`{path bytes}` of every file in `jar`, but the manifest and the pom tools.build
  writes, which come from the build and not from the tree."
  [^java.io.File jar]
  (with-open [jf (java.util.jar.JarFile. jar)]
    (into {}
          (for [^java.util.jar.JarEntry e (enumeration-seq (.entries jf))
                :let [n (.getName e)]
                :when (not (or (.isDirectory e) (= n "META-INF/MANIFEST.MF") (str/starts-with? n "META-INF/maven/")))]
            [n (with-open [in (.getInputStream jf e)] (.readAllBytes in))]))))

(defn- pom-of [^java.io.File jar lib]
  (with-open [jf (java.util.jar.JarFile. jar)]
    (when-let [e (.getEntry jf (str "META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml"))]
      (with-open [in (.getInputStream jf e)] (slurp in)))))

(defn- refuse-release! [message data]
  (throw (ex-info (str "release not verified: " message) data)))

(defn- fetch!
  "Copies `url` to `file`, bounded: thirty seconds to connect and to each read. Answers
  whether there was anything there."
  [url ^java.io.File file]
  (let [conn (doto (.openConnection (java.net.URL. url))
               (.setConnectTimeout 30000)
               (.setReadTimeout 30000))]
    (try
      (with-open [in (.getInputStream conn)]
        (io/make-parents file)
        (with-open [out (io/output-stream file)] (io/copy in out))
        true)
      (catch java.io.FileNotFoundException _ false))))

(defn verify-release!
  "Downloads `lib` `version`'s jar as a consumer receives it — from `repo-url`, Clojars
  unless given, into `local-dir`, so nothing already cached answers for it — and holds
  it to the tag: every file of the jar byte for byte equal to the file at `tag` under
  src/ or resources/, every such file in the jar, the pom inside naming `version` and
  `tag`, and `tag` on the remote. Returns `:verified`, or throws naming the first
  difference under `:check`."
  [dir {:keys [lib version tag local-dir repo-url] :or {repo-url "https://repo.clojars.org"}}]
  (when-not (seq (git dir "ls-remote" "--tags" "origin" tag))
    (refuse-release! (str "the tag " tag " is not on the remote") {:check :tag-not-on-remote :tag tag}))
  (let [path (str (str/replace (namespace lib) "." "/") "/" (name lib) "/" version "/" (name lib) "-" version ".jar")
        jar  (io/file local-dir path)]
    (when-not (fetch! (str repo-url "/" path) jar)
      (refuse-release! (str lib " " version " is not at " repo-url) {:check :unresolvable}))
    (let [pom (or (pom-of jar lib) "")]
      (when-not (and (str/includes? pom (str "<version>" version "</version>"))
                     (str/includes? pom (str "<tag>" tag "</tag>")))
        (refuse-release! (str "the jar's pom does not name " version " and " tag) {:check :pom})))
    (let [expected (shipped-at dir tag)
          entries  (jar-entries jar)]
      (when-let [p (first (sort (remove (set (keys entries)) (keys expected))))]
        (refuse-release! (str (get expected p) " at " tag " is not in the jar") {:check :missing-from-jar :path p}))
      (when-let [p (first (sort (remove (set (keys expected)) (keys entries))))]
        (refuse-release! (str p " is in the jar and in neither src nor resources at " tag) {:check :not-in-tag :path p}))
      (doseq [[p bs] (sort-by key entries)]
        (when-not (java.util.Arrays/equals ^bytes bs (git-bytes dir tag (get expected p)))
          (refuse-release! (str p " in the jar differs from " (get expected p) " at " tag) {:check :differs :path p}))))
    :verified))
