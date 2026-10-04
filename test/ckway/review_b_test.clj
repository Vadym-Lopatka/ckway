(ns ckway.review-b-test
  "Review findings B1-B15: the bridge disk cache, the coroutine layer, `kt/reify`, keyword-safe Kotlin source.
  Fixtures: test-fixtures/fx/ReviewB.kt, test-fixtures/kw (package kw.`in`), test-fixtures/old11 (JVM target 11).
  Tests that need a fresh JVM start one with the class path of this one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.bridge :as bridge]
            [ckway.bridge.cache :as cache]
            [ckway.co :as co]
            [ckway.core :as kt])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(kt/require '[fx :as f])
(kt/require '[kw.in :as k])
(kt/require '[old11 :as o])

;; ---------------------------------------------------------------- helpers

(defn- tmp-dir ^File [] (.toFile (Files/createTempDirectory "ckway-rb" (make-array FileAttribute 0))))

(defn- cp-entries
  "The class path of this JVM, with absolute entries (a started JVM may have another working directory)."
  []
  (mapv #(.getCanonicalPath (io/file ^String %)) (str/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))))

(def ^:private base-cp (str/join File/pathSeparator (cp-entries)))

(defn- join-cp [entries] (str/join File/pathSeparator entries))

(defn- without-compiler [entries] (remove #(str/includes? % "kotlin-compiler-embeddable") entries))

(defn- env-with [m] (merge (into {} (System/getenv)) m))

(defn- jvm-proc
  "Start a JVM that runs `code` (class path `cp`, system properties `props`, environment additions `env`, working
  directory `dir`). => the Process."
  ^Process [cp props env dir code]
  (let [pb (ProcessBuilder. ^java.util.List (vec (concat ["clojure"] (for [[k v] props] (str "-J-D" k "=" v)) ["-Scp" cp "-M" "-e" code])))]
    (when dir (.directory pb (io/file dir)))
    (doseq [[k v] (env-with env)] (.put (.environment pb) k v))
    (.start pb)))

(defn- finish [^Process p]
  (let [out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))
        exit (.waitFor p)]
    {:exit exit :out @out :err @err}))

(defn- jvm
  "Run `code` in a new JVM. The cache directory is chosen by the caller (props or env): a test must never
  write into the real user cache."
  ([cp props code] (jvm cp props {} nil code))
  ([cp props env dir code] (finish (jvm-proc cp props env dir code))))

(def ^:private stats-code
  "(println \"STATS\" (pr-str (select-keys @ckway.bridge/kotlin-stats [:compiled :cache-hits])))")

(defn- stats [out] (edn/read-string (second (re-find #"STATS (\{.*\})" out))))

(def ^:private one-call
  "(require '[ckway.core :as kt] 'ckway.bridge) (kt/require '[fx :as f])
   (println \"OUT\" (eval '(f/typeName :<> String)))")

(defn- entries
  "The entry directories of a cache directory."
  [^File dir]
  (vec (filter #(and (.isDirectory ^File %) (not (str/starts-with? (.getName ^File %) ".tmp-"))) (.listFiles dir))))

(defn- main-class-file ^File [^File entry]
  (first (filter #(re-matches #"ckway\.bridge\.K_[A-Za-z0-9_]+__[0-9a-f]{10}\.class" (.getName ^File %)) (.listFiles entry))))

(defn- major [^bytes bs] (+ (* 256 (bit-and 0xff (aget bs 6))) (bit-and 0xff (aget bs 7))))

(defn- bytes-of [^File f] (Files/readAllBytes (.toPath f)))

(defn- resource-major [^String cn]
  (with-open [in (.getResourceAsStream (clojure.lang.RT/baseLoader) (str (str/replace cn "." "/") ".class"))]
    (major (.readAllBytes in))))

(defmacro ^:private with-cache-dir
  "Run body with -Dckway.cache.dir set to `dir` (restored afterwards)."
  [dir & body]
  `(let [old# (System/getProperty "ckway.cache.dir")]
     (System/setProperty "ckway.cache.dir" (.getPath ~dir))
     (try ~@body
          (finally (if old# (System/setProperty "ckway.cache.dir" old#) (System/clearProperty "ckway.cache.dir"))))))

(defn- root-message [^Throwable t] (ex-message (loop [t t] (if-let [c (.getCause t)] (recur c) t))))

(defn- thrown-message [f] (try (f) nil (catch Throwable t (root-message t))))

(defn- eval-in-ns [form]
  (binding [*ns* (the-ns 'ckway.review-b-test)] (eval form)))

;; ---------------------------------------------------------------- B1

(deftest b1-default-cache-directory-is-per-user
  (let [props {"os.name" "Linux" "user.home" "/home/u"}]
    (is (= (io/file "/x/xdg/ckway") (cache/default-dir {"XDG_CACHE_HOME" "/x/xdg"} props)))
    (is (= (io/file "/home/u/.cache/ckway") (cache/default-dir {} props)))
    (is (= (io/file "/home/u/.cache/ckway") (cache/default-dir {"XDG_CACHE_HOME" "relative"} props)) "a relative XDG_CACHE_HOME is ignored")
    (is (= (io/file "/home/u/.cache/ckway") (cache/default-dir {"XDG_CACHE_HOME" ""} props)))
    (is (= (io/file "C:\\L" "ckway") (cache/default-dir {"LOCALAPPDATA" "C:\\L"} {"os.name" "Windows 11" "user.home" "C:\\Users\\u"})))
    (is (= (io/file "C:\\Users\\u" "AppData" "Local" "ckway") (cache/default-dir {} {"os.name" "Windows 11" "user.home" "C:\\Users\\u"})))
    (testing "never the working directory"
      (is (not (str/includes? (str (cache/default-dir {} props)) ".ckway-cache"))))))

(deftest b1-property-and-off-switch
  (let [d (tmp-dir)]
    (with-cache-dir d (is (= d (cache/dir))))
    (let [old (System/getProperty "ckway.cache.dir")]
      (System/setProperty "ckway.cache.dir" "")
      (try (is (nil? (cache/dir)) "an empty value turns the cache off")
           (finally (if old (System/setProperty "ckway.cache.dir" old) (System/clearProperty "ckway.cache.dir")))))))

(deftest b1-working-directory-cache-is-ignored
  (let [work (tmp-dir) xdg (tmp-dir) wd-cache (io/file work ".ckway-cache")]
    (.mkdirs wd-cache)
    (spit (io/file wd-cache "planted.txt") "x")
    (testing "no property: the per-user directory (XDG_CACHE_HOME) is used, `.ckway-cache` in the working directory is not"
      (let [r (jvm base-cp {} {"XDG_CACHE_HOME" (.getPath xdg)} work (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "OUT String"))
        (is (= 1 (count (entries (io/file xdg "ckway")))) "the entry is in the per-user directory")
        (is (= ["planted.txt"] (map #(.getName ^File %) (.listFiles wd-cache))) "nothing was read from or written to ./.ckway-cache")))
    (testing "the property selects the directory, even one named .ckway-cache"
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath wd-cache)} {"XDG_CACHE_HOME" (.getPath xdg)} work (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (= 1 (count (entries wd-cache))))))
    (testing "the per-user directory is created with owner-only permissions"
      (when (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
        (is (= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath (io/file xdg "ckway")) (make-array java.nio.file.LinkOption 0)))))
        (is (= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath (first (entries (io/file xdg "ckway")))) (make-array java.nio.file.LinkOption 0)))))))))

(deftest b1-per-user-directory-must-be-private
  (when (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
    (let [xdg (tmp-dir) c (io/file xdg "ckway")]
      (.mkdirs c)
      (Files/setPosixFilePermissions (.toPath c) (PosixFilePermissions/fromString "rwxrwxrwx"))
      (let [r (jvm base-cp {} {"XDG_CACHE_HOME" (.getPath xdg)} nil (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "OUT String") "still works (compiled)")
        (is (= 1 (:compiled (stats (:out r)))))
        (is (empty? (entries c)) "a directory that others can write is not used for the cache")))))

(defn- fill-cache!
  "Run one reified call in a new JVM with the cache `dir`. => the entry directory."
  [^File dir]
  (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
    (is (zero? (:exit r)) (:err r))
    (is (= 1 (:compiled (stats (:out r)))))
    (first (entries dir))))

(defn- evil-class-bytes
  "A class of the name of the bridge `cname` whose `call()` returns the line separator."
  [cname]
  ((requiring-resolve 'ckway.bridge.gen/call-bridge-bytes) cname
                                                           {:kind :static :class "java.lang.System" :name "lineSeparator" :desc "()Ljava/lang/String;" :access :public}))

(deftest b1-planted-class-is-not-executed
  (let [dir (tmp-dir) entry (fill-cache! dir)
        main (main-class-file entry)
        cname (str/replace (.getName main) #"\.class$" "")]
    (testing "the key (entry.txt) is untouched, the class file is another class of the same name"
      (Files/write (.toPath main) ^bytes (evil-class-bytes cname) (make-array java.nio.file.OpenOption 0))
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "OUT String") (str "the planted class would print a line separator: " (:out r)))
        (is (= {:compiled 1 :cache-hits 0} (stats (:out r))) "a hash mismatch is a miss: compiled again")))
    (testing "the bad entry was replaced by a good one: a JVM without the compiler uses it"
      (let [r (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "OUT String"))
        (is (= {:compiled 0 :cache-hits 1} (stats (:out r)))))
      (is (not= (seq (evil-class-bytes cname)) (seq (bytes-of (main-class-file (first (entries dir))))))))))

(deftest b1-hash-file-limits
  (testing "the protection is a hash in the same directory: whoever writes both can plant code (limits.md says so)"
    (let [dir (tmp-dir) entry (fill-cache! dir)
          main (main-class-file entry)
          cname (str/replace (.getName main) #"\.class$" "")
          evil (evil-class-bytes cname)
          mf (io/file entry "entry.txt")]
      (Files/write (.toPath main) ^bytes evil (make-array java.nio.file.OpenOption 0))
      (spit mf (str/replace (slurp mf) #"class (\S+) [0-9a-f]{64}" (str "class $1 " (cache/sha256-hex evil))))
      (let [r (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (str/includes? (:out r) (System/lineSeparator)) "documented: an attacker who writes class and hash is not stopped")
        (is (= 1 (:cache-hits (stats (:out r)))))))))

;; ---------------------------------------------------------------- B2

(deftest b2-damaged-entries-are-repaired
  (testing "a truncated class file with a matching hash (a writer that died): ClassFormatError is caught, entry deleted, recompiled"
    (let [dir (tmp-dir) entry (fill-cache! dir)
          main (main-class-file entry)
          cname (str/replace (.getName main) #"\.class$" "")
          cut (java.util.Arrays/copyOf (bytes-of main) 40)
          mf (io/file entry "entry.txt")]
      (Files/write (.toPath main) ^bytes cut (make-array java.nio.file.OpenOption 0))
      (spit mf (str/replace (slurp mf) #"class (\S+) [0-9a-f]{64}" (str "class $1 " (cache/sha256-hex cut))))
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "OUT String"))
        (is (= {:compiled 1 :cache-hits 0} (stats (:out r)))))
      (let [r (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (= {:compiled 0 :cache-hits 1} (stats (:out r))) "the repaired entry works without the compiler"))))
  (testing "a truncated class file whose hash does not match"
    (let [dir (tmp-dir) entry (fill-cache! dir)
          main (main-class-file entry)]
      (Files/write (.toPath main) ^bytes (java.util.Arrays/copyOf (bytes-of main) 40) (make-array java.nio.file.OpenOption 0))
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (str/includes? (:out r) "OUT String"))
        (is (= {:compiled 1 :cache-hits 0} (stats (:out r)))))))
  (testing "a missing inner class (listed in entry.txt, no file)"
    (let [dir (tmp-dir) entry (fill-cache! dir)
          mf (io/file entry "entry.txt")
          cname (str/replace (.getName (main-class-file entry)) #"\.class$" "")]
      (spit mf (str (slurp mf) "class " cname "$call$1 " (apply str (repeat 64 "0")) "\n"))
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (str/includes? (:out r) "OUT String"))
        (is (= {:compiled 1 :cache-hits 0} (stats (:out r)))))))
  (testing "garbage in entry.txt, and an entry without entry.txt (an old half-written layout)"
    (let [dir (tmp-dir) entry (fill-cache! dir)]
      (spit (io/file entry "entry.txt") "nonsense")
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
        (is (= {:compiled 1 :cache-hits 0} (stats (:out r))))))))

(deftest b2-unwritable-cache-directory-is-no-cache
  (when (and (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
             (not= "root" (System/getProperty "user.name")))
    (let [dir (tmp-dir)]
      (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "r-x------"))
      (try
        (let [r (jvm base-cp {"ckway.cache.dir" (.getPath dir)} (str one-call stats-code))]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:out r) "OUT String"))
          (is (= {:compiled 1 :cache-hits 0} (stats (:out r))))
          (is (not (re-find #"Exception|Error" (:err r))) (:err r)))
        (let [r (jvm base-cp {"ckway.cache.dir" (str (.getPath dir) "/sub/dir")} (str one-call stats-code))]
          (is (zero? (:exit r)) "a directory that cannot be created is no cache either")
          (is (= 1 (:compiled (stats (:out r))))))
        (finally (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "rwx------")))))))

(deftest b2-two-jvms-write-the-same-entry
  (let [dir (tmp-dir)
        props {"ckway.cache.dir" (.getPath dir)}
        code (str one-call stats-code)
        ps (doall (repeatedly 3 #(jvm-proc base-cp props {} nil code)))
        rs (mapv finish ps)]
    (doseq [r rs]
      (is (zero? (:exit r)) (:err r))
      (is (str/includes? (:out r) "OUT String")))
    (is (= 1 (count (entries dir))) "one entry")
    (is (empty? (filter #(str/starts-with? (.getName ^File %) ".tmp-") (.listFiles dir))) "no temporary directory left")
    (let [r (jvm (join-cp (without-compiler (cp-entries))) props code)]
      (is (= {:compiled 0 :cache-hits 1} (stats (:out r))) "the entry is whole: it verifies and loads"))))

(deftest b2-store-is-atomic-and-safe-to-repeat
  (let [dir (tmp-dir) classes {"ckway.bridge.K_x__0123456789" (byte-array [1 2 3])}]
    (cache/store! dir "ckway.bridge.K_x__0123456789" "base1" "2.4.20" classes)
    (cache/store! dir "ckway.bridge.K_x__0123456789" "base1" "2.4.20" classes)
    (is (= 1 (count (entries dir))))
    (is (= [1 2 3] (vec (get (cache/lookup dir "ckway.bridge.K_x__0123456789" "base1" "2.4.20") "ckway.bridge.K_x__0123456789"))))))

;; ---------------------------------------------------------------- B3

(deftest b3-compiler-version-and-entry-names
  (let [dir (tmp-dir) cn "ckway.bridge.K_x__0123456789" classes {cn (byte-array [1 2 3])}]
    (cache/store! dir cn "base1" "2.4.20" classes)
    (cache/store! dir cn "base1" "2.5.0" classes)
    (is (= 2 (count (entries dir))) "two compilers never share an entry name")
    (is (every? #(re-matches #"ckway\.bridge\.K_x__0123456789-[0-9a-f]{32}" (.getName ^File %)) (entries dir)) "the key is in the name")
    (is (some? (cache/lookup dir cn "base1" "2.4.20")))
    (is (nil? (cache/lookup dir cn "base1" "9.9.9")) "an entry of another compiler is not used by a JVM that has a compiler")
    (is (some? (cache/lookup dir cn "base1" nil)) "a JVM without a compiler finds the entry by its base key")
    (is (nil? (cache/lookup dir cn "other-base" nil)) "...but only when everything else is the same")
    (cache/store! dir cn "base2" "2.4.20" classes)
    (is (nil? (cache/lookup dir cn "base1" "2.4.20")) "the same compiler with changed inputs replaces the older entry")
    (is (= (cache/compiler-version) "2.4.20"))))

(deftest b3-part-classes-of-a-multi-file-facade-are-stamped
  (let [stamp @#'bridge/stamp-of]
    (is (str/includes? (stamp "fx.MultiKt") "fx.MultiKt__Multi1Kt") "the part classes hold the bodies")
    (is (str/includes? (stamp "fx.MultiKt") "fx.MultiKt__Multi2Kt"))
    (is (str/includes? (stamp "kotlin.text.StringsKt") "kotlin.text.StringsKt__StringsJVMKt"))
    (is (not (str/includes? (stamp "fx.Uid") "{")) "a plain class has no parts")))

(deftest b3-different-inputs-different-entry-names
  (let [root (tmp-dir) lib (io/file root "lib") cache-dir (tmp-dir) src (io/file root "Lib.kt")
        build! (fn [body]
                 (spit src (str "package ck3\ninline fun <reified T> libName(): String = \"" body "\"\n"))
                 (.mkdirs lib)
                 (let [r (sh "kotlinc" "-module-name" "ck3" "-nowarn" (.getPath src) "-d" (.getPath lib))]
                   (is (zero? (:exit r)) (str (:out r) (:err r)))))
        cp (join-cp (concat (cp-entries) [(.getPath lib)]))
        run (fn [] (let [r (jvm cp {"ckway.cache.dir" (.getPath cache-dir)}
                                (str "(require '[ckway.core :as kt] 'ckway.bridge) (kt/require '[ck3 :as c])
                                      (println \"OUT\" (eval '(c/libName :<> String)))" stats-code))]
                     (is (zero? (:exit r)) (:err r))
                     (:out r)))]
    (build! "v1") (run)
    (let [n1 (map #(.getName ^File %) (entries cache-dir))]
      (build! "v2") (is (str/includes? (run) "OUT v2"))
      (is (not= n1 (map #(.getName ^File %) (entries cache-dir))) "a changed library class gives another entry name"))))

;; ---------------------------------------------------------------- B4

(deftest b4-jvm-target-is-the-class-file-version-of-the-declaration
  (is (= 55 (resource-major "old11.Old11Box")) "the fixture is compiled for JVM target 11")
  (is (= "11" (bridge/jvm-target ["old11.Old11Box"])))
  (is (= "1.8" (bridge/jvm-target ["fx.Uid"])) "fx fixtures are version 52")
  (is (= (str (.feature (Runtime/version))) (bridge/jvm-target ["no.such.Class"])) "unknown owner: the running JVM, as before")
  (let [old (System/getProperty "ckway.jvm-target")]
    (System/setProperty "ckway.jvm-target" "17")
    (try (is (= "17" (bridge/jvm-target ["old11.Old11Box"])) "the property still overrides")
         (finally (if old (System/setProperty "ckway.jvm-target" old) (System/clearProperty "ckway.jvm-target"))))))

(def ^:private old11-code
  "(require '[ckway.core :as kt] 'ckway.bridge) (kt/require '[old11 :as o])
   (println \"OUT\" (eval '(o/.firstOf (o/Old11Box [1 \"a\"]) :<> String)) (eval '(o/old11TypeName :<> o/Old11Box)))")

(deftest b4-generated-kotlin-bridge-has-the-version-of-the-fixture
  (let [d (tmp-dir)
        r (jvm base-cp {"ckway.cache.dir" (.getPath d)} (str old11-code stats-code))]
    (is (zero? (:exit r)) (:err r))
    (is (str/includes? (:out r) "OUT a Old11Box"))
    (is (= 55 (resource-major "old11.Old11Box")) "the fixture is compiled for JVM target 11")
    (let [mains (map main-class-file (entries d))]
      (is (= 2 (count mains)))
      (doseq [m mains]
        (is (= 55 (major (bytes-of m))) (str (.getName ^File m) ": the bridge has the version of the fixture class, not of this JVM (" (.feature (Runtime/version)) ")")))))
  (testing "a bridge to a version 52 fixture is version 52"
    (let [d2 (tmp-dir) entry (fill-cache! d2)]
      (is (= 52 (resource-major "fx.Uid")))
      (is (= 52 (major (bytes-of (main-class-file entry))))))))

(deftest b4-property-overrides-the-target
  (let [d (tmp-dir)
        r (jvm base-cp {"ckway.cache.dir" (.getPath d) "ckway.jvm-target" "17"} (str one-call stats-code))]
    (is (zero? (:exit r)) (:err r))
    (is (= 61 (major (bytes-of (main-class-file (first (entries d)))))))))

(deftest b4-asm-bridges-and-reify-classes-are-version-52
  (let [gen-call (requiring-resolve 'ckway.bridge.gen/call-bridge-bytes)
        gen-reify (requiring-resolve 'ckway.bridge.gen/reify-class-bytes)]
    (is (= 52 (major (gen-call "ckway.bridge.C_t" {:kind :static :class "java.lang.System" :name "lineSeparator" :desc "()Ljava/lang/String;" :access :public}))))
    (is (= 52 (major (gen-reify "ckway.bridge.R_t" {:ifaces ["java.lang.Runnable"] :methods []}))))
    (testing "a version 52 reify class implements an interface of a newer class-file version"
      (let [x (kt/reify o/Old11Iface (.go [this] "went"))]
        (is (= "went" (.go ^old11.Old11Iface x)))
        (is (str/starts-with? (.getName (class x)) "ckway.bridge.R_"))))))

;; ---------------------------------------------------------------- B5

(deftest b5-cancelled-body-waits-for-the-callee
  (let [pure (vec (f/rbPureKotlinOrder))
        clj (deref (future (vec (f/rbTimeoutOrder (fn [] (f/rbCleanupCallee))))) 10000 :timeout)]
    (is (= ["inner-cleanup-done" "outer-resumed"] pure) "Kotlin")
    (is (= pure clj) "the Clojure body resumes its parent only after the callee finished its cleanup")))

;; ---------------------------------------------------------------- B6

(defn- run-interrupted
  "Run `(f)` in a thread, interrupt it after `ms`. => {:ex exception :flag interrupt-flag-after :ms ms-after-interrupt}."
  [f ms]
  (let [res (promise) t0 (volatile! 0)
        th (Thread. (fn [] (res (try (f) {:value :returned}
                                     (catch Throwable e {:ex e :flag (Thread/interrupted) :ms (/ (- (System/nanoTime) @t0) 1e6)})))))]
    (.start th)
    (Thread/sleep ms)
    (vreset! t0 (System/nanoTime))
    (.interrupt th)
    (deref res 10000 :timeout)))

(deftest b6-top-level-call-has-its-own-job
  (let [j (f/rbNeedJob)]
    (is (instance? kotlinx.coroutines.Job j))
    (is (.isCompleted ^kotlinx.coroutines.Job j) "the Job is completed when the call is done")
    (is (not (identical? j (f/rbNeedJob))) "each call has its own")))

(deftest b6-interrupt-cancels-the-callee
  (testing "the callee is cancelled, the caller gets InterruptedException with the flag cleared"
    (set! (. fx.RbTicker ticks) 0)
    (set! (. fx.RbTicker cancelled) false)
    (let [r (run-interrupted #(f/rbTickForever) 150)]
      (is (instance? InterruptedException (:ex r)) (pr-str r))
      (is (false? (:flag r)) "the exception replaces the flag: it is clear")
      (is (true? (. fx.RbTicker cancelled)) "the callee saw its cancellation before the caller returned")
      (let [n (. fx.RbTicker ticks)]
        (Thread/sleep 150)
        (is (= n (. fx.RbTicker ticks)) "the Kotlin coroutine does not tick on"))))
  (testing "the caller waits until the callee finished cancelling (bounded by the callee)"
    (set! (. fx.RbTicker cancelled) false)
    (let [r (run-interrupted #(f/rbSlowCancel) 100)]
      (is (instance? InterruptedException (:ex r)))
      (is (>= (:ms r) 250) (str "returned after " (:ms r) " ms, the cleanup takes 300"))
      (is (true? (. fx.RbTicker cancelled))))))

;; ---------------------------------------------------------------- B7

(defn- with-timeout-result [f] (deref (future (f)) 8000 :hang))

(deftest b7-hooks-that-throw-fail-the-coroutine-instead-of-hanging
  (is (= "THROWN update-boom" (with-timeout-result #(f/rbWithThrowingElement true (fn [x] 1)))))
  (is (= "THROWN restore-boom" (with-timeout-result #(f/rbWithThrowingElement false (fn [x] 1)))))
  (testing "the body's own failure comes first"
    (is (= "THROWN body-boom" (with-timeout-result #(f/rbWithThrowingElement false (fn [x] (throw (ex-info "body-boom" {}))))))))
  (testing "an Error in the body"
    (is (re-find #"err-boom" (with-timeout-result #(f/failsOf (fn [x] (throw (AssertionError. "err-boom")))))))
    (is (re-find #"err-boom" (with-timeout-result #(f/rbRunWith (f/rbInterceptor false) (fn [x] (throw (AssertionError. "err-boom")))))))))

(deftest b7-interceptor-that-throws
  (let [r (with-timeout-result #(f/rbRunWith (f/rbInterceptor true) (fn [x] 1)))]
    (is (str/includes? r "intercept-boom") r)))

(deftest b7-intercepted-continuation-is-released
  (let [ip (f/rbInterceptor false)
        r (with-timeout-result #(f/rbRunWith ip (fn [x] 41)))]
    (is (= "Success(41)" r))
    (is (= 1 (.get (.getIntercepted ^fx.RbInterceptor ip))))
    (is (= 1 (.get (.getReleased ^fx.RbInterceptor ip))) "every interceptContinuation has its release")))

(deftest b7-completion-handlers-are-disposed
  (let [[registered disposed intercepted released] (with-timeout-result #(vec (f/rbRunBodiesOnOneJob 25 (fn [x] x))))]
    (is (= 25 registered))
    (is (= registered disposed) "no handler stays on the long-lived Job")
    (is (= intercepted released))))

;; ---------------------------------------------------------------- B8

(deftest b8-keyword-package
  (testing "the source text back-quotes every segment that needs it"
    (let [ksrc-build (requiring-resolve 'ckway.bridge.ksrc/build)
          ksrc-source (requiring-resolve 'ckway.bridge.ksrc/source)
          decl (first (get (ckway.meta/package-index "kw.in") "kwTypeName"))
          _ (is (some? decl))
          spec (ksrc-build decl [{:class "kw/in/object" :nullable? false :args []}] #{})
          text (ksrc-source spec "ckway.bridge.K_x__0")]
      (is (str/includes? text "import kw.`in`.`kwTypeName` as ktFn") text)
      (is (str/includes? text "<kw.`in`.`object`>") text)))
  (testing "calls into a package named kw.in"
    (is (= "String" (k/kwTypeName :<> String)))
    (is (= "Holder" (k/kwTypeName :<> k/Holder)))
    (is (= "object" (k/kwTypeName :<> k/object)) "a class named like a keyword")
    (is (= "a" (k/.firstOf (k/Holder [1 "a"]) :<> String)))
    (is (= "a" (k/kwTypeOfHolder (k/Holder [1 "a"]) :<> String)))
    (is (= "Long:8" (k/kwPick (k/object 7) :<> Long)))
    (is (= "String:11" (k/kwPick (k/object 7) 4 :<> String)))))

;; ---------------------------------------------------------------- B9

(deftest b9-canon-ignores-print-variables
  (let [spec {:ifaces ["a.B" "c.D"] :methods [{:name "m" :desc "()V" :idx 0 :annotations [{:class "x.A" :elements {"v" [1 2 3]}}]}]}
        plain (bridge/canon spec)]
    (doseq [b [{#'*print-level* 1} {#'*print-length* 1} {#'*print-namespace-maps* true} {#'*print-meta* true}
               {#'*print-readably* false} {#'*print-dup* true} {#'*print-level* 0 #'*print-length* 0}]]
      (is (= plain (with-bindings b (bridge/canon spec))) (str b)))
    (is (not= plain (bridge/canon (assoc spec :ifaces ["a.B"]))))
    (is (= (bridge/canon {:a 1 :b 2}) (bridge/canon (array-map :b 2 :a 1))) "map order does not matter")))

(deftest b9-reify-class-does-not-depend-on-print-variables
  (let [form-a '(kt/reify f/RbPlain (.plain [this x] x))
        form-b '(kt/reify f/RbPlain java.lang.Runnable (.plain [this x] x) (run [this] nil))
        cls (fn [form] (.getName (class (eval-in-ns form))))
        plain-a (cls form-a) plain-b (cls form-b)]
    (is (not= plain-a plain-b))
    (doseq [b [{#'*print-level* 2} {#'*print-level* 1 #'*print-length* 1} {#'*print-namespace-maps* true} {#'*print-meta* true} {#'*print-length* 0}]]
      (with-bindings b
        (is (= plain-a (cls form-a)) (str b))
        (is (= plain-b (cls form-b)) (str "a second form does not get the class of the first under " b))))))

;; ---------------------------------------------------------------- B10

(deftest b10-bridge-method-for-a-specialised-generic-interface
  (testing "with the hint"
    (let [v (kt/reify f/RbStrVis (.rbVisit [this ^String x] (str x "!")))]
      (is (= "a!" (f/rbUseVis v "a")) "Kotlin calls it as RbVis<String>: visit(Object)Object")
      (is (= "b!" (.rbVisit ^fx.RbStrVis v "b")))))
  (testing "without a hint: one candidate, no ambiguity"
    (let [v (kt/reify f/RbStrVis (.rbVisit [this x] (str x "?")))]
      (is (= "a?" (f/rbUseVis v "a")))))
  (testing "a primitive in the override: the bridge unboxes and boxes"
    (let [v (kt/reify f/RbIntVis (.rbVisit [this x] (inc x)))]
      (is (= 6 (f/rbUseIntVis v 5)))
      (is (= 6 (.rbVisit ^fx.RbIntVis v 5)))))
  (testing "not written: the bridge leads to the abstract member, which says so"
    (let [v (kt/reify f/RbStrVis)
          msg (try (f/rbUseVis v "a") nil (catch AbstractMethodError e (ex-message e)))]
      (is (str/includes? (str msg) "rbVisit")))))

(deftest b10-ambiguity-message-names-the-object-hint
  (let [msg (thrown-message #(eval-in-ns '(kt/reify f/RbOver (.rbPut [this x] "x"))))]
    (is (str/includes? msg "is ambiguous") msg)
    (is (not (str/includes? msg "or ^.")) "no empty hint")
    (is (str/includes? msg "^java.lang.Object") msg)
    (is (str/includes? msg "^java.lang.String") msg)))

;; ---------------------------------------------------------------- B11

(deftest b11-redefined-interface-gets-a-new-class
  (eval-in-ns '(definterface RbRedef (^String m [])))
  (let [make '(kt/reify RbRedef (m [this] "v"))
        o1 (eval-in-ns make)]
    (is (true? ((eval-in-ns '(fn [x] (instance? RbRedef x))) o1)))
    (eval-in-ns '(definterface RbRedef (^String m [])))
    (let [o2 (eval-in-ns make)
          inst? (eval-in-ns '(fn [x] (instance? RbRedef x)))]
      (is (inst? o2) "the new object implements the CURRENT interface")
      (is (not (inst? o1)) "(the old object implements the old interface)")
      (is (not= (class o1) (class o2)))
      (is (= "v" ((eval-in-ns '(fn [x] (.m ^RbRedef x))) o2))))
    (testing "the same interface again reuses the class"
      (is (identical? (class (eval-in-ns make)) (class (eval-in-ns make)))))))

;; ---------------------------------------------------------------- B12

(deftest b12-conflicting-defaults
  (let [msg (thrown-message #(eval-in-ns '(kt/reify f/RbDefA f/RbDefB)))]
    (is (some? msg) "a compile error at macroexpansion")
    (is (str/includes? msg ".rbSay") msg)
    (is (str/includes? msg "Write it") msg)
    (is (str/includes? msg "RbDefA") msg)
    (is (str/includes? msg "RbDefB") msg))
  (testing "written: fine"
    (let [x (kt/reify f/RbDefA f/RbDefB (.rbSay [this] "mine"))]
      (is (= "mine" (f/rbHello x)))))
  (testing "one interface alone keeps its default"
    (is (= "A" (f/rbHello (kt/reify f/RbDefA))))))

;; ---------------------------------------------------------------- B13

(deftest b13-target-not-found
  (let [t {:kind :static :class "no.such.Class" :name "x" :desc "()V"}
        e (try (bridge/needed? t) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (:kt/error (ex-data e)))
    (is (str/includes? (ex-message e) "kt:"))
    (is (str/includes? (ex-message e) "no.such.Class")))
  (let [t {:kind :static :class "fx.BasicsKt" :name "noSuchFunction" :desc "(I)V"}
        e (try (bridge/needed? t) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (str/includes? (ex-message e) "noSuchFunction"))
    (is (str/includes? (ex-message e) "(I)V"))
    (is (str/includes? (ex-message e) "fx.BasicsKt"))
    (is (thrown? clojure.lang.ExceptionInfo (bridge/bridge-class t)))))

(deftest b13-hierarchy-lookup-and-declaring-class
  (let [t {:kind :static :class "kotlin.text.StringsKt" :name "format" :desc "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"}
        r (bridge/resolve-target t)]
    (is (= "kotlin.text.StringsKt__StringsJVMKt" (.getName ^Class (:declaring r))) "the facade inherits it from a part class")
    (is (= :private (:access r)) "@InlineOnly: private in the part class")
    (is (false? (:public? r)))
    (is (true? (bridge/needed? t)))
    (testing "the bridge is made for the declaring class and works"
      (let [c (Class/forName (bridge/bridge-class t))
            m (first (filter #(= "call" (.getName ^java.lang.reflect.Method %)) (.getMethods c)))]
        (is (= "x=5" (.invoke ^java.lang.reflect.Method m nil (object-array ["x=%d" (object-array [5])])))))))
  (testing "a public method of a public class is public, whichever owner the index gives (facade or part)"
    (let [t {:kind :static :class "fx.MultiKt" :name "multiA" :desc "(I)I"}]
      (is (:public? (bridge/resolve-target t)))
      (is (false? (bridge/needed? t)))
      (testing "the owner that the index may give instead: the (package-private) part class. It needs a bridge, and the bridge works"
        (let [tp (assoc t :class "fx.MultiKt__Multi1Kt")]
          (is (= "fx.MultiKt__Multi1Kt" (.getName ^Class (:declaring (bridge/resolve-target tp)))))
          (is (true? (bridge/needed? tp)))
          (let [c (Class/forName (bridge/bridge-class tp))]
            (is (= 5 (.invoke (.getMethod c "call" (into-array Class [Integer/TYPE])) nil (object-array [(int 4)]))))))))))

;; ---------------------------------------------------------------- B14

(deftest b14-compiler-noise
  (let [d (tmp-dir)
        r (jvm base-cp {"ckway.cache.dir" (.getPath d)} (str one-call stats-code))]
    (is (zero? (:exit r)) (:err r))
    (is (= 1 (:compiled (stats (:out r)))) "the compiler ran")
    (is (not (str/includes? (:err r) "WARNING")) (:err r))
    (is (not (str/includes? (:err r) "Unsafe")) (:err r))
    (is (not (str/includes? (:err r) "FastJarFileSystem")) (:err r))))

;; ---------------------------------------------------------------- B15

(defn- in-threads
  "Run `(f i)` in `n` threads that start together. => the results; a thread's exception is its result."
  [n f]
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        futs (doall (for [i (range n)] (future (.await latch) (try (f i) (catch Throwable t t)))))]
    (.countDown latch)
    (mapv #(deref % 60000 :timeout) futs)))

(deftest b15-concurrent-first-use
  (testing "8 threads define the same bytecode bridge: no error, one class"
    (let [t {:kind :static :class "kotlin.text.StringsKt" :name "format" :desc "(Ljava/lang/String;Ljava/util/Locale;[Ljava/lang/Object;)Ljava/lang/String;"}
          names (in-threads 8 (fn [_] (bridge/bridge-class t)))]
      (is (every? string? names) (pr-str names))
      (is (= 1 (count (distinct names))))
      (is (= 1 (count (distinct (in-threads 8 (fn [_] (Class/forName (first names))))))))))
  (testing "8 threads make the same kt/reify class: no error, one class"
    (let [cs (in-threads 8 (fn [_] (class (eval-in-ns '(kt/reify f/RbPlain java.lang.Runnable (.plain [this x] x) (run [this] nil))))))]
      (is (every? class? cs) (pr-str cs))
      (is (= 1 (count (distinct cs))))))
  (testing "8 threads need the same Kotlin bridge: one compilation, one class"
    (let [d (tmp-dir) before (:compiled @bridge/kotlin-stats)]
      (with-cache-dir d
        (let [rs (in-threads 8 (fn [_] (eval-in-ns '(f/rbTypeOf :<> Double))))]
          (is (every? #(= "Double!" %) rs) (pr-str rs))))
      (is (= 1 (- (:compiled @bridge/kotlin-stats) before)) "compiled once")
      (is (= 1 (count (entries d))))))
  (testing "8 threads, different Kotlin bridges"
    (let [d (tmp-dir)]
      (with-cache-dir d
        (let [rs (in-threads 8 (fn [i] (case (int (mod i 4))
                                         0 (eval-in-ns '(f/rbConcurrent :<> Int))
                                         1 (eval-in-ns '(f/rbConcurrent :<> Long))
                                         2 (eval-in-ns '(f/rbConcurrent :<> Short))
                                         3 (eval-in-ns '(f/rbConcurrent :<> Byte)))))]
          (is (= ["c:Integer" "c:Long" "c:Short" "c:Byte" "c:Integer" "c:Long" "c:Short" "c:Byte"] rs)))))))
