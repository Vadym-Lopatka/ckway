(ns ckway.fixes-test
  "Fun interfaces with a value-class method (mangled JVM name), a wrong receiver class on the static path, a wrong
  result of a Clojure function, Kotlin function values and references called wrongly."
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct :refer [both compile-error root-message]]
            [ckway.core :as kt]
            [ckway.rt :as rt]))

(kt/require '[fx :as f] '[kotlinx.coroutines :as co])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))
(defn- m ^fx.Meters [n] (f/Meters n))
(defn- lab ^fx.Label [s] (f/Label s))

;; ---------------------------------------------------------------- fun interface with a value-class method

(deftest both-value-class-over-primitive
  (testing "the Clojure function gets the boxed object, its result is unboxed for the long slot"
    (let [seen (atom nil)
          g (fn [u] (reset! seen (class u)) (m (inc (f/m u))))]
      (both (m 6) f/useBoth g (m 5))
      (is (= fx.Meters @seen))
      (both (m 1001) f/.total (f/Cart2) g)))
  (testing "a literal fn: the parameter is typed as the value class, so kt calls on it are static"
    (is (= (m 1100) (f/.total (f/Cart2) (fn [d] (f/Meters (+ 100 (f/m d)))))))
    (is (= (m 7) (f/useBoth (fn [u] (f/Meters (+ 2 (f/m u)))) (m 5))))))

(deftest param-only-and-return-only
  (both true f/useParam (fn [u k] (= (f/m u) (+ k 1))) (m 5) 4)
  (both false f/useParam (fn [u k] (= (f/m u) (+ k 1))) (m 5) 9)
  (both (m 42) f/useRet (fn [k] (m (* k 2))) 21)
  (testing "Kotlin gives an Int to the function"
    (let [seen (atom nil)]
      (f/useRet (fn [k] (reset! seen (class k)) (m 1)) 3)
      (is (= Integer @seen)))))

(deftest value-class-over-reference-type
  (both (lab "AB") f/useRefBoth (fn [n] (lab (str/upper-case (f/s n)))) (lab "ab")))

(deftest nullable-value-class
  (testing "Meters? : the JVM slot holds the object, the name is still mangled"
    (both nil f/useNullBoth (fn [u] u) nil)
    (both (m 5) f/useNullBoth (fn [u] u) (m 5))
    (both nil f/useNullBoth (fn [u] nil) (m 5))
    (both (m 6) f/useNullBoth (fn [u] (when u (m (inc (f/m u))))) (m 5)))
  (testing "Label? over String"
    (both nil f/useNullRef (fn [n] n) nil)
    (both (lab "x") f/useNullRef (fn [n] n) (lab "x"))
    (both nil f/useNullRef (fn [n] nil) (lab "x"))))

(deftest default-methods-and-function-args
  (testing "a Kotlin default method of the interface stays"
    (both 12 f/useDefault (fn [u] (m (+ 3 (f/m u)))) (m 6))
    (both "vcd" f/useDefaultName (fn [u] u)))
  (testing "a Kotlin function value as parameter of the method is a Clojure function"
    (both (m 11) f/useFnArg (fn [u g] (g (g u))) (m 9))))

(deftest suspend-method
  (both (m 8) f/useSusp (fn [u] (m (+ 3 (f/m u)))) (m 5))
  (testing "the body can suspend"
    (is (= (m 6) (f/useSusp (fn [u] (co/delay 5) (m (inc (f/m u)))) (m 5))))))

(deftest generic-fun-interface-is-unchanged
  (both 2 f/useConvT (fn [x] (inc x)) 1))

(deftest implementing-objects-pass-through
  (testing "a kt/reify object and a Kotlin object are passed on, never adapted again"
    (let [o (kt/reify f/VcBoth (.adjust [this u] (m (* 2 (f/m u)))))]
      (is (= (m 10) (f/useBoth o (m 5))))
      (is (= (m 2000) (f/.total (f/Cart2) o)))
      (is (identical? o (f/sameVc o)))
      (is (= (m 14) (rt/call-dyn #'f/useBoth [o (m 7)] {}))))
    (let [k (f/KImpl)]
      (is (identical? k (f/sameVc k)))
      (is (true? (f/isKotlinImpl (f/sameVc k))))
      (is (= (m 20) (f/useBoth k (m 2))))
      (is (= (m 10000) (f/.total (f/Cart2) k))))
    (let [o (kt/reify f/VcDefault (.adjust [this u] (m (inc (f/m u)))))]
      (is (= 7 (f/useDefault o (m 5)))))))

(deftest adapter-is-a-real-class
  (testing "the adapter implements the interface, so Kotlin can call it from any thread"
    (let [g (fn [u] u)
          a (f/sameVc g)]
      (is (instance? fx.VcBoth a))
      (is (identical? a (f/sameVc a))))))

(deftest other-function-types-and-references
  (testing "function types with a value class: the boxed object (generic FunctionN), also receiver and suspend"
    (is (= (m 6) (f/recvVc (fn [u] (m (inc (f/m u)))) (m 5))))
    (is (= (m 6) (f/suspVc (fn [u] (m (inc (f/m u)))) (m 5))))
    (is (= (lab "x5") ((f/retFnVc) (m 5))))
    (is (= (lab "L5") (f/twoVc (fn [u l] (lab (str (f/s l) (f/m u)))) (m 5)))))
  (testing "kt/ref of members and functions with a value class (also with a fun interface parameter)"
    (is (= (m 1001) ((kt/ref f/Cart2 .total) (f/Cart2) (fn [d] (m (inc (f/m d)))))))
    (is (= (m 1000) ((kt/ref f/Cart2 .total) (f/Cart2) (kt/reify f/VcBoth (.adjust [this u] u)))))
    (is (= 5 ((kt/ref f/Cart2 .add) (f/Cart2) (m 3) 2)))
    (is (= (m 9) ((kt/ref f/useRet) (fn [k] (m 9)) 3)))
    (is (= [true] (map (kt/ref f/useParam) [(fn [u k] true)] [(m 1)] [2])))))

(deftest aot-fresh-jvm
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "kt-fixes-aot" (make-array java.nio.file.attribute.FileAttribute 0)))
        src (java.io.File. root "src") classes (java.io.File. root "classes")
        _ (.mkdirs (java.io.File. src "aot")) _ (.mkdirs classes)
        _ (spit (java.io.File. (java.io.File. src "aot") "fixes.clj")
                "(ns aot.fixes (:require [ckway.core :as kt]))
(kt/require '[fx :as f])
(defn run []
  (let [o (kt/reify f/VcBoth (.adjust [this u] (f/Meters (* 2 (f/m u)))))]
    (println (f/m (f/useBoth (fn [u] (f/Meters (inc (f/m u)))) (f/Meters 5)))
             (f/m (f/.total (f/Cart2) (fn [d] (f/Meters (+ 2 (f/m d))))))
             (f/m (f/useBoth o (f/Meters 4)))
             (f/useParam (fn [u k] (= (f/m u) k)) (f/Meters 3) 3)
             (f/s (f/useNullRef (fn [n] n) (f/Label \"z\")))
             (f/useDefault (fn [u] (f/Meters (+ 1 (f/m u)))) (f/Meters 1))
             (f/m (f/useSusp (fn [u] (f/Meters (+ 1 (f/m u)))) (f/Meters 1)))
             (contains? (loaded-libs) 'ckway.bridge.gen))
    (println (try (f/useRet (fn [k] nil) 1) (catch Exception e (ex-message e))))))
")
        cp (System/getProperty "java.class.path")
        sep java.io.File/pathSeparator
        sh (fn [cp & args] (apply clojure.java.shell/sh "clojure" "-Scp" cp "-M" "-e" args))
        c (sh (str cp sep (.getPath src) sep (.getPath classes))
              (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.fixes))"))]
    (is (zero? (:exit c)) (:err c))
    (let [r (sh (str cp sep (.getPath classes)) "(require 'aot.fixes) (aot.fixes/run)")
          lines (str/split-lines (str/trim (:out r)))]
      (is (zero? (:exit r)) (:err r))
      (is (= "6 1002 8 true z 3 2 false" (first lines)) "the generator was not loaded at run time")
      (is (str/includes? (second lines) "kt: nil where Kotlin expects a non-null fx.Meters")))))

;; ---------------------------------------------------------------- wrong receiver class

(def ^:private cart (f/Cart2))
(def ^:private num5 5)
(def ^:private nilv nil)

(deftest unknown-receiver-wrong-class
  (testing "a var has no static type: one candidate, so the static path casts the receiver"
    (let [e (thrown #(f/.weigh cart))]
      (is (some? e))
      (is (not (instance? ClassCastException e)))
      (is (:kt/error (ex-data e)))
      (let [msg (ex-message e)]
        (is (str/starts-with? msg "kt: (f/.weigh cart)"))
        (is (str/includes? msg "the receiver `cart` is a fx.Cart2") msg)
        (is (str/includes? msg "needs fx.Other") msg)
        (is (str/includes? msg "Kotlin: fun fx.Other.weigh(): Int") msg)
        (is (str/includes? msg "`weigh` is a property of fx.Cart2: write (f/weigh cart)") msg))))
  (testing "no hint when the class has no member of that name"
    (let [msg (ex-message (thrown #(f/.size cart)))]
      (is (str/includes? msg "the receiver `cart` is a fx.Cart2"))
      (is (not (str/includes? msg "Hint")))))
  (testing "an argument of unknown type: the parameter, the Kotlin type and the class"
    (let [msg (ex-message (thrown #(f/lenOf num5)))]
      (is (str/starts-with? msg "kt: (f/lenOf num5)"))
      (is (str/includes? msg "the argument `s` (String) is java.lang.Long 5") msg)
      (is (str/includes? msg "Kotlin: fun lenOf(s: String): Int") msg))
    (is (str/includes? (ex-message (thrown #(f/half cart))) "the argument `d` (Double) is fx.Cart2"))
    (is (str/includes? (ex-message (thrown #(f/flip num5))) "the argument `b` (Boolean) is java.lang.Long 5")))
  (testing "a correct call and nil are as before"
    (is (= 11 (f/.weigh (f/Other))))
    (let [o (f/Other)] (is (= 11 (f/.weigh o))))
    (is (= 3 (f/lenOf "abc")))
    (is (= 2.5 (f/half 5)))
    (is (true? (f/flip false)))
    (is (instance? NullPointerException (thrown #(f/.weigh nilv))) "nil receiver: unchanged")))

(deftest known-types-emit-no-check
  (testing "a literal: no instance? check in the expansion"
    (let [ex (ct/expansions '(fn [] (f/lenOf "x")))]
      (is (not-any? #(str/includes? (pr-str %) "wrong-class") (:static ex)) (pr-str (:static ex)))))
  (testing "a hint that the user wrote can be wrong: one instance? check, and a wrong-class kt error instead of a ClassCastException"
    (let [ex (ct/expansions '(fn [^fx.Other o] (f/.weigh o)))]
      (is (some #(str/includes? (pr-str %) "wrong-class") (:static ex)) (pr-str (:static ex)))))
  (testing "an unknown one has the check"
    (let [ex (ct/expansions '(fn [o] (f/.weigh o)))]
      (is (some #(str/includes? (pr-str %) "wrong-class") (:static ex))))))

;; ---------------------------------------------------------------- wrong result of a Clojure function

(deftest wrong-result-of-a-clojure-function
  (testing "a value class: nil and a wrong class"
    (doseq [call [#(f/useBoth (fn [u] nil) (m 5))
                  #(rt/call-dyn #'f/useBoth [(fn [u] nil) (m 5)] {})]]
      (is (= "kt: nil where Kotlin expects a non-null fx.Meters" (ex-message (thrown call)))))
    (doseq [call [#(f/useBoth (fn [u] 5) (m 5))
                  #(rt/call-dyn #'f/useBoth [(fn [u] 5) (m 5)] {})]]
      (let [e (thrown call)]
        (is (= "kt: expected fx.Meters where Kotlin expects it, got java.lang.Long 5. A value class is always the object, not the underlying value."
               (ex-message e)))
        (is (:kt/error (ex-data e)))))
    (is (str/includes? (ex-message (thrown #(f/useRefBoth (fn [n] "s") (lab "a")))) "expected fx.Label"))
    (is (str/includes? (ex-message (thrown #(f/useNullBoth (fn [n] 5) (m 1)))) "expected fx.Meters"))
    (is (str/includes? (ex-message (thrown #(f/useSusp (fn [n] nil) (m 1)))) "nil where Kotlin expects a non-null fx.Meters")))
  (testing "a nullable value class takes nil"
    (is (nil? (f/useNullBoth (fn [n] nil) (m 1))))
    (is (= "null" (f/mapLabelNull (fn [k] nil)))))
  (testing "a function type (generic FunctionN) with a value class or String result"
    (is (= "kt: nil where Kotlin expects a non-null fx.Meters" (ex-message (thrown #(f/mapMeters (fn [k] nil))))))
    (is (str/starts-with? (ex-message (thrown #(f/mapMeters (fn [k] 5)))) "kt: expected fx.Meters where Kotlin expects it, got java.lang.Long 5"))
    (is (= "kt: expected String (java.lang.String) where Kotlin expects it, got java.lang.Long 5"
           (ex-message (thrown #(f/mapStr (fn [k] 5))))))
    (is (= "kt: nil where Kotlin expects a non-null String" (ex-message (thrown #(f/mapStr (fn [k] nil))))))
    (is (= "1" (f/mapStr (fn [k] (str k))))))
  (testing "a fun interface that Clojure `reify` implements (plain name) with a String result"
    (is (= "kt: expected String (java.lang.String) where Kotlin expects it, got java.lang.Long 5"
           (ex-message (thrown #(f/useStrFn (fn [k] 5))))))
    (is (= "kt: expected String (java.lang.String) where Kotlin expects it, got java.lang.Long 5"
           (ex-message (thrown #(rt/call-dyn #'f/useStrFn [(fn [k] 5)] {})))))
    (is (= "k1" (f/useStrFn (fn [k] (str "k" k))))))
  (testing "a kt/reify member"
    (let [o (kt/reify f/VcBoth (.adjust [this u] nil))]
      (is (= "kt: nil where Kotlin expects a non-null fx.Meters" (ex-message (thrown #(f/useBoth o (m 1)))))))
    (let [o (kt/reify f/StrFn (.text [this k] 7))]
      (is (str/starts-with? (ex-message (thrown #(f/useStrFn o))) "kt: expected String"))))
  (testing "a generic position (T) is left alone"
    (is (= 2 (f/useConvT (fn [x] (inc x)) 1)))))

;; ---------------------------------------------------------------- Kotlin function values and references called wrongly

(deftest kotlin-function-value-with-a-wrong-argument
  (let [add (f/mkAdd) mm (f/mkMeters)]
    (is (= "abab" (add 2 "ab")))
    (is (= "kt: argument 2 of the Kotlin function (Int, String) -> String: expected String (java.lang.String) where Kotlin expects it, got java.lang.Long 5"
           (ex-message (thrown #(add 2 5)))))
    (is (str/starts-with? (ex-message (thrown #(add "x" "ab"))) "kt: argument 1 of the Kotlin function (Int, String) -> String: "))
    (is (str/starts-with? (ex-message (thrown #(mm 5)))
                          "kt: argument 1 of the Kotlin function (fx.Meters) -> fx.Meters: expected fx.Meters where Kotlin expects it, got java.lang.Long 5"))
    (is (str/includes? (ex-message (thrown #(mm nil))) "nil where Kotlin expects a non-null fx.Meters"))
    (is (not (str/includes? (str (ex-message (thrown #(add 2 5)))) "argument type mismatch")))))

(deftest reference-with-a-wrong-argument
  (let [r (kt/ref f/describeIt)]
    (is (= "2s3" (r 2 "s" (m 3))))
    (let [msg (ex-message (thrown #(r 1 5 (m 3))))]
      (is (str/starts-with? msg "kt: parameter `s` (String) expects java.lang.String, got java.lang.Long 5") msg)
      (is (str/includes? msg "Kotlin: fun describeIt(n: Int, s: String, m: fx.Meters): String") msg))
    (is (str/starts-with? (ex-message (thrown #(r "x" "s" (m 3)))) "kt: parameter `n` (Int) expects int, got java.lang.String \"x\""))
    (is (str/includes? (ex-message (thrown #(r 1 "s" 5))) "`m` expects fx.Meters, got java.lang.Long 5"))
    (let [t (kt/ref f/Cart2 .total)]
      (is (str/starts-with? (ex-message (thrown #(t 5 (fn [d] d)))) "kt: the receiver (`this`) expects fx.Cart2, got java.lang.Long 5")))))

(deftest reference-with-the-wrong-number-of-arguments
  (let [r (kt/ref f/describeIt)
        e (thrown #(r 1 "s"))]
    (is (:kt/error (ex-data e)))
    (is (= "kt: (kt/ref f/describeIt) takes 3 arguments (n, s, m), got 2. Kotlin: fun describeIt(n: Int, s: String, m: fx.Meters): String"
           (ex-message e)))
    (is (instance? clojure.lang.ArityException (ex-cause e)))
    (is (str/includes? (ex-message (thrown #(r 1 "s" (m 1) 4))) "takes 3 arguments (n, s, m), got 4"))
    (is (str/includes? (ex-message (thrown #(r))) "got 0"))
    (is (str/includes? (ex-message (thrown #(apply r [1]))) "got 1"))
    (let [one (kt/ref f/lenOf)]
      (is (= "kt: (kt/ref f/lenOf) takes 1 argument (s), got 2. Kotlin: fun lenOf(s: String): Int"
             (ex-message (thrown #(one "a" "b")))))
      (is (= 3 (one "abc"))))
    (let [t (kt/ref f/Cart2 .total)]
      (is (str/starts-with? (ex-message (thrown #(t (f/Cart2)))) "kt: (kt/ref f/Cart2 .total) takes 2 arguments (this, d), got 1")))
    (testing "Kotlin defaults are not applied: the message says so"
      (let [a (kt/ref f/Cart2 .add)]
        (is (str/includes? (ex-message (thrown #(a (f/Cart2) (m 1)))) "Kotlin defaults are not applied"))))))
