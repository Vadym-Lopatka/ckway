(ns ckway.sweep
  "The stdlib sweep of the sixth review (W2): every family of extension functions of `kotlin.collections`,
  `kotlin.sequences` and `kotlin.text` that has the same name on several of the receivers Iterable, Collection, List,
  MutableIterable, MutableCollection, MutableList, Sequence, Array, CharSequence, String is called with an ArrayList, a
  `mutableListOf`, a `listOf`, a Clojure vector and a String, with arguments made from the parameters of each overload,
  and the declaration that the library selects is compared with the one that KOTLIN selects for a receiver of the same
  static type.

  Kotlin's answer: `-main` (run by bin/build-fixtures) writes one Kotlin function for each call,
  `fun k17(r: MutableList<Int>, a0: (Int) -> Boolean): Any? = r.removeAll(a0)`, and the list of the calls
  (`round7-sweep-cases.edn`); bin/kotlin-sweep-oracle compiles the functions with `-Xno-inline` and reads from the
  byte code (`javap -c`) which JVM method each one calls (`round7-sweep-oracle.txt`). Nothing is run.

  The library's answer: `choice`, the declaration that `ckway.resolve/choose` selects on the static path (the forms),
  the dynamic path (the values, integer literals marked) and for the var as a value (the values). Nothing is called."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.resolve :as r]))

(kt/require '[kotlin.collections :as kc] '[kotlin.sequences :as ks] '[kotlin.text :as tx])

(def packages ["kotlin.collections" "kotlin.sequences" "kotlin.text"])

(def ^:private family-receivers
  #{"kotlin/collections/Iterable" "kotlin/collections/Collection" "kotlin/collections/List"
    "kotlin/collections/MutableIterable" "kotlin/collections/MutableCollection" "kotlin/collections/MutableList"
    "kotlin/sequences/Sequence" "kotlin/Array" "kotlin/CharSequence" "kotlin/String"})

(def receivers
  "name -> the Kotlin static type, the element type of its arguments, the Clojure form. A vector and a `listOf` are both
  a Kotlin `List<Int>`."
  (array-map
   :arrayList {:kt "java.util.ArrayList<Int>" :elem :int :clj '(java.util.ArrayList. [1 2 3])}
   :mutableListOf {:kt "MutableList<Int>" :elem :int :clj '(kc/mutableListOf 1 2 3)}
   :listOf {:kt "List<Int>" :elem :int :clj '(kc/listOf 1 2 3)}
   :vector {:kt "List<Int>" :elem :int :clj '[1 2 3]}
   :string {:kt "String" :elem :char :clj "abc"}
   :sequence {:kt "kotlin.sequences.Sequence<Int>" :elem :int :clj '(ks/sequenceOf 1 2 3)}))

(def ^:private simple
  "kind -> [Kotlin type, Clojure form]"
  {:int ["Int" 1] :long ["Long" '(long 1)] :bool ["Boolean" true] :char ["Char" \a] :string ["String" "a"]
   :double ["Double" 1.5] :unit ["Unit" nil]})

(def ^:private class-kinds
  {"kotlin/Int" :int "kotlin/Long" :long "kotlin/Boolean" :bool "kotlin/Char" :char "kotlin/String" :string
   "kotlin/CharSequence" :string "kotlin/Double" :double "kotlin/Unit" :unit})

(defn- simple-kind
  "The kind of the Kotlin type `t` as an argument, or nil: a type parameter (and `Any`) is the element type."
  [t elem]
  (cond (:fn-type t) nil
        (:type-param t) elem
        (= "kotlin/Any" (:class t)) elem
        :else (class-kinds (:class t))))

(defn- arg-of
  "{:kt Kotlin type :clj Clojure form} of an argument for a parameter of type `t`, or nil when this sweep has no value
  for it."
  [t elem]
  (cond
    (:fn-type t)
    (let [f (:fn-type t)
          ps (map #(simple-kind % elem) (:args f))
          ret (simple-kind (:return f) elem)]
      (when (and (not (:suspend? f)) (not (:receiver? f)) (every? some? ps) ret (not (:nullable? t)))
        {:kt (str "(" (str/join ", " (map #(first (simple %)) ps)) ") -> " (first (simple ret)))
         :clj (list 'fn ['& '_] (second (simple ret)))}))
    (contains? #{"kotlin/collections/Iterable" "kotlin/collections/Collection" "kotlin/collections/List"} (:class t))
    (when-let [k (some-> (first (:args t)) (simple-kind elem))]
      (when-not (= :unit k)
        {:kt (str "List<" (first (simple k)) ">") :clj [(second (simple k))]}))
    :else (when-let [k (simple-kind t elem)]
            (when-not (= :unit k) {:kt (first (simple k)) :clj (second (simple k))}))))

(defn- extension? [d]
  (and (= :function (:kind d)) (= [:extension] (mapv :role (:receivers d)))))

(defn- arg-tuples
  "The argument lists that the overloads of a family give for a receiver with elements `elem`: for each overload, an
  argument for each parameter that has no default (a `vararg` gets none), when the sweep has a value for all of them."
  [decls elem]
  (->> decls
       ;; the overloads on the receivers that this sweep is about (an overload on DoubleArray gives `(Double) -> Boolean`,
       ;; which no list of Int takes)
       (filter #(contains? (if (= :char elem) #{"kotlin/CharSequence" "kotlin/String"} (disj family-receivers "kotlin/CharSequence" "kotlin/String"))
                           (:class (:type (first (:receivers %))))))
       (keep (fn [d]
               (let [ps (remove #(or (:default? %) (:vararg? %)) (:params d))
                     as (map #(arg-of (:type %) elem) ps)]
                 (when (and (every? some? as) (not-any? :reified? (:type-params d))) (vec as)))))
       distinct
       (sort-by #(mapv :kt %))
       (take 6)))

(defn families
  "[package var-name declarations] of the families of the sweep, in a fixed order."
  []
  (for [pkg packages
        [vn ds] (meta/package-index pkg)
        :let [ext (filter extension? ds)]
        ;; (a var can also hold members of a Kotlin class of the package: `ArrayDeque.removeAll`)
        :when (and (str/starts-with? vn ".")
                   (< 1 (count (filter #(family-receivers (:class (:type (first (:receivers %))))) ext))))]
    [pkg vn (vec ds)]))

(defn cases
  "The calls of the sweep: {:id :pkg :var :recv :args [{:kt :clj}]}."
  []
  (map-indexed
   (fn [i c] (assoc c :id i))
   (for [[pkg vn ds] (families)
         [rname {:keys [elem]}] receivers
         ;; each package with the receivers that its functions are for
         :when (contains? ({"kotlin.collections" #{:arrayList :mutableListOf :listOf :vector} "kotlin.sequences" #{:sequence}
                            "kotlin.text" #{:string}} pkg)
                          rname)
         args (arg-tuples ds elem)]
     {:pkg pkg :var vn :recv rname :args args})))

(defn- kotlin-source [cs]
  (str "// Generated by ckway.sweep (bin/build-fixtures). One call per line; see test/ckway/sweep.clj.\n"
       "@file:Suppress(\"UNUSED_PARAMETER\")\npackage fx.sweep\n\n"
       (str/join "\n"
                 (for [{:keys [id var recv args]} cs]
                   (str "fun k" id "(r: " (:kt (receivers recv))
                        (apply str (map-indexed (fn [i a] (str ", a" i ": " (:kt a))) args))
                        "): Any? = r." (subs var 1) "(" (str/join ", " (map #(str "a" %) (range (count args)))) ")")))
       "\n"))

(defn -main [kt-file edn-file]
  (let [cs (vec (cases))]
    (io/make-parents kt-file)
    (spit kt-file (kotlin-source cs))
    (spit edn-file (binding [*print-length* nil *print-level* nil] (pr-str cs)))
    (println "ckway.sweep:" (count (distinct (map (juxt :pkg :var) cs))) "families," (count cs) "calls")))

;; ---------------------------------------------------------------- the library's answer

(defn- forms [{:keys [recv args]}] (vec (cons (:clj (receivers recv)) (map :clj args))))

(defn- jvm-key
  "The JVM methods that a call of the declaration `d` can be: the member itself, and its `$default` synthetic (Kotlin
  calls that one when the call leaves a default out)."
  [d]
  ;; a function of a multi-file class part: Kotlin calls it through the facade (the `:owner`)
  (let [m (:jvm d) dm (:default m)]
    {:owners (set (remove nil? [(:class m) (:call-class m) (:owner d) (:class dm) (:call-class dm)]))
     :methods (set (remove nil? [[(:name m) (:desc m)] (when dm [(:name dm) (:desc dm)])]))
     :signature (:signature d)}))

(defn- outcome [f]
  (try (let [res (f)] (if (:dynamic? res) :deferred (jvm-key (:decl res))))
       (catch clojure.lang.ExceptionInfo e
         (let [m (str (ex-message e))]
           (cond (or (str/includes? m "take the same JVM parameter types") (str/includes? m "what a collection holds")) :ambiguous-erased
                 (str/includes? m "is ambiguous") :ambiguous
                 (:kt/no-fit (ex-data e)) :no-fit
                 :else [:error (first (str/split-lines m))])))))

(defn choice
  "What the library selects for the case on `path` (:static, :dynamic, :var): {:owners :name :desc} of the JVM method of
  the declaration, :ambiguous (:ambiguous-erased when the error says that the candidates differ only in what the JVM
  erases), :no-fit, or :deferred (the static path leaves the choice to the run time)."
  [c path]
  (binding [*ns* (the-ns 'ckway.sweep)]
    (let [v (ns-resolve *ns* (symbol ({"kotlin.collections" "kc" "kotlin.sequences" "ks" "kotlin.text" "tx"} (:pkg c)) (:var c)))
          decls (:kt/decls (meta v))
          fs (forms c)]
      (if (= :static path)
        (outcome #(r/choose (:var c) decls (r/parse-args (:var c) decls fs (fn [f] {:arg f :info (r/form-info nil f)})) true))
        (let [vals (mapv eval fs)
              lit (fn [i f] (when (and (= :dynamic path) (integer? f)) {:lit :int :val f}))
              parsed {:positional (vec (map-indexed (fn [i x] {:arg x :info (merge (r/value-info x) (lit i (nth fs i))) :idx i}) vals))
                      :named []}]
          (outcome #(r/choose (:var c) decls parsed false)))))))

(defn kotlin-answers
  "{id answer} from the oracle file: {:owners #{class} :name :desc} for an extension that Kotlin calls, :member when it
  calls a member of the receiver (a Java or built-in member is no var), :ambiguous, :none (no candidate for these static
  types), :error (another compiler error)."
  [text]
  (into {}
        (for [line (str/split-lines text)
              :let [[id kind target] (str/split line #"\t")]]
          [(parse-long id)
           (case kind
             "STATIC" (let [[_ owner nm desc] (re-matches #"([^.]+)\.([^:]+):(.*)" target)]
                        {:owners #{(str/replace owner "/" ".")} :name nm :desc desc})
             "MEMBER" :member "AMBIGUOUS" :ambiguous "NONE" :none :error)])))

(defn same-method? [k lib]
  (and (map? k) (map? lib) (contains? (:methods lib) [(:name k) (:desc k)])
       (boolean (some (:owners lib) (:owners k)))))
