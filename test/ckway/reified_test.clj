(ns ckway.reified-test
  "Step 5, T2, T4, T5: type arguments with `:<>` (DESIGN-2 rule 5). A reified call is made by a Kotlin
  bridge that the Kotlin compiler builds (needs the :kotlinc alias; bin/test runs with it)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.bridge :as bridge]
            [ckway.call-test :as ct :refer [compile-error expansions reflection-output]]
            [ckway.core :as kt]
            [ckway.meta]
            [ckway.rt :as rt]
            [ckway.types]))

(kt/require '[fx :as f] '[kotlin.reflect :as r])

(defmacro static-only
  "Assert `expected` = `call`, and that the call is on the static path: its expansion calls a Kotlin
  bridge class, there is no call-dyn and no reflection warning."
  [expected call]
  `(let [e# (expansions '~call)]
     (is (= ~expected ~call) (pr-str '~call))
     (is (seq (:static e#)) (pr-str '~call))
     (is (some #(re-find #"ckway\.bridge\.K_" (pr-str %)) (:static e#)) (str "bridge class in " (pr-str '~call)))
     (is (empty? (:dynamic e#)) (str "no call-dyn in " (pr-str '~call)))
     (is (not (str/includes? (:warnings e#) "Reflection warning")) (:warnings e#))))

(defn- cls [x] (some-> x class))

(defn- jclass [c] (.getJClass ^kotlin.jvm.internal.ClassReference c))

;; ---------------------------------------------------------------- the rule

(deftest static-path-proof
  (testing "four reified calls: a direct call of ckway.bridge.K_..., no call-dyn, no reflection warning"
    (static-only "String" (f/typeName :<> String))
    (static-only 42 (f/.parseAs "42" :<> Int))
    (static-only "v:Int:5" (f/describe 5 :<> Int))
    (static-only [2 4] (f/mapEach [1 2] (fn [x] (* 2 x)) :<> Int)))
  (testing "the expansion of one of them, as the compiler sees it"
    (let [s (pr-str (:static (expansions '(f/.parseAs "42" :<> Long))))]
      (is (re-find #"\(\. ckway\.bridge\.K_fx_ReifiedKt_parseAs__[0-9a-f]{10} \(call " s) s)
      (is (not (str/includes? s "call-dyn")))))
  (testing "the same call twice is one bridge: nothing is compiled or read from disk again"
    (f/typeName :<> Short)
    (let [before @bridge/kotlin-stats]
      (is (= "Short" (eval '(do (ckway.core/require '[fx :as f]) (f/typeName :<> Short)))))
      (is (= (select-keys before [:compiled :cache-hits]) (select-keys @bridge/kotlin-stats [:compiled :cache-hits]))))))

(deftest type-forms-in-calls
  (testing "a kt class var, a Kotlin default name, a class that Clojure resolves, Any?"
    (is (= "Uid" (f/typeName :<> f/Uid)))
    (is (= "String" (f/typeName :<> String)))
    (is (= "UUID" (f/typeName :<> java.util.UUID)))
    (is (= "Object" (f/typeName :<> Object)))
    (is (= "v:Any?:null" (f/describe nil :<> Any?)))
    (is (= "v:Any?:7" (f/describe 7 :<> Any?)))
    (is (= "v:List<Map<String, Any?>>:[]" (f/describe [] :<> (List (Map String Object?))))
        "DESIGN-2 line 24 writes Object?: Kotlin sees java.lang.Object as Any"))
  (testing "nullable and nested generic forms"
    (is (= "v:String?:null" (f/describe nil :<> String?)))
    (is (= "v:Box2?:null" (f/describe nil :<> f/Box2?)))
    (is (= "v:List<Map<String, Any?>>:[]" (f/describe [] :<> (List (Map String Any?)))))
    (is (= "v:List<*>:[]" (f/describe [] :<> (List *))))
    (is (= "v:Pair<Int, List<String?>>:(1, [])" (f/describe (kotlin.Pair. 1 []) :<> (Pair Int (List String?))))))
  (testing "a vector gives several type arguments"
    (is (= "String->List<Int>" (f/mapTypes :<> [String (List Int)])))
    (is (= "Long->Long" (f/mapTypes :<> [Long Long])))
    (is (= "String" (f/typeName :<> [String])) "a vector of one is allowed")))

(deftest selection-with-and-without-type-arguments
  (let [box (f/Box2 [1 "a"])]
    (testing "the `path` shape of a web library: without :<> the call is the non-generic twin, never the reified one"
      (is (= "1" (f/.firstOf box)))
      (is (= "1" (rt/call-dyn #'f/.firstOf [box] {})))
      (is (= "1" ((fn [x] (f/.firstOf x)) box)) "unknown receiver type: still not the reified twin")
      (let [e (expansions '(f/.firstOf (f/Box2 [1 "a"])))]
        (is (empty? (:dynamic e)))
        (is (empty? (:warnings e)))
        (is (not (str/includes? (pr-str (:static e)) "ckway.bridge.K_")) "no bridge, no compiler: the plain method")
        (is (str/includes? (pr-str (:static e)) "(firstOf)"))))
    (testing "the KClass twin is a third, different declaration"
      (is (= "a" (f/.firstOf box (kotlin.jvm.JvmClassMappingKt/getKotlinClass String)))))
    (testing "with :<> only the declaration with one type parameter is a candidate"
      (is (= "a" (f/.firstOf box :<> String)))
      (is (= 1 (f/.firstOf box :<> Long)))
      (is (nil? (f/.firstOf box :<> Int)) "1 is a Long, not an Int: Kotlin's own `is` check")
      (is (nil? (f/.firstOf box :<> Double))))))

(deftest type-argument-errors
  (testing "a reified function needs :<>"
    (let [m (compile-error '(f/typeName))]
      (is (str/starts-with? m "kt: typeName is `inline reified`"))
      (is (str/includes? m "inline fun <reified T> typeName(): String")))
    (is (str/includes? (compile-error '(f/.parseAs "1")) "is `inline reified`")))
  (testing "a var used as a value has no :<>"
    (let [m (try (apply f/typeName []) (catch Throwable t (ex-message t)))]
      (is (str/includes? m "A var used as a value cannot take `:<>`")))
    (let [m (try (rt/call-dyn #'f/describe [5] {"<>" 'Int}) (catch Throwable t (ex-message t)))]
      (is (str/includes? m "`:<>` needs the static path")))
    (is (str/includes? (try (doall (map f/.parseAs ["1"])) (catch Throwable t (ex-message t))) "cannot take `:<>`")))
  (testing "the number of type arguments must be the number of type parameters"
    (let [m (compile-error '(f/typeName :<> [String Int]))]
      (is (str/includes? m "gives 2 type arguments"))
      (is (str/includes? m "no declaration has 2 type parameters"))
      (is (str/includes? m "inline fun <reified T> typeName(): String")))
    (is (str/includes? (compile-error '(f/mapTypes :<> String)) "no declaration has 1 type parameter"))
    (is (str/includes? (compile-error '(f/greet "a" :<> String)) "no declaration has 1 type parameter")))
  (testing "type form errors are compile errors"
    (is (str/includes? (compile-error '(f/typeName :<> Foo)) "unknown type name `Foo`"))
    (is (str/includes? (compile-error '(f/typeName :<> List)) "has 1 type parameter, but the form gives 0"))
    (is (str/includes? (compile-error '(f/typeName :<> (List Int Int))) "gives 2 type arguments"))
    (is (str/includes? (compile-error '(let [t String] (f/typeName :<> t))) "unknown type name `t`")
        "a local is not looked at: a type form is a literal")
    (is (str/includes? (compile-error '(f/typeName :<> (str "S"))) "unknown type name `str`"))
    (is (str/includes? (compile-error '(f/typeName :<> "String")) "is not a type form")))
  (testing "`:<>` given twice"
    (is (str/includes? (compile-error '(f/typeName :<> String :<> Int)) "given twice")))
  (testing "an argument that does not fit the parameter type with the type arguments substituted"
    (is (str/includes? (compile-error '(f/describe nil :<> String)) "`nil` passed to non-nullable `x`"))
    (is (str/includes? (compile-error '(f/uidType 5 :<> String)) "is Uid but got Long"))))

(deftest unknown-receiver-needs-a-type-hint
  (testing "several declarations fit and the receiver type is unknown: a compile error that asks for a hint"
    (let [m (compile-error '(fn [x] (f/.pick x :<> Int)))]
      (is (str/includes? m "`:<>` needs the static path"))
      (is (str/includes? m "Add a type hint")))
    (is (str/includes? (compile-error '(fn [x] (f/.pick x :<> Int))) "fx.PickA.pick")))
  (testing "with the hint it is static"
    (is (= "A:Int" ((ct/eval-here-fn '(fn [^fx.PickA x] (f/.pick x :<> Int))) (fx.PickA.))))
    (is (= "B:Int" ((ct/eval-here-fn '(fn [^fx.PickB x] (f/.pick x :<> Int))) (fx.PickB.)))))
  (testing "one candidate is static even when the receiver type is unknown"
    (is (= "a" ((ct/eval-here-fn '(fn [x] (f/.firstOf x :<> String))) (f/Box2 [1 "a"]))))))

;; ---------------------------------------------------------------- the kinds of declaration

(deftest top-level-extension-member-and-operator
  (testing "extension function (the generated source imports it)"
    (is (= 42 (f/.parseAs "42" :<> Int)))
    (is (instance? Integer (f/.parseAs "42" :<> Int)))
    (is (instance? Long (f/.parseAs "42" :<> Long)))
    (is (= "x" (f/.parseAs "x" :<> String)))
    (is (nil? (f/.parseAs "x" :<> Int))))
  (testing "member"
    (is (= "a" (f/.firstOf (f/Box2 [1 "a"]) :<> String))))
  (testing "operator function called by name (an extension)"
    (is (= "a" (f/.get (f/Box2 [1 "a"]) 1 :<> String)))
    (is (= 1 (f/.get (f/Box2 [1 "a"]) 0 :<> Long)))
    (is (thrown-with-msg? ClassCastException #"cannot be cast" (f/.get (f/Box2 [1 "a"]) 1 :<> Int))))
  (testing "member-extension in an object: dispatch receiver, then extension receiver; a skipped default"
    (is (= "conv:Int:x" (f/.tagged f/Conv "x" :<> Int)))
    (is (= "conv-Int-x" (f/.tagged f/Conv "x" :sep "-" :<> Int)))
    (is (= "conv:Int:x" (f/.tagged f/Conv "x" ":" :<> Int))))
  (testing "object member"
    (is (= "kind:String" (f/.kind f/Conv :<> String))))
  (testing "companion member, with and without a default"
    (is (= "String" (f/.construct f/RComp :<> String)))
    (is (= "IntInt" (f/.construct f/RComp :n 2 :<> Int)))
    (is (str/includes? (compile-error '(f/.construct f/Conv :<> Int)) "expects the class var of fx.RComp")))
  (testing "context parameter"
    (is (= "ctx Int" (f/.ctxType "ctx" :<> Int))))
  (testing "two type parameters, several bounds, a bound"
    (is (= "String->Int" (f/mapTypes :<> [String Int])))
    (is (= "String:3" (f/bothBounds "abc" :<> String)))
    (is (= "Long:6" (f/sumAs 1 2 3 :<> Long)))))

(deftest parameters
  (testing "skipped defaults: the generated call writes the parameters it has by name"
    (is (= "v:Int:5" (f/describe 5 :<> Int)))
    (is (= "z:Int:5" (f/describe 5 :prefix "z" :<> Int)))
    (is (= "v:Int:5v:Int:5" (f/describe 5 :times 2 :<> Int)))
    (is (= "z:Int:5z:Int:5z:Int:5" (f/describe 5 "z" 3 :<> Int)))
    (is (= "z:Long:9" (f/describe :x 9 :prefix "z" :<> Long))))
  (testing "vararg: positional, none, and a collection"
    (is (= "Long:6" (f/sumAs 1 2 3 :<> Long)))
    (is (= "Long:0" (f/sumAs :<> Long)))
    (is (= "Int:3" (f/sumAs :xs [1 2] :<> Int)))
    (is (= "Int:7" (f/sumAs 7 :<> Int))))
  (testing "function-type parameter: the Clojure function is adapted, T = Int makes the elements Integers"
    (is (= [2 4 6] (f/mapEach [1 2 3] (fn [x] (* 2 x)) :<> Int)))
    (is (every? #(instance? Integer %) (f/mapEach [1 2 3] (fn [x] (* 2 x)) :<> Int)))
    (is (every? #(instance? Long %) (f/mapEach [1 2 3] (fn [x] (* 2 x)) :<> Long)))
    (is (= ["1" "2"] (f/mapEach [1 2] (fn [x] (str x)) :<> String)))
    (is (= [2 4] (f/mapEach :xs [1 2] :f #(* 2 %) :<> Int)))
    (is (= [2 3] (f/mapEach [1 2] inc :<> Int)) "a var and a function value, not a literal"))
  (testing "nullable parameter"
    (is (= "v:String?:null" (f/describe nil :<> String?)))
    (is (= "v:String?:a" (f/describe "a" :<> String?)))))

(deftest value-classes
  (testing "a value-class parameter: the bridge takes the boxed object (no mangled name)"
    (is (= "String:5" (f/uidType (f/Uid 5) :<> String)))
    (is (= "List<Int>:7" (f/uidType (f/Uid 7) :<> (List Int)))))
  (testing "a value-class result is the boxed object"
    (is (instance? fx.Uid (f/uidFor 5 :<> String)))
    (is (= 11 (f/v (f/uidFor 5 :<> String))))
    (is (= (f/Uid 11) (f/uidFor 5 :<> String)))
    (is (= 8 (f/v (f/uidFor 5 :<> Int)))))
  (testing "nullable value class parameter and result"
    (is (nil? (f/uidOrNullFor nil :<> String)))
    (is (= 12 (f/v (f/uidOrNullFor 6 :<> String)))))
  (testing "a value class as the type argument"
    (is (= "Uid" (f/typeName :<> f/Uid)))
    (is (= "v:Uid:Uid(v=3)" (f/describe (f/Uid 3) :<> f/Uid)))
    (let [box (f/Box2 [(f/Name "n")])]
      (is (= (f/Name "n") (f/.firstOf box :<> f/Name)))
      (is (instance? fx.Name (f/.firstOf box :<> f/Name)))
      (is (nil? (f/.firstOf (f/Box2 []) :<> f/Name))))))

(deftest suspend-reified
  (testing "a suspend inline reified function: the bridge is `suspend`, the call waits through ckway.co"
    (is (= 12 (f/fetchAs "12" :<> Int)))
    (is (instance? Integer (f/fetchAs "12" :<> Int)))
    (is (= "12" (f/fetchAs "12" :<> String)))
    (is (nil? (f/fetchAs "12" :<> Long)))
    (let [t0 (System/nanoTime)]
      (f/fetchAs "1" :<> Int)
      (is (>= (/ (- (System/nanoTime) t0) 1e6) 9) "it did wait for the delay"))
    (static-only 12 (f/fetchAs "12" :<> Int))
    (is (str/includes? (pr-str (:static (expansions '(f/fetchAs "12" :<> Int)))) "ckway.co/wait-for"))))

(deftest stdlib-typeOf
  (testing "(r/typeOf :<> (List String?)) is the KType of List<String?>"
    (let [t (r/typeOf :<> (List String?))]
      (is (instance? kotlin.reflect.KType t))
      (is (= java.util.List (-> t .getClassifier jclass)))
      (is (false? (.isMarkedNullable t)))
      (let [a (first (.getArguments t))]
        (is (= java.lang.String (-> a .getType .getClassifier jclass)))
        (is (true? (.isMarkedNullable (.getType a)))))))
  (testing "a Kotlin function of the fixtures that returns typeOf<T>()"
    (is (= java.util.Map (-> (f/kTypeOf :<> (Map String Any?)) .getClassifier jclass)))
    (is (= 2 (count (.getArguments (f/kTypeOf :<> (Map String Any?))))))))

;; ---------------------------------------------------------------- T4: a generic function that is not reified

(deftest non-reified-type-arguments
  (testing ":<> is legal and changes nothing at run time"
    (is (= 1 (f/firstOrNullOf [1 2] :<> Int)))
    (is (= "a" (f/firstOrNullOf ["a"] :<> String)))
    (is (nil? (f/firstOrNullOf [] :<> String)))
    (is (= 5 (f/echo 5 :<> Long)))
    (is (nil? (f/echo nil :<> String?))))
  (testing "no bridge: the call is the direct JVM call, and no compiler is needed"
    (let [s (pr-str (:static (expansions '(f/echo 5 :<> Long))))]
      (is (not (str/includes? s "ckway.bridge.K_")))
      (is (str/includes? s "fx.ReifiedKt (echo"))))
  (testing "the type argument is the static type of the result and of the parameter"
    (is (= Long (cls (f/echo 5 :<> Long))))
    (is (= Integer (cls (f/echo 5 :<> Int))) "the argument is converted to the type argument: Kotlin Int")
    (testing "overloads on the result of a generic call: static with :<>, dynamic (still correct) without"
      (let [e (expansions '(f/tag (f/echo (f/Name "a") :<> f/Name)))]
        (is (= "name" (f/tag (f/echo (f/Name "a") :<> f/Name))))
        (is (empty? (:dynamic e)))
        (is (empty? (:warnings e))))
      (let [e (expansions '(f/tag (f/echo (f/Name "a"))))]
        (is (= "name" (f/tag (f/echo (f/Name "a")))))
        (is (seq (:dynamic e)) "without :<> the result type is not known")))
    (is (= "uid" (f/tag (f/echo (f/Uid 1) :<> f/Uid))))
    (is (= "name" (f/tag (f/firstOrNullOf [(f/Name "z")] :<> f/Name)))))
  (testing "the arguments are checked against the substituted parameter types"
    (is (str/includes? (compile-error '(f/echo 5 :<> String)) "`x` is String but got Long"))
    (is (str/includes? (compile-error '(f/echo nil :<> String)) "`nil` passed to non-nullable `x`"))
    (is (str/includes? (compile-error '(f/echo nil :<> Int)) "`nil` passed to non-nullable `x`")))
  (testing "a class-level type parameter stays erased (not done): the member keeps its T"
    (is (= 1 (f/.firstOf (f/Box2 [1]) :<> Long)))))

;; ---------------------------------------------------------------- T5: errors

(deftest kotlin-compile-errors
  (let [t (try (eval '(f/sumAs 1 2 :<> String)) nil (catch Throwable t t))
        root (loop [t t] (if-let [c (.getCause t)] (recur c) t))
        m (ex-message root)]
    (testing "a type argument that violates a bound: the user's form, the Kotlin signature, Kotlin's message"
      (is (some? t))
      (is (str/starts-with? m "kt: the Kotlin compiler rejected (f/sumAs 1 2 :<> String)"))
      (is (str/includes? m "Kotlin signature: inline fun <reified T : Number> sumAs(vararg xs: Int): String"))
      (is (str/includes? m "type arguments: kotlin.String"))
      (is (str/includes? m "Kotlin says:"))
      (is (str/includes? m "not within its bounds") m))
    (testing "not the generated source and no stack trace of it"
      (is (not (str/includes? m "@file:JvmName")))
      (is (not (str/includes? m "fun call(")))
      (is (not (str/includes? m ".kt:")))
      (is (not (str/includes? m "/var/folders")))
      (is (= 1 (count (str/split-lines (re-find #"kt: the Kotlin compiler rejected [^\n]*" m))))))
    (testing "the generated source is data on the exception, for debugging"
      (is (str/includes? (:kt/bridge-source (ex-data root)) "fun call(")))))

(deftest generated-source-of-a-member-extension-with-a-skipped-default
  (let [build (requiring-resolve 'ckway.bridge.ksrc/build)
        source (requiring-resolve 'ckway.bridge.ksrc/source)
        decl (first (filter #(= "fx.Conv" (:owner %)) (get (ckway.meta/package-index "fx") ".tagged")))
        targs [(ckway.types/resolve-form (the-ns 'ckway.reified-test) 'Int)]
        spec (build decl targs #{})
        text (source spec "ckway.bridge.K_example")]
    (is (= (str "@file:JvmName(\"K_example\")\n"
                "@file:Suppress(\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"NOTHING_TO_INLINE\")\n"
                "package ckway.bridge\n\n\n"
                "fun call(`kt_this`: fx.Conv, `kt_ext`: kotlin.String) = with(`kt_this`) { `kt_ext`.`tagged`<kotlin.Int>() }\n")
           text))))

;; ---------------------------------------------------------------- limits that are known and tested as limits

(deftest known-limits
  ;; step 8 (H4): a member that takes the class's own T is bridged (see hardening_test). Before: "star projection".
  (testing "a member of a generic class that takes the class's T, and one that does not"
    (is (= 5 (f/.only (f/GBox (long 5)) :<> Long)))
    (is (= 7 (f/.conv (f/GBox (long 5)) 7 :<> Long))))
  (testing "an inline reified property is not supported (a clear message, no bridge)"
    (is (str/includes? (compile-error '(f/refName "x" :<> String)) "inline reified properties is not supported yet"))
    (is (str/includes? (compile-error '(f/refName "x")) "is `inline reified`")))
  ;; step 8 (H2b): the stdlib type aliases (ArrayList, HashMap...) are Kotlin default names, read from the stdlib metadata
  (testing "Kotlin type aliases (ArrayList, HashMap...) are default names"
    (is (= "ArrayList" (f/typeName :<> (ArrayList String))))
    (is (= "ArrayList" (f/typeName :<> (java.util.ArrayList String))) "and the Java class works")))
