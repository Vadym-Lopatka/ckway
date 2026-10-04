(ns ckway.round6-test
  "Fifth review: one declaration for one JVM member, however it is reached (Z1); the `$default` synthetic of an interface
  member is found where the compiler put it (Z2); one member of two interfaces is one virtual call (Z3); the choice among
  applicable candidates is Kotlin's most-specific rule (Z4).

  Every selection test runs the call on the three paths - static (the form is compiled), dynamic (`rt/call-dyn`, with the
  integer literals marked as the static path marks them) and the var as a value (`apply`: positional values only) - and
  compares with KOTLIN: a `k...` function of the fixture makes the same call, or, for Z4, the answer is in
  `target/fixtures/round6-oracle.txt`, which `bin/kotlin-oracle` writes by compiling and running
  `test-fixtures/oracle/Round6Calls.kt`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.rt :as rt])
  (:import [java.io StringWriter]))

(kt/require '[fx.r6 :as p] '[fx.r6far :as q] '[fx.r6s :as s]
            '[fx.legacy6 :as pl] '[fx.jd6en :as pe] '[fx.jd6nc :as pn]
            '[kotlinx.coroutines.channels :as ch])

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round6-test)] (eval form)))

(defn- outcome
  "The value of `(f)`; `:ambiguous` for the library's ambiguity error, `[:error first-line]` for any other error."
  [f]
  (try (f)
       (catch Throwable t
         (let [m (str (ct/root-message t))]
           (if (str/includes? m "is ambiguous") :ambiguous [:error (first (str/split-lines m))])))))

(defn- split-call
  "The call form `(alias/f a b :k v)` as [var positional-forms named-pairs]."
  [form]
  (let [[head & args] form
        [pos named] (split-with (complement keyword?) args)]
    [(ns-resolve (the-ns 'ckway.round6-test) head) (vec pos) (mapv vec (partition 2 named))]))

(defn- lit-kind [form]
  (when (integer? form) (if (<= Integer/MIN_VALUE form Integer/MAX_VALUE) :int :long)))

(defn- static-path [form] (outcome #(eval-here form)))

(defn- dynamic-path
  "The call through `rt/call-dyn`, as the static path emits it when it cannot select at compile time: the values, and
  which of them were integer literals."
  [form]
  (let [[v pos named] (split-call form)
        lits (into {} (concat (keep-indexed (fn [i f] (when-let [k (lit-kind f)] [i k])) pos)
                              (keep (fn [[k f]] (when-let [l (lit-kind f)] [(name k) l])) named)))]
    (outcome #(rt/call-dyn v (mapv eval-here pos) (apply array-map (mapcat (fn [[k f]] [(name k) (eval-here f)]) named)) lits))))

(defn- positional? [form] (empty? (nth (split-call form) 2)))

(defn- var-path
  "The var as a value: `(apply f values)`. Only for a call without named arguments."
  [form]
  (let [[v pos _] (split-call form)]
    (outcome #(apply @v (mapv eval-here pos)))))

(defmacro ^:private all-paths
  "`expected` on the static and the dynamic path, and on the var-as-value path when the call has no named argument."
  [expected form]
  `(let [e# ~expected f# '~form]
     (is (= e# (static-path f#)) (str "static " (pr-str f#)))
     (is (= e# (dynamic-path f#)) (str "dynamic " (pr-str f#)))
     (when (positional? f#)
       (is (= e# (var-path f#)) (str "var as a value " (pr-str f#))))))

(defn- warnings [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

;; ---------------------------------------------------------------- Z1

(deftest z1-narrowing-override-with-inherited-defaults-and-a-subclass
  (testing "the class that overrides"
    (all-paths (p/kStr2) (p/.mk (p/StrSrc) 2))
    (all-paths (p/kStr2) (p/.mk (p/StrSrc) :x 2))
    (all-paths (p/kStr) (p/.mk (p/StrSrc)))
    (is (= "mk2" (p/kStr2))) (is (= "mk7" (p/kStr))))
  (testing "the subclass that does not override"
    (all-paths (p/kSub2) (p/.mk (p/SubSrc) 2))
    (all-paths (p/kSub2) (p/.mk (p/SubSrc) :x 2))
    (all-paths (p/kSub) (p/.mk (p/SubSrc)))
    (is (= "mk2" (p/kSub2))) (is (= "mk7" (p/kSub))))
  (testing "an override with the same result type, and its subclass"
    (all-paths (p/kAny) (p/.mk (p/AnySrc)))
    (all-paths (p/kAnySub) (p/.mk (p/AnySub)))
    (all-paths (p/kAnySub2) (p/.mk (p/AnySub) 2))
    (all-paths (p/kAnySub2) (p/.mk (p/AnySub) :x 2))
    (is (= "any7" (p/kAnySub))))
  (testing "an untyped receiver"
    (is (= "mk2" (let [o (identity (p/SubSrc))] (p/.mk o 2))))
    (is (= "mk7" (let [o (identity (p/SubSrc))] (p/.mk o))))
    (is (= "any7" (let [o (identity (p/AnySub))] (p/.mk o)))))
  (testing "the result has the narrowed type: no reflection"
    (is (= "" (warnings '(fn [] (.length (p/.mk (p/SubSrc) 2))))))))

(deftest z1-subclass-in-another-package
  (all-paths (q/kFarSub2) (q/.mk (q/FarSub) 2))
  (all-paths (q/kFarSub2) (q/.mk (q/FarSub) :x 2))
  (all-paths (q/kFarSub) (q/.mk (q/FarSub)))
  (all-paths (q/kFarOver2) (q/.mk (q/FarOver) 2))
  (all-paths (q/kFarOver) (q/.mk (q/FarOver)))
  (is (= ["mk7" "mk2" "far7" "far2"] [(q/kFarSub) (q/kFarSub2) (q/kFarOver) (q/kFarOver2)]))
  (testing "the var of the other package calls the same member"
    (all-paths (q/kFarSub2) (p/.mk (q/FarSub) 2))
    (all-paths (q/kFarOver) (p/.mk (q/FarOver)))))

(deftest z1-diamond
  (all-paths (p/kDiamond) (p/.dm (p/Diamond)))
  (all-paths (p/kDiamond2) (p/.dm (p/Diamond) 2))
  (all-paths (p/kDiamond2) (p/.dm (p/Diamond) :x 2))
  (all-paths (p/kDiamondSub) (p/.dm (p/DiamondSub)))
  (all-paths (p/kDiamondSub2) (p/.dm (p/DiamondSub) 2))
  (all-paths (q/kFarDiamond) (q/.dm (q/FarDiamond)))
  (is (= ["dm3" "dm2" "dm3" "dm2" "dm3"] [(p/kDiamond) (p/kDiamond2) (p/kDiamondSub) (p/kDiamondSub2) (q/kFarDiamond)])))

(deftest z1-three-levels-with-a-redeclaration-in-the-middle
  (is (= "l2-5y" (p/kL2) (str (p/.lv (p/L2)))))
  (all-paths (p/kL3) (p/.lv (p/L3)))
  (all-paths (p/kL4) (p/.lv (p/L4)))
  (all-paths (p/kL4x) (p/.lv (p/L4) 1))
  (all-paths (p/kL4y) (p/.lv (p/L4) :y "z"))
  (all-paths (p/kL4xy) (p/.lv (p/L4) 1 "z"))
  (all-paths (p/kL4xy) (p/.lv (p/L4) :y "z" :x 1))
  (all-paths (q/kFarL4) (q/.lv (q/FarL4)))
  (all-paths (q/kFarL4y) (q/.lv (q/FarL4) :y "z"))
  (is (= ["l3-5y" "l3-5y" "l3-1y" "l3-5z" "l3-1z" "l3-5y" "l3-5z"]
         [(p/kL3) (p/kL4) (p/kL4x) (p/kL4y) (p/kL4xy) (q/kFarL4) (q/kFarL4y)])))

(defn- jvm-identity
  "The test's own statement of the identity of the JVM member of a declaration: declaring class, JVM name (or field),
  descriptor. nil for a declaration without a JVM member."
  [d]
  (let [m (if (= :property (:kind d)) (or (:getter d) (:jvm d)) (:jvm d))]
    (when m [(:class m) (or (:name m) (:field m) (:instance-field m)) (:desc m)])))

(defn- duplicates
  "[var-name identity n] for every JVM member that is in a var of the package more than once."
  [pkg]
  (for [[var-name decls] (meta/package-index pkg)
        [id n] (frequencies (keep jvm-identity decls))
        :when (> n 1)]
    [var-name id n]))

(defn- drifted
  "[var-name identity] for every JVM member of which the classes of the package give DIFFERENT declarations (the class
  itself, and each subclass that inherits it): the stream of declarations before the index de-duplicates it."
  [pkg]
  (for [[k ds] (group-by (juxt :var-name jvm-identity) (mapcat #'meta/class-file-decls (#'meta/class-names-in pkg)))
        :when (and (second k) (> (count (distinct ds)) 1))]
    k))

(deftest z1-one-member-is-one-map-on-every-path
  (testing "own, inherited by a subclass of the package, inherited in another package: the same declaration"
    (let [of (fn [v owner] (filter #(= owner (:owner %)) (:kt/decls (meta v))))
          a (of #'p/.mk "fx.r6.StrSrc") b (of #'q/.mk "fx.r6.StrSrc")
          own (filter #(= ".mk" (:var-name %)) (meta/own-declarations (Class/forName "fx.r6.StrSrc")))]
      (is (= 1 (count a) (count b) (count own)))
      (is (= (first a) (first b) (first own)))
      (is (= [true] (map :default? (:params (first a)))))
      (is (= "fx.r6.Src" (:class (:default (:jvm (first a))))))))
  (testing "three levels: each override has the defaults of the original"
    (is (= #{"fx.r6.L1"} (set (map #(:class (:default (:jvm %))) (:kt/decls (meta #'p/.lv))))))
    (is (= #{"fx.r6.L1" "fx.r6.L2" "fx.r6.L3"} (set (map :owner (:kt/decls (meta #'p/.lv))))))))

(deftest z1-invariant-one-declaration-for-one-jvm-member
  (doseq [pkg ["fx.r6" "fx.r6far" "fx.r6s" "fx.r5" "fx.r5far" "fx.r4" "fx" "fx.legacy" "fx.legacy6" "fx.jd6en" "fx.jd6nc"
               "kotlin" "kotlin.collections" "kotlin.text" "kotlin.sequences" "kotlin.ranges" "kotlin.io" "kotlin.time"
               "kotlinx.coroutines" "kotlinx.coroutines.flow" "kotlinx.coroutines.channels" "kotlinx.coroutines.sync"]]
    (testing pkg
      (let [idx (meta/package-index pkg)]
        (is (pos? (count idx)) "the package has vars")
        (is (= [] (vec (duplicates pkg))) "no var holds one JVM member twice")
        (is (= [] (vec (drifted pkg))) "every path to a member gives the same declaration")))))

(deftest z1-ambiguity-of-equal-members-does-not-talk-about-type-arguments
  ;; the shape of the regression: one member twice (same class, same parameters), here made by hand. The help for
  ;; declarations that differ in an erased type argument is not for them.
  (let [d (first (filter #(= "fx.r6.StrSrc" (:owner %)) (:kt/decls (meta #'p/.mk))))
        twin (assoc d :signature "fun fx.r6.StrSrc.mk(x: Int): String")
        help (#'ckway.resolve/ambiguity-help ".mk" [{:decl d :items [] :checks []} {:decl twin :items [] :checks []}])]
    (is (some? d))
    (is (not (str/includes? (str help) "type argument")) (str help))))

;; ---------------------------------------------------------------- Z2

(defmacro ^:private interface-defaults
  "The assertions of Z2 for the package alias `a` (one alias for each `-jvm-default` mode)."
  [a]
  (let [sym (fn [n] (symbol (str a) n))]
    `(do
       (testing "a default of an interface member is omitted"
         (all-paths (~(sym "kMk")) (~(sym ".mk") (~(sym "lsOf"))))
         (all-paths (~(sym "kMk2")) (~(sym ".mk") (~(sym "lsOf")) 2))
         (is (= ["ls7" "ls2"] [(~(sym "kMk")) (~(sym "kMk2"))])))
       (testing "an override in a class inherits the default of the interface (Z1 and Z2)"
         (all-paths (~(sym "kImplMk")) (~(sym ".mk") (~(sym "LSImpl"))))
         (all-paths (~(sym "kSubMk")) (~(sym ".mk") (~(sym "LSSub"))))
         (all-paths (~(sym "kAnyMk")) (~(sym ".mk") (~(sym "LSAny"))))
         (all-paths (~(sym "kAnySubMk")) (~(sym ".mk") (~(sym "LSAnySub"))))
         (all-paths "ls3" (~(sym ".mk") (~(sym "LSSub")) :x 3))
         (is (= ["ls7" "ls7" "any7" "any7"] [(~(sym "kImplMk")) (~(sym "kSubMk")) (~(sym "kAnyMk")) (~(sym "kAnySubMk"))])))
       (testing "a member with a body"
         (all-paths (~(sym "kPlain")) (~(sym ".plain") (~(sym "lsOf"))))
         (all-paths (~(sym "kPlainY")) (~(sym ".plain") (~(sym "lsOf")) :y "z"))
         (all-paths (~(sym "kPlainX")) (~(sym ".plain") (~(sym "lsOf")) 5))
         (is (= ["p1y" "p1z" "p5y"] [(~(sym "kPlain")) (~(sym "kPlainY")) (~(sym "kPlainX"))])))
       (testing "suspend"
         (all-paths (~(sym "kSk")) (~(sym ".sk") (~(sym "lsOf"))))
         (all-paths (~(sym "kSk2")) (~(sym ".sk") (~(sym "lsOf")) 2))
         (is (= ["sk4" "sk2"] [(~(sym "kSk")) (~(sym "kSk2"))])))
       (testing "a value-class parameter"
         (all-paths (~(sym "kVk")) (~(sym ".vk") (~(sym "lsOf"))))
         (all-paths (~(sym "kVk2")) (~(sym ".vk") (~(sym "lsOf")) (~(sym "LUid") 3)))
         (is (= ["vk9" "vk3"] [(~(sym "kVk")) (~(sym "kVk2"))])))
       (testing "an extension receiver"
         (all-paths (~(sym "kEk")) (~(sym ".ek") (~(sym "lsOf")) "e"))
         (all-paths (~(sym "kEk2")) (~(sym ".ek") (~(sym "lsOf")) "e" 5))
         (is (= ["e2" "e5"] [(~(sym "kEk")) (~(sym "kEk2"))])))
       (testing "property accessors with a default body"
         (all-paths (~(sym "kProp")) (~(sym "prop") (~(sym "lsOf"))))
         (all-paths (~(sym "kPropAny")) (~(sym "prop") (~(sym "LSAnySub"))))
         (all-paths (~(sym "kMprop")) (~(sym "mprop") (~(sym "lsOf"))))
         (is (= ["prop-default" "prop-any" "m-default"] [(~(sym "kProp")) (~(sym "kPropAny")) (~(sym "kMprop"))]))
         (is (= "v" (eval-here '(kt/set! (~(sym "mprop") (~(sym "lsOf"))) "v"))) "the setter with a default body")))))

(defn- default-owner [v owner]
  (some #(when (= owner (:owner %)) (:class (:default (:jvm %)))) (:kt/decls (meta v))))

(deftest z2-disable-the-default-synthetic-is-in-DefaultImpls
  (interface-defaults pl)
  (testing "the index names the class that really has the synthetic"
    (is (= "fx.legacy6.LS$DefaultImpls" (default-owner #'pl/.mk "fx.legacy6.LS")))
    (is (= "fx.legacy6.LS$DefaultImpls" (default-owner #'pl/.mk "fx.legacy6.LSImpl")) "the override has the same one")
    (is (= "fx.legacy6.LS$DefaultImpls" (default-owner #'pl/.sk "fx.legacy6.LS")))))

(deftest z2-enable-the-default-synthetic-is-in-the-interface
  (interface-defaults pe)
  (is (= "fx.jd6en.LS" (default-owner #'pe/.mk "fx.jd6en.LS")))
  (testing "@JvmDefaultWithoutCompatibility: no DefaultImpls at all"
    (is (nil? (try (Class/forName "fx.jd6en.LA$DefaultImpls") (catch ClassNotFoundException _ nil))))
    (all-paths (pe/kLaMk) (pe/.mk (pe/laOf)))
    (all-paths (pe/kLaBody) (pe/.body (pe/laOf)))
    (is (= ["la7" "b1"] [(pe/kLaMk) (pe/kLaBody)]))))

(deftest z2-no-compatibility-the-default-synthetic-is-in-the-interface
  (interface-defaults pn)
  (is (= "fx.jd6nc.LS" (default-owner #'pn/.mk "fx.jd6nc.LS")))
  (is (nil? (try (Class/forName "fx.jd6nc.LS$DefaultImpls") (catch ClassNotFoundException _ nil))) "this mode writes no DefaultImpls")
  (testing "@JvmDefaultWithCompatibility: the interface AND DefaultImpls have it; the interface is used"
    (is (some? (Class/forName "fx.jd6nc.LA$DefaultImpls")))
    (is (= "fx.jd6nc.LA" (default-owner #'pn/.mk "fx.jd6nc.LA")))
    (all-paths (pn/kLaMk) (pn/.mk (pn/laOf)))
    (all-paths (pn/kLaBody) (pn/.body (pn/laOf)))
    (is (= ["la7" "b1"] [(pn/kLaMk) (pn/kLaBody)]))))

(deftest z2-a-published-library-compiled-the-old-way
  ;; kotlinx.coroutines: `interface SendChannel { fun close(cause: Throwable? = null): Boolean }`; javap shows
  ;; `close$default` in SendChannel$DefaultImpls and not in SendChannel
  (is (= "kotlinx.coroutines.channels.SendChannel$DefaultImpls"
         (default-owner #'ch/.close "kotlinx.coroutines.channels.SendChannel")))
  (is (true? (let [c (ch/Channel 1)] (ch/.close c))) "static")
  (is (true? (rt/call-dyn #'ch/.close [(ch/Channel 1)] {} {})) "dynamic")
  (is (true? (apply ch/.close [(ch/Channel 1)])) "var as a value")
  (is (false? (let [c (ch/Channel 1)] (ch/.close c) (ch/.close c))) "the second close says false: the call is the member"))

;; ---------------------------------------------------------------- Z3

(deftest z3-one-member-of-two-interfaces
  (testing "the class overrides the member of both"
    (all-paths (p/kQC) (p/.run2 (p/QC)))
    (is (= "qc" (p/kQC))))
  (testing "an untyped receiver, and one that is known as one of the interfaces"
    (is (= "qc" (let [o (identity (p/QC))] (p/.run2 o))))
    (is (= "qc" (p/.run2 (p/q1Of))))
    (is (= "" (warnings '(fn [] (p/.run2 (p/q1Of))))) "static"))
  (testing "a class that has the name but neither interface"
    (all-paths "qx" (p/.run2 (p/QX))))
  (testing "the interfaces give other result types"
    (all-paths (p/kTC) (p/.rt (p/TC)))
    (is (= "tc" (p/kTC))))
  (testing "a superclass has the body, an interface declares the member"
    (all-paths (p/kCB) (p/.cb (p/CB)))
    (is (= "b1" (p/kCB))))
  (testing "with a default (declared by one of the interfaces) and a property"
    (all-paths (p/kWd) (p/.wd (p/WC)))
    (all-paths (p/kWd2) (p/.wd (p/WC) 5))
    (all-paths (p/kWd2) (p/.wd (p/WC) :x 5))
    (all-paths (p/kWp) (p/wp (p/WC)))
    (is (= ["wd1" "wd5" "wp"] [(p/kWd) (p/kWd2) (p/kWp)]))))

(deftest z3-parameter-types-that-differ-only-before-erasure
  ;; G<T>.gf(x: T) and H.gf(x: String) are two JVM methods (gf(Object), gf(String)): not merged, the more specific one is
  ;; chosen, and it runs the one method of GC
  (all-paths (p/kGf) (p/.gf (p/GC) "x"))
  (is (= "gcx" (p/kGf))))

(deftest z3-members-that-kotlin-keeps-apart-stay-apart
  (testing "other parameter types"
    (all-paths (p/kPmLong) (p/.pm (p/PC) (long 1)))
    (is (= (p/kPmInt) (p/.pm (p/PC) 1)) "an Int literal: static")
    (is (= (p/kPmInt) (rt/call-dyn #'p/.pm [(p/PC) 1] {} {1 :int})) "an Int literal: dynamic")
    (is (= (p/kPmInt) (apply p/.pm [(p/PC) (int 1)])) "an Integer: var as a value")
    (is (= ["int" "long"] [(p/kPmInt) (p/kPmLong)])))
  (testing "a property and a function of one name"
    (all-paths (p/kRvProp) (p/rv (p/RC)))
    (all-paths (p/kRvFun) (p/.rv (p/RC)))
    (is (= ["prop" "fun"] [(p/kRvProp) (p/kRvFun)])))
  (testing "a member and an extension"
    (all-paths (p/kEm) (p/.em (p/EC) "x"))
    (all-paths (p/kEmExt) (p/.em (p/EOther) "x"))
    (is (= ["memberx" "extx"] [(p/kEm) (p/kEmExt)]))))

;; ---------------------------------------------------------------- Z4

(def ^:private oracle
  "{id answer}: Kotlin's own answer for each call of test-fixtures/oracle/Round6Calls.kt (the name of the overload that
  ran, AMBIGUOUS, or NONE)."
  (delay (into {} (map #(vec (str/split % #"\t")) (str/split-lines (slurp (io/resource "round6-oracle.txt")))))))

(def ^:private calls
  "id -> the Clojure form of the Kotlin call with that id in Round6Calls.kt. A Kotlin `1L` is `(long 1)`."
  '{"oo-named" (s/oo :a 1 :b 2)
    "oo-named-rev" (s/oo :b 1 :a 2)
    "oo-pos" (s/oo 1 2)
    "oo-long" (s/oo 1 (long 2))
    "s5-two" (s/s5 1 "x")
    "s5-one" (s/s5 1)
    "s5-long" (s/s5 (long 1) "x")
    "s5-three" (s/s5 1 "x" 2)
    "s5-named-c" (s/s5 1 :c 2)
    "t1-both" (s/t1 "a" "b")
    "t1-int-first" (s/t1 1 "b")
    "t1-int-second" (s/t1 "a" 1)
    "n1-int" (s/n1 1)
    "n1-long" (s/n1 (long 1))
    "n1-big" (s/n1 3000000000)
    "n2-int" (s/n2 1)
    "n2-long" (s/n2 (long 1))
    "n2-double" (s/n2 1.5)
    "n3-int" (s/n3 1)
    "n3-long" (s/n3 (long 1))
    "n4-int" (s/n4 1)
    "n5-int" (s/n5 1)
    "n5-null" (s/n5 nil)
    "n6-int" (s/n6 1)
    "n7-int" (s/n7 1)
    "n7-str" (s/n7 "x")
    "n8-int-int" (s/n8 1 2)
    "n8-int-long" (s/n8 1 (long 2))
    "n9-int" (s/n9 1)
    "r1-str" (s/r1 "x")
    "r1-null" (s/r1 nil)
    "r1-int" (s/r1 1)
    "r2-str" (s/r2 "x")
    "r2-sb" (s/r2 (StringBuilder. "x"))
    "g1-str" (s/g1 "x")
    "g1-int" (s/g1 1)
    "g1-null" (s/g1 nil)
    "g2-int-str" (s/g2 1 "x")
    "g2-str-str" (s/g2 "a" "x")
    "g3-str" (s/g3 "x")
    "g3-int" (s/g3 1)
    "g4-int" (s/g4 "x" 1)
    "g4-long" (s/g4 "x" (long 1))
    "v1-one" (s/v1 1)
    "v1-two" (s/v1 1 2)
    "v1-none" (s/v1)
    "v2-one" (s/v2 1)
    "v2-two" (s/v2 1 "x")
    "v2-three" (s/v2 1 "x" "y")
    "v2-named" (s/v2 :a 1)
    "v3-two" (s/v3 "a" "b")
    "v3-three" (s/v3 "a" "b" "c")
    "v3-int" (s/v3 1 2)
    "v4-one" (s/v4 1)
    "v4-two" (s/v4 1 "x")
    "v5-str" (s/v5 "a" "b")
    "v5-mixed" (s/v5 "a" 1)
    "d1-one" (s/d1 1)
    "d1-two" (s/d1 1 2)
    "d1-named" (s/d1 :a 1)
    "d2-none" (s/d2)
    "d2-int" (s/d2 1)
    "d2-str" (s/d2 "x")
    "d2-named-a" (s/d2 :a 1)
    "d2-named-b" (s/d2 :b "x")
    "d2-named-ab" (s/d2 :a 1 :b "x")
    "d2-named-ba" (s/d2 :b "x" :a 1)
    "d2-named-a-long" (s/d2 :a (long 1))
    "d3-one" (s/d3 1)
    "d3-two" (s/d3 1 2)
    "d3-three" (s/d3 1 2 3)
    "d3-named-c" (s/d3 1 :c 3)
    "m1-named-ba" (s/m1 :b "x" :a 1)
    "m1-named-ab" (s/m1 :a "x" :b 1)
    "m1-pos" (s/m1 1 "x")
    "m1-pos-rev" (s/m1 "x" 1)
    "m2-x" (s/m2 :x 1)
    "m2-x-long" (s/m2 :x (long 1))
    "m2-xy" (s/m2 :x 1 :y 2)
    "m2-yx" (s/m2 :y 1 :x 2)
    "m2-pos" (s/m2 1 2)
    "c1-dog-dog" (s/c1 (s/Dog) (s/Dog))
    "c1-animal-dog" (s/c1 (s/Animal) (s/Dog))
    "c1-named" (s/c1 :b (s/Dog) :a (s/Animal))
    "c2-dog" (s/c2 (s/Dog))
    "c2-animal" (s/c2 (s/Animal))
    "c2-str" (s/c2 "x")
    "k1" (s/k1 1 "x")
    "k1-long" (s/k1 (long 1) "x")
    "k2" (s/k2 1 "x")
    "k2-named" (s/k2 :b "x" :a 1)
    "nl-str" (s/nl "x" 1)
    "nl-null" (s/nl nil 1)
    "nl-long" (s/nl "x" (long 1))
    "sh" (s/sh 1 2)
    "ll-list" (s/ll ["a"])
    "w1-one" (s/w1 1)
    "w1-two" (s/w1 1 "x")
    "w1-three" (s/w1 1 "x" 2)
    "h1" (s/h1 (fn [x] x))
    "h2" (s/h2 "x" (fn [x] x))
    "h2-int" (s/h2 1 (fn [x] x))
    "h3" (s/h3 1 (fn [x] x))
    "h3-long" (s/h3 (long 1) (fn [x] x))
    "h4" (s/h4 (fn [a b] (- a b)))
    "h5" (s/h5 "x" (fn [x] x))
    "h5-int" (s/h5 1 (fn [x] x))
    "h6" (s/h6 "x" (fn [x] x))
    "h7" (s/h7 "x" (fn [x] x))})

(defn- kotlin-says [id] (let [a (get @oracle id)] (if (= "AMBIGUOUS" a) :ambiguous a)))

(defn- has-int-literal? [form] (boolean (some #(= :int (lit-kind %)) (rest form))))

(defn- comparison
  "One row for each call and path that can be compared with Kotlin: {:id :path :kotlin :kt}.
  The static and the dynamic path are compared with the call as written. The var as a value takes positional values
  only, and its integers are Longs: a call with an integer literal is compared with the `<id>/v` call of the oracle
  (every integer a Long), and is left out when Kotlin has no candidate for a Long there (NONE: what the library does
  then is its own number conversion, not a choice that Kotlin makes)."
  []
  (for [[id form] (sort-by key calls)
        [path f k] [[:static static-path (kotlin-says id)]
                    [:dynamic dynamic-path (kotlin-says id)]
                    (when (positional? form)
                      [:var var-path (kotlin-says (if (has-int-literal? form) (str id "/v") id))])]
        :when (and path (not= "NONE" k))]
    {:id id :path path :kotlin k :kt (f form)}))

(deftest z4-the-oracle-and-the-table-have-the-same-calls
  (is (= (set (keys calls)) (set (remove #(str/ends-with? % "/v") (keys @oracle)))))
  (is (<= 40 (count calls)))
  (is (<= 20 (count (distinct (map #(first (val %)) calls)))) "overload sets"))

(deftest z4-the-choice-is-kotlins
  (let [rows (comparison)
        bad (remove #(= (:kotlin %) (:kt %)) rows)]
    (println (str "Z4 KOTLIN COMPARISON: " (count calls) " calls of " (count (distinct (map #(first (val %)) calls)))
                  " overload sets, " (count rows) " comparisons (static, dynamic, var as a value); "
                  (- (count rows) (count bad)) " agree, " (count bad) " disagree"))
    (doseq [b bad] (println "  DISAGREE" (pr-str b)))
    (is (= [] (vec bad)))))

(deftest z4-the-two-reported-shapes
  (testing "(a) named arguments that bind to other positions: kotlinc says ambiguity (oracle: oo-named)"
    (is (= :ambiguous (kotlin-says "oo-named")))
    (is (= :ambiguous (static-path '(s/oo :a 1 :b 2))))
    (is (= :ambiguous (dynamic-path '(s/oo :a 1 :b 2)))))
  (testing "(b) not each at least as specific as the other: the default-count tie-break does not apply (oracle: s5-two)"
    (is (= :ambiguous (kotlin-says "s5-two")))
    (is (= :ambiguous (static-path '(s/s5 1 "x"))))
    (is (= :ambiguous (dynamic-path '(s/s5 1 "x"))))))
