(ns ckway.bridge.cache
  "The disk cache of the Kotlin bridges (`ckway.bridge`): where it is, what an entry is, how it is verified.

  WHERE. `-Dckway.cache.dir=<dir>` selects the directory (an EMPTY value turns the cache off). Without the
  property the cache is per user, never in the working directory:
    $XDG_CACHE_HOME/ckway     when XDG_CACHE_HOME is set (and absolute)
    %LOCALAPPDATA%\\ckway      on Windows (else <home>\\AppData\\Local\\ckway)
    ~/.cache/ckway            otherwise
  A directory that kt creates gets owner-only permissions where the file system has POSIX permissions. The
  per-user DEFAULT directory is only used when it belongs to the current user and is not writable by group or
  others; if it is not, the cache is off (the directory of the property is the user's own choice and is not
  checked).

  ENTRY. One entry is a directory `<bridge class name>-<key>` that holds the class files `<binary name>.class`
  and `entry.txt`, written last:
    ckway-cache-entry 1
    base <sha-256 of everything the bridge depends on, except the compiler>
    compiler <version of the Kotlin compiler that made the classes>
    class <binary name> <sha-256 of the class file bytes>
  `<key>` is a hash of `base` and `compiler`, so JVMs with different class paths or compilers never share
  an entry name. A JVM without the compiler on its class path (it only runs stored bridges) cannot name the
  compiler, so it finds the entry by `base` among the entries of the bridge.
  A class file is read only after its SHA-256 equals the one in `entry.txt`. Any entry that does not verify, or
  that the JVM cannot define and link, is deleted and compiled again.
  An entry is written to a temporary directory and renamed (atomic): a reader sees all of an entry or none, and two
  JVMs that write the same entry cannot mix their files. Every failure of the cache (read-only directory, no
  space...) means \"no cache\", never an error."
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
  system properties `props` (os.name, user.home). Pure: no file system access."
  ^File [env props]
  (let [xdg (get env "XDG_CACHE_HOME")
        win? (str/starts-with? (str (get props "os.name")) "Windows")
        home (get props "user.home")]
    (cond
      (and (not (str/blank? xdg)) (.isAbsolute (io/file xdg))) (io/file xdg "ckway")
      win? (io/file (let [l (get env "LOCALAPPDATA")]
                      (if (str/blank? l) (io/file home "AppData" "Local") (io/file l)))
                    "ckway")
      :else (io/file home ".cache" "ckway"))))

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

(defn- private-dir?
  "Is the existing directory ours and closed for writing by group and others?"
  [^File dir]
  (let [p (.toPath dir)]
    (or (not (posix? p))
        (and (= (System/getProperty "user.name") (.getName (Files/getOwner p (make-array java.nio.file.LinkOption 0))))
             (let [perms (Files/getPosixFilePermissions p (make-array java.nio.file.LinkOption 0))]
               (not (or (contains? perms PosixFilePermission/GROUP_WRITE) (contains? perms PosixFilePermission/OTHERS_WRITE))))))))

(defn dir
  "The cache directory, or nil when the cache is off. See the namespace docstring."
  ^File []
  (let [p (System/getProperty "ckway.cache.dir")]
    (cond
      (nil? p) (let [d (default-dir (System/getenv) {"os.name" (System/getProperty "os.name") "user.home" (System/getProperty "user.home")})]
                 (cond (not (.exists d)) d
                       (and (.isDirectory d) (private-dir? d)) d
                       :else (do (debug "not used (not a private directory of this user): " d) nil)))
      (str/blank? p) nil
      :else (io/file p))))

;; ---------------------------------------------------------------- entries

(defn compiler-version
  "The version of the Kotlin compiler on the class path, or nil when there is none."
  []
  (try (let [c (Class/forName "org.jetbrains.kotlin.config.KotlinCompilerVersion" true (clojure.lang.RT/baseLoader))]
         (str (.get (.getField c "VERSION") nil)))
       (catch Throwable _ nil)))

(defn- entry-name [cname base compiler]
  (str cname "-" (subs (sha256-str (str base "|" compiler)) 0 32)))

(defn- delete-tree! [^File f]
  (try
    (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree! c)))
    (.delete f)
    (catch Throwable _ nil)))

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

(defn- verified
  "{binary bytes} of the entry directory `d` when it verifies, else nil. `cname` is the main class."
  [^File d cname base]
  (try
    (let [mf (io/file d "entry.txt")]
      (when (.isFile mf)
        (let [{b :base cs :classes} (parse-manifest mf)]
          (when (and (= base b) (seq cs) (some #(= cname (first %)) cs))
            (let [out (into {}
                            (for [[n h] cs]
                              (do (when-not (and (re-matches binary-name-re n) (or (= n cname) (str/starts-with? n (str cname "$"))))
                                    (throw (ex-info "bad class name in entry" {:n n})))
                                  (let [bs (Files/readAllBytes (.toPath (io/file d (str n ".class"))))]
                                    (when-not (= h (sha256-hex bs)) (throw (ex-info "hash mismatch" {:n n})))
                                    [n bs]))))]
              out)))))
    (catch Throwable e (debug "entry " d " does not verify: " e) ::bad)))

(defn lookup
  "The verified classes {binary bytes} of the cached bridge `cname`, or nil (a miss). An entry that does
  not verify is deleted. `compiler` is the version of the compiler that this JVM has, or nil."
  [^File cache-dir cname base compiler]
  (try
    (when (and cache-dir (.isDirectory cache-dir))
      (let [direct (when compiler (io/file cache-dir (entry-name cname base compiler)))
            cands (if direct
                    (when (.isDirectory ^File direct) [direct])
                    (filter (fn [^File f] (and (.isDirectory f) (str/starts-with? (.getName f) (str cname "-"))))
                            (.listFiles cache-dir)))]
        (some (fn [^File d]
                (let [r (verified d cname base)]
                  (cond (= ::bad r) (do (delete-tree! d) nil)
                        :else r)))
              cands)))
    (catch Throwable e (debug "lookup failed: " e) nil)))

(defn discard!
  "Delete the entry of `cname` that has `base` (and `compiler`)."
  [^File cache-dir cname base compiler]
  (try
    (doseq [^File f (.listFiles cache-dir)
            :when (and (.isDirectory f) (str/starts-with? (.getName f) (str cname "-")))
            :let [mf (io/file f "entry.txt")
                  m (when (.isFile mf) (try (parse-manifest mf) (catch Throwable _ nil)))]
            :when (or (nil? m) (and (= base (:base m)) (or (nil? compiler) (= compiler (:compiler m)))))]
      (delete-tree! f))
    (catch Throwable e (debug "discard failed: " e))))

(defn store!
  "Write the entry atomically. Never throws."
  [^File cache-dir cname base compiler classes]
  (try
    (when (make-dirs! cache-dir)
      (let [name (entry-name cname base compiler)
            final (io/file cache-dir name)
            tmp (io/file cache-dir (str ".tmp-" (java.util.UUID/randomUUID)))]
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
        ;; entries of this bridge made by the same compiler for other (older) inputs
        (doseq [^File f (.listFiles cache-dir)
                :when (and (.isDirectory f) (str/starts-with? (.getName f) (str cname "-")) (not= name (.getName f)))
                :let [mf (io/file f "entry.txt")
                      m (when (.isFile mf) (try (parse-manifest mf) (catch Throwable _ nil)))]
                :when (and m (= compiler (:compiler m)) (not= base (:base m)))]
          (delete-tree! f))))
    (catch Throwable e (debug "store failed: " e)))
  nil)
