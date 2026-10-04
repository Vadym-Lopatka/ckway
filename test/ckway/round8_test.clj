(ns ckway.round8-test
  "Seventh review: two candidates are not ordered on what a collection holds (V1); a Clojure persistent collection is
  read-only (V2); a declaration with `@Deprecated(level = ERROR)` is no var (V3); the documented behaviours of V4.

  As in `ckway.round6-test`: the static path, the dynamic path and the var as a value, compared with KOTLIN
  (`target/fixtures/round8-oracle.txt`, written by `bin/kotlin-oracle` from `test-fixtures/oracle/Round8Calls.kt`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.rt :as rt]))

(kt/require '[fx.r8 :as d] '[kotlin.collections :as kc])

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round8-test)] (eval form)))

(defn- outcome [f]
  (try (f)
       (catch Throwable t
         (let [m (str (ct/root-message t))]
           (cond (and (str/includes? m "is ambiguous") (str/includes? m "what a collection holds")) :ambiguous-erased
                 (str/includes? m "is ambiguous") :ambiguous
                 :else [:error (str (.getName (class t)) " " (first (str/split-lines m)))])))))

(defn- split-call [form]
  (let [[head & args] form
        [pos named] (split-with (complement keyword?) args)]
    [(ns-resolve (the-ns 'ckway.round8-test) head) (vec pos) (mapv vec (partition 2 named))]))

(defn- static-path [form] (outcome #(eval-here form)))
(defn- dynamic-path [form]
  (let [[v pos _] (split-call form)
        lits (into {} (keep-indexed (fn [i f] (when (integer? f) [i :int])) pos))]
    (outcome #(rt/call-dyn v (mapv eval-here pos) {} lits))))
(defn- var-path [form]
  (let [[v pos _] (split-call form)] (outcome #(apply @v (mapv eval-here pos)))))

(defn- three-paths [form] [(static-path form) (dynamic-path form) (var-path form)])

(def ^:private oracle
  (delay (into {} (map #(vec (str/split % #"\t")) (str/split-lines (slurp (io/resource "round8-oracle.txt")))))))

;; ---------------------------------------------------------------- V1

(def ^:private unseen
  "id of the oracle -> the Clojure call. Each pair of ids is ONE Clojure shape with two things in the collection:
  Kotlin gives two different answers, by a type argument that the JVM erased."
  '{"u9-ints" (d/u9 (java.util.ArrayList. [1]))
    "u9-strings" (d/u9 (java.util.ArrayList. ["a"]))
    "mxc-ints" (d/mxc [1])
    "mxc-lists" (d/mxc [[1]])
    "ks-strings" (d/ks (java.util.HashMap. {"a" 1}))
    "ks-lists" (d/ks (java.util.HashMap. {[1] 1}))
    "nn-ints" (d/nn [1])
    "nn-null" (d/nn [1 nil])
    "lk-same" (d/lk (java.util.ArrayList. [1]) (java.util.ArrayList. [2]))
    "lk-other" (d/lk (java.util.ArrayList. [1]) (java.util.ArrayList. ["a"]))
})

(def ^:private concrete
  "NOT fixed (the seventh review, V1, stopped here): a candidate with a CONCRETE type argument (`Collection<String>`,
  `MutableList<Int>`) against a generic one. Kotlin answers by what the collection holds; the library takes the
  non-generic candidate for every value, as `ckway.round3-test/type-arguments-in-specificity` asserts for
  `gl(xs: List<T>)` / `gl(xs: Collection<Int>)` with `[1]`."
  '{"two-ints" (d/two [1] [2])
    "two-string-int" (d/two ["a"] [1])
    "cg-ints" (d/cg (java.util.ArrayList. [1]))
    "cg-strings" (d/cg (java.util.ArrayList. ["a"]))})

(def ^:private seen
  "Controls: the order of the candidates rests on the class of the value, or on the value itself."
  '{"fr-list" (d/.fr (java.util.ArrayList. [1]) (fn [x] true))
    "fr-set" (d/.fr (java.util.HashSet. [1]) (fn [x] true))
    "sm-list" (d/sm (java.util.ArrayList. ["a"]) "b")
    "sm-set" (d/sm (java.util.HashSet. ["a"]) "b")
    "cs-list" (d/cs (java.util.ArrayList. ["a"]))
    "cs-set" (d/cs (java.util.HashSet. ["a"]))
    "tb-int" (d/tb (int 1))
    "tb-string" (d/tb "a")})

(def ^:private read-only
  '{"iv-readonly" (d/iv [1])
    "iv-mutable" (d/iv (java.util.ArrayList. [1]))
    "iv-set" (d/iv #{1})
    "im-readonly" (d/im {1 2})
    "im-mutable" (d/im (java.util.HashMap. {1 2}))
    "is-readonly" (d/ist #{1})
    "is-mutable" (d/ist (java.util.HashSet. [1]))})

(deftest v-the-oracle-and-the-tables-have-the-same-calls
  (is (= (set (keys @oracle))
         (into #{"iv-kt-readonly" "iv-kt-mutable" "u9-targ-int" "mxc-targ-int"} (mapcat keys [unseen concrete seen read-only])))))

(deftest v1-no-order-on-what-a-collection-holds
  (testing "Kotlin's answer depends on the type argument: the same Clojure shape, two answers"
    (doseq [[a b] (partition 2 (sort (keys unseen)))]
      (is (not= (@oracle a) (@oracle b)) (str a " " b))))
  (testing "the library says so: ambiguous, with the reason and the way out, on the three paths"
    (doseq [[id form] (sort-by key unseen)]
      (is (= [:ambiguous-erased :ambiguous-erased :ambiguous-erased] (three-paths form)) (str id " " (pr-str form)))))
  (testing "the message"
    (let [m (try (eval-here '(d/u9 (java.util.ArrayList. ["a"]))) (catch Throwable t (ct/root-message t)))]
      (is (str/includes? m "The JVM erases that") m)
      (is (str/includes? m ":<>") m)
      (is (str/includes? m "(fx.r8.Round8Kt/u9 x)") "the Java interop call of each candidate"))))

(deftest v1-type-arguments-that-are-given-are-used
  (is (= (@oracle "u9-targ-int") (d/u9 (java.util.ArrayList. [1]) :<> Int)))
  (is (= "u9-any" (d/u9 (java.util.ArrayList. ["a"]) :<> String)) "String is no Number: the bounded overload is no candidate")
  (is (= (@oracle "mxc-targ-int") (d/mxc [1] :<> Int)))
  (is (= "two-TT" (d/two [1] [2] :<> Int)))
  (is (= ["u9-number" "mxc-cmp"] [(@oracle "u9-targ-int") (@oracle "mxc-targ-int")])))

(deftest v1-what-can-be-seen-still-orders
  (let [rows (for [[id form] (sort-by key seen) [path r] (map vector [:static :dynamic :var] (three-paths form))]
               {:id id :path path :kotlin (@oracle id) :kt r})
        bad (remove #(= (:kotlin %) (:kt %)) rows)]
    (println (str "V1-V2 KOTLIN COMPARISON (what can be seen): " (+ (count seen) (count read-only)) " calls; controls "
                  (- (count rows) (count bad)) " of " (count rows) " agree"))
    (is (= [] (vec bad))))
  (testing "the stdlib pair of the sixth review"
    (is (= [true [2 3]] (let [ml (java.util.ArrayList. [1 2 3])] [(kc/.removeAll ml (fn [x] (= x 1))) (vec ml)])))))

(deftest v1-open-a-concrete-type-argument-still-decides
  (is (= ["two-TT" "two-SI" "cg-int" "cg-generic"] (mapv @oracle ["two-ints" "two-string-int" "cg-ints" "cg-strings"])) "Kotlin: two answers each")
  (is (= ["two-SI" "two-SI"] [(d/two [1] [2]) (d/two ["a"] [1])]) "the library: the non-generic one, whatever the lists hold")
  (is (= ["cg-int" "cg-int"] [(d/cg (java.util.ArrayList. [1])) (d/cg (java.util.ArrayList. ["a"]))])))

;; ---------------------------------------------------------------- V2

(deftest v2-a-persistent-collection-is-read-only
  (let [rows (for [[id form] (sort-by key read-only) [path r] (map vector [:static :dynamic :var] (three-paths form))]
               {:id id :path path :kotlin (@oracle id) :kt r})
        bad (remove #(= (:kotlin %) (:kt %)) rows)]
    (is (= [] (vec bad)))
    (is (= 21 (count rows))))
  (testing "a list and a lazy seq too"
    (is (= "iv-iterable" (d/iv '(1)) (d/iv (map inc [1])))))
  (testing "the result of a kt call that Kotlin declares read-only (static path: the declared type is known)"
    (is (= (@oracle "iv-kt-readonly") (d/iv (d/readOnly))))
    (is (= (@oracle "iv-kt-mutable") (d/iv (d/mutableOne))))
    (is (= ["iv-iterable" "iv-mutable"] [(@oracle "iv-kt-readonly") (@oracle "iv-kt-mutable")])))
  (testing "only a Mutable* candidate: the call goes through, and a mutation throws"
    (is (= 2 (d/onlyMutable (java.util.ArrayList. [1]))))
    (is (str/includes? (str (static-path '(d/onlyMutable [1]))) "UnsupportedOperationException"))
    (is (str/includes? (str (dynamic-path '(d/onlyMutable [1]))) "UnsupportedOperationException")))
  (testing "the stdlib pairs"
    (is (= "kotlin.collections.ReversedListReadOnly" (.getName (class (kc/.asReversed [1 2 3])))) "List.asReversed")
    (is (= "kotlin.collections.ReversedList" (.getName (class (kc/.asReversed (java.util.ArrayList. [1 2 3]))))) "MutableList.asReversed")
    (is (= {1 2} (kc/.withDefault {1 2} (fn [k] 0))))
    (is (str/includes? (str (static-path '(kc/.removeAll [1 2 3] [1]))) "UnsupportedOperationException")
        "removeAll has only Mutable* receivers: the Collection overload is called (not the predicate one), Java refuses the change")))

;; ---------------------------------------------------------------- V3

(defn- has-var? [n] (some? (ns-resolve (the-ns 'ckway.round8-test) (symbol "d" n))))

(deftest v3-error-deprecated-declarations-are-no-vars
  (is (not (has-var? "onlyError")))
  (is (not (has-var? "errorProp")))
  (is (not (has-var? ".errorMember")))
  (is (not (has-var? "errorMemberProp")))
  (is (not (has-var? "ErrorClass8")))
  (is (= ["class Dep8()"] (mapv :signature (:kt/decls (meta #'d/Dep8)))))
  (is (= ["fun err8(a: Int = ..., b: Int = ...): String"] (mapv :signature (:kt/decls (meta #'d/err8)))))
  (is (= "visible" (d/kErr8) (d/err8 1 2)))
  (is (= ["warned" 7] [(d/warned8) (d/.fine (d/Dep8))]) "a WARNING stays")
  (testing "the stdlib: `MutableList.sort(comparison)` and `sort(comparator)` (errorSince 1.x: their bodies only throw)"
    (is (not-any? #(re-find #"MutableList<T>\.sort\((comparison|comparator)" (:signature %)) (:kt/decls (meta #'kc/.sort))))
    (is (= [1 2 3] (let [al (java.util.ArrayList. [3 1 2])] (kc/.sort al) (vec al))) "the sort that Kotlin has")))

;; ---------------------------------------------------------------- V4

(deftest v4-documented-behaviours
  (testing "a set or a map as a predicate: no Clojure truthiness"
    (is (str/includes? (str (static-path '(kc/.filter [1 2 3] #{1 2}))) "expected a Boolean"))
    (is (str/includes? (str (static-path '(kc/.any [1 2] #{2}))) "nil where Kotlin expects a non-null Boolean"))
    (is (= [1 2] (kc/.filter [1 2 3] (fn [x] (contains? #{1 2} x))))))
  (testing "an inferred type is an upper bound: the member of the run-time class wins; a hint is the static type"
    (is (= "ext" (d/kShow)))
    (is (= "member" (d/.show (d/e1))))
    (is (= "ext" (d/.show ^fx.r8.E1 (d/e1))))))
