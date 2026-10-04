(ns kt.value-test
  "Step 3: value classes (DESIGN-2 rule 7, note \"Value class\"). In Clojure a value-class value is
  always the boxed object; kt unboxes where the JVM slot holds the underlying type and boxes a
  result of such a slot. Every V1-V5 case runs on the static path (as written) and on the
  dynamic path (`rt/call-dyn`) with the same assertions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kt.call-test :as ct :refer [both compile-error root-message expansions reflection-output]]
            [kt.core :as kt]
            [kt.rt :as rt]))

(kt/require '[fx :as f] '[kotlin.time :as t] '[kotlin :as kk])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

(defn- uid ^fx.Uid [n] (f/Uid n))

;; ---------------------------------------------------------------- V2 constructing

(deftest v2-construct
  (testing "primary constructor: constructor-impl + box-impl, the result is the boxed object"
    (both (uid 5) f/Uid 5)
    (is (instance? fx.Uid (f/Uid 5)))
    (is (instance? fx.Uid (rt/call-dyn #'f/Uid [5] {})))
    (is (= "Uid(v=5)" (str (f/Uid 5)))))
  (testing "secondary constructors, also with a default (Kotlin prefers the candidate that needs no default)"
    (both (uid 12) f/Uid "12")
    (both (uid 3) f/Uid 1 2)
    (both (uid 1) f/Uid 1)
    (both (uid 101) f/Uid :a 1)
    (both (uid 8) f/Uid :a 3 :b 5))
  (testing "constructor defaults of the primary constructor"
    (both (f/Cnt 3) f/Cnt)
    (both 3 f/cnt)
    (both 4 f/cnt (f/Cnt 4))
    (both 4 f/cnt :c (f/Cnt 4))
    (is (instance? fx.Cnt (f/Cnt)))
    (is (= 3 (f/n (f/Cnt))))
    (is (= 4 (f/n (f/Cnt 4)))))
  (testing "reference underlying type and a generic value class"
    (both (f/Name "x") f/Name "x")
    (is (instance? fx.Name (f/Name "x")))
    (is (instance? fx.Id (f/Id "abc")))
    (is (= "abc" (f/raw (f/Id "abc"))))
    (both "abc" f/idOf (f/mkId "abc")))
  (testing "a class with a constructor that takes a value class (JVM constructor with a marker)"
    (is (= 1 (f/v (f/uid (f/Holder)))))
    (is (= 9 (f/v (f/uid (f/Holder (uid 9))))))
    (is (= 9 (f/v (f/uid (rt/call-dyn #'f/Holder [(uid 9)] {})))))
    (is (= 9 (f/v (f/uid (f/Holder :uid (uid 9)))))))
  (testing "no public constructor (kotlin.UInt, Duration have an internal one)"
    (is (str/includes? (compile-error '(t/Duration)) "has no public constructor"))
    (is (str/includes? (compile-error '(kk/UInt)) "has no public constructor"))))

;; ---------------------------------------------------------------- V1 calls

(deftest v1-top-level-calls
  (testing "parameter and return are value classes"
    (both (uid 6) f/nextUid (uid 5))
    (both (uid 6) f/nextUid (f/Uid 5))
    (both (uid 7) f/nextUid (f/nextUid (uid 5)))
    (both (uid 6) f/nextUid :u (uid 5)))
  (testing "nullable over a primitive: the JVM slot is the box, the object passes unchanged"
    (both nil f/uidOrNull nil)
    (both (uid 2) f/uidOrNull (uid 1)))
  (testing "nullable over a reference: the JVM slot is the underlying String"
    (both nil f/nameOrNull nil)
    (both (f/Name "a!") f/nameOrNull (f/Name "a")))
  (testing "generic position: a list of value classes holds the objects"
    (both [(uid 10) (uid 20)] f/uidList [(uid 1) (uid 2)])
    (is (every? #(instance? fx.Uid %) (f/uidList [(uid 1)]))))
  (testing "$default with a mangled name"
    (both 8 f/withUidDefault 1)
    (both 3 f/withUidDefault 1 (uid 2))
    (both 4 f/withUidDefault :u (uid 3) :x 1)
    (both 8 f/withUidDefault :x 1))
  (testing "vararg of a value class (only unsigned types are allowed by Kotlin 2.4: UIntArray)"
    (both 0 f/uints)
    (both 3 f/uints (f/mkU 1) (f/mkU 2))
    (both 13 f/uints (f/mkU 1) (f/mkU 2) :base 10)
    (both 3 f/uints :xs [(f/mkU 1) (f/mkU 2)])
    (both 14 f/uints :xs [(f/mkU 1) (f/mkU 3)] :base 10))
  (testing "extension whose receiver is a value class, extension property"
    (both (uid 10) f/.double (uid 5))
    (both (uid 15) f/tripled (uid 5)))
  (testing "overloads that differ only by the value class"
    (both "uid" f/tag (uid 1))
    (both "name" f/tag (f/Name "n"))
    (is (= "uid" (f/tag ^fx.Uid (uid 1))))
    (is (= "name" (f/tag ^fx.Name (f/Name "n"))))))

(deftest v1-members
  (let [h (f/Holder (uid 5))]
    (testing "member with a value-class parameter and return"
      (is (= (uid 15) (f/.bump h (uid 10))))
      (is (= (uid 15) (rt/call-dyn #'f/.bump [h (uid 10)] {}))))
    (testing "member with a mangled $default"
      (is (= 11 (f/.bumpD h)))
      (is (= 11 (rt/call-dyn #'f/.bumpD [h] {})))
      (is (= 20 (f/.bumpD h (uid 10) 5)))
      (is (= 12 (f/.bumpD h :n 2))))
    (testing "property of a value-class type (mangled getter)"
      (is (= (uid 5) (f/uid h)))
      (is (= (uid 5) (rt/call-dyn #'f/uid [h] {})))
      (is (= (uid 5) (f/twin h))))
    (testing "interface method with a value-class parameter, implemented by a class"
      (let [tr (f/TrackerImpl)]
        (is (= "tracked:5" (f/.track tr (uid 5))))
        (is (= "tracked:5" (rt/call-dyn #'f/.track [tr (uid 5)] {})))
        (is (= "tracked:5" (f/.track ^fx.Tracker tr (uid 5))))))))

(deftest v1-companion
  (testing "factory in the companion: the JVM returns the underlying value"
    (both (uid 9) f/.of f/Uid 9))
  (testing "property of the companion with a mangled getter"
    (both (uid 0) f/ZERO f/Uid))
  (testing "const"
    (both 99 f/LIMIT f/Uid)))

;; ---------------------------------------------------------------- V3 members of a value class

(deftest v3-value-class-members
  (let [u (uid 5)]
    (testing "the primary property is a plain getter of the box"
      (both 5 f/v u)
      (both "s" f/s (f/Name "s")))
    (testing "methods and properties compile to static name-impl(underlying, ...)"
      (both (uid 8) f/.plus u 3)
      (both "uid:5" f/.describe u)
      (both "x:5" f/.describe u "x")
      (both "x:5" f/.describe u :prefix "x")
      (both 10 f/doubled u)
      (both (uid 6) f/next u)
      (both "X" f/.shout (f/Name "x")))
    (testing "equals / hashCode / toString through Clojure"
      (is (= (uid 5) (f/Uid 5)))
      (is (not= (uid 5) (uid 6)))
      (is (= (hash (uid 5)) (hash (f/Uid 5))))
      (is (= "Uid(v=5)" (str u)))
      (is (= #{(uid 1)} (conj #{(uid 1)} (f/Uid 1))) "usable as a set element and a map key")
      (is (= {(uid 1) :a} {(f/Uid 1) :a})))
    (testing "a value is always the object, never the underlying number"
      (is (instance? fx.Uid (f/nextUid u)))
      (is (not (number? (f/nextUid u)))))))

;; ---------------------------------------------------------------- V4 stdlib value classes

(deftest v4-duration
  (testing "member-extension property of the companion; receivers in JVM order: companion, then the Int"
    (both (t/seconds t/Duration 5) t/seconds t/Duration 5)
    (is (instance? kotlin.time.Duration (t/seconds t/Duration 5)))
    (is (instance? kotlin.time.Duration (rt/call-dyn #'t/seconds [t/Duration 5] {}))))
  (testing "property of the box"
    (both 5000 t/inWholeMilliseconds (t/seconds t/Duration 5)))
  (testing "operator member"
    (both (t/seconds t/Duration 5) t/.plus (t/seconds t/Duration 2) (t/seconds t/Duration 3))
    (is (= 5000 (t/inWholeMilliseconds (t/.plus (t/seconds t/Duration 2) (t/seconds t/Duration 3))))))
  (testing "Duration as a parameter, as a return, and as a $default-ed parameter"
    (both 2000 f/pause (t/seconds t/Duration 2))
    (both 1000 f/pause)
    (both 3000 f/pause :d (t/seconds t/Duration 3))
    (both (t/seconds t/Duration 4) f/twice (t/seconds t/Duration 2))
    (is (= "4s" (str (f/twice (t/seconds t/Duration 2)))))))

(deftest v4-uint-and-result
  (testing "kotlin.UInt as parameter and return"
    (both (f/mkU 42) f/u (f/mkU 41))
    (is (instance? kotlin.UInt (f/u (f/mkU 41))))
    (both 42 f/uVal (f/u (f/mkU 41)))
    (is (= "42" (str (f/u (f/mkU 41))))))
  (testing "Int.toUInt is a hidden inline function (private in the JVM): it works through the bridge"
    (both (f/mkU 5) kk/.toUInt 5))
  (testing "kotlin.Result: the JVM returns Object"
    (let [ok (f/tryIt false) bad (f/tryIt true)]
      (is (instance? kotlin.Result ok))
      (is (instance? kotlin.Result bad))
      (is (true? (kk/isSuccess ok)))
      (is (false? (kk/isSuccess bad)))
      (is (true? (kk/isFailure bad)))
      (is (true? (rt/call-dyn #'kk/isSuccess [ok] {})))
      (is (= 7 (kk/.getOrNull ok)))
      (is (= 7 (rt/call-dyn #'kk/.getOrNull [ok] {})))
      (is (nil? (kk/.getOrNull bad)))
      (is (nil? (kk/.exceptionOrNull ok)))
      (is (= "bad" (ex-message (kk/.exceptionOrNull bad))))
      (is (= "bad" (ex-message (rt/call-dyn #'kk/.exceptionOrNull [bad] {})))))))

;; ---------------------------------------------------------------- V5 function types

(deftest v5-value-classes-in-function-types
  (testing "a Clojure function takes and returns boxed objects, adapters pass them unchanged"
    (both (uid 6) f/mapUid (uid 5) (fn [u] (f/.plus u 1)))
    (both (uid 5) f/mapUid (uid 5) identity)
    (let [seen (atom nil)]
      (f/mapUid (uid 5) (fn [u] (reset! seen (class u)) u))
      (is (= fx.Uid @seen))))
  (testing "a Kotlin lambda that takes and returns a value class"
    (let [g (f/uidFn) d (rt/call-dyn #'f/uidFn [] {})]
      (is (= (uid 105) (g (uid 5))))
      (is (= (uid 105) (d (uid 5))))
      (is (instance? fx.Uid (g (uid 5))))
      (testing "and it goes back to Kotlin"
        (is (= (uid 205) (f/mapUid (uid 5) (fn [u] (g (g u))))))
        (is (= (uid 105) (f/mapUid (uid 5) g)))))))

;; ---------------------------------------------------------------- errors

(deftest value-class-errors
  (testing "the underlying number is not a value (rule: a value class is always the object)"
    (let [m (compile-error '(f/nextUid 1))]
      (is (str/starts-with? m "kt: "))
      (is (str/includes? m "fun nextUid(u: fx.Uid): fx.Uid"))
      (is (str/includes? m "`u` is Uid but got Long")))
    (let [m (root-message (thrown #(rt/call-dyn #'f/nextUid [1] {})))]
      (is (str/includes? m "`u` is Uid but got Long")))
    (let [m (root-message (thrown #((ct/eval-here-fn '(fn [x] (f/nextUid x))) 1)))]
      (is (str/includes? m "expects fx.Uid, got java.lang.Long 1"))
      (is (str/includes? m "A value class is always the object"))))
  (testing "nil for a non-null value class"
    (is (str/includes? (compile-error '(f/nextUid nil)) "`nil` passed to non-nullable `u`"))
    (is (str/includes? (root-message (thrown #(rt/call-dyn #'f/nextUid [nil] {}))) "`nil` passed to non-nullable `u`"))
    (is (str/includes? (root-message (thrown #((ct/eval-here-fn '(fn [x] (f/nextUid x))) nil)))
                       "nil where Kotlin expects a non-null fx.Uid")))
  (testing "a wrong value class"
    (is (str/includes? (compile-error '(f/nextUid (f/Name "x"))) "`u` is Uid but got Name"))
    (is (str/includes? (root-message (thrown #(rt/call-dyn #'f/nextUid [(f/Name "x")] {}))) "`u` is Uid but got Name")))
  (testing "overloads on a value class and an unknown argument type: dynamic path with a warning"
    (let [x (expansions '(fn [a] (f/tag a)))]
      (is (seq (:dynamic x)))
      (is (str/includes? (:warnings x) "using the dynamic path")))))

;; ---------------------------------------------------------------- static path proofs

(defn- bridge-call? [form] (str/includes? (pr-str form) "kt.bridge."))

(deftest static-path-is-direct
  (testing "five calls: a call of a bridge class, no call-dyn, no reflection warning"
    (doseq [form ['(f/nextUid (f/Uid 5))
                  '(f/pause)
                  '(t/seconds t/Duration 5)
                  '(f/.plus (f/Uid 5) 3)
                  '(f/uidOrNull (f/Uid 1))
                  '(f/withUidDefault 1)
                  '(f/.double (f/Uid 2))
                  '(kk/.getOrNull (f/tryIt false))]
            :let [x (expansions `(fn [] ~form))]]
      (testing (pr-str form)
        (is (seq (:static x)))
        (is (every? bridge-call? (remove #(str/includes? (pr-str %) "fx.Holder") (:static x))) (pr-str (:static x)))
        (is (empty? (:dynamic x)))
        (is (not (str/includes? (pr-str (:static x)) "call-dyn")))
        (is (= "" (:warnings x)))
        (is (= "" (reflection-output `(fn [] ~form)))))))
  (testing "a nullable-over-primitive call has a bridge only for the target, no box or unbox"
    (let [s (pr-str (:static (expansions '(fn [] (f/uidOrNull (f/Uid 1))))))]
      (is (not (str/includes? s "unbox_impl")))))
  (doseq [form ['(f/nextUid (f/Uid 5))
                '(f/pause)
                '(t/seconds t/Duration 5)]]
    (println "EXPANSION" (pr-str form) "=>" (pr-str (first (:static (expansions `(fn [] ~form))))))))

(deftest nested-value-class-types-resolve-statically
  (testing "an overloaded call on the result of another kt call: its Kotlin return type is a value class"
    (let [x (expansions '(fn [] (f/tag (f/nextUid (f/Uid 1)))))]
      (is (empty? (:dynamic x)))
      (is (= "" (:warnings x))))
    (is (= "uid" (f/tag (f/nextUid (f/Uid 1)))))))
