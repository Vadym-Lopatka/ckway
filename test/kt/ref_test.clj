(ns kt.ref-test
  "Step 6, R9: `(kt/ref X y)` and `(kt/ref y)`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kt.call-test :as ct :refer [expansions]]
            [kt.core :as kt]
            [kt.ref :as ref]
            [kt.resolve :as r])
  (:import [kotlin.jvm.functions Function0 Function1 Function2 Function3]
           [kotlin.reflect KClass KFunction KProperty0 KProperty1 KMutableProperty0 KMutableProperty1]))

(kt/require '[fx :as f] '[fx.other :as o])

(def ^:private person (f/Person 1 "Ann"))

(defn- msg [form] (ct/compile-error form))

;; ---------------------------------------------------------------- there is no kotlin-reflect here

(deftest no-kotlin-reflect-on-this-class-path
  (testing "the default test class path has none: everything below works without it"
    (is (thrown? ClassNotFoundException (Class/forName "kotlin.reflect.full.KClasses")))
    (is (thrown? ClassNotFoundException (Class/forName "kotlin.reflect.jvm.internal.ReflectionFactoryImpl")))
    (is (thrown? Throwable (.getGetter ^KProperty1 (kt/ref f/Person firstName)))
        "the reflection that needs kotlin-reflect fails; `name`, `get`, `set` do not need it")))

;; ---------------------------------------------------------------- X::class

(deftest class-references
  (testing "a kt class, a Kotlin default name, a Java class, an object"
    (let [c (kt/ref f/Person class)]
      (is (instance? KClass c))
      (is (= fx.Person (.getJClass ^kotlin.jvm.internal.ClassBasedDeclarationContainer c)))
      (is (= "fx.Person" (f/className c))))
    (is (= "java.lang.String" (f/className (kt/ref String class))))
    (is (= "int" (f/className (kt/ref Int class))) "Int::class is the primitive class, as in Kotlin")
    (is (= "java.lang.Object" (f/className (kt/ref Any class))))
    (is (= "java.util.List" (f/className (kt/ref List class))))
    (is (= "java.util.ArrayList" (f/className (kt/ref java.util.ArrayList class))))
    (is (= "fx.Registry" (f/className (kt/ref f/Registry class))) "an object is a type for ::class")
    (is (= "fx.Color" (f/className (kt/ref f/Color class))))
    (is (= "fx.Outer$Inner" (f/className (kt/ref f/Outer.Inner class))))
    (is (= "fx.Uid" (f/className (kt/ref f/Uid class)))))
  (testing "equal to the reference that Kotlin makes"
    (is (= (f/kPersonClass) (kt/ref f/Person class)))
    (is (= (hash (f/kPersonClass)) (hash (kt/ref f/Person class))))))

;; ---------------------------------------------------------------- X::property

(deftest property-references
  (let [p (kt/ref f/Person firstName)]
    (testing "a KProperty1 (val): name, get, invoke, not mutable"
      (is (instance? KProperty1 p))
      (is (not (instance? KMutableProperty1 p)))
      (is (= "firstName" (.getName ^KProperty1 p)))
      (is (= "Ann" (.get ^KProperty1 p person)))
      (is (= "Ann" (.invoke ^Function1 p person)) "a property reference is also (T) -> V")
      (is (= "Ann" (f/readProp p person)))
      (is (= "firstName" (f/propName p)))
      (is (false? (f/isMutableRef p))))
    (testing "equal to the one that kotlinc makes: owner, name, JVM signature"
      (let [k (f/kPersonFirstName)]
        (is (= k p))
        (is (= (hash k) (hash p)))
        (is (= (.getSignature ^kotlin.jvm.internal.CallableReference k) (.getSignature ^kotlin.jvm.internal.CallableReference p))))))
  (testing "a var is a KMutableProperty1; Kotlin APIs take it"
    (let [p (kt/ref f/Account owner) a (f/Account "x" 1)]
      (is (instance? KMutableProperty1 p))
      (is (true? (f/isMutableRef p)))
      (f/writeProp p a "y")
      (is (= "y" (f/readProp p a)))
      (.set ^KMutableProperty1 p a "z")
      (is (= "z" (f/owner a)))))
  (testing "private set: not a KMutableProperty1 (Kotlin: the type depends on what is accessible)"
    (let [p (kt/ref f/Gadget secret)]
      (is (not (instance? KMutableProperty1 p)))
      (is (= (f/kGadgetSecret) p))
      (is (= 1 (f/readProp p (f/Gadget))))))
  (testing "var with a public setter, lateinit, @JvmField"
    (let [g (f/Gadget)]
      (is (instance? KMutableProperty1 (kt/ref f/Gadget level)))
      (is (= (f/kGadgetLevel) (kt/ref f/Gadget level)))
      (f/writeProp (kt/ref f/Gadget level) g 12)
      (is (= 12 (f/level g)))
      (f/writeProp (kt/ref f/Gadget late) g "L")
      (is (= "L" (f/readProp (kt/ref f/Gadget late) g)))
      (f/writeProp (kt/ref f/Gadget raw) g 4)
      (is (= 4 (f/readProp (kt/ref f/Gadget raw) g)))
      (f/writeProp (kt/ref f/Gadget label) g nil)
      (is (nil? (f/readProp (kt/ref f/Gadget label) g)))))
  (testing "value class: the getter returns the boxed Uid, the setter takes it (mangled names)"
    (let [p (kt/ref f/Gadget uid) g (f/Gadget)]
      (is (= (f/Uid 1) (f/readProp p g)))
      (f/writeProp p g (f/Uid 5))
      (is (= (f/Uid 5) (f/uid g))))
    (is (= (f/Uid 1) (f/readProp (kt/ref f/Person uid) person)))
    (is (= (f/kPersonUid) (kt/ref f/Person uid))))
  (testing "extension property of a JDK type; a mutable one"
    (let [p (kt/ref String wordCount)]
      (is (instance? KProperty1 p))
      (is (not (instance? KMutableProperty1 p)))
      (is (= 3 (f/readProp p "a b c")))
      (is (= (f/kWordCount) p)))
    (let [p (kt/ref StringBuilder firstChar) sb (StringBuilder. "abc")]
      (is (instance? KMutableProperty1 p))
      (f/writeProp p sb \q)
      (is (= "qbc" (str sb)))
      (is (= \q (f/readProp p sb)))
      (is (= (f/kFirstChar) p))))
  (testing "inherited from a class, an interface; an override"
    (let [dog (f/Dog "rex" ["sit"])]
      (is (= 4 (f/readProp (kt/ref f/Dog legs) dog)) "declared in Animal")
      (is (= "rex" (f/readProp (kt/ref f/Dog name) dog)) "overridden in Dog")
      (is (= "rex" (f/readProp (kt/ref f/Animal name) dog)) "Animal::name on a Dog: virtual"))
    (is (= "a@b" (f/readProp (kt/ref f/Contact email) (f/Contact "a@b" "n"))))
    (is (= "a@b" (f/readProp (kt/ref f/Emailed email) (f/Contact "a@b" "n"))))
    (let [c (f/Clock)]
      (f/writeProp (kt/ref f/Clock ticks) c 5)
      (is (= 5 (f/ticks c)))))
  (testing "a data class property used the way a query DSL uses it: `KProperty1<T, V>.eq(value)`"
    (let [^kotlin.Pair pair (f/.eq (kt/ref f/Person firstName) "Ann")]
      (is (= ["firstName" "Ann"] [(.getFirst pair) (.getSecond pair)])))
    (is (= (f/buildEq) (f/.eq (kt/ref f/Person firstName) "Ann"))
        "the Pair that Kotlin's `Person::firstName eq \"Ann\"` builds")))

(deftest references-are-clojure-functions-too
  (testing "rule 6: a Kotlin function value that Clojure gets is a Clojure function"
    (let [ps [(f/Person 1 "a") (f/Person 2 "b")]]
      (is (= ["a" "b"] (map (kt/ref f/Person firstName) ps)))
      (is (= [1 2] (mapv (kt/ref f/Person id) ps)))
      (is (= "Hey a" ((kt/ref f/Person .greet) (first ps) "Hey")))
      (is (= "Hi, Bob?" (apply (kt/ref f/greet) ["Bob" "Hi" "?"])))
      (is (= 41 ((kt/ref f/topVal))))
      (is (= "a" (apply (kt/ref f/Person firstName) [(first ps)])))
      (is (= 5 (do (f/writeProp0 (kt/ref f/topVar) 5) ((kt/ref f/topVar)))))
      (is (thrown? clojure.lang.ArityException ((kt/ref f/Person firstName))))
      ;; F5 (fixes_test): a kt error with the real count; the ArityException is its cause
      (is (instance? clojure.lang.ArityException (ex-cause (try (apply (kt/ref f/greet) ["Bob"]) (catch Exception e e)))))
      (is (= "Yo Ann" (f/callRef (kt/ref f/Person .greet) person)) "and still the Kotlin Function2"))))

(deftest core-api-is-exactly-four
  (is (= '#{require set! ref data reify} (set (keys (ns-publics 'kt.core))))))

(deftest top-level-property-references
  (let [p (kt/ref f/topVal)]
    (testing "val: KProperty0"
      (is (instance? KProperty0 p))
      (is (not (instance? KMutableProperty0 p)))
      (is (= 41 (.get ^KProperty0 p)))
      (is (= 41 (f/readProp0 p)))
      (is (= "topVal" (.getName ^KProperty0 p)))
      (is (= (f/kTopVal) p))))
  (let [p (kt/ref f/topVar)]
    (testing "var: KMutableProperty0"
      (is (instance? KMutableProperty0 p))
      (f/writeProp0 p 17)
      (is (= 17 (f/topVar)))
      (is (= 17 (.get ^KMutableProperty0 p)))
      (is (= (f/kTopVar) p)))))

;; ---------------------------------------------------------------- function references

(deftest function-references
  (testing "X::member: receiver first, FunctionN; the default value is not used"
    (let [r (kt/ref f/Person .greet)]
      (is (instance? KFunction r))
      (is (instance? Function2 r))
      (is (= 2 (.getArity ^KFunction r)))
      (is (= "greet" (f/fnName r)))
      (is (= "Yo Ann" (f/callRef r person)) "Kotlin calls it as (Person, String) -> String")
      (is (= "Hey Ann" (.invoke ^Function2 r person "Hey")))
      (is (= (.getName ^KFunction (f/kGreet)) (.getName ^KFunction r)))
      (is (= (.getArity ^KFunction (f/kGreet)) (.getArity ^KFunction r)))
      (is (= (.getSignature ^kotlin.jvm.internal.CallableReference (f/kGreet)) (.getSignature ^kotlin.jvm.internal.CallableReference r)))
      (is (= (f/kGreet) r))))
  (testing "a top-level function"
    (let [r (kt/ref f/greet)]
      (is (= 3 (.getArity ^KFunction r)))
      (is (= "Hi, Bob?" (.invoke ^Function3 r "Bob" "Hi" "?")))
      (is (= (f/kGreetTop) r))))
  (testing "an extension function of a JDK type"
    (let [r (kt/ref String .shout)]
      (is (= 2 (.getArity ^KFunction r)))
      (is (= "AB!!" (.invoke ^Function2 r "ab" 2)) "Int parameter: a Long becomes an Int")))
  (testing "value class parameter and result (boxed in FunctionN)"
    (let [r (kt/ref f/nextUid)]
      (is (= (f/Uid 6) (.invoke ^Function1 r (f/Uid 5)))))
    (is (= "7:2" (.invoke ^Function3 (kt/ref f/Person .withUid) person (f/Uid 7) 2))))
  (testing "Unit result: kotlin.Unit"
    (let [r (kt/ref f/ping) before f/sideEffect]
      (is (identical? kotlin.Unit/INSTANCE (.invoke ^Function0 r)))))
  (testing "vararg: the parameter is the array that Kotlin passes"
    (is (= "a,b" (.invoke ^Function1 (kt/ref f/join) (into-array String ["a" "b"])))))
  (testing "the function the other direction: a Kotlin function of Clojure"
    (is (= "Yo Ann" (f/callRef (kt/ref f/Person .greet) person)))
    (is (= "Zed" (f/asFn1 (kt/ref f/Person firstName))) "a property reference is a (T) -> V")))

(deftest constructor-references
  (let [r (kt/ref f/Person2)]
    (is (instance? KFunction r))
    (is (= "<init>" (.getName ^KFunction r)))
    (is (= 2 (.getArity ^KFunction r)))
    (is (= (f/Person2 1 "x") (f/make r)))
    (is (= (f/kPerson2) r)))
  (testing "a class with a default value: the reference has all four parameters"
    (let [r (kt/ref f/Person)]
      (is (= 4 (.getArity ^KFunction r)))
      (is (= (f/Person 5 "Zed" nil (f/Uid 3))
             (.invoke ^kotlin.jvm.functions.Function4 r 5 "Zed" nil (f/Uid 3))))))
  (testing "a value class constructor"
    (let [r (kt/ref f/Name)]
      (is (= (f/Name "n") (.invoke ^Function1 r "n"))))))

;; ---------------------------------------------------------------- errors

(deftest one-declaration-or-an-error
  (testing "overloaded member"
    (let [m (msg '(kt/ref f/Person .tag))]
      (is (str/includes? m "2 Kotlin declarations"))
      (is (str/includes? m "fun fx.Person.tag(a: Int): String"))
      (is (str/includes? m "fun fx.Person.tag(a: String): String"))))
  (testing "overloaded top-level function, two constructors"
    (let [m (msg '(kt/ref f/eq))]
      (is (str/includes? m "fun eq(a: String, b: Any): String"))
      (is (str/includes? m "fun eq(a: Int, b: Any): String")))
    (let [m (msg '(kt/ref f/User))]
      (is (str/includes? m "class User(id: Long")))
    (let [m (msg '(kt/ref f/Uid))]
      (is (str/includes? m "Kotlin declarations"))))
  (testing "no such member / needs a receiver / not a kt var"
    (is (str/includes? (msg '(kt/ref f/Person nope)) "no property `nope`"))
    (is (str/includes? (msg '(kt/ref f/Person .nope)) "no function `.nope`"))
    (is (str/includes? (msg '(kt/ref f/.greet)) "needs a receiver type"))
    (is (str/includes? (msg '(kt/ref inc)) "not a kt var"))
    (is (str/includes? (msg '(kt/ref)) "kt/ref takes"))
    (is (str/includes? (msg '(kt/ref f/Person firstName extra)) "kt/ref takes")))
  (testing "unsupported kinds say so"
    (is (str/includes? (msg '(kt/ref f/step)) "suspend"))
    (is (str/includes? (msg '(kt/ref f/typeName)) "reified"))
    (is (str/includes? (msg '(kt/ref f/Pred)) "no public constructor")))
  (testing "a companion member is no X::member"
    (is (str/includes? (msg '(kt/ref f/WithCompanion make)) "no property"))))

(deftest bound-references-are-not-supported
  (testing "a local, an expression, a var, an enum entry (an object is H3: bound to the object)"
    (is (str/includes? (msg '(let [u (f/Person 1 "a")] (kt/ref u firstName))) "bound references are not supported"))
    (is (str/includes? (msg '(kt/ref (f/Person 1 "a") firstName)) "bound references are not supported"))
    ;; step 8 (H3): (kt/ref f/Registry size) is legal Kotlin and now a bound reference, see hardening_test
    (is (str/includes? (msg '(kt/ref f/Color.RED hex)) "bound references are not supported"))
    (is (str/includes? (msg '(kt/ref clojure.core/inc firstName)) "bound references are not supported")))
  (testing "String? has no ::"
    (is (str/includes? (msg '(kt/ref String? class)) "nullable"))))

;; ---------------------------------------------------------------- a reference is a constant

(deftest the-expansion-does-not-rebuild-the-reference
  (doseq [form ['(fn [] (kt/ref f/Person class))
                '(fn [] (kt/ref f/Person firstName))
                '(fn [] (kt/ref f/Gadget level))
                '(fn [] (kt/ref String wordCount))
                '(fn [] (kt/ref f/Person .greet))
                '(fn [] (kt/ref f/greet))
                '(fn [] (kt/ref f/Person2))
                '(fn [] (kt/ref f/topVar))]]
    (let [e (expansions form)
          h (ct/eval-here-fn form)]
      (is (= "" (:warnings e)) (pr-str form))
      (is (empty? (:dynamic e)) (pr-str form))
      (is (identical? (h) (h)) (str "the same object on every evaluation: " (pr-str form)))))
  (testing "the same declaration from two different forms: the same object"
    (is (identical? (kt/ref f/Person firstName) (kt/ref f/Person firstName)))
    (is (identical? (kt/ref f/Person class) (kt/ref f/Person class))))
  (testing "the expansion is a quoted constant, not code that builds the reference"
    (let [x (ct/eval-here-fn '(macroexpand-1 '(kt/ref f/Person firstName)))]
      (is (= 'kt.ref/const (first x)))
      (is (= 'quote (first (second x))))
      (is (< (count (pr-str x)) 3000) "plain data only"))))

(deftest the-spec-is-plain-data
  (binding [*ns* (the-ns 'kt.call-test)]
    (doseq [args ['(f/Person class) '(f/Person firstName) '(f/Gadget uid) '(String wordCount) '(f/Person .greet)
                  '(f/greet) '(f/Person2) '(f/topVar) '(f/nextUid) '(f/join) '(f/Person .withUid)]]
      (let [spec (ref/spec-of {} (map #(if (and (seq? %) (= 'f/greet (first %))) % %) args))]
        (is (= spec (read-string (pr-str spec))) (pr-str args))))))

(deftest macroexpansions-for-the-report
  (println "EXPANSION (kt/ref f/Person firstName) =>"
           (pr-str (ct/eval-here-fn '(macroexpand-1 '(kt/ref f/Person firstName)))))
  (println "EXPANSION (kt/ref f/Person class) =>"
           (pr-str (ct/eval-here-fn '(macroexpand-1 '(kt/ref f/Person class)))))
  (is true))
