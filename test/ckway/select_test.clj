(ns ckway.select-test
  "Overload selection (S1, S2, S4 of the call-path review). Every `k...` function of `test-fixtures/fx/Select.kt`
  makes the same call in Kotlin source and returns the declaration that KOTLIN picked; a test compares the answer
  of ckway - on the static path (the call as written) and on the dynamic path (`ckway.rt/call-dyn` with the
  same values) - with it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.rt :as rt])
  (:import [java.io StringWriter]))

(kt/require '[fx.sel :as p] '[kotlin :as k] '[kotlin.collections :as c] '[kotlin.text :as tx])

(defmacro same
  "`oracle` is the form that evaluates to Kotlin's answer. Assert that the call `(f args...)` gives it on the static
  path and on the dynamic path. An integer literal is marked as a literal on the dynamic path, like the static
  path types it (Int when it fits)."
  [oracle f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))
        lits (into {} (keep-indexed (fn [i a] (when (and (integer? a) (<= Integer/MIN_VALUE a Integer/MAX_VALUE)) [i :int]))
                                    pos))]
    `(let [expected# ~oracle]
       (is (= expected# (~f ~@args)) (str "static " '(~f ~@args)))
       (is (= expected# (rt/call-dyn (var ~f) [~@pos] ~nm ~lits)) (str "dynamic " '(~f ~@args))))))

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.select-test)] (eval form)))

(defn reflection-output
  "What the compiler prints to *err* while it compiles `form` with *warn-on-reflection* on."
  [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

;; ---------------------------------------------------------------- S1: the vararg array is typed

(deftest vararg-overload-is-the-one-kotlin-picks
  (same (p/kLo1) p/lo 1)
  (same (p/kLo2) p/lo 1 2)
  (same (p/kLo0) p/lo)
  (is (= "vararg:2" (p/lo 1 2)))
  (is (= "vararg:3" (p/lo 1 2 3)))
  (is (= "single" (p/lo 1))))

(deftest vararg-overloads-of-int-and-string
  (same (p/kLovInts) p/lov 1 2 3)
  (same (p/kLovStrs) p/lov "a" "b")
  (is (= "int:3" (p/lov 1 2 3)))
  (is (= "str:2" (p/lov "a" "b"))))

(deftest stdlib-vararg-calls-build-the-array-once
  (testing "the array is the vararg, not one element"
    (is (= [1 2] (vec (c/listOf 1 2))))
    (is (= 2 (count (c/listOf 1 2))))
    (is (= #{1 2} (set (c/setOf 1 2))))
    (is (= [1 2] (vec (c/listOfNotNull 1 nil 2))))
    (is (= {"a" 1 "b" 2} (into {} (c/mapOf (k/.to "a" 1) (k/.to "b" 2)))))
    (is (= ["a" "b"] (vec (tx/.split "a,b" ","))))))

(deftest primitive-and-boxed-overloads
  (same (p/kPrimInt) p/prim 1)
  (same (p/kPrimLong) p/prim 5000000000)
  (same (p/kPrimShort) p/prim (short 1))
  (same (p/kPrimByte) p/prim (byte 1))
  (same (p/kPrimChar) p/prim \c)
  (same (p/kPrimFloat) p/prim (float 1.5))
  (same (p/kPrimDouble) p/prim 1.5)
  (same (p/kPrimBool) p/prim true)
  (same (p/kPrimStr) p/prim "s")
  (same (p/kBbTrue) p/bb true)
  (same (p/kBbNil) p/bb nil))

(deftest default-masks-and-overloads
  (same (p/kDfltStr) p/dflt "s")
  (same (p/kDfltInt) p/dflt 7)
  (same (p/kDfltStrB) p/dflt "s" :b 3)
  (same (p/kDfltStrC) p/dflt "s" :c true))

(deftest no-reflection-warning-for-a-broad-set-of-static-calls
  (let [out (reflection-output
             '(fn []
                [(p/lo 1) (p/lo 1 2) (p/lo) (p/lov 1 2) (p/lov "a")
                 (p/prim 1) (p/prim 5000000000) (p/prim (short 1)) (p/prim (byte 1)) (p/prim \c) (p/prim (float 1))
                 (p/prim 1.5) (p/prim true) (p/prim "s") (p/bb true) (p/bb nil)
                 (p/dflt "s") (p/dflt 7) (p/dflt "s" :b 3) (p/dflt "s" :c true)
                 (p/coll [1 2]) (p/nn nil) (p/ni 1) (p/one 1) (p/one 1 2) (p/seqs "s")
                 (p/.f (p/A) "s") (p/.rcv "s")
                 (c/listOf 1) (c/listOf 1 2) (c/listOf) (c/setOf 1 2) (c/listOfNotNull 1 nil 2)
                 (c/mapOf (k/.to "a" 1) (k/.to "b" 2)) (c/mapOf (k/.to "a" 1))
                 (c/.count [1 2 3]) (c/.first [1 2 3]) (c/.last [1 2 3]) (c/.firstOrNull [1 2 3])
                 (c/.toMutableList [1 2]) (c/.plus [1] 2) (c/.isNotEmpty [1]) (c/.toList [1])
                 (tx/.split "a,b" ",") (tx/.trim " a ") (tx/.uppercase "a") (tx/.lowercase "A") (tx/.toInt "1")
                 (tx/.padStart "a" 3) (tx/.replace "a" "b" "c") (tx/.toRegex "a") (tx/.substringBefore "a-b" "-")
                 (k/require true) (k/.let 1 inc) (k/.to 1 2)]))]
    (is (not (str/includes? out "Reflection warning")) out)))

;; ---------------------------------------------------------------- S2: a member beats an extension

(deftest an-applicable-member-beats-a-more-specific-extension
  (let [a (p/A)]
    (same (p/kAf a) p/.f a "s")
    (same (p/kAf2 a) p/.f a 1)
    (same (p/kAg a) p/.g a "s")
    (same (p/kAg2 a) p/.g a 1)
    (same (p/kAh a) p/.h a 1)
    (same (p/kAhs a) p/.h a "s")
    (same (p/kAonly a) p/.only a "s"))
  (is (= "member" (p/.f (p/A) "s")))
  (is (= "ext-only" (p/.only (p/A) "s"))))

;; ---------------------------------------------------------------- S4: Kotlin's most specific

(deftest most-specific-by-parameter-types
  (same (p/kColl [1 2]) p/coll [1 2])
  (same (p/kColl (java.util.ArrayList. [1])) p/coll (java.util.ArrayList. [1]))
  (same (p/kCollC (java.util.HashSet. [1])) p/coll (java.util.HashSet. [1]))
  (same (p/kNnNil) p/nn nil)
  (same (p/kNnStr) p/nn "s")
  (same (p/kNnInt) p/nn 1)
  (same (p/kNi) p/ni 1)
  (same (p/kNiNil) p/ni nil)
  (same (p/kSeqsStr) p/seqs "s")
  (same (p/kRcvStr) p/.rcv "s")
  (same (p/kTwoStr) p/two 1 "s")
  (same (p/kTwoStr2) p/two "s" 1)
  (is (= "list" (p/coll [1 2])))
  (is (= "String?" (p/nn nil)))
  (is (= "Int" (p/ni 1))))

(deftest non-vararg-beats-vararg
  (same (p/kOne1) p/one 1)
  (same (p/kOne2) p/one 1 2)
  (same (p/kOne0) p/one)
  (same (p/kLo1) p/lo 1))

(deftest stdlib-calls-that-were-ambiguous
  (same 3 c/.count [1 2 3])
  (same 1 c/.first [1 2 3])
  (same 3 c/.last [1 2 3])
  (same 1 c/.firstOrNull [1 2 3])
  (same [1 2] c/.toMutableList [1 2])
  (same [1 2] c/.plus [1] 2)
  (same [1] c/listOf 1)
  (same [] c/listOf)
  (same {"a" 1} c/mapOf (k/.to "a" 1))
  (same "a" tx/.trim " a "))

(deftest an-upper-bound-class-never-selects-a-narrower-overload
  (testing "a Collection-typed local that holds an ArrayList: the static path takes Kotlin's static answer"
    (let [^java.util.Collection x (java.util.ArrayList. [1 2])]
      (is (= (p/kCollC x) (p/coll x)) "static: Collection, not List")
      (is (= "collection" (p/coll x)))
      (testing "the dynamic path knows the run-time class"
        (is (= (p/kColl x) (rt/call-dyn (var p/coll) [x] {}))))))
  (testing "a CharSequence-typed local that holds a String"
    (let [^CharSequence x "s"]
      (is (= (p/kSeqsCs x) (p/seqs x)) "static: CharSequence, not String")
      (is (= (p/kSeqsStr) (rt/call-dyn (var p/seqs) [x] {})))))
  (testing "a local of unknown type: the run-time class decides"
    (let [x (identity "s")]
      (is (= (p/kSeqsStr) (p/seqs x)))
      (is (= (p/kSeqsStr) (rt/call-dyn (var p/seqs) [x] {}))))
    (let [x (identity (java.util.ArrayList. [1]))]
      (is (= (p/kColl x) (p/coll x)))
      (is (= (p/kColl x) (rt/call-dyn (var p/coll) [x] {}))))))

(deftest no-guess-stays-an-error
  (testing "neither candidate is more specific"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous|fits"
                          (rt/call-dyn (var p/two) [1 2] {})))))
