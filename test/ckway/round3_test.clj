(ns ckway.round3-test
  "Number rule, hygienic expansions, specificity, type hints, named varargs, hidden members and messages. Every selection test compares the answer of ckway, on the static path (the
  call as written) and on the dynamic path (`ckway.rt/call-dyn` with the same values), with the answer of KOTLIN: a
  `k...` function of `test-fixtures/fx/Round3.kt` makes the same call in Kotlin source."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.resolve :as r]
            [ckway.rt :as rt])
  (:import [java.io StringWriter]))

(kt/require '[fx.r3 :as r3] '[kotlin :as k] '[kotlin.collections :as c] '[kotlin.text :as tx])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round3-test)] (eval form)))

(defn- compile-error
  "Message of the error that compiling/evaluating `form` in this namespace throws, or nil."
  [form]
  (try (eval-here form) nil (catch Throwable t (ct/root-message t))))

(defn- dyn-message
  "The message of the error that the dynamic call of the var `v` throws, or nil."
  [v pos named]
  (some-> (thrown #(rt/call-dyn v pos named {})) ex-message))

(defmacro same
  "`oracle` is the form that evaluates to Kotlin's answer. Assert that the call `(f args...)` gives it on the static
  path and on the dynamic path. An integer literal that fits Int is marked as a literal on the dynamic path, as the
  static path knows it (it chooses between an Int and a Long overload)."
  [oracle f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))
        lits (into {} (keep-indexed (fn [i a] (when (and (integer? a) (<= Integer/MIN_VALUE a Integer/MAX_VALUE)) [i :int]))
                                    pos))]
    `(let [expected# ~oracle]
       (is (= expected# (~f ~@args)) (str "static " '(~f ~@args)))
       (is (= expected# (rt/call-dyn (var ~f) [~@pos] ~nm ~lits)) (str "dynamic " '(~f ~@args))))))

;; ---------------------------------------------------------------- one number rule

(deftest lookups-in-collections-built-in-clojure-hit
  (is (true? (c/.contains (c/listOf 1 2) 1)))
  (is (= 1 (c/.indexOf (c/listOf 1 2) 2)))
  (is (= "a" (c/.get (c/mapOf (k/.to 1 "a")) 1)))
  (is (true? (contains? (c/setOf 1 2) 1)))
  (is (= [2] (vec (c/.minus (c/listOf 1 2) 1))))
  (is (= [Long Long Long] (mapv class (c/.plus (c/listOf 1 2) 3))))
  (is (= [Long Long] (let [f c/listOf] (mapv class (f 1 2)))) "a var used as a value")
  (is (= [Long Long] (mapv class (c/listOf 1 2))) "the written call")
  (is (= [Long Long] (mapv class (rt/call-dyn #'c/listOf [1 2] {} {0 :int 1 :int}))) "the dynamic path, literals marked"))

(deftest any-number-and-type-parameter-take-the-value-as-it-is
  (doseq [[what f] [["Any" #'r3/anyClass] ["Number" #'r3/numClass] ["T" #'r3/tClass]]]
    (testing what
      (is (= "java.lang.Long" (@f 1)) "var as a value")
      (is (= "java.lang.Long" (rt/call-dyn f [1] {} {0 :int})) "dynamic path")
      (is (= "java.lang.Long" (rt/call-dyn f [(long 1)] {})))))
  (is (= "java.lang.Long" (r3/anyClass 1)))
  (is (= "java.lang.Long" (r3/numClass 1)))
  (is (= "java.lang.Long" (r3/tClass 1)))
  (is (= "java.lang.Long" (r3/anyClass 5000000000)))
  (is (= "java.lang.Integer" (r3/anyClass (int 1))) "a value that is an Int already stays one")
  (is (= [Long Long] (mapv class (r3/tList 1 2))))
  (is (= [Long Long] (mapv class (r3/tList 1 (long 2)))))
  (testing "Kotlin itself: a list of Long values holds them, and finds a Long"
    (is (true? (r3/kContains (c/listOf 1 2) 1)))
    (is (= [Long Long] (mapv class (c/listOf 1 2))))))

(deftest the-literal-still-chooses-between-overloads
  (same (r3/kAmb) r3/amb 1)
  (is (= "Int" (r3/amb 1)))
  (is (= "Long" (r3/amb 5000000000)))
  (is (= "Long" (r3/amb (long 1))))
  (is (= "Long" (let [f r3/amb] (f 1))) "a var as a value has no literal: its Long picks the Long overload"))

(deftest type-arguments-still-convert
  (is (= "java.lang.Integer" (r3/tClass 1 :<> Int)))
  (is (= "java.lang.Long" (r3/tClass 1 :<> Long)))
  (is (= [Integer Integer] (mapv class (r3/tList 1 2 :<> Int)))))

(deftest data-made-by-kotlin-with-int-needs-an-int
  (let [m (r3/kotlinIntMap)]
    (is (nil? (c/.get m 1)) "a Clojure integer is a Long: no Long key 1 in a map with Int keys")
    (is (= "a" (c/.get m (int 1))))
    (is (= "a" (c/.get m 1 :<> [Int String])) "or the type arguments")
    (is (false? (c/.contains (r3/kotlinIntList) 1)))
    (is (true? (c/.contains (r3/kotlinIntList) (int 1))))
    (is (true? (c/.contains (r3/kotlinLongList) 1)))))

;; ---------------------------------------------------------------- hygienic expansions

(def ^:private hygiene-forms
  "Forms of kt calls; every symbol is qualified, because the test shadows the unqualified names."
  '[(r3/takesInt 5) (r3/takesInt (clojure.core/long 5)) (r3/takesLong 5) (r3/takesLong (clojure.core/int 5))
    (r3/takesShort 5) (r3/takesByte 5) (r3/takesDouble 5) (r3/takesFloat 2) (r3/takesChar \a) (r3/takesBool true)
    (r3/takesBoxedInt 5) (r3/takesBoxedInt nil) (r3/takesInt (clojure.core/identity 6))
    (r3/withDefaults 1) (r3/withDefaults 1 "x" :c 9) (r3/withDefaults 2 :b "y")
    (r3/spread 1 2 3) (r3/spread) (r3/strs "a" "b") (r3/spread :xs [1 2]) (r3/strs :xs ["p" "q"])
    (r3/meters (r3/mk 5))
    (r3/applyF 5 (clojure.core/fn [x] (clojure.core/inc x)))
    (r3/applyP (clojure.core/fn [a b] (clojure.core/str a b)))
    (r3/useConv (clojure.core/fn [x] (clojure.core/inc x)))
    (r3/susInt 5)
    (r3/typeNameOf :<> Int) (r3/castTo "x" :<> String) (r3/tClass 7 :<> Int)
    (r3/d33 "p" 5) (c/listOf 1 2) (tx/.uppercase "abc") (tx/.toInt "42") (tx/.padStart "a" 3)
    (clojure.core/let [h (r3/Holder 1 "s")] (kt/set! (r3/n h) 9) (r3/n h))
    (clojure.core/let [h (r3/Holder 1 "s")] (kt/set! (r3/n h) (clojure.core/long 9)) (r3/n h))
    (clojure.core/str (kt/ref r3/Pt x))
    (clojure.core/str (kt/ref r3/Pt class))
    (clojure.core/vec (clojure.core/map (kt/ref r3/Pt x) [(r3/Pt 3 "a")]))
    (kt/data (r3/Pt 1 "a"))])

(def ^:private common-names
  '[int long short byte char name count first fn str let if-let when cond do doseq loop recur -> ->> list vector vec
    map filter reduce apply identity object-array into-array array-map assoc get nth seq keyword keyword? instance?
    boolean double float number? class cast ns type meta with-meta format println print throw try catch finally
    reify new var deref symbol resolve eval inc dec + - * < > = not nil? some? true? false? and or not])

(defn- unqualified-core-symbols
  "The unqualified symbols of the form `x` that name something in clojure.core."
  [x]
  (into #{} (comp (filter symbol?) (remove namespace)
                  (filter #(contains? (ns-publics 'clojure.core) %)))
        (tree-seq coll? seq (walk/macroexpand-all x))))

(defn- emitted-forms
  "The forms that the static and the dynamic path emit while `form` is compiled, plus the macroexpansion of
  `kt/set!` and `kt/ref` forms."
  [form]
  (let [out (atom []) emit r/emit dynf r/dynamic-form]
    (with-redefs [r/emit (fn [p] (let [x (emit p)] (swap! out conj x) x))
                  r/dynamic-form (fn [v parsed] (let [x (dynf v parsed)] (swap! out conj x) x))]
      (binding [*warn-on-reflection* false] (eval-here form)))
    (into @out
          (for [f (tree-seq coll? seq form)
                :when (and (seq? f) (symbol? (first f)) (#{"kt/set!" "kt/ref"} (str (first f))))]
            (binding [*ns* (the-ns 'ckway.round3-test)] (macroexpand-1 f))))))

(defn- shadowed
  "`form`, in a `let` that binds each name of `names` to a function that throws."
  [names form]
  `(clojure.core/let [~@(mapcat (fn [n] [n `(clojure.core/fn [~'& ~'_] (throw (ex-info (str "captured " '~n) {})))]) names)]
     ~form))

(defn- result-of [form]
  (let [v (eval-here form)] (if (or (instance? clojure.lang.IPersistentCollection v) (not (coll? v))) v (vec v))))

(deftest no-local-of-the-consumer-is-captured
  (let [emitted (mapcat emitted-forms hygiene-forms)
        used (into #{} (mapcat unqualified-core-symbols) emitted)
        names (vec (sort (set/union used (set common-names))))
        specials '#{if do let* fn* loop* recur throw try catch finally new var set! quote def . & monitor-enter monitor-exit}
        names (vec (remove specials names))]
    (testing "the library names that the emitted code uses are all qualified"
      (is (empty? used) (str "unqualified clojure.core names in emitted code: " (sort used))))
    (doseq [f hygiene-forms]
      (let [plain (result-of f)
            under (try (result-of (shadowed names f)) (catch Throwable t (str "FAILED " (ex-message t))))]
        (is (= (pr-str plain) (pr-str under)) (str f))))
    (testing "the two cases of the review"
      (is (= 5 (let [int inc] (r3/takesInt 5))))
      (is (= 5 (let [long (fn [x] 99)] (r3/takesLong 5))))
      (is (= 5 (let [long 5] (r3/takesLong 5)))))))

;; ---------------------------------------------------------------- widening only when no candidate fits as it is

(defmacro ^:private n3-cases
  "The calls of every overload set (w1 ... w13) with every kind of argument, against the Kotlin function that makes
  the same call with a value of the Kotlin type that the Clojure value has."
  []
  (let [sets (range 1 14)
        kinds [["int" '(clojure.core/int 1)] ["long" '(clojure.core/long 1)] ["short" '(clojure.core/short 1)]
               ["byte" '(clojure.core/byte 1)] ["float" '(clojure.core/float 1.5)] ["double" 1.5]]
        lits [["lit" 1] ["big" 5000000000] ["dlit" 1.5]]
        sy (fn [s] (symbol "r3" s))]
    `(do ~@(for [n sets
                 [kind form] kinds]
             `(same (~(sy (str "kW" n "_" kind)) ~form) ~(sy (str "w" n)) ~form))
         ~@(for [n sets
                 [kind form] lits]
             `(same (~(sy (str "kW" n "_" kind))) ~(sy (str "w" n)) ~form)))))

(deftest widening-is-the-last-resort
  (testing "the example of the review"
    (is (= "Any" (r3/w1 1)))
    (is (= "Double" (r3/w1 1.5))))
  (n3-cases))

;; ---------------------------------------------------------------- specificity respects type arguments

(deftest type-arguments-in-specificity
  (testing "List<T> against Collection<Int>: neither is a subtype of the other, the non-generic one wins"
    (same (r3/kGl [1]) r3/gl [1])
    (is (= "Collection<Int>" (r3/gl [1]))))
  (testing "covariant collections"
    (same (r3/kM1 [1]) r3/m1 [1])
    (same (r3/kM8 [1]) r3/m8 [1])
    (same (r3/kM11 [1]) r3/m11 [1])
    (is (= "List<Int>" (r3/m1 [1]))))
  (testing "MutableList is invariant and a subtype of List"
    (same (r3/kM2 (java.util.ArrayList. [1])) r3/m2 (java.util.ArrayList. [1]))
    (is (= "MutableList<Int>" (r3/m2 (java.util.ArrayList. [1])))))
  (testing "a generic Iterable<T> and a List<String>"
    (same (r3/kM3 ["a"]) r3/m3 ["a"]))
  (testing "two generic declarations: the more specific one wins"
    (same (r3/kM4 [1]) r3/m4 [1])
    (is (= "List<T>" (r3/m4 [1]))))
  (testing "Set<Int> against Collection<T>"
    (same (r3/kM6 (java.util.HashSet. [1])) r3/m6 (java.util.HashSet. [1])))
  (testing "T shared by two parameters"
    (same (r3/kM7 [1] 2) r3/m7 [1] (clojure.core/int 2)))
  (testing "Map<String, Any> and Map<K, V> are one JVM signature: a value cannot show the type arguments, so it is an error"
    (doseq [m [(compile-error '(r3/m12 {"a" 1})) (dyn-message #'r3/m12 [{"a" 1}] {})]]
      (is (str/includes? m "ambiguous") m)))
  (testing "Map and MutableMap"
    (same (r3/kM9 (java.util.HashMap. {"a" (int 1)})) r3/m9 (java.util.HashMap. {"a" (int 1)})))
  (testing "no subtype either way and nothing else to tell them apart: an error, never a guess"
    (doseq [m [(compile-error '(r3/m10 [1])) (dyn-message #'r3/m10 [[1]] {})]]
      (is (some? m))
      (is (str/includes? m "ambiguous") m))))

;; ---------------------------------------------------------------- a hint that the user wrote is the static type

(deftest user-hint-is-the-static-type
  (let [^fx.r3.B b (r3/B)]
    (testing "the review's case: a member mu(String), an extension mu(CharSequence)"
      (is (= "ext" (r3/kMuCs b "s")))
      (is (= "ext" (let [^CharSequence s "s"] (r3/.mu b s))) "a hinted local")
      (is (= "ext" (r3/.mu b ^CharSequence (identity "s"))) "a hinted form")
      (is (= "ext" ((fn [^CharSequence s] (r3/.mu b s)) "s")) "a hinted parameter"))
    (testing "no hint, a String-typed local, ^Object: the member"
      (is (= "member" (r3/kMuStr b "s")))
      (is (= "member" (let [s "s"] (r3/.mu b s))))
      (is (= "member" (let [s (identity "s")] (r3/.mu b s))))
      (is (= "member" (let [^Object s "s"] (r3/.mu b s))) "^Object is unknown")
      (is (= "member" (rt/call-dyn #'r3/.mu [b "s"] {}))))
    (testing "a member that fits wins whatever the hint"
      (is (= (r3/kMvStr b "s") (let [^String s "s"] (r3/.mv b s))))
      (is (= (r3/kMvAny b "s") (let [^Object s "s"] (r3/.mv b s)))))
    (testing "overloads on CharSequence and String"
      (is (= (r3/kCsCs "s") (let [^CharSequence x "s"] (r3/cs x))))
      (is (= (r3/kCsStr "s") (let [x (identity "s")] (r3/cs x))))
      (is (= (r3/kCsStr "s") (let [^String x "s"] (r3/cs x)))))
    (testing "a type that only the compiler inferred stays an upper bound"
      (is (= (r3/kNbLong 3) (loop [x (identity 0)] (if (< x 3) (recur (inc x)) (r3/nb x)))) "a loop variable inferred as Number")
      (is (= ["Long" "Long"] (vec (for [g [inc dec]] (r3/nb (g 5))))) "a function of unknown class")
      (is (= ["Long" "Long"] (let [out (atom [])]
                               (doseq [g [inc dec]] (swap! out conj (r3/nb (g 5))))
                               @out))))))

;; ---------------------------------------------------------------- a named vararg collection

(deftest vararg-collection-chooses-when-the-element-type-is-known
  (is (= (r3/kVaInts) (r3/va :xs (int-array [1 2]))))
  (is (= (r3/kVaStrs) (r3/va :xs (into-array String ["a" "b"]))))
  (is (= (r3/kVaInts) (rt/call-dyn #'r3/va [] {"xs" (int-array [1 2])} {})))
  (is (= (r3/kVaStrs) (rt/call-dyn #'r3/va [] {"xs" (into-array String ["a" "b"])} {})))
  (is (= (r3/kSpStrs) (r3/sp :d ["a" "b"])) "a literal vector of strings")
  (is (= (r3/kSpInts) (r3/sp :d [1 2])) "a literal vector of integers")
  (is (= (r3/kSpInts) (r3/sp :d [1 (clojure.core/int 2)])))
  (is (= "Int:0" (r3/sp :d (int-array 0)))))

(deftest vararg-collection-whose-elements-cannot-be-seen-says-so
  (doseq [m [(compile-error '(clojure.core/let [xs (clojure.core/vec (clojure.core/identity ["a" "b"]))] (r3/sp :d xs)))
             (dyn-message #'r3/sp [] {"d" ["a" "b"]})]]
    (is (str/includes? m "ambiguous") m)
    (is (str/includes? m "element type of the collection") m)
    (is (str/includes? m "positionally") m)
    (is (str/includes? m "typed array") m)
    (is (not (str/includes? m "Add a type hint")) m)))

;; ---------------------------------------------------------------- a hidden member is called as fast as a bridge

(deftest hidden-members-work-and-do-not-use-the-handle-call
  (is (= "ABC" (tx/.uppercase "abc")))
  (is (= 42 (tx/.toInt "42")))
  (is (= "ABC" (rt/call-dyn #'tx/.uppercase ["abc"] {})))
  (let [text (pr-str (emitted-forms '(fn [] (tx/.uppercase "abc") (tx/.toInt "42"))))]
    (is (not (str/includes? text "call-jvm")) text)
    (is (not (str/includes? text "jvm-handle")) text)))

;; ---------------------------------------------------------------- messages

(deftest out-of-range-literal-and-value-say-the-same
  (let [lit (compile-error '(r3/small 5000000000))
        val (ex-message (thrown #(r3/small (identity 5000000000))))
        dyn (ex-message (thrown #(rt/call-dyn #'r3/small [5000000000] {})))]
    (doseq [m [lit val dyn]]
      (is (str/includes? m "5000000000") m)
      (is (str/includes? m "`n`") m)
      (is (str/includes? m "out of range for Int") m)
      (is (not (str/includes? m "no Kotlin declaration")) m)))
  (testing "Short"
    (let [m (compile-error '(r3/tiny 100000))]
      (is (str/includes? m "100000") m)
      (is (str/includes? m "out of range for Short") m))))

(deftest var-as-value-with-a-keyword-says-positional-only-in-the-message-the-user-sees
  (doseq [f [#(apply r3/withDefaults [1 :c 9])
             #(apply r3/takesInt [:x 5])
             #(apply r3/takesInt [:x])
             #(mapv r3/withDefaults [1 :b])
             #(apply r3/strs [:xs ["a"]])]]
    (let [e (thrown f)]
      (when e
        (is (str/includes? (ex-message e) "takes positional arguments only") (ex-message e))))))
