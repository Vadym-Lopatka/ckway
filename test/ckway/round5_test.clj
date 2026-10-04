(ns ckway.round5-test
  "Fourth review: inherited defaults on narrowed overrides (Y1), a suspend call completes its own Job on every exit (Y2),
  specificity on every parameter (Y3), a narrowed override and an untyped receiver (Y4), `kt/reify` for a Java and a Kotlin
  leaf (Y5), cache warnings (Y6, Y7), the sqrt message (Y8).
  Selection tests compare with KOTLIN: a `k...` function of `test-fixtures/fx/Round5.kt` makes the same call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [clojure.java.io :as io]
            [ckway.bridge.cache :as cache]
            [ckway.rt :as rt])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(kt/require '[fx.r5 :as p] '[fx.r5far :as q] '[kotlin.math :as m] '[fx.r4 :as r4])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))
(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round5-test)] (eval form)))
(defn- compile-error [form] (try (eval-here form) nil (catch Throwable t (ct/root-message t))))
(defn- msg [f] (some-> (thrown f) ct/root-message))
(defn- deadline
  "(f) on a daemon thread; :timeout if it has not finished after `ms`."
  ([f] (deadline f 4000))
  ([f ms]
   (let [p (promise)
         t (doto (Thread. ^Runnable (fn [] (deliver p (try (f) (catch Throwable e e))))) (.setDaemon true) (.start))
         r (deref p ms :timeout)]
     (when (= :timeout r) (.interrupt t))
     r)))

;; ---------------------------------------------------------------- Y2

(deftest y2-child-thread-throw-does-not-hang
  (testing "static: future in a body"
    (is (= :body-done (deadline #(p/blockingRun (fn [] @(future (try (p/boomNow) (catch Throwable e :caught))) :body-done))))))
  (testing "the child sees the exception"
    (is (= :caught (deadline #(p/blockingRun (fn [] @(future (try (p/boomNow) (catch Throwable e :caught)))))))))
  (testing "dynamic"
    (is (= :body-done (deadline #(p/blockingRun
                                  (fn [] @(future (try (rt/call-dyn #'p/boomNow [] {} {}) (catch Throwable e :caught))) :body-done))))))
  (testing "pmap with one throwing call"
    (is (= [1 :caught 3] (deadline #(p/blockingRun (fn [] (vec (pmap (fn [n] (try (p/boomArg n) (catch Throwable e :caught))) [1 2 3]))))))))
  (testing "pmap, dynamic"
    (is (= [1 :caught 3] (deadline #(p/blockingRun (fn [] (vec (pmap (fn [n] (try (rt/call-dyn #'p/boomArg [n] {} {}) (catch Throwable e :caught))) [1 2 3])))))))))

(deftest y2-controls
  (testing "no throw, child thread"
    (is (= 5 (deadline #(p/blockingRun (fn [] @(future (p/okNow))))))))
  (testing "throw on the body's own thread"
    (is (= :caught (deadline #(p/blockingRun (fn [] (try (p/boomNow) (catch Throwable e :caught))))))))
  (testing "throw at top level"
    (is (= "now" (ex-message (deadline #(p/boomNow)))))
    (is (= "now" (ex-message (deadline #(rt/call-dyn #'p/boomNow [] {} {})))))))

(deftest y2-top-level-job-is-completed
  (testing "synchronous throw, static"
    (is (= "job" (ex-message (thrown #(p/boomJob)))))
    (is (true? (p/lastJobCompleted))))
  (testing "synchronous throw, dynamic"
    (is (some? (thrown #(rt/call-dyn #'p/boomJob [] {} {}))))
    (is (true? (p/lastJobCompleted))))
  (testing "normal return"
    (is (= 1 (p/okJob)))
    (is (true? (p/lastJobCompleted))))
  (testing "child thread of a body: the child's own Job is completed too"
    (is (= :caught (deadline #(p/blockingRun (fn [] @(future (try (p/boomJob) (catch Throwable e :caught))))))))
    (is (true? (p/lastJobCompleted)))))

;; ---------------------------------------------------------------- Y1

(defn- warnings [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))
(defn- dyn [v pos & [named]] (rt/call-dyn v pos (or named {}) {}))
(defn- bare-var [x] x)

(deftest y1-narrowed-override-has-the-defaults-of-the-original
  (testing "interface -> class, same package"
    (is (= (p/kDSame) "same7"))
    (is (= "same7" (p/.mk (p/DSame))))
    (is (= "same7" (dyn #'p/.mk [(p/DSame)])))
    (is (= "same7" (let [d (identity (p/DSame))] (p/.mk d))))
    (is (= "same4" (p/.mk (p/DSame) 4)))
    (is (= "same4" (p/.mk (p/DSame) :x 4)) "named")
    (is (= "same4" (dyn #'p/.mk [(p/DSame)] {"x" 4})) "named, dynamic"))
  (testing "interface -> class, other package"
    (is (= (q/kFar) "far7"))
    (is (= "far7" (q/kFar)))
    (is (= "far7" (q/.mk (q/Far))))
    (is (= "far7" (dyn #'q/.mk [(q/Far)])))
    (is (= "far7" (let [d (identity (q/Far))] (q/.mk d))) "untyped receiver")
    (is (= "far9" (q/.mk (q/Far) 9)))
    (is (= "far9" (q/.mk (q/Far) :x 9))))
  (testing "class -> subclass, a default that refers to another parameter"
    (is (= "l3,4" (p/kDLeaf)))
    (is (= "l3,4" (p/.get (p/DLeaf))) "three levels")
    (is (= "l3,4" (dyn #'p/.get [(p/DLeaf)])))
    (is (= "l3,5" (p/kDLeafY)))
    (is (= "l3,5" (p/.get (p/DLeaf) :y 5)))
    (is (= "l3,5" (dyn #'p/.get [(p/DLeaf)] {"y" 5})))
    (is (= "m1,2" (p/kDMid)))
    (is (= "m1,2" (p/.get (p/DMid) 1)) "two levels")
    (is (= "fs3,4" (q/kFarSub)))
    (is (= "fs3,4" (q/.get (q/FarSub))) "other package")
    (is (= "fs3,5" (q/kFarSubY)))
    (is (= "fs3,5" (q/.get (q/FarSub) :y 5)))
    (is (= "fl2,3" (q/kFarLeaf)))
    (is (= "fl2,3" (q/.get (q/FarLeaf) 2)) "three levels, other package")
    (is (= "fl2,3" (dyn #'q/.get [(q/FarLeaf) 2]))))
  (testing "a default that refers to this"
    (is (= "sub:T!" (p/kDThis)))
    (is (= "sub:T!" (p/.m (p/DThisSub))))
    (is (= "far:T!" (q/kFarThis)))
    (is (= "far:T!" (q/.m (q/FarThis))))
    (is (= "far:T!" (dyn #'q/.m [(q/FarThis)]))))
  (testing "a suspend member"
    (is (= "s2" (deadline p/kDSusp)))
    (is (= "s2" (p/.s (p/DSuspImpl))))
    (is (= "fs2" (q/.s (q/FarSusp))))
    (is (= "fs2" (dyn #'q/.s [(q/FarSusp)])))
    (is (= "fs5" (q/.s (q/FarSusp) 5))))
  (testing "a value-class parameter with a default"
    (is (= "vc9" (p/kDVc)))
    (is (= "vc9" (p/.vc (p/DVcImpl))))
    (is (= "fvc9" (q/.vc (q/FarVc))))
    (is (= "fvc9" (dyn #'q/.vc [(q/FarVc)])))
    (is (= "fvc3" (q/.vc (q/FarVc) (p/VId 3)))))
  (testing "interface -> interface -> class"
    (is (= "i2-7" (p/kDI2)))
    (is (= "i2-7" (p/.mk (p/DIface2Impl))))
    (is (= "fi2-7" (q/.mk (q/FarI2))))
    (is (= "fi2-7" (dyn #'q/.mk [(q/FarI2)]))))
  (testing "the result is statically typed as the narrowed return type: no reflection"
        (is (= "" (warnings '(fn [] (.length (q/.mk (q/Far)))))))
    (is (= 4 (.length ^String (q/.mk (q/Far)))))))

(deftest y1-var-as-value-and-ref
  (testing "var as a value: all arguments are given (positional only)"
    (is (= "far4" (let [f p/.mk] (f (q/Far) 4)))))
  (testing "kt/ref"
    (is (= "far4" ((kt/ref p/DSrc .mk) (q/Far) 4)))))

(deftest y1-doc-shows-the-inherited-defaults
  (let [out (with-out-str (eval-here '(do (require (quote clojure.repl)) (clojure.repl/doc p/.mk))))]
    (is (str/includes? out "x: Int = ...") out)))

;; ---------------------------------------------------------------- Y3

(defn- ambiguous? [m] (str/includes? (str m) "ambiguous"))

(deftest y3-every-parameter-must-be-at-least-as-specific
  (testing "mx(CharSequence, Int) / mx(String, Long): kotlinc says ambiguous for the literal 1 (see Round5.kt)"
    (is (ambiguous? (compile-error '(p/mx "s" 1))) "static, literal")
    (is (ambiguous? (msg #(rt/call-dyn #'p/mx ["s" 1] {} {1 :int}))) "dynamic, the literal marked"))
  (testing "a Long takes only the Long overload, as in Kotlin (kMxL); a var as a value has no literal: its Long is a Long"
    (is (= "String,Long" (p/kMxL)))
    (is (= "String,Long" (let [n (long 1)] (p/mx "s" n))) "static, a long local")
    (is (= "String,Long" (dyn #'p/mx ["s" 1])) "dynamic, no literal info")
    (is (= "String,Long" (let [f p/mx] (f "s" 1))) "var as a value")))

(deftest y3-controls
  (testing "better on both parameters wins"
    (is (= "String,Int" (p/kMy)))
    (is (= "String,Int" (p/my "s" 1)))
    (is (= "String,Int" (rt/call-dyn #'p/my ["s" 1] {} {1 :int})))
    (is (= "String,Int" (let [f p/my] (f "s" (int 1))))))
  (testing "mn(Int, Long) / mn(Long, Int) is ambiguous for two literals (Kotlin too)"
    (is (ambiguous? (compile-error '(p/mn 1 1))))))

;; ---------------------------------------------------------------- Y8

(deftest y8-number-message-has-no-float-literal
  (let [m (compile-error '(m/sqrt 4))]
    (is (ambiguous? m) m)
    (is (not (str/includes? m "4.0f")) "`4.0f` is not Clojure syntax")
    (is (str/includes? m "`4.0`"))
    (is (str/includes? m "(float x)"))
    (is (str/includes? m "(double x)"))))

;; ---------------------------------------------------------------- Y5

(deftest y5-one-form-serves-a-java-and-a-kotlin-leaf
  (testing "Java interface extends the Kotlin one (JCs.get(): CharSequence, S2.get(): Comparable)"
    (let [o (eval-here '(kt/reify r4/S2 pj.JCs (.get [this] "ab")))]
      (is (= "ab/S2" (r4/readS2 o)))
      (is (= "ab/Src" (r4/readSrc o)))
      (is (= "ab" (.get ^pj.JCs o)) "the Java leaf: no AbstractMethodError")))
  (testing "the Java interface first"
    (let [o (eval-here '(kt/reify pj.JCs r4/S2 (.get [this] "cd")))]
      (is (= "cd/S2" (r4/readS2 o)))
      (is (= "cd" (.get ^pj.JCs o)))))
  (testing "the form written under the Java name serves the Kotlin leaf too"
    (let [o (eval-here '(kt/reify pj.JCs (get [this] "ef") r4/S2))]
      (is (= "ef/S2" (r4/readS2 o)))
      (is (= "ef" (.get ^pj.JCs o)))))
  (testing "a Java interface that does not extend the Kotlin one"
    (let [o (eval-here '(kt/reify r4/S2 pj.JInd (.get [this] "gh")))]
      (is (= "gh/S2" (r4/readS2 o)))
      (is (= "gh" (.get ^pj.JInd o)))))
  (testing "control: the Kotlin leaves alone, as before"
    (let [o (eval-here '(kt/reify r4/S1 r4/S2 (.get [this] "ab")))]
      (is (= "ab/S1" (r4/readS1 o)))
      (is (= "ab/S2" (r4/readS2 o))))))

;; ---------------------------------------------------------------- Y6, Y7

(defn- private-dir ^File []
  (.toFile (Files/createTempDirectory "ckway-r5" (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]))))
(defn- err-of [f] (let [w (StringWriter.)] (binding [*err* w] (f)) (str w)))
(defn- classes [cname] {cname (byte-array [1 2 3 4])})

(deftest y6-a-foreign-directory-on-an-entry-name-is-reported-once
  (let [d (private-dir)
        entry-name @#'cache/entry-name
        _ (reset! @#'cache/warned-in-the-way false)
        in-way (io/file d (entry-name "ckway.bridge.K_y6" "b1" "2.4.20"))
        other-way (io/file d (entry-name "ckway.bridge.K_y6b" "b1" "2.4.20"))]
    (.mkdirs in-way) (spit (io/file in-way "thesis.txt") "mine")
    (spit other-way "a plain file")
    (let [out (err-of #(cache/store! d "ckway.bridge.K_y6" "b1" "2.4.20" (classes "ckway.bridge.K_y6")))]
      (is (= 1 (count (str/split-lines out))) out)
      (is (str/includes? out (.getPath in-way)) "names the path")
      (is (str/includes? out "will not touch")))
    (is (.exists (io/file in-way "thesis.txt")) "the foreign directory stays")
    (is (= "" (err-of #(cache/store! d "ckway.bridge.K_y6b" "b1" "2.4.20" (classes "ckway.bridge.K_y6b")))) "once per JVM")
    (is (= "a plain file" (slurp other-way)))))

(deftest y7-a-temporary-directory-is-deleted-only-when-it-holds-what-ours-holds
  (let [d (private-dir)
        _ (reset! @#'cache/warned-stale-tmp false)
        mk (fn [uuid-tail files]
             (let [t (io/file d (str ".ckway-tmp-00000000-0000-0000-0000-" uuid-tail))]
               (.mkdirs t)
               (doseq [f files] (let [x (io/file t f)] (.mkdirs (.getParentFile x)) (spit x "x")))
               (.setLastModified t (- (System/currentTimeMillis) (* 3 60 60 1000)))
               t))
        ours (mk "000000000001" ["a.class" "entry.txt"])
        foreign (mk "000000000002" ["a.class" "notes/thesis.txt"])
        foreign2 (mk "000000000003" ["thesis.txt"])]
    (let [out (err-of #(cache/store! d "ckway.bridge.K_y7" "b1" "2.4.20" (classes "ckway.bridge.K_y7")))]
      (is (not (.exists ours)) "one that holds a class file and the manifest goes")
      (is (.exists (io/file foreign "notes" "thesis.txt")) "foreign content stays")
      (is (.exists (io/file foreign2 "thesis.txt")))
      (is (= 1 (count (str/split-lines out))) out)
      (is (str/includes? out "will not")))))

;; ---------------------------------------------------------------- Y4

(deftest y4-untyped-receiver-of-a-narrowed-member-is-a-static-call
  (testing "no dynamic path, no warning"
    (is (= "" (warnings '(fn [] (let [s (identity (p/TStrImpl))] (p/.fetch s))))))
    (is (= "" (warnings '(fn [] (let [s (identity (p/TStrImpl))] (p/label s)))))))
  (testing "the call is virtual: the narrowed override runs, and a plain implementation works too"
    (is (= "t-str" (let [s (identity (p/TStrImpl))] (p/.fetch s))))
    (is (= 42 (let [s (identity (p/TSrcImpl))] (p/.fetch s))))
    (is (= "t-tag" (let [s (identity (p/TStrImpl))] (p/label s))))
    (is (= 7 (let [s (identity (p/TSrcImpl))] (p/label s)))))
  (testing "a wrong class is the usual checked error"
    (is (str/includes? (str (msg #(let [s (identity "x")] (p/.fetch s)))) "String")))
  (testing "controls: a typed receiver still gets the narrowed result type; the dynamic path is the same"
    (is (= "" (warnings '(fn [] (.length (p/.fetch (p/tStr)))))))
    (is (= "t-str" (dyn #'p/.fetch [(p/TStrImpl)])))
    (is (= 42 (dyn #'p/.fetch [(p/TSrcImpl)])))))

(deftest y2-function-value-wrapper
  (let [f (p/boomFn)]
    (testing "top level"
      (is (= "fn" (ex-message (thrown f))))
      (is (true? (p/lastJobCompleted))))
    (testing "from a child thread of a body"
      (is (= :caught (deadline #(p/blockingRun (fn [] @(future (try (f) (catch Throwable e :caught))))))))
      (is (true? (p/lastJobCompleted))))))
