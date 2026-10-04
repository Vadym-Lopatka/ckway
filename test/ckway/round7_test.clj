(ns ckway.round7-test
  "Sixth review: an override and what it overrides are one member whatever their JVM names (W1); generic candidates are
  ordered by unifying the type variables of the less specific one (W2, with the stdlib sweep of `ckway.sweep`); the
  function adapter is a conversion for a value that is no function (W3); function type, Kotlin `fun interface`, Java
  functional interface (W4); what Kotlin source cannot call is no var (W5).

  As in `ckway.round6-test`, a selection test runs the call on the static path, the dynamic path and for the var as a
  value, and compares with KOTLIN: a `k...` function of the fixture, or `target/fixtures/round7-oracle.txt`
  (`bin/kotlin-oracle` on `test-fixtures/oracle/Round7Calls.kt`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.rt :as rt]
            [ckway.sweep :as sweep])
  (:import [java.io StringWriter]))

(kt/require '[fx.r7 :as c] '[fx.r7far :as cf] '[fx.r7s :as s] '[fx.r6 :as p6] '[fx.r6s :as s6]
            '[kotlin.collections :as kc] '[kotlin.text :as tx] '[kotlinx.coroutines.channels :as ch])

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round7-test)] (eval form)))

(defn- outcome [f]
  (try (f)
       (catch Throwable t
         (let [m (str (ct/root-message t))]
           (if (str/includes? m "is ambiguous") :ambiguous [:error (first (str/split-lines m))])))))

(defn- split-call [form]
  (let [[head & args] form
        [pos named] (split-with (complement keyword?) args)]
    [(ns-resolve (the-ns 'ckway.round7-test) head) (vec pos) (mapv vec (partition 2 named))]))

(defn- lit-kind [form]
  (when (integer? form) (if (<= Integer/MIN_VALUE form Integer/MAX_VALUE) :int :long)))

(defn- static-path [form] (outcome #(eval-here form)))

(defn- dynamic-path [form]
  (let [[v pos named] (split-call form)
        lits (into {} (concat (keep-indexed (fn [i f] (when-let [k (lit-kind f)] [i k])) pos)
                              (keep (fn [[k f]] (when-let [l (lit-kind f)] [(name k) l])) named)))]
    (outcome #(rt/call-dyn v (mapv eval-here pos) (apply array-map (mapcat (fn [[k f]] [(name k) (eval-here f)]) named)) lits))))

(defn- positional? [form] (empty? (nth (split-call form) 2)))

(defn- var-path [form]
  (let [[v pos _] (split-call form)]
    (outcome #(apply @v (mapv eval-here pos)))))

(defn- three-paths
  "[static dynamic var-as-a-value] of the call, each through `post` (a function of the result)."
  [form post]
  (let [f (fn [path] (let [r (path form)] (if (or (= :ambiguous r) (and (vector? r) (= :error (first r)))) r (post r))))]
    (cond-> [(f static-path) (f dynamic-path)] (positional? form) (conj (f var-path)))))

(defmacro ^:private all-paths
  "`expected` on the static and the dynamic path, and for the var as a value when the call has no named argument.
  `post` (optional) is applied to each result first."
  ([expected form] `(all-paths ~expected ~form identity))
  ([expected form post]
   `(let [e# ~expected f# '~form rs# (three-paths f# ~post)]
      (is (= (vec (repeat (count rs#) e#)) rs#) (str "static, dynamic, var as a value: " (pr-str f#))))))

(defn- warnings [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

;; ---------------------------------------------------------------- W1

(deftest w1-an-override-with-another-jvm-name-is-the-same-member
  (testing "the result is narrowed to a value class: JVM `amt-<hash>` over `amt`"
    (all-paths (c/kAmt) (c/.amt (c/AmtImpl2)) c/n)
    (all-paths (c/kAmtMid) (c/.amt (c/AmtMid)) c/n)
    (all-paths (c/kAmtLeaf) (c/.amt (c/AmtLeaf)) c/n)
    (all-paths (c/kAmtSrc) (c/.amt (c/amtSrc)) c/n)
    (all-paths (c/kAmtPlain) (c/.amt (c/AmtPlain)))
    (is (= [1 1 3 1 "plain"] [(c/kAmt) (c/kAmtMid) (c/kAmtLeaf) (c/kAmtSrc) (c/kAmtPlain)])))
  (testing "the reviewer's forms"
    (is (= [1 3] (mapv c/n (mapv c/.amt [(c/AmtImpl2) (c/AmtLeaf)]))))
    (is (instance? fx.r7.W (c/.amt (c/AmtImpl2))) "the boxed value class")
    (is (= 3 (let [o (identity (c/AmtLeaf))] (c/n (c/.amt o)))) "an untyped receiver"))
  (testing "a value-class parameter: the override's JVM name has another hash"
    (all-paths (c/kTake) (c/.take (c/AmtImpl2) (c/W 4)) c/n)
    (all-paths (c/kTakeLeaf) (c/.take (c/AmtLeaf) (c/W 4)) c/n)
    (all-paths (c/kTakePlain) (c/.take (c/AmtPlain) (c/W 4)))
    (is (= [5 5 "took4"] [(c/kTake) (c/kTakeLeaf) (c/kTakePlain)])))
  (testing "a suspend override"
    (all-paths (c/kSamt) (c/.samt (c/AmtImpl2)) c/n)
    (all-paths (c/kSamtLeaf) (c/.samt (c/AmtLeaf)) c/n)
    (is (= [2 2] [(c/kSamt) (c/kSamtLeaf)])))
  (testing "a default that the override inherits; the result of the `$default` synthetic of the original is the box"
    (all-paths (c/kDflt) (c/.dflt (c/AmtImpl2)) c/n)
    (all-paths (c/kDflt2) (c/.dflt (c/AmtLeaf) (c/W 2)) c/n)
    (is (= [10 4] [(c/kDflt) (c/kDflt2)])))
  (testing "a property"
    (all-paths (c/kPamt) (c/pamt (c/AmtLeaf)) c/n)
    (is (= 8 (c/kPamt))))
  (testing "a subclass in another package"
    (all-paths (cf/kFarAmt) (cf/.amt (cf/FarAmt)) c/n)
    (all-paths (cf/kFarAmt) (c/.amt (cf/FarAmt)) c/n)
    (all-paths (cf/kFarAmtOver) (cf/.amt (cf/FarAmtOver)) c/n)
    (is (= [1 9] [(cf/kFarAmt) (cf/kFarAmtOver)])))
  (testing "kt/ref"
    (is (= 1 (c/n ((kt/ref c/AmtImpl2 .amt) (c/AmtImpl2)))))
    (is (= 3 (c/n ((kt/ref c/AmtImpl2 .amt) (c/AmtLeaf)))) "virtual")
    (is (= 5 (c/n ((kt/ref c/AmtImpl2 .take) (c/AmtImpl2) (c/W 4))))))
  (testing "the static result type is the narrowed one: no reflection on the result"
    (is (= "" (warnings '(fn [] (.getN (c/.amt (c/AmtImpl2)))))))))

(deftest w1-the-override-relation-is-a-fact-of-the-declaration
  (let [by-owner (fn [v owner] (first (filter #(= owner (:owner %)) (:kt/decls (meta v)))))
        id meta/jvm-member-id]
    (testing "another JVM name, the same member"
      (let [o (by-owner #'c/.amt "fx.r7.AmtSrc2") d (by-owner #'c/.amt "fx.r7.AmtImpl2")
            l (first (filter #(= ".amt" (:var-name %)) (meta/own-declarations fx.r7.AmtLeaf)))]
        (is (not= (:name (:jvm o)) (:name (:jvm d))))
        (is (= #{(id o)} (:overrides d)))
        (is (= #{(id o) (id d)} (:overrides l)) "all levels")
        (is (nil? (:overrides o)))))
    (testing "the type argument of the supertype is put in: put(x: String) overrides Box7<T>.put(x: T)"
      (let [o (by-owner #'c/.put "fx.r7.Box7") d (by-owner #'c/.put "fx.r7.SBox7")]
        (is (= #{(id o)} (:overrides d)))
        (is (= [false true] (mapv :default? (:params d))) "and it has the default of the original")))))

(deftest w1-an-override-that-gives-a-type-argument
  (all-paths (c/kPut) (c/.put (c/SBox7) "a"))
  (all-paths (c/kPutSub) (c/.put (c/SBox7Sub) "a" "u"))
  (all-paths (c/kPutSub) (c/.put (c/SBox7Sub) "a" :tag "u"))
  (all-paths (c/kGet7) (c/.get7 (c/SBox7Sub)))
  (is (= ["sat" "sau" "g"] [(c/kPut) (c/kPutSub) (c/kGet7)])))

(deftest w1-round-6-keep-apart-cases-still-hold
  (testing "other parameter types"
    (is (= "long" (p6/.pm (p6/PC) (long 1))))
    (is (= "int" (p6/.pm (p6/PC) 1))))
  (testing "a property and a function"
    (is (= ["prop" "fun"] [(p6/rv (p6/RC)) (p6/.rv (p6/RC))])))
  (testing "a member and an extension"
    (is (= ["memberx" "extx"] [(p6/.em (p6/EC) "x") (p6/.em (p6/EOther) "x")])))
  (testing "unrelated classes: one member of two interfaces, and a class that has only the name"
    (is (= ["qc" "qx" "tc" "b1"] [(p6/.run2 (p6/QC)) (p6/.run2 (p6/QX)) (p6/.rt (p6/TC)) (p6/.cb (p6/CB))])))
  (testing "two generic interfaces with the same member and other type arguments: two members"
    (all-paths (c/kTwoA) (c/.f2 (c/Two) (int 1)))
    (all-paths (c/kTwoB) (c/.f2 (c/Two) "x"))
    (is (= "a1" (c/.f2 (c/Two) 1)) "an Int literal")
    (is (= ["a1" "bx"] [(c/kTwoA) (c/kTwoB)]))
    (is (vector? (static-path '(c/.f2 (c/Two) 1.5))) "neither member takes a Double: an error, not a call")))

;; ---------------------------------------------------------------- W2, W3, W4: the oracle

(def ^:private oracle
  (delay (into {} (map #(vec (str/split % #"\t")) (str/split-lines (slurp (io/resource "round7-oracle.txt")))))))

(defn- kotlin-says [id] (let [a (get @oracle id)] (if (= "AMBIGUOUS" a) :ambiguous a)))

(def ^:private calls
  "id -> the Clojure form of the Kotlin call with that id in Round7Calls.kt."
  '{"u1-mutable" (s/.u1 (kc/mutableListOf 1 2) (fn [x] true))
    "u1-arraylist" (s/.u1 (java.util.ArrayList. [1 2]) (fn [x] true))
    "u1-set" (s/.u1 (java.util.HashSet. [1 2]) (fn [x] true))
    "u2-mutable" (s/u2 (kc/mutableListOf "a") "b")
    "u2-arraylist" (s/u2 (java.util.ArrayList. ["a"]) "b")
    "u2-set" (s/u2 (java.util.HashSet. ["a"]) "b")
    "u3-mutable" (s/.u3 (java.util.LinkedHashMap. {1 2}))
    "u3-hashmap" (s/.u3 (java.util.HashMap. {1 2}))
    "u4-strings" (s/.u4 (kc/mutableListOf "a"))
    "u4-arraylist" (s/.u4 (java.util.ArrayList. ["a"]))
    "u5-lists" (s/u5 (kc/mutableListOf 1) (kc/mutableListOf 2))
    "u5-list-set" (s/u5 (kc/mutableListOf 1) (java.util.HashSet. [2]))
    "u5-arraylists" (s/u5 (java.util.ArrayList. [1]) (java.util.ArrayList. [2]))
    "u6-mutable" (s/u6 (kc/mutableListOf 1))
    "u6-arraylist" (s/u6 (java.util.ArrayList. [1]))
    "u6-set" (s/u6 (java.util.HashSet. [1]))
    "u7-lists" (s/u7 (kc/mutableListOf 1) (kc/mutableListOf 2))
    "u7-list-set" (s/u7 (kc/mutableListOf 1) (java.util.HashSet. [2]))
    "u8-mutable" (s/.u8 (kc/mutableListOf 1))
    "u8-arraylist" (s/.u8 (java.util.ArrayList. [1]))
    "u8-set" (s/.u8 (java.util.HashSet. [1]))
    "u8-string" (s/.u8 "abc")
    "u8-builder" (s/.u8 (StringBuilder. "abc"))
    "u8-array" (s/.u8 (into-array String ["a"]))
    "u9-mutable" (s/u9 (kc/mutableListOf 1))
    "u9-arraylist" (s/u9 (java.util.ArrayList. [1]))
    "u9-set" (s/u9 (java.util.HashSet. [1]))
    "w3a-list" (s/w3a [1 2])
    "w3a-fn" (s/w3a (fn [x] x))
    "w3b-set" (s/w3b #{1 2})
    "w3b-fn" (s/w3b (fn [x] true))
    "w3c-map" (s/w3c {1 2})
    "w3c-fn" (s/w3c (fn [x] x))
    "w3d-list" (s/w3d [1 2])
    "w3d-set" (s/w3d #{1 2})
    "w3d-fn" (s/w3d (fn [x] true))
    "a1" (s/a1 (fn [x] x))
    "a2" (s/a2 (fn [x] x))
    "a3" (s/a3 (fn [x] x))
    "a4" (s/a4 (fn [x] x))
    "a5" (s/a5 "x" (fn [x] x))
    "a5-int" (s/a5 1 (fn [x] x))
    "a6" (s/a6 "x" (fn [x] x))
    "a7" (s/a7 (fn [x] x))
    "a8" (s/a8 "x" (fn [x] x))
    "a9" (s/a9 "x" (fn [x] x))})

(def ^:private erased-limit
  "Calls where Kotlin chooses by the type argument of a VALUE (`MutableList<String>`: the `T : Comparable<T>` overload)
  and the library cannot: both candidates take the same Kotlin class, the JVM erased what the list holds. The library
  says ambiguous (doc/limits.md, 18)."
  #{"u4-strings" "u4-arraylist"})

(deftest w234-the-oracle-and-the-table-have-the-same-calls
  (is (= (set (keys calls)) (set (keys @oracle)))))

(deftest w234-the-choice-is-kotlins
  (let [rows (for [[id form] (sort-by key calls)
                   [path f] [[:static static-path] [:dynamic dynamic-path] [:var var-path]]]
               {:id id :path path :kotlin (kotlin-says id) :kt (f form)})
        bad (remove #(= (:kotlin %) (:kt %)) rows)
        {limit true other false} (group-by #(contains? erased-limit (:id %)) bad)]
    (println (str "W2-W4 KOTLIN COMPARISON: " (count calls) " calls, " (count rows) " comparisons (static, dynamic, var as a value); "
                  (- (count rows) (count bad)) " agree, " (count limit) " are the erased-type-argument limit (library: ambiguous), "
                  (count other) " disagree"))
    (doseq [b other] (println "  DISAGREE" (pr-str b)))
    (is (= [] (vec other)))
    (is (every? #(= :ambiguous (:kt %)) limit) "the limit is an error, never another choice")
    (is (= 6 (count limit)))))

;; ---------------------------------------------------------------- W2 on the stdlib

(deftest w2-the-reported-calls
  (testing "removeAll / retainAll with a predicate: MutableList<T> before MutableIterable<T>"
    (is (= [true [2 3]] (let [ml (kc/mutableListOf 1 2 3)] [(kc/.removeAll ml (fn [x] (= x 1))) (vec ml)])))
    (is (= [true [2 3]] (let [ml (java.util.ArrayList. [1 2 3])] [(kc/.removeAll ml (fn [x] (= x 1))) (vec ml)])))
    (is (= [true [1]] (let [ml (kc/mutableListOf 1 2 3)] [(kc/.retainAll ml (fn [x] (= x 1))) (vec ml)])))
    (is (= [true [2 3]] (let [ml (java.util.ArrayList. [1 2 3])] [(rt/call-dyn #'kc/.removeAll [ml (fn [x] (= x 1))] {} {}) (vec ml)])) "dynamic")
    (is (= [true [2 3]] (let [ml (java.util.ArrayList. [1 2 3])] [(apply kc/.removeAll [ml (fn [x] (= x 1))]) (vec ml)])) "var as a value")
    (is (= [true #{2 3}] (let [ms (java.util.HashSet. [1 2 3])] [(kc/.removeAll ms (fn [x] (= x 1))) (set ms)])) "a set: MutableIterable"))
  (testing "the declaration is the MutableList one"
    (let [chosen (fn [recv] (:signature (:decl (ckway.resolve/choose ".removeAll" (:kt/decls (meta #'kc/.removeAll))
                                                                     {:positional [{:arg recv :info (ckway.resolve/value-info recv) :idx 0}
                                                                                   {:arg inc :info (ckway.resolve/value-info inc) :idx 1}]
                                                                      :named []}
                                                                     false))))]
      (is (str/includes? (chosen (java.util.ArrayList.)) "MutableList<T>.removeAll(predicate"))
      (is (str/includes? (chosen (java.util.HashSet.)) "MutableIterable<T>.removeAll(predicate")))))

(def ^:private sweep-erased-families
  "Families where Kotlin chooses by the element type of the receiver or by the result type of the lambda, which the JVM
  erases: the library says ambiguous and lists the candidates with their Java interop calls (doc/limits.md, 18)."
  #{".average" ".max" ".maxOf" ".maxOfOrNull" ".maxOrNull" ".min" ".minOf" ".minOfOrNull" ".minOrNull"
    ".replaceFirstChar" ".sum" ".sumOf"})

(def ^:private sweep-read-only-families
  "Families with one overload for `List` and one for `MutableList`: a `listOf` and a Clojure vector are a read-only `List`
  for Kotlin and a java.util.List for the JVM, so the library takes the `MutableList` overload."
  #{".asReversed"})

(deftest w2-stdlib-sweep
  (let [cases (edn/read-string (slurp (io/resource "round7-sweep-cases.edn")))
        kotlin (sweep/kotlin-answers (slurp (io/resource "round7-sweep-oracle.txt")))
        rows (for [cs cases path [:static :dynamic :var]]
               (let [k (kotlin (:id cs)) lib (sweep/choice cs path)]
                 {:case cs :path path :kotlin k :lib lib
                  :class (cond (= :none k) :kotlin-has-no-candidate
                               (= :error k) :kotlin-other-error
                               (= :member k) :kotlin-calls-a-member
                               (= :deferred lib) :deferred
                               (and (= :ambiguous k) (#{:ambiguous :ambiguous-erased} lib)) :agree
                               (sweep/same-method? k lib) :agree
                               (and (map? k) (= :ambiguous-erased lib)) :erased
                               (and (map? k) (map? lib) (#{:listOf :vector} (:recv cs))
                                    (str/includes? (:signature lib) "MutableList<T>.")) :read-only
                               :else :disagree)}))
        n (frequencies (map :class rows))
        families (fn [cls] (set (map (comp :var :case) (filter #(= cls (:class %)) rows))))]
    (println (str "W2 STDLIB SWEEP: " (count (distinct (map (juxt :pkg :var) cases))) " families, " (count cases) " calls, "
                  (count rows) " comparisons (static, dynamic, var as a value); "
                  (n :agree 0) " agree; not comparable: " (n :kotlin-has-no-candidate 0) " Kotlin has no candidate, "
                  (n :kotlin-other-error 0) " other Kotlin error, " (n :kotlin-calls-a-member 0) " Kotlin calls a member; "
                  "known limits: " (n :erased 0) " erased (library: ambiguous), " (n :read-only 0) " read-only List; "
                  (n :deferred 0) " deferred to run time; " (n :disagree 0) " disagree"))
    (doseq [r (filter #(= :disagree (:class %)) rows)]
      (println "  DISAGREE" (pr-str (select-keys (:case r) [:pkg :var :recv]) (mapv :kt (:args (:case r))) (:path r) (:kotlin r) (:lib r))))
    (is (< 250 (count (distinct (map (juxt :pkg :var) cases)))) "the sweep is not empty")
    (is (< 1500 (n :agree 0)))
    (is (zero? (n :disagree 0)))
    ;; `(kc/.first (kc/listOf 1))`: the var also holds the member `ArrayDeque.first()`, and the static type of the
    ;; receiver (a List) does not rule an ArrayDeque out, so the static path leaves the choice to the run time. The
    ;; dynamic row of the same call is compared.
    (is (= #{".first" ".firstOrNull" ".last" ".lastOrNull"} (families :deferred)))
    (is (= sweep-erased-families (families :erased)))
    (is (= sweep-read-only-families (families :read-only)))))

;; ---------------------------------------------------------------- W3

(deftest w3-a-collection-is-a-collection-before-it-is-a-function
  (testing "the reported calls"
    (is (= [true [3]] (let [ml (kc/mutableListOf 1 2 3)] [(kc/.removeAll ml [1 2]) (vec ml)])))
    (is (= [true [1 2]] (let [ml (kc/mutableListOf 1 2 3)] [(kc/.retainAll ml #{1 2}) (vec ml)])))
    (is (= [true [3]] (let [ml (java.util.ArrayList. [1 2 3])] [(rt/call-dyn #'kc/.removeAll [ml [1 2]] {} {}) (vec ml)])) "dynamic")
    (is (= [true [3]] (let [ml (java.util.ArrayList. [1 2 3])] [(apply kc/.removeAll [ml [1 2]]) (vec ml)])) "var as a value"))
  (testing "a value that only the adapter can pass is adapted as before"
    (all-paths "w3e-fn" (s/w3e [1 2]))
    (all-paths "w3e-fn" (s/w3e #{1}))
    (all-paths "w3e-fn" (s/w3e {1 2}))
    (is (= [1 2] (let [k :a] (kc/.map [{:a 1} {:a 2}] k))) "a keyword")
    (is (= [:a :b] (kc/.map [1 2] {1 :a 2 :b})) "a map")
    (is (= [:x :y] (kc/.map [0 1] [:x :y])) "a vector")
    (is (= [2 3] (kc/.map [1 2] #'inc)) "a var")
    (is (= [1 2] (rt/call-dyn #'kc/.map [[{:a 1} {:a 2}] :a] {} {})) "dynamic")
    (is (= [1 2] (apply kc/.map [[{:a 1} {:a 2}] :a])) "var as a value"))
  (testing "a real function is no conversion: a function type is taken though the function is a java.util.Comparator"
    (all-paths "h42" (s6/h4 (fn [a b] (- a b))))))

;; ---------------------------------------------------------------- W5

(defn- has-var? [a n] (some? (ns-resolve (the-ns 'ckway.round7-test) (symbol a n))))

(deftest w5-what-kotlin-source-cannot-call-is-no-var
  (testing "@Deprecated(level = HIDDEN)"
    (all-paths (c/kHid) (c/hid))
    (is (= "visible" (c/kHid)))
    (is (= ["fun hid(a: Int = ..., b: Int = ...): String"] (mapv :signature (:kt/decls (meta #'c/hid)))))
    (is (not (str/includes? (:doc (meta #'c/hid)) "hid(a: Int = ...): String")) "not in the doc")
    (is (not (has-var? "c" "onlyHidden")))
    (is (not (has-var? "c" "hiddenProp")))
    (is (not (has-var? "c" ".hiddenMember")))
    (is (not (has-var? "c" "hiddenMemberProp")))
    (is (not (has-var? "c" "HiddenClass7")))
    (is (= ["class Vis7()"] (mapv :signature (:kt/decls (meta #'c/Vis7)))) "a hidden constructor"))
  (testing "not public"
    (is (not (has-var? "c" "pubApi")) "@PublishedApi internal")
    (is (not (has-var? "c" "internalFun")))
    (is (not (has-var? "c" "privateFun")))
    (is (not (has-var? "c" ".protectedFun")))
    (is (not (has-var? "c" "protectedProp")))
    (is (not (has-var? "c" ".internalMember")))
    (is (not (has-var? "c" ".privateMember"))))
  (testing "what Kotlin source CAN call stays"
    (all-paths (c/kSynth) (c/synth))
    (is (= ["synth" "since" "optin" "warned" "errored"] [(c/synth) (c/since) (c/optIn) (c/warned) (c/errored)])
        "@JvmSynthetic, @SinceKotlin, @RequiresOptIn, @Deprecated WARNING and ERROR")
    (is (= [4 8 9] [(c/.publicMember (c/Vis7)) (c/publicProp (c/Vis7)) (c/.synthMember (c/Vis7))])))
  (testing "a real library: the hidden `Channel(capacity)` of kotlinx.coroutines, the stdlib's old `maxBy` (hiddenSince)"
    (is (= 1 (count (filter #(str/includes? (:signature %) "fun <E> Channel(") (:kt/decls (meta #'ch/Channel))))))
    (is (some? (ch/Channel)))
    (is (= 2 (kc/.maxBy [1 2] (fn [x] x))))
    (is (= \c (tx/.max "abc")))))

(deftest w5-invariant-no-hidden-declaration-in-a-var
  (doseq [pkg ["fx.r7" "kotlin" "kotlin.collections" "kotlin.text" "kotlin.sequences" "kotlinx.coroutines" "kotlinx.coroutines.channels"]
          [vn ds] (meta/package-index pkg)
          d ds
          :let [m (:jvm d)]
          :when (and (#{:function} (:kind d)) (:class m) (:name m) (:desc m))]
    (let [^Class cl (try (Class/forName (:class m) false (clojure.lang.RT/baseLoader)) (catch Throwable _ nil))
          ^java.lang.reflect.Method jm (when cl (first (filter #(and (= (:name m) (.getName ^java.lang.reflect.Method %))
                                                                     (= (:desc m) (.toMethodDescriptorString (java.lang.invoke.MethodType/methodType (.getReturnType ^java.lang.reflect.Method %) (.getParameterTypes ^java.lang.reflect.Method %)))))
                                                               (try (.getDeclaredMethods cl) (catch Throwable _ nil)))))
          dep (some-> jm (.getAnnotation kotlin.Deprecated))
          since (some-> jm (.getAnnotation kotlin.DeprecatedSinceKotlin))]
      (when (or (and dep (= kotlin.DeprecationLevel/HIDDEN (.level ^kotlin.Deprecated dep)))
                (and since (not (str/blank? (.hiddenSince ^kotlin.DeprecatedSinceKotlin since))) (.isSynthetic jm)))
        (is false (str pkg " " vn " " (:signature d)))))))
