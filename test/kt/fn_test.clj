(ns kt.fn-test
  "DESIGN-2 rule 6: functions. A Clojure function goes where Kotlin wants a function type or a
  fun interface, a lambda receiver is the first parameter, a Kotlin function value is a Clojure function."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kt.call-test :as ct :refer [both compile-error root-message expansions reflection-output]]
            [kt.core :as kt]
            [kt.rt :as rt])
  (:import [java.lang.reflect Proxy]
           [kotlin.jvm.functions Function1 Function2]))

(kt/require '[fx :as f] '[fx.legacy :as l])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

(defn- ev [form] (binding [*ns* (the-ns 'kt.call-test)] (eval form)))

;; ---------------------------------------------------------------- B1 function types

(deftest b1-function-types
  (testing "arguments pass through, the Long result becomes an Int"
    (both 2 f/apply1 (fn [x] (inc x)) 1)
    (both 10 f/apply1 (fn [x] (* 5 x)) 2)
    (both "a2" f/apply2 (fn [s n] (str s n)))
    (both 7 f/twoArgs (fn [a b] (+ a b))))
  (testing "the result is an Integer, not a Long (the same typed-position conversion as for a parameter)"
    (let [seen (atom nil)]
      (is (= 5 (f/apply1 (fn [x] (reset! seen (class x)) (+ x 4)) 1)))
      (is (= Integer @seen) "Kotlin hands the function an Integer")))
  (testing "any Clojure IFn: a var, a keyword-like map, a core fn"
    (both 2 f/apply1 inc 1)
    (both 5 f/apply1 {1 5} 1)
    (both 3 f/apply1 #'inc 2))
  (testing "Unit: the adapter returns kotlin.Unit, the Clojure value is ignored"
    (both "ran" f/run0 (fn [] 42))
    (both "ran" f/run0 (fn [] nil))
    (let [n (atom 0)]
      (is (= "ran" (f/run0 (fn [] (swap! n inc)))))
      (is (= 1 @n))))
  (testing "lambda with receiver: the receiver is the first argument"
    (both "hi!" f/build (fn [sb] (.append ^StringBuilder sb "hi!") nil))
    (both "x1" f/build (fn [^StringBuilder sb] (.append sb "x") (.append sb 1))))
  (testing "a function that returns a function for a (Int) -> (Int) -> Int parameter"
    (both 3 f/nested (fn [a] (fn [b] (+ a b)))))
  (testing "nullable function type and defaults"
    (both -1 f/maybe)
    (both -1 f/maybe nil)
    (both 5 f/maybe inc 4)
    (both 8 f/maybe :f (fn [x] (* 2 x)) :x 4)
    (both 8 f/maybe :x 4 :f (fn [x] (* 2 x)))
    (both -4 f/maybe :x 4)
    (testing "an omitted function parameter keeps the Kotlin default"
      (both 8 f/withDefaultFn 4)
      (both 5 f/withDefaultFn 4 (fn [x] (inc x)))
      (both 9 f/withDefaultFn 4 :f (fn [x] (+ x 5)))))
  (testing "an instance of the JVM type is passed on, not wrapped"
    (let [k (reify Function1 (invoke [_ x] (int (* 3 x))))]
      (both 9 f/apply1 k 3)))
  (testing "nil result for a non-null Int is a clear kt error, not an NPE"
    (doseq [t [(thrown #(f/apply1 (fn [x] nil) 1))
               (thrown #(rt/call-dyn #'f/apply1 [(fn [x] nil) 1] {}))]]
      (is (str/includes? (root-message t) "kt: nil where Kotlin expects a non-null Int"))))
  (testing "not an integer where Int is expected"
    (is (str/includes? (root-message (thrown #(f/apply1 (fn [x] "s") 1))) "expected an integer")))
  (testing "a value that is no function"
    (is (str/includes? (compile-error '(f/apply1 "s" 1)) "`f` is Function1 but got String"))
    (is (str/includes? (root-message (thrown #((ev '(fn [g] (f/apply1 g 1))) 5))) "kt: expected a function"))
    (is (str/includes? (root-message (thrown #(rt/call-dyn #'f/apply1 [5 1] {}))) "`f` is Function1 but got Long"))
    (is (str/includes? (compile-error '(f/apply1 nil 1)) "`nil` passed to non-nullable `f`")))
  (testing "a function of the wrong arity (F1): a kt error that says what Kotlin called, the ArityException is the cause"
    (let [g (fn [a b] a)
          e (thrown #(f/apply1 g 1))
          e2 (thrown #(rt/call-dyn #'f/apply1 [g 1] {}))
          msg "kt: Kotlin called this function as (Int) -> Int with 1 argument, but the Clojure function does not accept 1 argument"]
      (is (= msg (ex-message e)))
      (is (= msg (ex-message e2)))
      (is (instance? clojure.lang.ArityException (ex-cause e)))
      (is (instance? clojure.lang.ArityException (ex-cause e2)))
      (println "WRONG-ARITY message:" (ex-message e)))))

(deftest b1-static-expansion
  (let [x (expansions '(fn [] (f/apply1 (fn [x] (inc x)) 1)))]
    (is (empty? (:dynamic x)))
    (is (= "" (:warnings x)))
    (is (str/includes? (pr-str (:static x)) "kotlin.jvm.functions.Function1"))
    (is (str/includes? (pr-str (:static x)) "reify"))
    (println "EXPANSION (f/apply1 (fn [x] (inc x)) 1) =>" (pr-str (first (:static x))))))

;; ---------------------------------------------------------------- B2 fun interface

(deftest b2-fun-interface
  (both true f/check (fn [x] (pos? x)) 1)
  (both false f/check (fn [x] (pos? x)) -1)
  (both true f/check even? 4)
  (testing "the Kotlin method with a body works on the adapter (JVM default method)"
    (both false f/negCheck (fn [x] (pos? x)) 1)
    (both true f/negCheck (fn [x] (pos? x)) -1)
    (let [p (f/idPred (fn [x] (> x 2)))]
      (is (true? (f/.test p 3)))
      (is (false? (f/.test (f/.negate p) 3)))))
  (testing "an interface compiled without JVM default methods (body in $DefaultImpls)"
    (both false l/negLegacy (fn [x] (pos? x)) 1)
    (both true l/negLegacy (fn [x] (pos? x)) -1))
  (testing "an instance is passed on"
    (let [p (reify fx.Pred (test [_ x] (= x 1)))]
      (is (identical? p (f/idPred p)))
      (is (identical? p (rt/call-dyn #'f/idPred [p] {})))))
  (testing "static path and dynamic path both make a reify class (F2: no java.lang.reflect.Proxy)"
    (let [s (f/idPred (fn [x] true))
          d (rt/call-dyn #'f/idPred [(fn [x] true)] {})]
      (is (not (Proxy/isProxyClass (class s))))
      (is (not (Proxy/isProxyClass (class d))))
      (is (instance? fx.Pred s))
      (is (instance? fx.Pred d))))
  (testing "non-boolean result for a Boolean method"
    (is (str/includes? (root-message (thrown #(f/check (fn [x] 1) 1))) "expected a Boolean"))
    (is (str/includes? (root-message (thrown #(rt/call-dyn #'f/check [(fn [x] nil) 1] {}))) "nil where Kotlin expects a non-null Boolean")))
  (testing "expansion"
    (let [x (expansions '(fn [] (f/check (fn [x] (pos? x)) 1)))]
      (is (str/includes? (pr-str (:static x)) "reify fx.Pred"))
      (is (empty? (:dynamic x)))
      (println "EXPANSION (f/check (fn [x] (pos? x)) 1) =>" (pr-str (first (:static x)))))))

;; ---------------------------------------------------------------- B3 Kotlin function value

(deftest b3-kotlin-function-values
  (let [g (f/adder 10)]
    (testing "callable as a Clojure function, and still the original FunctionN"
      (is (= 11 (g 1)))
      (is (= [11 12] (map g [1 2])))
      (is (= 13 (apply g [3])))
      (is (= 12 (apply g 2 [])))
      (is (ifn? g))
      (is (fn? g))
      (is (instance? Function1 g)))
    (testing "arguments are coerced to the declared parameter types (a Long to an Int)"
      (is (= 11 (g 1)))
      (is (= 11 (g (Long/valueOf 1)))))
    (testing "a number that is no integer is a kt error"
      (is (str/includes? (root-message (thrown #(g 1.5))) "expected an integer")))
    (testing "wrong number of arguments"
      (is (instance? clojure.lang.ArityException (thrown #(g 1 2))))
      (is (instance? clojure.lang.ArityException (thrown #(g)))))
    (testing "handed back to Kotlin it is the original function, not a wrapper of a wrapper"
      (is (true? (f/same g (f/echo g))))
      (is (true? (f/same g (rt/call-dyn #'f/echo [g] {}))))
      (is (= 12 (f/apply1 g 2)))
      (is (= 12 (rt/call-dyn #'f/apply1 [g 2] {})))
      (is (= 23 ((f/compose g (f/adder 2)) 11))))
    (testing "dynamic path wraps the same"
      (let [d (rt/call-dyn #'f/adder [10] {})]
        (is (= 11 (d 1)))
        (is (instance? Function1 d)))))
  (testing "two parameters, other types"
    (is (= "abab" ((f/pairFn) 2 "ab")))
    (is (= "abab" ((rt/call-dyn #'f/pairFn [] {}) 2 "ab"))))
  (testing "nullable function result"
    (is (nil? (f/optFn))))
  (testing "a function that Kotlin passes INTO a Clojure function is a Clojure function"
    (both 6 f/callHandler (fn [x handler] (handler x)))
    (both 7 f/callHandler (fn [x handler] (handler (handler x))))
    (both 6 f/callHandler (fn [x handler] (first (map handler [x])))))
  (testing "a Clojure function returned from a Clojure function adapts too"
    (is (= 3 (f/nested (fn [a] (fn [b] (+ a b))))))))

;; ---------------------------------------------------------------- B4 typing inside a fn literal

(deftest b4-fn-literal-parameter-types
  (let [form '(f/.ctx (f/Router2) "/p" (fn [r] (f/.get r "/x" (fn [p] (str "got " p)))))
        x (expansions `(fn [] ~form))]
    (testing "the call on the parameter is resolved statically (control below shows it is ambiguous otherwise)"
      (is (= 3 (count (:static x))) (pr-str (:static x)))
      (is (empty? (:dynamic x)))
      (is (= "" (:warnings x)))
      (is (not (str/includes? (pr-str (:static x)) "call-dyn"))))
    (println "EXPANSION" (pr-str form) "=>\n  " (str/join "\n   " (map pr-str (:static x))))
    (testing "it runs"
      (let [r ((ev `(fn [] ~form)))]
        (is (instance? fx.Router2 r))
        (is (= ["/p" "/x"] (vec (.getLog ^fx.Router2 r)))))))
  (testing "control: the same call on an untyped local needs the dynamic path"
    (let [x (expansions '(fn [r] (f/.get r "/x" (fn [p] p))))]
      (is (seq (:dynamic x)))
      (is (str/includes? (:warnings x) "using the dynamic path"))))
  (testing "#() literal, named fn, multi-arity, multi-expression body, destructuring"
    (doseq [form ['(f/.ctx (f/Router2) "/p" #(f/.get % "/x" (fn [p] p)))
                  '(f/.ctx (f/Router2) "/p" (fn named [r] (f/.get r "/x" (fn [p] p))))
                  '(f/.ctx (f/Router2) "/p" (fn ([r] (f/.get r "/x" (fn [p] p))) ([r s] nil)))
                  '(f/.ctx (f/Router2) "/p" (fn [r] (identity "side effect ignored") (f/.get r "/x" (fn [p] p))))
                  '(f/.ctx (f/Router2) "/p" (fn [r] (let [[a b] [1 2]] (f/.get r "/x" (fn [p] (str a b p))))))]
          :let [x (expansions `(fn [] ~form))]]
      (testing (pr-str form)
        (is (empty? (:dynamic x)) (pr-str (:dynamic x)))
        (is (= "" (str/replace (:warnings x) #"(?m)^.*side effect.*$" "")))
        (is (instance? fx.Router2 ((ev `(fn [] ~form))))))))
  (testing "a destructuring parameter stays as the user wrote it"
    (is (= "" (f/build (fn [[a b]] nil))))
    (is (= "" (f/build (fn [& more] nil))) "a rest parameter is left alone"))
  (testing "a hint the user wrote wins"
    (let [x (expansions '(fn [] (f/.ctx (f/Router2) "/p" (fn [^fx.Store r] (f/.get r "/x" 1)))))]
      (is (empty? (:dynamic x)))
      (let [inner (binding [*print-meta* true] (pr-str (last (:static x))))]
        (is (str/includes? inner "fx.Store") inner)
        (is (not (str/includes? inner "fx.Router2")) inner))))
  (testing "lambda receiver of a kt call that is not a literal: no change"
    (let [h (fn [^fx.Router2 r] (f/.get r "/q" (fn [p] p)))]
      (is (instance? fx.Router2 (f/.ctx (f/Router2) "/p" h)))))
  (testing "function-type parameters are typed too: Int arrives as Integer, so the Int overload is chosen"
    (let [x (expansions '(fn [] (f/apply1 (fn [n] (when (= "int" (f/amb n)) n)) 1)))]
      (is (empty? (:dynamic x)))
      (is (= "" (:warnings x))))
    (is (= 1 (f/apply1 (fn [n] (when (= "int" (f/amb n)) n)) 1)))))

;; ---------------------------------------------------------------- B5 annotations

(deftest b5-annotation-on-fn-literal
  (testing "function type: the annotation is on the invoke method that Kotlin reflects on"
    (is (true? (f/markerOf ^{fx.Marker true} (fn [x] x))))
    (is (false? (f/markerOf (fn [x] x)))))
  (testing "fun interface: the annotation is on the interface method"
    (is (true? (f/tagMarker ^{fx.Marker true} (fn [x] "t"))))
    (is (false? (f/tagMarker (fn [x] "t")))))
  (testing "dynamic path: no annotations (the adapter is built at run time from the types, not from your fn literal)"
    (is (false? (rt/call-dyn #'f/markerOf [^{fx.Marker true} (fn [x] x)] {})))))

;; ---------------------------------------------------------------- B6 still not supported

(deftest b6-not-supported-yet
  (let []
    (testing "suspend function types and suspend fun interface methods are supported since step 4 (see kt.suspend-test)"
      (is (= "susp" (f/withSusp (fn [x] x))))
      (is (= "usp" (f/useSusp (fn [x] x))))
      (is (= "usp" (rt/call-dyn #'f/useSusp [(fn [x] x)] {}))))
    (testing "an object that already implements it is fine"
      (is (= "susp" (f/withSusp (reify Function2 (invoke [_ a b] a))))))))

;; ---------------------------------------------------------------- F1, F2 (step 3)

(defn str-two [a b] a)

(deftest f1-wrong-arity-message
  (let [msg "kt: Kotlin called this function as (Int) -> Int with 1 argument, but the Clojure function does not accept 1 argument"]
    (testing "a fn, a var, a zero-argument fn: the same message on both paths, the ArityException is the cause"
      (doseq [g [(fn [a b] a) #'str-two (fn [] 1)]
              :let [static (ev '(fn [g] (f/apply1 g 1)))] ; g has no known type: static path with a run-time adapter
              e [(thrown #(static g)) (thrown #(rt/call-dyn #'f/apply1 [g 1] {}))]]
        (is (= msg (ex-message e)) (pr-str g))
        (is (instance? clojure.lang.ArityException (ex-cause e)))))
    (testing "more than one argument, a fun interface, a Kotlin function type with two parameters"
      (let [e (thrown #(f/apply2 (fn [a] a)))
            e2 (thrown #(rt/call-dyn #'f/apply2 [(fn [a] a)] {}))
            e3 (thrown #(f/check (fn [] true) 1))
            e4 (thrown #(rt/call-dyn #'f/check [(fn [] true) 1] {}))]
        (is (= "kt: Kotlin called this function as (String, Int) -> String with 2 arguments, but the Clojure function does not accept 2 arguments"
               (ex-message e) (ex-message e2)))
        (is (= "kt: Kotlin called this function as fx.Pred.test(int) with 1 argument, but the Clojure function does not accept 1 argument"
               (ex-message e3) (ex-message e4)))
        (is (every? #(instance? clojure.lang.ArityException (ex-cause %)) [e e2 e3 e4]))))
    (testing "an ArityException from a call deeper in the function is not renamed"
      (let [inner (fn [a b] a)
            e (thrown #(f/apply1 (fn [x] (inner x)) 1))
            e2 (thrown #(rt/call-dyn #'f/apply1 [(fn [x] (inner x)) 1] {}))]
        (is (instance? clojure.lang.ArityException e))
        (is (instance? clojure.lang.ArityException e2))))))

(deftest f2-checked-exception-reaches-kotlin-as-itself
  (let [boom (fn [x] (throw (java.io.IOException. "boom")))]
    (testing "Kotlin code catches the IOException that the Clojure function throws (static reify and dynamic adapter)"
      (is (= "io:boom" (f/catchIo boom)))
      (is (= "io:boom" (rt/call-dyn #'f/catchIo [boom] {})))
      (is (= "io:boom" (f/catchIoPred (fn [x] (throw (java.io.IOException. "boom"))))))
      (is (= "io:boom" (rt/call-dyn #'f/catchIoPred [(fn [x] (throw (java.io.IOException. "boom")))] {}))))
    (testing "uncaught by Kotlin, the caller gets the IOException itself"
      (doseq [e [(thrown #(f/apply1 boom 1)) (thrown #(rt/call-dyn #'f/apply1 [boom 1] {}))
                 (thrown #(f/check (fn [x] (throw (java.io.IOException. "p"))) 1))
                 (thrown #(rt/call-dyn #'f/check [(fn [x] (throw (java.io.IOException. "p"))) 1] {}))]]
        (is (instance? java.io.IOException e))
        (is (not (instance? java.lang.reflect.UndeclaredThrowableException e))))))
  (testing "unchecked exceptions are unchanged"
    (is (instance? IllegalStateException (thrown #(rt/call-dyn #'f/apply1 [(fn [x] (throw (IllegalStateException. "u"))) 1] {}))))))
