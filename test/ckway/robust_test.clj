(ns ckway.robust-test
  "Robust discovery, checked conversions, literal Int, Java SAM, error
  messages. Fixtures: `test-fixtures/fx/Robust.kt` (package fx.rb) and the special class paths that
  `bin/build-fixtures` makes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.resolve :as r]
            [ckway.rt :as rt])
  (:import [clojure.lang DynamicClassLoader RT]
           [java.io File StringWriter]
           [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]))

(kt/require '[fx.rb :as p] '[kotlin.collections :as c] '[kotlin.text :as tx] '[kotlin :as k] '[fx.opt :as o])

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.robust-test)] (eval form)))

(defn- root-message [^Throwable t]
  (ex-message (loop [t t] (if-let [c (.getCause t)] (recur c) t))))

(defn- error-of
  "Message of the exception that evaluating `form` throws (compile or run time), or nil."
  [form]
  (try (eval-here form) nil (catch Throwable t (root-message t))))

(defn- reflection-output [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

(defn- err-output
  "What the compiler prints to *err* while it compiles `form`."
  [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

;; ---------------------------------------------------------------- checked conversions

(deftest conversions-are-checked-whatever-the-consumers-compiler-flags
  (doseq [[call bad ptext] [['(p/intp x) 5000000000 "Int"]
                            ['(p/shortp x) 70000 "Short"]
                            ['(p/bytep x) 300 "Byte"]]
          flags [false true :warn-on-boxed]]
    (testing (str call " " bad " unchecked-math " flags)
      (let [form (list 'fn '[x] call)
            f (binding [*unchecked-math* flags *ns* (the-ns 'ckway.robust-test)] (eval form))
            e (try (f bad) nil (catch Throwable t t))]
        (is (some? e) "no silent truncation")
        (is (instance? clojure.lang.ExceptionInfo e) (str e))
        (is (re-find #"^kt: " (str (ex-message e))))
        (is (re-find (re-pattern (str "`x` \\(" ptext "\\)")) (str (ex-message e))) (ex-message e))
        (is (re-find #"out of range" (str (ex-message e)))))))
  (testing "in range values still work, with the flag on"
    (binding [*unchecked-math* :warn-on-boxed *ns* (the-ns 'ckway.robust-test)]
      (is (= "int:7" ((eval '(fn [x] (p/intp x))) 7)))
      (is (= "short:7" ((eval '(fn [x] (p/shortp x))) 7)))
      (is (= "byte:7" ((eval '(fn [x] (p/bytep x))) 7)))
      (is (= "long:5000000000" ((eval '(fn [x] (p/longp x))) 5000000000))))))

(deftest dynamic-path-range-check-is-a-kt-error
  (doseq [[v bad ptext] [[#'p/intp 5000000000 "Int"] [#'p/shortp 70000 "Short"] [#'p/bytep 300 "Byte"]]]
    (let [e (try (rt/call-dyn v [bad] {}) nil (catch Throwable t t))]
      (is (instance? clojure.lang.ExceptionInfo e) (str e))
      (is (re-find (re-pattern (str "`x` \\(" ptext "\\)")) (str (ex-message e))) (ex-message e))
      (is (re-find #"out of range" (str (ex-message e)))))))

(deftest nil-in-a-primitive-slot-is-a-kt-error
  (doseq [f ['(fn [x] (p/boolp x)) '(fn [x] (p/intp x)) '(fn [x] (p/longp x)) '(fn [x] (p/doublep x))
            '(fn [x] (p/charp x)) '(fn [x] (p/shortp x))]]
    (let [e (try ((eval-here f) nil) nil (catch Throwable t t))]
      (is (instance? clojure.lang.ExceptionInfo e) (str f " " e))
      (is (re-find #"^kt: " (str (ex-message e))) (str f " " (ex-message e)))
      (is (re-find #"nil" (str (ex-message e))))))
  (doseq [v [#'p/boolp #'p/intp #'p/longp #'p/doublep #'p/charp]]
    (let [e (try (rt/call-dyn v [nil] {}) nil (catch Throwable t t))]
      (is (instance? clojure.lang.ExceptionInfo e) (str v " " e)))))

;; ---------------------------------------------------------------- named arguments in written order

(deftest dynamic-path-evaluates-named-arguments-in-written-order
  (let [log (atom [])
        t (fn [k v] (swap! log conj k) v)
        f (eval-here '(fn [t]
                        (p/nineo :a (t :a (identity "a")) :b (t :b "b") :c (t :c "c") :d (t :d "d") :e (t :e "e")
                                 :f (t :f "f") :g (t :g "g") :h (t :h "h") :i (t :i "i"))))]
    (is (= "abcdefghi" (f t)))
    (is (= [:a :b :c :d :e :f :g :h :i] @log)))
  (testing "positional values are evaluated first, in order"
    (let [log (atom [])
          t (fn [k v] (swap! log conj k) v)
          f (eval-here '(fn [t] (p/nineo (t :a (identity "a")) (t :b "b") :c (t :c "c") :d (t :d "d"))))]
      (is (= "abcd" (f t)))
      (is (= [:a :b :c :d] @log)))))

;; ---------------------------------------------------------------- type hints

(deftest a-type-hint-on-a-kt-call-is-kept
  (is (not (str/includes? (reflection-output '(fn [] (.length ^String (p/echo "abc")))) "Reflection warning")))
  (is (= 3 ((eval-here '(fn [] (.length ^String (p/echo "abc")))))))
  (testing "the declared return class is the type when the user gave none"
    (is (not (str/includes? (reflection-output '(fn [] (.length (p/str1)))) "Reflection warning")))
    (is (not (str/includes? (reflection-output '(fn [] (.length (p/echo "abc" :<> String)))) "Reflection warning"))))
  (testing "a primitive return stays usable"
    (is (= 4 ((eval-here '(fn [] (+ 1 (p/plus1 2)))))))
    (is (= 3 ((eval-here '(fn [] (long (p/plus1 2)))))))
    (is (= 3 ((eval-here '(fn [] (inc (p/plus1 1)))))))))

;; ---------------------------------------------------------------- line numbers

(deftest dynamic-path-warning-has-the-line
  (let [out (binding [*file* "robust.clj"]
              (err-output (with-meta '(fn [x] (p/nineo (identity x)))
                            {:line 41 :column 3})))]
    (is (re-find #"robust\.clj:\d+:\d+ - call to nineo can't be resolved statically" out) out)))

;; ---------------------------------------------------------------- Char

(deftest an-integer-is-not-a-char
  (is (re-find #"^kt: no Kotlin declaration of `ch` fits" (str (error-of '(p/ch 65)))))
  (is (= "ch:A" (p/ch \A)))
  (is (= "ch:A" (rt/call-dyn #'p/ch [\A] {})))
  (testing "with a Char and a Double overload an integer is the Double (kt widens numbers), never the Char"
    (is (= "Char" (p/chd \A)))
    (is (= "Double" (p/chd 65)))
    (is (= "Double" (p/chd 65.0)))
    (is (= "Double" (p/kChdD)))
    (is (= "Double" (rt/call-dyn #'p/chd [65] {})))))

;; ---------------------------------------------------------------- messages: ambiguity, a var as a value, a missing class

(defn ^String label [s] (str "L" s))

(deftest a-var-tag-of-a-function-is-not-the-type-of-its-value
  (is (= ["La" "Lb"] (vec (p/mapper ["a" "b"] label))))
  (is (= ["La"] (vec (p/mapper ["a"] #'label)))))

(deftest a-var-as-a-value-takes-positional-arguments-only
  (let [msg (try (apply p/order [1 :b 2]) (catch Throwable t (ex-message t)))]
    (is (re-find #"positional arguments only" msg) msg)
    (is (re-find #"written form" msg) msg))
  (is (= "1-2-3" (p/order 1 :b 2 :c 3))))

(deftest a-function-and-a-property-with-one-name
  (let [msg (str (error-of '(p/name (p/A))))]
    (is (re-find #"is ambiguous" msg))
    (is (re-find #"function and a property" msg) msg)
    ;; the ways out (round9, A1): the function by a named argument, the property by a reference
    (is (re-find #"\(name :a \.\.\.\)" msg) msg)
    (is (re-find #"\(\(kt/ref X name\) x\)" msg) msg)))

;; ---------------------------------------------------------------- an integer literal that fits Int is an Int

(defmacro same-both
  [oracle f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))
        lits (into {} (keep-indexed (fn [i a] (when (and (integer? a) (<= Integer/MIN_VALUE a Integer/MAX_VALUE)) [i :int]))
                                    pos))]
    `(let [expected# ~oracle]
       (is (= expected# (~f ~@args)) (str "static " '(~f ~@args)))
       (is (= expected# (rt/call-dyn (var ~f) [~@pos] ~nm ~lits)) (str "dynamic " '(~f ~@args))))))

(deftest an-integer-at-any-and-type-parameters-stays-a-long
  (same-both "Long" p/anyKind 1)
  (same-both "Long" p/anyKind 5000000000)
  (same-both "Long" p/tKind 1)
  (same-both "Long,Long" p/varKind 1 2)
  (same-both "Long" p/numKind 1)
  (same-both (p/kLongKind) p/longKind 1)
  (testing "a value that is not a literal is the same"
    (is (= "Long" (p/anyKind (long 1))))
    (is (= "Long" (let [x 1] (p/anyKind (identity x)))))
    (is (= "Integer" (p/anyKind (int 1)))))
  (testing "stdlib: listOf(1, 2) holds the Clojure Longs"
    (is (every? #(instance? Long %) (c/listOf 1 2)))
    (is (every? #(instance? Long %) (rt/call-dyn #'c/listOf [1 2] {} {0 :int 1 :int})))
    (is (instance? Long (first (c/listOf 1))))
    (is (every? #(instance? Long %) (c/listOf (long 1) (long 2))))))

;; ---------------------------------------------------------------- a Clojure function for a Java functional interface

(deftest a-clojure-function-for-a-java-functional-interface
  (same-both 3 p/jfun (fn [s] (count s)))
  (same-both true p/jpred (fn [s] (= "abc" s)))
  (same-both "x" p/jsup (fn [] "x"))
  (same-both "ran" p/jrun (fn [] nil))
  (same-both 5 p/jbi (fn [a b] (+ a b)))
  (same-both -1 p/jcmp (fn [a b] (compare a b)))
  (same-both 7 p/jtwo (fn [s] (count s)) (fn [n] (+ n 5)))
  (testing "a Kotlin value or a Java object that already implements the interface passes as it is"
    (is (= 3 (p/jfun (reify java.util.function.Function (apply [_ s] (Integer/valueOf (count s)))))))
    (is (= "ran" (p/jrun (reify Runnable (run [_] nil))))))
  (testing "the result is converted like the result of a Kotlin function type: a Long becomes an Int"
    (is (= 3 (p/jfun (fn [s] 3))))
    (is (= 5 (p/jbi (fn [a b] 5)))))
  (testing "errors have the same quality"
    (let [msg (fn [f] (try (f) nil (catch Throwable t (ex-message t))))]
      (is (re-find #"^kt: Kotlin called this function as .*apply.* with 1 argument" (str (msg #(p/jfun (fn [] 1))))))
      (is (re-find #"^kt: nil where Kotlin expects a non-null Int" (str (msg #(p/jfun (fn [s] nil))))))
      (is (re-find #"^kt: expected an integer" (str (msg #(p/jfun (fn [s] "x")))))))))

;; ---------------------------------------------------------------- kt/ref and kt/set! with the part class of a multi-file facade

(deftest multi-file-facade-references-and-assignment
  (testing "references to public stdlib functions and properties of multi-file facades"
    (is (= [true false] (vec (map (kt/ref String .isNotBlank) ["a" ""]))))
    (is (= [true] (vec (map (kt/ref Collection .isNotEmpty) [[1]]))))
    (is (= [true] (vec (map (kt/ref String .toBoolean) ["true"]))))
    (is (= [true] (vec (map (kt/ref Result isSuccess) [(k/runCatching (fn [] 1))])))))
  (testing "a top-level function, a property, and an @InlineOnly function of a multi-file facade"
    (p/counter)
    (let [before (p/counter)]
      (is (= (inc before) ((kt/ref p/bump))))
      (is (= (inc before) ((kt/ref p/counter))))
      (is (= (* 2 (inc before)) ((kt/ref p/doubled))))
      (is (= 6 ((kt/ref p/inlineOnlyTwice) 3)))
      (is (= 6 (p/inlineOnlyTwice 3)))))
  (testing "kt/set! on top-level vars of a multi-file facade, static and dynamic"
    (is (= 5 (kt/set! (p/counter) 5)))
    (is (= 5 (p/counter)))
    (is (= "z" (kt/set! (p/label) "z")))
    (is (= "z-5" (p/joinLabel "-")))
    (let [v (identity 7)]
      (is (= 7 (kt/set! (p/counter) v)))
      (is (= 7 (p/counter))))))

;; ---------------------------------------------------------------- a class that mentions a missing class

(deftest one-class-with-a-missing-class-does-not-break-the-package
  (testing "the classpath really lacks pbopt.Missing"
    (is (nil? (r/jvm-class "pbopt.Missing"))))
  (testing "the rest of the package is there and works, static and dynamic"
    (same-both "ok" o/.ok (o/Opt))
    (same-both "ok2:3" o/.ok2 (o/Opt) 3)
    (same-both "prop" o/okProp (o/Opt))
    (same-both "fine" o/.fine (o/Plain))
    (same-both "top" o/topOk))
  (testing "a declaration that needs the missing class is a kt error that names it"
    (doseq [form ['(o/.uses (o/Opt) "x") '(o/topUses "x")]]
      (let [msg (str (error-of form))]
        (is (re-find #"^kt: " msg) msg)
        (is (re-find #"pbopt\.Missing" msg) msg)))
    (let [e (try (rt/call-dyn #'o/.uses [(o/Opt) "x"] {}) nil (catch Throwable t t))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (re-find #"^kt: .*pbopt\.Missing" (str (ex-message e))))))
  (testing "a class that cannot be linked at all is a var that says so"
    (doseq [form ['(o/NeedsBase) '(o/Capable)]]
      (let [msg (str (error-of form))]
        (is (re-find #"^kt: " msg) msg)
        (is (re-find #"pbopt\.Missing(Base|Iface)" msg) msg)))))

;; ---------------------------------------------------------------- discovery and caches

(defn- temp-dir-with-space ^File []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory "ckway robust " (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    d))

(defn- copy-tree [^File from ^File to]
  (doseq [^File f (file-seq from) :when (.isFile f)]
    (let [rel (.relativize (.toPath from) (.toPath f))
          dst (.toFile (.resolve (.toPath to) rel))]
      (io/make-parents dst)
      (io/copy f dst))))

(defn- make-jar [^File jar entries-dir ^Manifest mf]
  (with-open [out (JarOutputStream. (io/output-stream jar) mf)]
    (when entries-dir
      (doseq [^File f (file-seq entries-dir) :when (.isFile f)]
        (.putNextEntry out (JarEntry. (str/replace (str (.relativize (.toPath entries-dir) (.toPath f))) File/separator "/")))
        (io/copy f out)
        (.closeEntry out)))))

(defn- manifest [& kvs]
  (let [mf (Manifest.)]
    (.putValue (.getMainAttributes mf) "Manifest-Version" "1.0")
    (doseq [[k v] (partition 2 kvs)] (.putValue (.getMainAttributes mf) k v))
    mf))

(defn- call-with-class-path
  "Run `f` with the URLs added to a fresh DynamicClassLoader that is the base loader (what add-lib does), every cache of
  the library empty before and after."
  [urls f]
  (let [dcl (DynamicClassLoader. (RT/baseLoader))
        old (.getContextClassLoader (Thread/currentThread))]
    (doseq [u urls] (.addURL dcl u))
    (meta/clear-caches!)
    (try
      (.setContextClassLoader (Thread/currentThread) dcl)
      (with-bindings {clojure.lang.Compiler/LOADER dcl}
        (f))
      (finally
        (.setContextClassLoader (Thread/currentThread) old)
        (meta/clear-caches!)))))

(defn- require-late [] (binding [*ns* (the-ns 'ckway.robust-test)] (kt/require '[late :as l])))

(defn- late-works? []
  (and (= "late" (eval-here '(l/.hi (l/Late))))
       (= 3 (eval-here '(l/lateTop 2)))))

(deftest a-package-that-is-not-there-is-an-error
  (let [e (try (kt/require '[nosuch.pkg :as n]) nil (catch Throwable t t))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (re-find #"^kt/require: no Kotlin class found for package `nosuch\.pkg` on the class path" (str (ex-message e))) (ex-message e)))
  (testing "close names are listed"
    (let [e (try (kt/require '[fx.rbb :as n]) nil (catch Throwable t t))]
      (is (re-find #"Packages with a close name: .*fx\.rb" (str (ex-message e))) (ex-message e)))
    (let [e (try (kt/require '[fx.o :as n]) nil (catch Throwable t t))]
      (is (re-find #"fx\.other|fx\.opt" (str (ex-message e))) (ex-message e)))))

(deftest the-classpath-may-change-after-a-failed-require
  (let [dir (temp-dir-with-space)
        classes (io/file dir "my classes")]
    (copy-tree (io/file "target/late-classes") classes)
    (call-with-class-path
     []
     (fn []
       (testing "before: an error, and nothing is remembered"
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no Kotlin class found for package `late`" (require-late)))
         (is (nil? (r/jvm-class "late.Late"))))
       (testing "the directory (a path with a space, as a %20 URL) is added: the same require now works"
         (.addURL ^DynamicClassLoader (RT/baseLoader) (.toURL (.toURI classes)))
         (require-late)
         (is (late-works?))
         (is (some? (r/jvm-class "late.Late"))))))))

(deftest a-directory-url-with-a-raw-space
  (let [dir (temp-dir-with-space)
        classes (io/file dir "raw space")]
    (copy-tree (io/file "target/late-classes") classes)
    (call-with-class-path
     [(.toURL classes)]            ; File.toURL: the space is not encoded
     (fn []
       (is (some #(= (.getCanonicalPath ^File classes) (.getCanonicalPath ^File %)) (meta/classpath-files)))
       (require-late)
       (is (late-works?))))))

(deftest a-jar-with-a-class-path-manifest-entry
  (let [dir (temp-dir-with-space)
        dep (io/file dir "lib dir" "dep.jar")
        main (io/file dir "main.jar")]
    (io/make-parents dep)
    (make-jar dep (io/file "target/late-classes") (manifest))
    (make-jar main nil (manifest "Class-Path" "lib%20dir/dep.jar"))
    (call-with-class-path
     [(.toURL (.toURI main))]
     (fn []
       (is (some #(= (.getCanonicalPath dep) (.getCanonicalPath ^File %)) (meta/classpath-files)) "the Class-Path jar is followed")
       (require-late)
       (is (late-works?))))))

(deftest a-jar-added-later-is-seen-by-a-repeated-require
  (let [dir (temp-dir-with-space)
        jar (io/file dir "late.jar")]
    (make-jar jar (io/file "target/late-classes") (manifest))
    (call-with-class-path
     []
     (fn []
       (is (thrown? clojure.lang.ExceptionInfo (require-late)))
       (.addURL ^DynamicClassLoader (RT/baseLoader) (.toURL (.toURI jar)))
       (require-late)
       (is (late-works?))))))

(deftest clear-caches-empties-every-cache
  (is (= "int:1" (p/intp 1)))
  (rt/call-dyn #'p/intp [(int 1)] {})
  (is (pos? (.size ^java.util.Map (:kt/cache (meta #'p/intp)))))
  (is (some? (r/jvm-class "java.lang.String")))
  (let [before (meta/package-index "fx.rb")]
    (is (identical? before (meta/package-index "fx.rb")) "cached while the class path is the same")
    (meta/clear-caches!)
    (is (zero? (.size ^java.util.Map (:kt/cache (meta #'p/intp)))))
    (is (not (identical? before (meta/package-index "fx.rb"))) "rebuilt after the reset")
    (is (= "int:1" (p/intp 1)))))
