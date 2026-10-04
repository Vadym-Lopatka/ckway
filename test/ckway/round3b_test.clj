(ns ckway.round3b-test
  "Second review of the coroutine layer, the bridge cache and `kt/reify`.
  M1 reify of interfaces that narrow a type, M2 a cancel after a callee that ignores it, M3 the interrupt of a
  top-level wait, M4/M6/M7/M8/M10 the bridge cache, M5 overloads, M9 one resume, M11 the compiler message,
  M12 comments. Fixtures: test-fixtures/fx/Round3B.kt."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.bridge.cache :as cache]
            [ckway.bridge.kotlinc :as kotlinc]
            [ckway.call-test :as ct :refer [compile-error]]
            [ckway.core :as kt])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions FileTime]
           [java.util.concurrent Callable Executors TimeUnit]))

(kt/require '[fx :as f] '[fx.legacy :as l])

(defn- tmp-dir ^File [] (.toFile (Files/createTempDirectory "ckway-r3b" (make-array FileAttribute 0))))

(defn- declares?
  "Does the class of `o` declare a non-abstract public method `name` with these parameter types and return type?"
  [o ^String name params ^Class ret]
  (boolean (some (fn [^java.lang.reflect.Method m]
                   (and (= name (.getName m)) (= ret (.getReturnType m)) (= (vec params) (vec (.getParameterTypes m)))
                        (not (java.lang.reflect.Modifier/isAbstract (.getModifiers m)))))
                 (.getDeclaredMethods (class o)))))

(defmacro ^:private cv
  "The var `s` of ckway.bridge.cache, looked up when the test runs."
  [s]
  `(deref (requiring-resolve '~(symbol "ckway.bridge.cache" (name s)))))

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round3b-test)] (eval form)))

(defmacro ^:private kt-reify
  "`kt/reify`, compiled when the test runs: an error of the form fails one test, not the loading of this file."
  [& specs]
  `(eval-here '(kt/reify ~@specs)))

(def ^:private level-state (atom 0))

(defn- occurrences [^String text ^String s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) text)))

;; ---------------------------------------------------------------- M1

(deftest m1-generic-super-interface-with-a-covariant-return
  (let [o (kt-reify f/R3GStr (.get [this] "g"))]
    (is (= "g" (.get ^fx.R3GStr o)))
    (is (= "g" (f/r3UseGStr o)) "through the interface that declares get(): String")
    (is (= "g" (f/r3UseG o)) "through R3GSrc<String>: get(): Object")
    (is (declares? o "get" [] String))
    (is (declares? o "get" [] Object) "the bridge method")))

(deftest m1-non-generic-function-and-property
  (let [o (kt-reify f/R3StrSrc (.get [this] "g") (name [this] "n"))]
    (is (= "g/n" (f/r3UseStrSrc o)))
    (is (= "g/n" (f/r3UseSrc o)) "through R3Src: get(): Object, getName(): Object")
    (is (declares? o "get" [] Object))
    (is (declares? o "getName" [] Object))
    (is (declares? o "getName" [] String))))

(deftest m1-three-levels
  (let [o (kt-reify f/R3L3 (.g [this] "deep"))]
    (is (= "deep" (f/r3UseL3 o)))
    (is (= "deep" (f/r3UseL2 o)))
    (is (= "deep" (f/r3UseL1 o)))
    (is (declares? o "g" [] String))
    (is (declares? o "g" [] CharSequence))
    (is (declares? o "g" [] Object)))
  (testing "the middle interface alone"
    (let [o (kt-reify f/R3L2 (.g [this] "mid"))]
      (is (= "mid" (f/r3UseL2 o)))
      (is (= "mid" (f/r3UseL1 o))))))

(deftest m1-generic-parameter-and-covariant-return-over-three-levels
  (let [o (kt-reify f/R3PStr2 (.put [this x] (str x "!")))]
    (is (= "a!" (f/r3UseP2 o "a")))
    (is (= "a!" (f/r3UseP o "a")) "through R3P<String>: put(Object): Object")
    (is (declares? o "put" [String] String))
    (is (declares? o "put" [String] CharSequence))
    (is (declares? o "put" [Object] Object))))

(deftest m1-two-super-interfaces-with-the-same-member
  (let [o (kt-reify f/R3Both (.h [this] "H") (tag [this] "T"))]
    (is (= "HT" (f/r3UseBoth o)))
    (is (= "HT" (f/r3UseLeft o)))
    (is (= "HT" (f/r3UseRight o))))
  (testing "exactly one writable member for each Kotlin member"
    (let [e (compile-error '(kt/reify f/R3Both (.nope [this] 1)))]
      (is (= 1 (occurrences e "(.h [this])")) e)
      (is (= 1 (occurrences e "(tag [this])")) e)))
  (testing "two interfaces that both declare it, none of them a sub-interface of the other"
    (let [o (kt-reify f/R3SameB (.h [this] "S") (tag [this] "T"))]
      (is (= "ST" (f/r3UseLeft o))))))

(deftest m1-suspend-members
  (let [o (kt-reify f/R3SStr (.s [this] "S") (.t [this x] (str "T" x)))]
    (is (= "ST2" (f/r3UseSSrc o))))
  (let [o (kt-reify f/R3SGStr (.sg [this x] (str x "!")))]
    (is (= "a!" (f/r3UseSG o "a")))
    (is (declares? o "sg" [Object kotlin.coroutines.Continuation] Object))))

(deftest m1-value-classes
  (let [o (kt-reify f/R3VB (.id [this] (f/R3Uid 7)) (.take [this x] (f/R3Uid (inc x))))]
    (is (= 9 (f/r3UseVB o)))
    (is (str/includes? (f/r3UseVA o) "7") "through R3VA: id(): Object")
    (is (str/includes? (f/r3UseVA o) "2")))
  (let [o (kt-reify f/R3VGU (.put [this u] u))]
    (is (= 6 (f/r3UseVGU o)))
    (is (= 5 (f/r3UseVG o)) "through R3VG<R3Uid>: put(Object): Object")))

(deftest m1-default-body-in-the-sub-interface
  (let [o (kt-reify f/R3DStr)]
    (is (= "dflt" (f/r3UseD o))))
  (let [o (kt-reify f/R3DStr (.get [this] "mine"))]
    (is (= "mine" (f/r3UseD o)) "a written member replaces the default also behind the erased signature")))

(deftest m1-property-of-a-sub-interface
  (let [o (kt-reify f/R3VarSub (level [this] @ckway.round3b-test/level-state) (level [this v] (reset! ckway.round3b-test/level-state v)))]
    (is (= 4 (f/r3UseVar o)))))

(deftest m1-the-list-of-members-is-never-empty
  (doseq [[form needle] [['(kt/reify f/R3GStr (.nope [this] 1)) "(.get [this])"]
                         ['(kt/reify f/R3StrSrc (.nope [this] 1)) "(.get [this])"]
                         ['(kt/reify f/R3L3 (.nope [this] 1)) "(.g [this])"]
                         ['(kt/reify f/R3SStr (.nope [this] 1)) "(.s [this])"]
                         ['(kt/reify f/R3VGU (.nope [this] 1)) "(.put [this x])"]]]
    (let [e (compile-error form)]
      (is (str/includes? e "Members that you can write:\n  fx.") (str form " -> " e))
      (is (str/includes? e needle) (str form " -> " e)))))

(deftest m1-last-rounds-case-still-works
  (let [v (kt-reify f/RbStrVis (.rbVisit [this x] (str x "?")))]
    (is (= "a?" (f/rbUseVis v "a")))
    (is (= "b?" (.rbVisit ^fx.RbStrVis v "b")))))

(deftest m1-generic-var-and-a-generic-interface-between
  (let [o (kt-reify f/R3PStrS (p [this] @ckway.round3b-test/level-state) (p [this v] (reset! ckway.round3b-test/level-state v)))]
    (is (= "w" (f/r3UsePS o)) "getter and setter, both with an erased signature"))
  (let [o (kt-reify f/R3MidStr (.get [this] "m"))]
    (is (= "m" (f/r3UseG o)))))

(deftest m1-interfaces-compiled-without-jvm-default
  (let [o (kt-reify l/LgStr (.lg [this] "a") (.lgPut [this x] (str x "!")))]
    (is (= "ap!" (l/lgUse o))))
  (let [o (kt-reify l/LgVB (.lgId [this] (l/LgUid 3)))]
    (is (str/includes? (l/lgUseVA o) "3"))))

;; ---------------------------------------------------------------- M5

(deftest m5-int-and-nullable-int-are-two-overloads
  (let [o (kt-reify f/R3Ov
                    (.f [this ^Integer x] (str "boxed:" x))
                    (.f [this ^int x] (str "prim:" x)))]
    (is (= "boxed:,prim:3" (f/r3UseOv o)) "both written, no `written twice`"))
  (testing "with only one written, the other is an abstract member, not a null pointer"
    (let [o (kt-reify f/R3Ov (.f [this ^int x] (str "prim:" x)))
          e (try (f/r3UseOv o) nil (catch AbstractMethodError e e))]
      (is (some? e) "f(Int?) is abstract")
      (is (str/includes? (str (ex-message e)) "f(x: Int?)") (str (ex-message e))))))

;; ---------------------------------------------------------------- M2

(defn- elapsed-ms [s] (parse-long (second (re-find #"@(\d+)" s))))

(deftest m2-cancel-after-a-callee-that-ignores-it
  (println "M2 KOTLIN suspending next call:" (f/r3KotlinSuspendingReference)
           "| blocking next call (Kotlin cannot interrupt it):" (f/r3KotlinBlockingReference)
           "| seen after the callee:" (f/r3SeenAfterUncancellable))
  (testing "the next blocking operation of the body fails at once"
    (let [r (f/r3TimeoutBlock 100 (fn [] (f/r3Uncancellable 300) (Thread/sleep 3000)))]
      (println "M2 CLOJURE blocking next call:" r)
      (is (str/starts-with? r "timeout@") r)
      (is (< (elapsed-ms r) 1000) r)))
  (testing "the next suspend call fails at once"
    (let [r (f/r3TimeoutBlock 100 (fn [] (f/r3Uncancellable 300) (f/r3Delay 3000)))]
      (is (str/starts-with? r "timeout@") r)
      (is (< (elapsed-ms r) 1000) r)))
  (testing "the value of the callee that ignored the cancel is returned to the body, as in Kotlin"
    (let [seen (promise)
          r (f/r3TimeoutBlock 100 (fn [] (deliver seen (f/r3Uncancellable 300))))]
      (is (= 5 (deref seen 2000 :none)))
      (is (str/starts-with? r "timeout@") r)))
  (testing "a body that is not cancelled is not disturbed"
    (let [r (f/r3TimeoutBlock 2000 (fn [] (f/r3Uncancellable 50) (Thread/sleep 50)))]
      (is (str/starts-with? r "completed@") r))))

;; ---------------------------------------------------------------- M3

(defn- run-interrupted
  "Run `(f)` in a daemon thread, interrupt it after 100 ms (and again after `again-ms` if given).
  => {:ex :flag :ms (since the first interrupt)} or {:value ..}, or {:blocked true} after `wait-ms`."
  [f wait-ms & [again-ms]]
  (let [res (promise) t0 (volatile! (System/nanoTime))
        th (doto (Thread. (fn [] (deliver res (try {:value (f)}
                                                   (catch Throwable e {:ex e :flag (Thread/interrupted)
                                                                       :ms (/ (- (System/nanoTime) @t0) 1e6)})))))
             (.setDaemon true))]
    (.start th)
    (Thread/sleep 100)
    (vreset! t0 (System/nanoTime))
    (.interrupt th)
    (when again-ms (Thread/sleep ^long again-ms) (.interrupt th))
    (let [r (deref res wait-ms ::blocked)]
      (if (= ::blocked r) {:blocked true :state (.getState th)} r))))

(defmacro ^:private with-grace [ms & body]
  `(let [old# (System/getProperty "ckway.interrupt.grace.ms")]
     (System/setProperty "ckway.interrupt.grace.ms" (str ~ms))
     (try ~@body
          (finally (if old# (System/setProperty "ckway.interrupt.grace.ms" old#) (System/clearProperty "ckway.interrupt.grace.ms"))))))

(deftest m3-what-run-blocking-does
  (let [slow (f/r3RunBlockingProbe "slow" 3000)
        never (f/r3RunBlockingProbe "never" 1500)]
    (println "M3 KOTLIN runBlocking, cancellable callee with a slow finally:" slow)
    (println "M3 KOTLIN runBlocking, callee that never resumes:" never)
    (is (re-find #"InterruptedException after \d+ ms, flag=false, cleaned=true" slow)
        "waits for the cleanup of the callee, then InterruptedException, flag clear")
    (is (str/starts-with? never "still blocked") "runBlocking waits for ever")))

(deftest m3-slow-cancel-is-waited-for
  (with-grace 5000
    (set! (. fx.R3State cleaned) false)
    (let [r (run-interrupted #(f/r3SlowFinally) 8000)]
      (println "M3 CLOJURE slow finally:" (dissoc r :ex) (some-> (:ex r) class))
      (is (instance? InterruptedException (:ex r)))
      (is (false? (:flag r)))
      (is (>= (:ms r) 250))
      (is (true? (. fx.R3State cleaned)) "the callee finished its cleanup before the caller got the exception"))))

(deftest m3-callee-that-never-resumes-is-not-waited-for-ever
  (with-grace 400
    (let [r (run-interrupted #(f/r3Never) 8000)]
      (println "M3 CLOJURE never resumes:" (dissoc r :ex) (some-> (:ex r) class))
      (is (not (:blocked r)))
      (is (instance? InterruptedException (:ex r)))
      (is (false? (:flag r)) "the exception replaces the flag")
      (is (<= 350 (:ms r) 3000) (str (:ms r) " ms: cancel, wait for the grace time, then throw")))))

(deftest m3-shutdown-now-stops-a-wait
  (with-grace 400
    (let [pool (Executors/newFixedThreadPool 1)
          fut (.submit pool ^Callable (fn [] (f/r3Never)))]
      (Thread/sleep 150)
      (.shutdownNow pool)
      (is (.awaitTermination pool 5 TimeUnit/SECONDS) "the pool thread ended")
      (is (thrown? java.util.concurrent.ExecutionException (.get fut 1 TimeUnit/SECONDS))))))

(deftest m3-second-interrupt-ends-the-wait-at-once
  (with-grace 20000
    (let [r (run-interrupted #(f/r3Never) 8000 150)]
      (is (instance? InterruptedException (:ex r)))
      (is (< (:ms r) 2000) (str (:ms r) " ms")))))

(deftest m3-interrupt-before-the-call
  (with-grace 400
    (let [r (run-interrupted (fn [] (Thread/sleep 150) (f/r3Never)) 8000)]
      (is (instance? InterruptedException (:ex r)) (pr-str (dissoc r :ex))))))

;; ---------------------------------------------------------------- M9

(deftest m9-continuation-is-resumed-once
  (let [errs (atom []) old (Thread/getDefaultUncaughtExceptionHandler)]
    (Thread/setDefaultUncaughtExceptionHandler (reify Thread$UncaughtExceptionHandler
                                                 (uncaughtException [_ _ e] (swap! errs conj e))))
    (try
      (is (= "1:Success(41)" (f/r3CountCompletions (f/r3ThrowAfterRun) (fn [x] 41))) "resumed exactly once")
      (is (= "1:Failure(java.lang.IllegalStateException: body)"
             (f/r3CountCompletions (f/r3ThrowAfterRun) (fn [x] (throw (IllegalStateException. "body"))))))
      (is (some #(= "after-run" (ex-message %)) @errs) "the exception of the interceptor goes to the uncaught handler")
      (finally (Thread/setDefaultUncaughtExceptionHandler old)))))

;; ---------------------------------------------------------------- the cache

(defn- entry-dirs [^File d cname]
  (into {} (for [^File f (.listFiles d) :when (and (.isDirectory f) (str/starts-with? (.getName f) (str cname "-")))
                 :let [m (io/file f "entry.txt")]
                 :when (.isFile m)]
             [(second (re-find #"base (\S+)" (slurp m))) f])))

(defn- age! [^File f ms-ago] (.setLastModified f (- (System/currentTimeMillis) ms-ago)))

(def ^:private minute 60000)
(def ^:private day (* 24 60 60 1000))

(defn- cls [cname] {cname (.getBytes (str "class " cname) "UTF-8")})

(deftest m4-entry-without-a-manifest-is-repaired
  (let [d (tmp-dir) cname "ckway.bridge.K_m4"]
    (cache/store! d cname "b1" "2.4.20" (cls cname))
    (is (some? (cache/lookup d cname "b1" "2.4.20")))
    (testing "missing entry.txt: lookup deletes the entry"
      (let [e (get (entry-dirs d cname) "b1")]
        (.delete (io/file e "entry.txt"))
        (is (nil? (cache/lookup d cname "b1" "2.4.20")))
        (is (not (.exists e)) "deleted")))
    (cache/store! d cname "b1" "2.4.20" (cls cname))
    (is (some? (cache/lookup d cname "b1" "2.4.20")) "rewritten")
    (testing "unparsable entry.txt: store! replaces the entry"
      (let [e (get (entry-dirs d cname) "b1")]
        (is (some? e) "the entry exists")
        (when e (spit (io/file e "entry.txt") "garbage\n"))
        (cache/store! d cname "b1" "2.4.20" (cls cname))
        (is (some? (cache/lookup d cname "b1" "2.4.20")) "store! repaired it")))
    (testing "an empty directory of the entry name"
      (let [e (first (vals (entry-dirs d cname)))]
        (doseq [x (.listFiles e)] (.delete x))
        (cache/store! d cname "b1" "2.4.20" (cls cname))
        (is (some? (cache/lookup d cname "b1" "2.4.20")))))))

(deftest m8-prune-by-age-and-count-not-by-base
  (let [d (tmp-dir) cname "ckway.bridge.K_m8" other "ckway.bridge.K_m8other"]
    (cache/store! d other "o1" "2.4.20" (cls other))
    (cache/store! d cname "b-a" "2.4.20" (cls cname))
    (cache/store! d cname "b-b" "2.4.20" (cls cname))
    (is (= #{"b-a" "b-b"} (set (keys (entry-dirs d cname)))) "another base of the same bridge does not evict")
    (testing "at most `keep` entries per bridge: the least recently used go"
      (doseq [i (range 12)]
        (cache/store! d cname (str "b" i) "2.4.20" (cls cname))
        (doseq [[b e] (entry-dirs d cname)
                :when (re-matches #"b\d+" b)]
          (when (not= b (str "b" i)) (age! e (* (- 100 (parse-long (subs b 1))) minute)))))
      (let [bs (set (keys (entry-dirs d cname)))]
        (is (<= (count bs) (cv keep-per-bridge)))
        (is (contains? bs "b11") "the newest stays")
        (is (not (contains? bs "b0")) "the oldest went"))
      (let [bs (set (keys (entry-dirs d cname)))
            oldest (first (sort-by #(parse-long (subs % 1)) (filter #(re-matches #"b\d+" %) bs)))]
        (is (some? (cache/lookup d cname oldest "2.4.20")))
        (cache/store! d cname "bnew" "2.4.20" (cls cname))
        (let [after (set (keys (entry-dirs d cname)))]
          (is (contains? after oldest) "a hit counts as a use: the entry that was read stays")
          (is (contains? after "bnew")))))
    (testing "a hit updates the last-use time and the entry still verifies"
      (let [e (get (entry-dirs d cname) "bnew")]
        (age! e (* 3 day))
        (is (some? (cache/lookup d cname "bnew" "2.4.20")))
        (is (< (- (System/currentTimeMillis) (.lastModified ^File e)) (* 5 minute)) "touched")
        (is (some? (cache/lookup d cname "bnew" "2.4.20")) "verifies after the touch")))
    (testing "an entry that was not used for a long time goes, whatever the bridge"
      (let [e (first (vals (entry-dirs d other)))]
        (age! e (* 200 day))
        (cache/store! d cname "b-late" "2.4.20" (cls cname))
        (is (not (.exists ^File e)))))))

(deftest m10-stale-temporary-directories-are-removed
  (let [d (tmp-dir) cname "ckway.bridge.K_m10"
        old (io/file d ".ckway-tmp-00000000-0000-0000-0000-000000000001") fresh (io/file d ".ckway-tmp-00000000-0000-0000-0000-000000000002")]
    (.mkdirs old) (.mkdirs fresh)
    (spit (io/file old "x.class") "x")
    (age! old (* 3 day))
    (cache/store! d cname "b1" "2.4.20" (cls cname))
    (is (not (.exists old)) "a temporary directory from a killed JVM")
    (is (.exists fresh) "a recent one may belong to a JVM that is writing right now")))

;; M6 / M7

(deftest m6-default-directory-must-be-absolute
  (doseq [props [{"os.name" "Linux" "user.home" "?"}
                 {"os.name" "Linux" "user.home" ""}
                 {"os.name" "Linux" "user.home" "relative/home"}
                 {"os.name" "Linux"}]]
    (is (nil? ((cv dir-for) nil {} props)) (pr-str props)))
  (is (nil? ((cv dir-for) nil {"LOCALAPPDATA" "rel"} {"os.name" "Windows 11" "user.home" "C:\\Users\\u"})))
  (testing "an absolute home works, also when user.name is `?`"
    (let [home (tmp-dir) old (System/getProperty "user.name")]
      (System/setProperty "user.name" "?")
      (try
        (is (= (io/file home ".cache" "ckway" "bridges-v1") ((cv dir-for) nil {} {"os.name" "Linux" "user.home" (.getPath home)})))
        (cache/make-dirs! (io/file home ".cache" "ckway"))
        (is (= (io/file home ".cache" "ckway" "bridges-v1") ((cv dir-for) nil {} {"os.name" "Linux" "user.home" (.getPath home)}))
            "an existing private directory of this user is used")
        (finally (System/setProperty "user.name" old))))))

(defn- chmod! [^File f ^String perms] (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString perms)))

(defn- warnings-of [f]
  (let [w (StringWriter.)] (binding [*err* w] (f)) (str w)))

(deftest m7-explicit-directory-is-checked
  (let [props {"os.name" "Linux" "user.home" "/nonexistent"}]
    (testing "a private directory of this user is used, silently"
      (let [d (tmp-dir)]
        (chmod! d "rwx------")
        (is (= "" (warnings-of #(is (= (io/file d "bridges-v1") ((cv dir-for) (.getPath d) {} props))))))))
    (testing "writable by the group: the cache is off, one warning line says why and how to fix it"
      (let [d (tmp-dir)]
        (chmod! d "rwxrwx---")
        (let [w (warnings-of #(do (is (nil? ((cv dir-for) (.getPath d) {} props)))
                                  (is (nil? ((cv dir-for) (.getPath d) {} props)))))]
          (is (= 1 (count (str/split-lines w))) w)
          (is (str/includes? w "ckway.cache.dir") w)
          (is (str/includes? w (.getPath d)) w)
          (is (str/includes? w "group") w)
          (is (str/includes? w "chmod 700") w))))
    (testing "writable by others"
      (let [d (tmp-dir)]
        (chmod! d "rwx---rwx")
        (let [w (warnings-of #(is (nil? ((cv dir-for) (.getPath d) {} props))))]
          (is (str/includes? w "others") w))))
    (testing "a symlink is resolved before the check"
      (let [bad (tmp-dir) good (tmp-dir) links (tmp-dir)]
        (chmod! bad "rwxrwxrwx") (chmod! good "rwx------")
        (let [l-bad (io/file links "bad") l-good (io/file links "good")]
          (Files/createSymbolicLink (.toPath l-bad) (.toPath bad) (make-array FileAttribute 0))
          (Files/createSymbolicLink (.toPath l-good) (.toPath good) (make-array FileAttribute 0))
          (is (str/includes? (warnings-of #(is (nil? ((cv dir-for) (.getPath l-bad) {} props)))) "ckway.cache.dir"))
          (is (= (io/file l-good "bridges-v1") ((cv dir-for) (.getPath l-good) {} props))))))
    (testing "a file, not a directory"
      (let [d (tmp-dir) f (io/file d "x")]
        (spit f "x")
        (let [w (warnings-of #(is (nil? ((cv dir-for) (.getPath f) {} props))))]
          (is (str/includes? w "not a directory") w))))
    (testing "empty value: off, no warning; a directory that does not exist yet: used (kt creates it private)"
      (is (= "" (warnings-of #(is (nil? ((cv dir-for) "" {} props))))))
      (let [d (io/file (tmp-dir) "new")]
        (is (= (io/file d "bridges-v1") ((cv dir-for) (.getPath d) {} props)))))
    (testing "the owner is compared with the owner of a file this process creates, not with a name"
      (let [d (tmp-dir) old (System/getProperty "user.name")]
        (System/setProperty "user.name" "?")
        (try (is (= (io/file d "bridges-v1") ((cv dir-for) (.getPath d) {} props)))
             (finally (System/setProperty "user.name" old))))
      (is (true? ((cv mine?) (tmp-dir))))
      (is (false? ((cv mine?) (io/file "/")))))))

;; ---------------------------------------------------------------- M11

(deftest m11-message-gives-the-dependency-for-a-consumer
  (let [msg (kotlinc/missing-message "(f/x :<> Int)")
        alias (get-in (edn/read-string (slurp "deps.edn")) [:aliases :kotlinc :extra-deps 'org.jetbrains.kotlin/kotlin-compiler-embeddable])]
    (is (str/includes? msg "org.jetbrains.kotlin/kotlin-compiler-embeddable") msg)
    (is (str/includes? msg (str "\"" (:mvn/version alias) "\"")) "the version of the :kotlinc alias of this repository")
    (is (str/includes? msg (pr-str (:exclusions alias))) msg)
    (is (str/includes? msg "deps.edn") msg)
    (is (str/includes? msg "only to compile") msg)
    (is (not (str/includes? msg "clojure -M:kotlinc")) "a consumer has no such alias")
    (is (not (str/includes? msg "`:kotlinc` alias of deps.edn")) msg)
    (is (str/includes? msg "not at run time") msg)))

;; ---------------------------------------------------------------- M12

(deftest m12-no-references-to-documents-that-are-not-in-the-repository
  (let [files (concat (map #(io/file "src/ckway" %) ["co.clj" "bridge.clj" "reify.clj"])
                      (filter #(str/ends-with? (.getName ^File %) ".clj") (concat (.listFiles (io/file "src/ckway/co")) (.listFiles (io/file "src/ckway/bridge"))))
                      (map #(io/file "test/ckway" %) ["review_b_test.clj" "reify_test.clj" "suspend_test.clj" "bridge_test.clj"
                                                      "reified_jvm_test.clj" "nocoro_test.clj"  "aot_test.clj"]))
        bad #"(?i)design-2|design problem|(?<![/\w])step \d|\bround \d|review finding|review b\d|\bbrief\b|\bagent\b"]
    (doseq [^File f files
            [i line] (map-indexed vector (str/split-lines (slurp f))) :when (re-find bad line)]
      (is false (str (.getPath f) ":" (inc i) ": " line)))))
