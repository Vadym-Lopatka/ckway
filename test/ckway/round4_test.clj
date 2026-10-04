(ns ckway.round4-test
  "Third review: library-added types are upper bounds (X1), the cache prunes only its own entries (X2), specificity
  before tiers (X3), the softer type-hint rule (X4, X5), the number message (X6), `kt/reify` with one member in two
  branches (X7), the cache directory check (X8), the interrupt grace (X9, X10) and the public metadata API (X11).
  Selection tests compare with KOTLIN: a `k...` function of `test-fixtures/fx/Round4.kt` makes the same call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.rt :as rt])
  (:import [java.io File]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute FileTime]))

(kt/require '[fx.r4 :as p] '[fx :as f] '[kotlin.collections :as c] '[kotlin.math :as m])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))
(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round4-test)] (eval form)))
(defn- compile-error [form] (try (eval-here form) nil (catch Throwable t (ct/root-message t))))
(defn- msg [f] (some-> (thrown f) ct/root-message))
(defn- dyn-message [v pos] (some-> (thrown #(rt/call-dyn v pos {} {})) ct/root-message))

;; ---------------------------------------------------------------- X1

(deftest x1-lambda-parameters-are-upper-bounds
  (testing "a member of the subclass in a plain lambda"
    (is (= ["pos1"] (eval-here '(p/eachEv [(p/Click 1)] (fn [e] (p/.pos e))))))
    (is (= ["pos1" "code2"] (eval-here '(p/eachEv (p/events) (fn [e] (if (instance? fx.r4.Click e) (p/.pos e) (p/.code e))))))))
  (testing "an overload of the subclass"
    (is (= ["click" "key"] (eval-here '(p/eachEv (p/events) (fn [e] (p/handle e)))))))
  (testing "the short literal"
    (is (= ["click" "key"] (eval-here '(p/eachEv (p/events) #(p/handle %))))))
  (testing "a nested lambda"
    (is (= ["pos1"] (eval-here '(p/eachEv [(p/Click 1)] (fn [e] (first (p/eachEv [e] (fn [e2] (p/.pos e2))))))))))
  (testing "a destructured parameter"
    (is (= ["pos1"] (eval-here '(p/eachEvs [[(p/Click 1) (p/Key 2)]] (fn [[a b]] (p/.pos a)))))))
  (testing "a suspend lambda"
    (is (= ["pos1"] (eval-here '(p/eachEvS [(p/Click 1)] (fn [e] (p/.pos e))))))
    (is (= ["click" "key"] (eval-here '(p/eachEvS (p/events) (fn [e] (p/handle e)))))))
  (testing "the user's own hint on a lambda parameter is the static type and wins over the library's"
    (is (= ["click"] (eval-here '(p/eachEv [(p/Click 1)] (fn [^fx.r4.Click e] (p/handle e))))))
    (is (= ["pos1"] (eval-here '(p/eachEv [(p/Click 1)] (fn [^fx.r4.Click e] (p/.pos e))))))
    (is (some? (compile-error '(p/eachEv [(p/Click 1)] (fn [^Long e] (p/.pos e)))))
        "^Long can never be a Click")))

(deftest x1-reify-parameters-are-upper-bounds
  (testing "kt/reify member parameter"
    (is (= "pos1" (eval-here '(p/runVisitor (kt/reify p/Visitor (.visit [this e] (p/.pos e))) (p/Click 1)))))
    (is (= ["click" "key"] (eval-here '(p/runVisitorAll (kt/reify p/Visitor (.visit [this e] (p/handle e))))))))
  (testing "a user hint on a reify parameter is the static type"
    (is (= "click" (eval-here '(p/runVisitor (kt/reify p/Visitor (.visit [this ^fx.r4.Click e] (p/handle e))) (p/Click 1)))))))

;; ---------------------------------------------------------------- X3

(deftest x3-specificity-before-tiers
  (testing "gl: List<T> / Collection<Int>"
    (is (= "Collection<Int>" (p/kGl [1])))
    (is (= "Collection<Int>" (p/gl [1])) "vector literal")
    (is (= "Collection<Int>" (p/gl (c/listOf 1))) "nested call")
    (is (= "Collection<Int>" (rt/call-dyn #'p/gl [(c/listOf 1)] {} {})) "dynamic")
    (is (= "Collection<Int>" (let [xs (c/listOf 1)] (p/gl xs))) "local")
    (is (= "Collection<Int>" (let [f p/gl] (f (c/listOf 1)))) "var as a value"))
  (testing "vt: List<T> / Iterable<Int>"
    (is (= "Iterable<Int>" (p/kVt [1])))
    (is (= "Iterable<Int>" (p/vt [1])))
    (is (= "Iterable<Int>" (p/vt (c/listOf 1))))
    (is (= "Iterable<Int>" (rt/call-dyn #'p/vt [(c/listOf 1)] {} {})))
    (is (= "Iterable<Int>" (let [f p/vt] (f (c/listOf 1))))))
  (testing "vc: MutableList<Int> / Collection<String> are unrelated: ambiguous"
    (is (str/includes? (str (compile-error '(p/vc [1]))) "ambiguous") "vector literal")
    (is (str/includes? (str (msg #(eval-here '(p/vc (c/listOf 1))))) "ambiguous") "nested call")
    (is (str/includes? (str (dyn-message #'p/vc [(c/listOf 1)])) "ambiguous") "dynamic")
    (is (str/includes? (str (msg #(let [f p/vc] (f (c/listOf 1))))) "ambiguous") "var as a value")))

;; ---------------------------------------------------------------- X4

(deftest x4-hints-that-fit-no-candidate-for-sure
  (testing "common Clojure hints at a Kotlin collection parameter work (the value decides at run time)"
    (is (true? (eval-here '(let [^clojure.lang.IPersistentMap m {1 "a"}] (p/hasKey m 1)))))
    (is (= 2 (eval-here '(let [^clojure.lang.IPersistentVector v [1 2]] (p/sizeOfList v)))))
    (is (= 1 (eval-here '(let [^clojure.lang.ISeq s (seq [1 2])] (p/firstOfSeq s)))))
    (is (= "1,2" (eval-here '(let [^java.util.Collection v [1 2]] (p/joinColl v)))))
    (is (= 2 (eval-here '(let [^java.util.Collection v [1 2]] (p/sizeOfList v))))
        "Collection at a List parameter")
    (is (= 2 (eval-here '(p/sizeOfList ^java.util.Collection [1 2])))))
  (testing "a hint that fits a candidate for sure decides, as a declared type does in Kotlin"
    (is (= 3 (eval-here '(let [^CharSequence s "abc"] (p/lenOf s)))) "a String is a CharSequence: no candidate for sure, runtime"))
  (testing "a wrong run-time class is still a kt error"
    (let [m (msg #(eval-here '(let [^clojure.lang.IPersistentMap m (identity [1])] (p/hasKey m 1))))]
      (is (and m (str/starts-with? m "kt:")) m)))
  (testing "a hint that can never fit stays a compile error"
    (let [m (compile-error '(let [^Long n (identity 1)] (p/strLen n)))]
      (is (and m (str/includes? m "kt:")) (str m))
      (is (str/includes? (str m) "Long") (str m)))))

;; ---------------------------------------------------------------- X5

(deftest x5-a-wrong-hint-is-a-kt-error
  (let [m (msg #(eval-here '(let [^fx.r4.Sq s (identity (p/circ))] (p/.area s))))]
    (is (some? m))
    (is (str/starts-with? (str m) "kt:") (str m))
    (is (and (str/includes? (str m) "Circ") (str/includes? (str m) "Sq") (str/includes? (str m) "area")) (str m)))
  (testing "an argument"
    (let [m (msg #(eval-here '(let [^fx.r4.Sq s (identity (p/circ))] (p/areaOf s))))]
      (is (str/starts-with? (str m) "kt:") (str m))
      (is (str/includes? (str m) "Circ") (str m))))
  (testing "the happy path"
    (is (= 4 (eval-here '(let [^fx.r4.Sq s (identity (p/Sq 2))] (p/.area s)))))))

;; ---------------------------------------------------------------- X6

(deftest x6-sqrt-of-an-integer
  (let [e (compile-error '(m/sqrt 4))]
    (is (str/includes? (str e) "ambiguous") (str e))
    (is (str/includes? (str e) "4.0") (str e))
    (is (str/includes? (str e) "(double x)") (str e)))
  (let [e (compile-error '(let [x 4] (m/sqrt x)))]
    (is (str/includes? (str e) "(double x)") (str e)))
  (is (= 2.0 (m/sqrt 4.0)))
  (is (= 2.0 (m/sqrt (double 4)))))

;; ---------------------------------------------------------------- X2

(defn- tmp-dir ^File []
  (.toFile (Files/createTempDirectory "ckway-r4" (make-array FileAttribute 0))))

(defn- age! [^File f ms-ago] (.setLastModified f (- (System/currentTimeMillis) ms-ago)))
(def ^:private day (* 24 60 60 1000))
(def ^:private hour (* 60 60 1000))

(defn- cache-ns [s] (deref (requiring-resolve (symbol "ckway.bridge.cache" s))))
(defn- classes-of [cname] {cname (.getBytes (str "class " cname) "UTF-8")})
(def ^:private entry-key "0123456789abcdef0123456789abcdef")

(defn- make-entry!
  "A real entry that the cache wrote (through `store!`), made old."
  [^File d cname base ms-ago]
  ((cache-ns "store!") d cname base "2.4.20" (classes-of cname))
  (let [e (first (filter #(and (.isDirectory ^File %) (str/starts-with? (.getName ^File %) (str cname "-"))
                               (str/includes? (slurp (io/file ^File % "entry.txt")) (str "base " base "\n")))
                         (.listFiles d)))]
    (age! e ms-ago)
    e))

(deftest x2-the-cache-deletes-only-what-it-created
  (let [root (tmp-dir)                                  ; the directory that the user names
        elsewhere (tmp-dir)
        _ (spit (io/file elsewhere "keep.txt") "outside")
        _ (age! elsewhere (* 200 day))
        sub ((cache-ns "dir-for") (.getPath root) {} {"os.name" "Linux" "user.home" "/nonexistent"})]
    (testing "the library works in its own subdirectory"
      (is (= (io/file root "bridges-v1") sub)))
    ;; what the reviewer had: foreign things in the directory that is named
    (let [precious (io/file root "precious" "sub")
          tool (io/file root ".tmp-other-tool")
          old-file (io/file root "old.txt")
          link (io/file root "link")]
      (.mkdirs precious) (spit (io/file precious "file.txt") "precious")
      (.mkdirs tool) (spit (io/file tool "x") "x")
      (spit old-file "old")
      (Files/createSymbolicLink (.toPath link) (.toPath elsewhere) (make-array FileAttribute 0))
      (doseq [f [precious (io/file root "precious") tool old-file]] (age! f (* 200 day)))
      ((cache-ns "store!") sub "ckway.bridge.K_x2" "b1" "2.4.20" (classes-of "ckway.bridge.K_x2"))
      (is (.exists (io/file precious "file.txt")) "an old sibling directory with content stays")
      (is (.exists (io/file tool "x")) "a foreign .tmp-* directory stays")
      (is (.exists old-file) "an old plain file stays")
      (is (.exists (io/file elsewhere "keep.txt")) "nothing behind a symbolic link"))
    ;; the same things inside the subdirectory itself (a user who put them there, or an older layout)
    (let [in-sub (fn [& n] (apply io/file sub n))
          precious (in-sub "precious" "sub")
          tool (in-sub ".tmp-other-tool")
          old-file (in-sub "old.txt")
          fake-entry-file (in-sub (str "ckway.bridge.K_file-" entry-key))
          fake-entry-dir (in-sub (str "ckway.bridge.K_foreign-" entry-key))   ; the name fits, the content is not an entry
          fake-tmp-dir (in-sub ".ckway-tmp-not-a-uuid")
          link-entry (in-sub (str "ckway.bridge.K_link-" entry-key))
          link-tmp (in-sub ".ckway-tmp-00000000-0000-0000-0000-0000000000aa")]
      (.mkdirs precious) (spit (io/file precious "file.txt") "precious")
      (.mkdirs tool) (spit (io/file tool "x") "x")
      (spit old-file "old")
      (spit fake-entry-file "plain file")
      (.mkdirs (io/file fake-entry-dir "docs")) (spit (io/file fake-entry-dir "docs" "thesis.txt") "mine")
      (.mkdirs fake-tmp-dir) (spit (io/file fake-tmp-dir "y") "y")
      (Files/createSymbolicLink (.toPath link-entry) (.toPath elsewhere) (make-array FileAttribute 0))
      (Files/createSymbolicLink (.toPath link-tmp) (.toPath elsewhere) (make-array FileAttribute 0))
      (doseq [f [precious (in-sub "precious") tool old-file fake-entry-file fake-entry-dir fake-tmp-dir]] (age! f (* 200 day)))
      (doseq [^File l [link-entry link-tmp]]
        (Files/setLastModifiedTime (.toPath l) (java.nio.file.attribute.FileTime/fromMillis (- (System/currentTimeMillis) (* 200 day)))
                                   ) )
      ;; ckway's own stale things
      (let [own-old (make-entry! sub "ckway.bridge.K_old" "o1" (* 200 day))
            own-tmp (in-sub ".ckway-tmp-00000000-0000-0000-0000-000000000001")
            own-fresh (in-sub ".ckway-tmp-00000000-0000-0000-0000-000000000002")]
        (.mkdirs own-tmp) (spit (io/file own-tmp "z.class") "z") (age! own-tmp (* 3 hour))
        (.mkdirs own-fresh)
        ((cache-ns "store!") sub "ckway.bridge.K_x2" "b2" "2.4.20" (classes-of "ckway.bridge.K_x2"))
        (is (not (.exists own-old)) "its own stale entry goes")
        (is (not (.exists own-tmp)) "its own stale temporary directory goes")
        (is (.exists own-fresh) "a recent one may belong to a JVM that is writing"))
      (is (.exists (io/file precious "file.txt")))
      (is (.exists (io/file tool "x")))
      (is (.exists old-file))
      (is (.exists fake-entry-file))
      (is (.exists (io/file fake-entry-dir "docs" "thesis.txt")) "a directory with an entry name and foreign content")
      (is (.exists (io/file fake-tmp-dir "y")))
      (is (.exists (io/file elsewhere "keep.txt")) "a symbolic link is never followed")
      (is (Files/isSymbolicLink (.toPath link-entry)) "the link itself stays too")
      (is (Files/isSymbolicLink (.toPath link-tmp))))))

(deftest x2-the-cache-never-reads-or-deletes-through-a-link
  (let [d (tmp-dir) elsewhere (tmp-dir)
        cname "ckway.bridge.K_x2link"
        real ((cache-ns "store!") d cname "b1" "2.4.20" (classes-of cname))
        entry (first (filter #(str/starts-with? (.getName ^File %) cname) (.listFiles d)))
        moved (io/file elsewhere (.getName ^File entry))]
    (Files/move (.toPath ^File entry) (.toPath moved) (make-array java.nio.file.CopyOption 0))
    (Files/createSymbolicLink (.toPath ^File entry) (.toPath moved) (make-array FileAttribute 0))
    (is (nil? ((cache-ns "lookup") d cname "b1" "2.4.20")) "an entry that is a link is not read")
    ((cache-ns "discard!") d cname "b1" "2.4.20")
    (is (.exists (io/file moved "entry.txt")) "discard does not follow the link")))

(deftest x2-subdirectory-and-its-check
  (let [props {"os.name" "Linux" "user.home" "/nonexistent"}
        dir-for (cache-ns "dir-for")
        w (java.io.StringWriter.)]
    (testing "the subdirectory is created private, with the chain above it that kt creates"
      (let [root (io/file (tmp-dir) "a" "b")
            sub (dir-for (.getPath root) {} props)]
        (is (= (io/file root "bridges-v1") sub))
        ((cache-ns "store!") sub "ckway.bridge.K_x2d" "b" "2.4.20" (classes-of "ckway.bridge.K_x2d"))
        (is (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                            (Files/getPosixFilePermissions (.toPath sub) (make-array LinkOption 0)))))
        (is (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                            (Files/getPosixFilePermissions (.toPath root) (make-array LinkOption 0)))))))
    (testing "a subdirectory that others can write is refused, with a line that names it"
      (let [root (tmp-dir) sub (io/file root "bridges-v1")]
        (.mkdirs sub)
        (Files/setPosixFilePermissions (.toPath sub) (java.nio.file.attribute.PosixFilePermissions/fromString "rwxrwx---"))
        (binding [*err* w] (is (nil? (dir-for (.getPath root) {} props))))
        (is (str/includes? (str w) "bridges-v1") (str w))))
    (testing "a subdirectory that is a symbolic link is refused"
      (let [root (tmp-dir) other (tmp-dir) w (java.io.StringWriter.)]
        (Files/createSymbolicLink (.toPath (io/file root "bridges-v1")) (.toPath other) (make-array FileAttribute 0))
        (binding [*err* w] (is (nil? (dir-for (.getPath root) {} props))))
        (is (str/includes? (str w) "symbolic link") (str w))))
    (testing "a named directory that the group can write is refused (as before)"
      (let [root (tmp-dir) w (java.io.StringWriter.)]
        (Files/setPosixFilePermissions (.toPath root) (java.nio.file.attribute.PosixFilePermissions/fromString "rwxrwx---"))
        (binding [*err* w] (is (nil? (dir-for (.getPath root) {} props))))
        (is (str/includes? (str w) "group") (str w))))))

;; ---------------------------------------------------------------- X9 / X10

(defmacro ^:private with-grace [ms & body]
  `(let [old# (System/getProperty "ckway.interrupt.grace.ms")]
     (System/setProperty "ckway.interrupt.grace.ms" (str ~ms))
     (try ~@body
          (finally (if old# (System/setProperty "ckway.interrupt.grace.ms" old#) (System/clearProperty "ckway.interrupt.grace.ms"))))))

(defn- run-interrupted
  "Run `(f)` in a daemon thread, interrupt it after 100 ms (and again after `again-ms` if given).
  => {:ex :flag :ms} or {:value ..}, or {:blocked true} after `wait-ms`."
  [f wait-ms & [again-ms]]
  (let [res (promise) t0 (volatile! (System/nanoTime))
        th (doto (Thread. (fn [] (deliver res (try {:value (f)}
                                                   (catch Throwable e {:ex e :flag (Thread/interrupted)
                                                                       :ms (/ (- (System/nanoTime) @t0) 1e6)})))))
             (.setDaemon true) (.start))]
    (Thread/sleep 100)
    (vreset! t0 (System/nanoTime))
    (.interrupt th)
    (when again-ms (Thread/sleep ^long again-ms) (.interrupt th))
    (deref res wait-ms {:blocked true})))

(deftest x9-a-huge-grace-time-is-clamped
  (doseq [g ["9223372036854775807" "999999999999999" "92233720368547758"]]
    (with-grace g
      (let [r (run-interrupted #(f/r3Never) 8000 150)]
        (is (instance? InterruptedException (:ex r)) (str g " " (pr-str (dissoc r :ex)) " " (some-> (:ex r) class)))))))

(deftest x9-negative-and-non-numeric-grace-time
  (testing "negative: no grace, InterruptedException as soon as the cancel is sent"
    (with-grace -5
      (let [r (run-interrupted #(f/r3Never) 8000)]
        (is (instance? InterruptedException (:ex r)) (pr-str (dissoc r :ex)))
        (is (< (:ms r) 2000)))))
  (testing "not a number: the default (5000 ms); a callee that finishes its cancellation quickly is waited for"
    (with-grace "abc"
      (set! (. fx.R3State cleaned) false)
      (let [r (run-interrupted #(f/r3SlowFinally) 8000)]
        (is (instance? InterruptedException (:ex r)) (pr-str (dissoc r :ex)))
        (is (true? (. fx.R3State cleaned)))))))

(deftest x10-the-interrupt-flag-is-set-only-on-the-body-thread
  (let [seen (promise)
        r (f/r3TimeoutBlock 100 (fn [] (let [fut (future (f/r3Uncancellable 300)
                                                         (deliver seen {:flag (.isInterrupted (Thread/currentThread))}))]
                                         @fut)))]
    (is (str/starts-with? r "timeout@") r)
    (is (= {:flag false} (deref seen 3000 :none))
        "the child thread did not get the interrupt of the cancel")))

(deftest x10-a-suspend-call-from-a-child-thread-is-a-top-level-call-in-the-body-context
  (with-grace 5000
    (set! (. fx.R3State cleaned) false)
    (let [out (promise)
          r (f/r3TimeoutBlock 5000
                              (fn [] (let [fut (future (try (f/r3SlowFinally) (catch Throwable e (deliver out {:ex e :cleaned (. fx.R3State cleaned)}))))]
                                       (Thread/sleep 150)
                                       (future-cancel fut)
                                       (deref out 3000 :none))))]
      (is (instance? InterruptedException (:ex (deref out 100 {}))) (pr-str (deref out 100 nil)))
      (is (true? (:cleaned (deref out 100 {}))) "the callee finished its cancellation before the exception, as for a top-level call"))))

(deftest x10-cancelling-the-body-cancels-the-call-of-the-child
  (set! (. fx.R3State cleaned) false)
  (let [out (promise)
        r (f/r3TimeoutBlock 150 (fn [] (let [fut (future (try (f/r3SlowFinally) (catch Throwable e (deliver out e))))]
                                         @fut)))]
    (is (str/starts-with? r "timeout@") r)
    (let [e (deref out 3000 nil)]
      (is (some? e) "the call of the child ended")
      (is (instance? java.util.concurrent.CancellationException e) (str (class e))))
    (is (true? (. fx.R3State cleaned)) "the callee got the cancellation of the body's Job through the child Job")))

;; ---------------------------------------------------------------- X7

(deftest x7-one-member-narrowed-in-two-branches
  (testing "one written form serves both leaf overrides and the original"
    (let [o (eval-here '(kt/reify p/S1 p/S2 (.get [this] "ab")))]
      (is (= "ab/S1" (p/readS1 o)))
      (is (= "ab/S2" (p/readS2 o)))
      (is (= "ab/Src" (p/readSrc o)))))
  (testing "the form under the first interface serves the second, which has no form"
    (let [o (eval-here '(kt/reify p/S1 (.get [this] "ab") p/S2))]
      (is (= "ab/S1" (p/readS1 o)))
      (is (= "ab/S2" (p/readS2 o)))))
  (testing "a member written separately under each interface still works"
    (let [o (eval-here '(kt/reify p/S1 (.get [this] "cs") p/S2 (.get [this] 5)))]
      (is (= "cs/S1" (p/readS1 o)))
      (is (= "5/S2" (p/readS2 o)))))
  (testing "the result is checked per JVM signature: a kt error with the usual text"
    (let [o (eval-here '(kt/reify p/S1 p/S2 (.get [this] 5)))]
      (is (= "5/S2" (p/readS2 o)))
      (let [m (msg #(p/readS1 o))]
        (is (and m (str/starts-with? m "kt:")) (str m))
        (is (str/includes? (str m) "CharSequence") (str m)))))
  (testing "different parameter types in the leaves: a compile error that says what to do"
    (let [m (compile-error '(kt/reify p/G1 p/G2 (.put [this x] "x")))]
      (is (str/includes? (str m) "different parameter types") (str m))
      (is (str/includes? (str m) "under each interface") (str m)))
    (let [o (eval-here '(kt/reify p/G1 (.put [this x] (str "s" x)) p/G2 (.put [this x] (str "i" x))))]
      (is (= "sa" (p/putG1 o "a")))
      (is (= "i3" (p/putG2 o 3))))))

;; ---------------------------------------------------------------- X11

(deftest x11-own-declarations-is-public
  (is (fn? (deref (requiring-resolve 'ckway.meta/own-declarations))))
  (is (:doc (meta (requiring-resolve 'ckway.meta/own-declarations))))
  (let [own (deref (requiring-resolve 'ckway.meta/own-declarations))
        ds (own fx.r4.StrSrc)]
    (is (= ["get"] (map :name ds)) "the override is there")
    (is (= "fx.r4.StrSrc" (:owner (first ds))))
    (is (= "kotlin/String" (:class (:return (first ds))))))
  (is (nil? ((deref (requiring-resolve 'ckway.meta/own-declarations)) String)) "no Kotlin class metadata")
  (testing "reify no longer reaches into private functions"
    (is (not (str/includes? (slurp "src/ckway/reify.clj") "@#'")))))

(deftest x11-a-narrowed-override-is-seen-by-other-callers
  (testing "the static path: the result of StrSrc.get() is a String, not Any"
    (let [r (binding [*warn-on-reflection* true]
              (let [w (java.io.StringWriter.)]
                (binding [*err* w]
                  (is (= 3 ((eval-here '(fn [^fx.r4.StrSrc s] (.length (p/.get s)))) (p/strBox)))))
                (str w)))]
      (is (not (str/includes? r "reflection")) r)))
  (testing "the result class of the declaration the call selects"
    (is (= String (class (p/.get (p/strBox))))))
  (testing "the other branches keep their own types"
    (let [o (eval-here '(kt/reify p/S1 p/S2 (.get [this] "ab")))]
      (is (= "ab" (p/.get ^fx.r4.S1 o)))
      (is (= "ab" (p/.get ^fx.r4.Src o)))))
  (testing "kt/ref picks the member of the interface that the reference names"
    (is (= "box" ((eval-here '(kt/ref p/StrSrc .get)) (p/strBox))))
    (is (= "box" ((eval-here '(kt/ref p/Src .get)) (p/strBox))))
    (is (= "impl" ((eval-here '(kt/ref p/StrSrc .get)) (p/StrImpl))))))
