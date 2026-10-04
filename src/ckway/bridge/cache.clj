(ns ckway.bridge.cache
  "The disk cache of the Kotlin bridges (`ckway.bridge`): where it is, what an entry is, how it is verified.

  WHERE. `-Dckway.cache.dir=<dir>` selects the directory (an EMPTY value turns the cache off). Without the
  property the cache is per user, never in the working directory:
    $XDG_CACHE_HOME/ckway     when XDG_CACHE_HOME is set (and absolute)
    %LOCALAPPDATA%\\ckway      on Windows (else <home>\\AppData\\Local\\ckway)
    ~/.cache/ckway            otherwise
  The directory that is named (the default one or the one of the property) is only the PARENT of the cache: kt
  works in its own subdirectory `<dir>/bridges-v1`, which it creates itself, and never reads, writes or deletes
  anything else in `<dir>`. A directory that kt creates gets owner-only permissions where the file system has POSIX
  permissions. The named directory, when it exists, and the subdirectory, when it exists, are each used only when
  (after symbolic links are resolved) they are a directory that belongs to the current user and is not writable by
  group or others, and the subdirectory is not itself a symbolic link; otherwise the cache is off. (The directories
  ABOVE the named one are not checked: see doc/limits.md section 15.) For the default directory this is silent (`-Dckway.debug` says why); for the
  directory of the property, which is an explicit setting, one warning line on stderr says why and how to fix it.
  The default directory must be absolute: when the home directory is unknown (`user.home` is `?` for a user
  that has no passwd entry, as in many containers) the cache is off, it never falls back to the working
  directory. The owner is compared with the owner of a file that this process creates, not with a user name.

  ENTRY. One entry is a directory `<bridge class name>-<key>` (the bridge class name starts with `ckway.bridge.`,
  the key is 32 hex digits) in the subdirectory. It holds the class files `<binary name>.class`
  and `entry.txt`, written last:
    ckway-cache-entry 1
    base <sha-256 of everything the bridge depends on, except the compiler>
    compiler <version of the Kotlin compiler that made the classes>
    class <binary name> <sha-256 of the class file bytes>
  `<key>` is a hash of `base` and `compiler`, so JVMs with different class paths or compilers never share
  an entry name. A JVM without the compiler on its class path (it only runs stored bridges) cannot name the
  compiler, so it finds the entry by `base` among the entries of the bridge.
  A class file is read only after its SHA-256 equals the one in `entry.txt`. Any entry that does not verify, that
  has no valid `entry.txt`, or that the JVM cannot define and link, is deleted and compiled again.
  An entry is written to a temporary directory and renamed (atomic): a reader sees all of an entry or none, and two
  JVMs that write the same entry cannot mix their files. Every failure of the cache (read-only directory, no
  space...) means \"no cache\", never an error.
  PRUNING (on every store; only in the subdirectory, and only what kt can prove that it created: a directory whose
  name is exactly an entry name or a `.ckway-tmp-<uuid>` name, that is a real directory (never a symbolic link) directly
  inside the cache directory, and whose files are class files and `entry.txt` only; a plain file or any other
  directory is never touched, and nothing is deleted through a symbolic link): the entries of one bridge name are kept up to `keep-per-bridge`, the most recently
  used first, so two projects with different versions of one Kotlin library do not evict each other; an entry
  that was not used for `max-age-ms` goes whatever its bridge; a temporary directory (`.ckway-tmp-<uuid>`) older than `stale-tmp-ms`
  (a JVM that was killed while it wrote) goes. \"Used\" is the modification time of the entry directory: a hit
  sets it to now (at most once per `touch-after-ms`), which changes neither the class files nor `entry.txt`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Path StandardCopyOption AtomicMoveNotSupportedException]
           [java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions]
           [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn debug
  "One debug-level note (stderr, only with -Dckway.debug)."
  [& xs]
  (when (System/getProperty "ckway.debug")
    (binding [*out* *err*] (println (apply str "ckway cache: " xs)))))

;; ---------------------------------------------------------------- hashes

(defn sha256-hex ^String [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) d))))

(defn sha256-str ^String [^String s] (sha256-hex (.getBytes s "UTF-8")))

;; ---------------------------------------------------------------- the directory

(defn windows? [] (str/starts-with? (str (System/getProperty "os.name")) "Windows"))

(defn default-dir
  "The per-user default cache directory (a File) for the environment `env` ({\"XDG_CACHE_HOME\" ...}) and the
  system properties `props` (os.name, user.home). Pure: no file system access. The result can be relative
  (`user.home` is `?`); `dir-for` refuses that. nil when there is no home directory to build it from."
  ^File [env props]
  (let [xdg (get env "XDG_CACHE_HOME")
        win? (str/starts-with? (str (get props "os.name")) "Windows")
        home (get props "user.home")
        home (when-not (str/blank? home) home)]
    (cond
      (and (not (str/blank? xdg)) (.isAbsolute (io/file xdg))) (io/file xdg "ckway")
      win? (let [l (get env "LOCALAPPDATA")]
             (cond (not (str/blank? l)) (io/file (io/file l) "ckway")
                   home (io/file (io/file home "AppData" "Local") "ckway")))
      home (io/file home ".cache" "ckway"))))

(defn- posix? [^Path p] (contains? (.supportedFileAttributeViews (.getFileSystem p)) "posix"))

(def ^:private owner-only (delay (PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))))

(defn make-dirs!
  "Create `dir` (and parents) with owner-only permissions where they exist. => true when it is a directory."
  [^File dir]
  (try
    (let [p (.toPath dir)]
      (if (posix? p)
        (Files/createDirectories p (into-array FileAttribute [@owner-only]))
        (Files/createDirectories p (make-array FileAttribute 0)))
      (.isDirectory dir))
    (catch Throwable e (debug "cannot create " dir ": " e) false)))

(def ^:private no-link-options (make-array java.nio.file.LinkOption 0))

(def ^:private my-principal
  "The file system's owner principal of this process: the owner of a file that it creates now. (The user name
  cannot be used: the JDK gives `?` for a user that has no passwd entry.) nil when no file can be created."
  (delay (try (let [f (Files/createTempFile "ckway-owner" ".tmp" (make-array FileAttribute 0))]
                (try (Files/getOwner f no-link-options) (finally (Files/deleteIfExists f))))
              (catch Throwable e (debug "cannot find out who this process is: " e) nil))))

(defn mine?
  "Does the file system's owner of `f` (symbolic links resolved) equal the owner of the files of this process?"
  [^File f]
  (let [me @my-principal]
    (boolean (and me (= me (Files/getOwner (.toRealPath (.toPath f) no-link-options) no-link-options))))))

(defn- unsafe-reason
  "Why the existing directory `dir` must not be a cache directory (a string), or nil when it is fine or does not
  exist yet (kt creates it private). Symbolic links are resolved first."
  [^File dir]
  (when (.exists dir)
    (if-not (.isDirectory dir)
      "is not a directory"
      (let [p (.toRealPath (.toPath dir) no-link-options)]
        (when (posix? p)
          (let [perms (Files/getPosixFilePermissions p no-link-options)
                g (contains? perms PosixFilePermission/GROUP_WRITE)
                o (contains? perms PosixFilePermission/OTHERS_WRITE)]
            (cond
              (not (mine? dir)) (str "belongs to another user (owner " (Files/getOwner p no-link-options) ")")
              (or g o) (str "can be written by " (str/join " and " (cond-> [] g (conj "its group") o (conj "others")))))))))))

(defonce ^:private warned (atom #{}))

(defn- warn!
  "One line on stderr, once for each text (an explicit user setting is wrong: silence would be wrong)."
  [text]
  (when-not (contains? @warned text)
    (swap! warned conj text)
    (binding [*out* *err*] (println text))))

(def entries-dirname
  "The subdirectory of the named cache directory in which kt keeps its entries (and deletes). The number is the layout."
  "bridges-v1")

(defn- reason-for
  "Why neither the named directory `d` nor its subdirectory of entries may be used (a string), or nil."
  [^File d]
  (or (try (unsafe-reason d) (catch Throwable e (str "cannot be checked (" e ")")))
      (let [sub (io/file d entries-dirname)]
        (try (if (and (.exists sub) (Files/isSymbolicLink (.toPath sub)))
               (str "has a subdirectory " entries-dirname " that is a symbolic link")
               (when-let [why (unsafe-reason sub)] (str "has a subdirectory " entries-dirname " that " why)))
             (catch Throwable e (str "cannot be checked (" e ")"))))))

(defn dir-for
  "The cache directory - the subdirectory `bridges-v1` of the directory that is named - for the value `p` of
  -Dckway.cache.dir (or nil), the environment `env` and the system properties `props`, or nil when the cache is
  off. See the namespace docstring."
  ^File [p env props]
  (cond
    (nil? p) (let [d (default-dir env props)]
               (cond (nil? d) (do (debug "not used (no home directory for the default location)") nil)
                     (not (.isAbsolute d)) (do (debug "not used (the default location is not absolute: " d ")") nil)
                     :else (if-let [why (reason-for d)]
                             (do (debug "not used (" d " " why ")") nil)
                             (io/file d entries-dirname))))
    (str/blank? p) nil
    :else (let [d (io/file p)]
            (if-let [why (reason-for d)]
              (do (warn! (str "ckway: the bridge cache is OFF: the directory " (.getPath d) " of -Dckway.cache.dir " why
                              ". Run `chmod 700 " (.getPath d) "` (as the owner), or name a directory that only you can write, "
                              "or set -Dckway.cache.dir= to turn the cache off and silence this line."))
                  nil)
              (io/file d entries-dirname)))))

(defn dir
  "The cache directory, or nil when the cache is off. See the namespace docstring."
  ^File []
  (dir-for (System/getProperty "ckway.cache.dir") (System/getenv)
           {"os.name" (System/getProperty "os.name") "user.home" (System/getProperty "user.home")}))

;; ---------------------------------------------------------------- entries

(defn compiler-version
  "The version of the Kotlin compiler on the class path, or nil when there is none."
  []
  (try (let [c (Class/forName "org.jetbrains.kotlin.config.KotlinCompilerVersion" true (clojure.lang.RT/baseLoader))]
         (str (.get (.getField c "VERSION") nil)))
       (catch Throwable _ nil)))

(defn- entry-name [cname base compiler]
  (str cname "-" (subs (sha256-str (str base "|" compiler)) 0 32)))

(def ^:private nofollow (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))

(defn- real-dir?
  "Is `f` a directory itself, not a symbolic link to one?"
  [^File f]
  (Files/isDirectory (.toPath f) nofollow))

(defn- delete-tree!
  "Delete `f` and, when it is a real directory, what is in it. A symbolic link is deleted as a link: its target is
  never entered or changed."
  [^File f]
  (try
    (let [p (.toPath f)]
      (when (Files/isDirectory p nofollow)
        (let [kids (with-open [ds (Files/newDirectoryStream p)] (vec (iterator-seq (.iterator ds))))]
          (doseq [^Path c kids] (delete-tree! (.toFile c)))))
      (Files/deleteIfExists p))
    (catch Throwable _ nil)))

(def ^:private entry-name-re
  "The name of an entry: the bridge class (`ckway.bridge.` + name) and the 32 hex digits of the key."
  #"ckway\.bridge\.[A-Za-z0-9_$.]+-[0-9a-f]{32}")

(def temp-prefix
  "The prefix of the temporary directory that a store writes before it renames it. Specific to this library: any
  other directory is never taken for one."
  ".ckway-tmp-")

(def ^:private temp-name-re #"\.ckway-tmp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- own-child?
  "Is `f` a real directory (not a link) that lies directly inside `root`, after both are resolved?"
  [^File root ^File f]
  (try (and (real-dir? f)
            (= (.toRealPath (.toPath root) no-link-options)
               (.toRealPath (.getParent (.toAbsolutePath (.toPath f))) no-link-options)))
       (catch Throwable _ false)))

(defn- ours-shaped?
  "Does the directory hold only what an entry holds: class files and `entry.txt`, as plain files?"
  [^File d]
  (try (with-open [ds (Files/newDirectoryStream (.toPath d))]
         (every? (fn [^Path c]
                   (let [n (str (.getFileName c))]
                     (and (Files/isRegularFile c nofollow)
                          (or (= "entry.txt" n) (boolean (re-matches #"[A-Za-z0-9_$.]+\.class" n))))))
                 (iterator-seq (.iterator ds))))
       (catch Throwable _ false)))

(defn- entry-dir?
  "Is `f` an entry directory that kt created in `root`? The exact entry name, a real directory directly in `root`,
  and only class files and `entry.txt` in it."
  [^File root ^File f]
  (and (boolean (re-matches entry-name-re (.getName f))) (own-child? root f) (ours-shaped? f)))

(defn- temp-dir?
  "Is `f` a temporary directory of kt in `root`? The exact name, a real directory directly in `root`, and only class
  files and `entry.txt` in it (as for an entry): a directory that is named like one but holds something else is not ours."
  [^File root ^File f]
  (and (boolean (re-matches temp-name-re (.getName f))) (own-child? root f) (ours-shaped? f)))

(defonce ^:private warned-in-the-way (atom false))
(defonce ^:private warned-stale-tmp (atom false))

(defn- warn-once!
  "One line on stderr per JVM for the flag `flag` (an atom): the cache does not touch what is not its own, and says so
  once, because otherwise it would silently stay off for that bridge."
  [flag text]
  (when (compare-and-set! flag false true)
    (binding [*out* *err*] (println text))))

(def ^:private binary-name-re #"[A-Za-z0-9_$.]+")

(defn- parse-manifest [^File f]
  (let [lines (str/split-lines (slurp f))]
    (when (= "ckway-cache-entry 1" (first lines))
      (reduce (fn [m l]
                (let [[k a b] (str/split l #" " 3)]
                  (case k
                    "base" (assoc m :base a)
                    "compiler" (assoc m :compiler a)
                    "class" (update m :classes (fnil conj []) [a b])
                    m)))
              {} (rest lines)))))

(def keep-per-bridge
  "How many entries of one bridge name `store!` keeps (the most recently used)."
  8)

(def max-age-ms
  "An entry that was not used for this long (90 days) is deleted by `store!`, whatever its bridge."
  (* 90 24 60 60 1000))

(def stale-tmp-ms
  "A `.ckway-tmp-<uuid>` directory older than this (one hour) was left by a JVM that was killed while it wrote."
  (* 60 60 1000))

(def touch-after-ms
  "A hit sets the last-use time of the entry when it is older than this (one hour): not a write at every start."
  (* 60 60 1000))

(defn- valid-manifest
  "The parsed `entry.txt` of the entry directory `d`, or nil when it is missing or not a manifest of this format."
  [^File d]
  (let [mf (io/file d "entry.txt")]
    (when (.isFile mf)
      (let [m (try (parse-manifest mf) (catch Throwable _ nil))]
        (when (and m (:base m) (seq (:classes m))) m)))))

(defn- verified
  "{binary bytes} of the entry directory `d` when it verifies, nil when it is a valid entry for another input
  (another `base`), ::bad when it is damaged: no valid `entry.txt`, a wrong hash, a bad class name.
  `cname` is the main class."
  [^File d cname base]
  (try
    (if-let [{b :base cs :classes} (valid-manifest d)]
      (when (and (= base b) (some #(= cname (first %)) cs))
        (into {}
              (for [[n h] cs]
                (do (when-not (and (re-matches binary-name-re n) (or (= n cname) (str/starts-with? n (str cname "$"))))
                      (throw (ex-info "bad class name in entry" {:n n})))
                    (let [bs (Files/readAllBytes (.toPath (io/file d (str n ".class"))))]
                      (when-not (= h (sha256-hex bs)) (throw (ex-info "hash mismatch" {:n n})))
                      [n bs])))))
      (do (debug "entry " d " has no valid entry.txt") ::bad))
    (catch Throwable e (debug "entry " d " does not verify: " e) ::bad)))

(defn- touch!
  "A hit is a use: set the modification time of the entry directory (not of any file in it) to now."
  [^File d]
  (try (when (> (- (System/currentTimeMillis) (.lastModified d)) touch-after-ms)
         (.setLastModified d (System/currentTimeMillis)))
       (catch Throwable _ nil)))

(defn lookup
  "The verified classes {binary bytes} of the cached bridge `cname`, or nil (a miss). An entry that does
  not verify is deleted. `compiler` is the version of the compiler that this JVM has, or nil."
  [^File cache-dir cname base compiler]
  (try
    (when (and cache-dir (.isDirectory cache-dir))
      (let [direct (when compiler (io/file cache-dir (entry-name cname base compiler)))
            cands (if direct
                    (when (entry-dir? cache-dir direct) [direct])
                    (filter (fn [^File f] (and (str/starts-with? (.getName f) (str cname "-")) (entry-dir? cache-dir f)))
                            (.listFiles cache-dir)))]
        (some (fn [^File d]
                (let [r (verified d cname base)]
                  (cond (= ::bad r) (do (delete-tree! d) nil)
                        r (do (touch! d) r))))
              cands)))
    (catch Throwable e (debug "lookup failed: " e) nil)))

(defn discard!
  "Delete the entry of `cname` that has `base` (and `compiler`)."
  [^File cache-dir cname base compiler]
  (try
    (doseq [^File f (.listFiles cache-dir)
            :when (and (str/starts-with? (.getName f) (str cname "-")) (entry-dir? cache-dir f))
            :let [mf (io/file f "entry.txt")
                  m (when (.isFile mf) (try (parse-manifest mf) (catch Throwable _ nil)))]
            :when (or (nil? m) (and (= base (:base m)) (or (nil? compiler) (= compiler (:compiler m)))))]
      (delete-tree! f))
    (catch Throwable e (debug "discard failed: " e))))

(defn- prune!
  "Delete what is stale in `cache-dir`: temporary directories of killed JVMs, entries not used for `max-age-ms`,
  and the entries of the bridge `cname` beyond `keep-per-bridge` (the least recently used first). The entry
  `keep` is never deleted. Only what kt can prove it created is touched (`entry-dir?`, `temp-dir?`): anything
  else in the directory - a file, another directory, a `.tmp-*` of another tool, a symbolic link - stays."
  [^File cache-dir cname keep]
  (let [now (System/currentTimeMillis)
        kids (vec (.listFiles cache-dir))]
    (doseq [^File f kids :when (and (> (- now (.lastModified f)) stale-tmp-ms) (re-matches temp-name-re (.getName f)))]
      (if (temp-dir? cache-dir f)
        (delete-tree! f)
        (when (own-child? cache-dir f)
          (warn-once! warned-stale-tmp
                      (str "ckway: " f " is named like a temporary directory of ckway but holds other files, so ckway will not "
                           "delete it. Delete it yourself if it is stale.")))))
    (let [entries (filter #(entry-dir? cache-dir %) kids)
          old? (fn [^File f] (> (- now (.lastModified f)) max-age-ms))
          [old live] ((juxt filter remove) #(and (not= keep (.getName ^File %)) (old? %)) entries)
          same (->> live
                    (filter (fn [^File f] (str/starts-with? (.getName f) (str cname "-"))))
                    (sort-by (fn [^File f] [(if (= keep (.getName f)) 0 1) (- (.lastModified f))])))]
      (doseq [f old] (delete-tree! f))
      (doseq [f (drop keep-per-bridge same) :when (not= keep (.getName ^File f))] (delete-tree! f)))))

(defn store!
  "Write the entry atomically. Never throws. An existing entry directory without a valid `entry.txt` is
  damaged: it is deleted and written again."
  [^File cache-dir cname base compiler classes]
  (try
    (when (and (make-dirs! cache-dir)
               ;; created by another process in the meantime? then it must pass the check too
               (if-let [why (unsafe-reason cache-dir)] (do (debug cache-dir " not used (" why ")") false) true))
      (let [name (entry-name cname base compiler)
            final (io/file cache-dir name)
            tmp (io/file cache-dir (str temp-prefix (java.util.UUID/randomUUID)))]
        (when (and (.exists final) (not (valid-manifest final)))
          (if (entry-dir? cache-dir final)
            (do (debug "entry " final " has no valid entry.txt, replaced")
                (delete-tree! final))
            (do (warn-once! warned-in-the-way
                            (str "ckway: " final " is in the way of the bridge cache: it is not an entry of ckway (a file, a link, or a "
                                 "directory with other files), so ckway will not touch it and does not cache this bridge "
                                 "(every run compiles it again). Move or delete it to turn the cache on."))
                (throw (ex-info (str final " is not an entry of kt and is not touched") {})))))
        (when-not (.exists final)
          (try
            (when-not (make-dirs! tmp) (throw (ex-info "no temp dir" {})))
            (doseq [[n ^bytes bs] classes]
              (Files/write (.toPath (io/file tmp (str n ".class"))) bs ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0)))
            (spit (io/file tmp "entry.txt")
                  (str "ckway-cache-entry 1\nbase " base "\ncompiler " compiler "\n"
                       (apply str (for [[n ^bytes bs] (sort-by key classes)] (str "class " n " " (sha256-hex bs) "\n")))))
            (try (Files/move (.toPath tmp) (.toPath final) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
                 (catch AtomicMoveNotSupportedException _
                   (Files/move (.toPath tmp) (.toPath final) (make-array StandardCopyOption 0))))
            (catch Throwable e (debug "cannot write " final ": " e))
            (finally (delete-tree! tmp))))
        (prune! cache-dir cname name)))
    (catch Throwable e (debug "store failed: " e)))
  nil)
