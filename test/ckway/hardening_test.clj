(ns ckway.hardening-test
  "Hardening of the call path: one section for each topic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct :refer [compile-error root-message]]
            [ckway.core :as kt]
            [ckway.rt :as rt]
            [ckway.types :as ty]))

(kt/require '[fx :as f] '[kotlin.time :as t] '[kotlinx.coroutines :as co])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

;; ---------------------------------------------------------------- checked exception out of a Kotlin function value

(deftest checked-exception-from-kotlin-function-value
  (testing "plain function value, static path and dynamic path"
    (let [g (f/throwingFn)
          gd (rt/call-dyn #'f/throwingFn [] {})]
      (doseq [h [g gd]]
        (let [e (thrown #(h 1))]
          (is (instance? java.io.IOException e) (str (class e)))
          (is (= "kboom" (ex-message e))))
        (is (instance? java.io.IOException (thrown #(apply h [1])))))))
  (testing "suspend function value, static path and dynamic path"
    (let [g (f/throwingSusp)
          gd (rt/call-dyn #'f/throwingSusp [] {})]
      (doseq [h [g gd]]
        (let [e (thrown #(h 1))]
          (is (instance? java.io.IOException e) (str (class e)))
          (is (= "sboom" (ex-message e)))))))
  (testing "a function value that Kotlin hands to a Clojure function"
    (let [e (thrown #(f/callWithThrowing (fn [h] (h 1))))
          e2 (thrown #(rt/call-dyn #'f/callWithThrowing [(fn [h] (h 1))] {}))]
      (is (instance? java.io.IOException e) (str (class e)))
      (is (instance? java.io.IOException e2) (str (class e2)))))
  (testing "the other properties stay"
    (let [g (f/throwingFn)]
      (is (instance? clojure.lang.IFn g))
      (is (instance? kotlin.jvm.functions.Function1 g))
      (is (not (java.lang.reflect.Proxy/isProxyClass (class g))))
      (is (= "#kt/fn" (subs (str g) 0 6)))
      (is (instance? clojure.lang.ArityException (thrown #(g))))
      (is (identical? (rt/own g) (rt/own (f/echo g))) "hands the original object back"))))

;; ---------------------------------------------------------------- type aliases

(def ^:private this-ns *ns*)
(defn- src [form] (ty/source (ty/resolve-form this-ns form)))
(defn- err [form] (try (ty/resolve-form this-ns form) nil (catch Exception e (ex-message e))))

(deftest alias-has-a-var
  (testing "every public typealias of the package is a var"
    (doseq [n '[f/JList f/UserAlias f/WcAlias f/RegAlias f/ColorAlias f/InnerAlias f/FnAlias f/SuspHandler
                f/Params f/Twin f/PredAlias f/GBoxAlias]]
      (is (var? (ns-resolve this-ns n)) (str n)))))

(deftest class-alias-constructs
  (testing "alias of a Java class: the constructors of the Java class"
    (let [l (f/JList)]
      (is (instance? java.util.ArrayList l))
      (is (instance? java.util.ArrayList (f/JList 10)) "ArrayList(int)")
      (is (instance? java.util.ArrayList (rt/call-dyn #'f/JList [] {})))))
  (testing "alias of a Kotlin class behaves as the class var"
    (let [u (f/UserAlias 7 "ann")]
      (is (instance? fx.User u))
      (is (= "Hi ann" (f/.greet u)))
      (is (= "n" (f/.initial (f/UserAlias "n"))))
      (is (= "Yo ann" (f/.greet u "Yo"))))
    (is (instance? fx.User (rt/call-dyn #'f/UserAlias [7 "ann"] {})))
    (is (= "ann" (f/name (f/UserAlias 7 "ann")))))
  (testing "companion receiver"
    (is (= "made" (f/.make f/WcAlias)))
    (is (= "made" (f/.make f/WithCompanion)))
    (is (= 7 (f/other f/WcAlias))))
  (testing "nested class alias"
    (is (= 4 (f/.twice (f/InnerAlias 2)))))
  (testing "alias of an object is the instance"
    (is (identical? f/Registry f/RegAlias))
    (is (= "v:k" (f/.lookup f/RegAlias "k"))))
  (testing "kt/ref X class"
    (is (= (kt/ref f/User class) (kt/ref f/UserAlias class)))
    (is (= (kt/ref f/WithCompanion class) (kt/ref f/WcAlias class)))
    (is (= java.util.ArrayList (.getJClass ^kotlin.jvm.internal.ClassBasedDeclarationContainer (kt/ref f/JList class)))))
  (testing "type forms: a class alias is that class"
    (is (= "fx.User" (src 'f/UserAlias)))
    (is (= "java.util.ArrayList<kotlin.String>" (src 'f/JList)))
    (is (= "fx.GBox<kotlin.Int>" (src '(f/GBoxAlias Int))))
    (is (= "kotlin.Pair<kotlin.Int, kotlin.Int>" (src '(f/Twin Int))))))

(deftest non-class-alias
  (testing "usable in a type form"
    (is (= "kotlin.collections.Map<kotlin.String, kotlin.String?>" (src 'f/Params)))
    (is (= "kotlin.collections.Map<kotlin.String, kotlin.String?>?" (src 'f/Params?)))
    (is (= "(kotlin.Int) -> kotlin.Int" (src 'f/FnAlias)))
    (is (= "suspend java.lang.StringBuilder.() -> kotlin.Any?" (src 'f/SuspHandler)))
    (is (= "fx.Pred" (src 'f/PredAlias)))
    )
  (testing "...and as the type argument of a reified call"
    (is (= "Map<String, String?>" (f/showType :<> f/Params)))
    (is (= "Function1<Int, Int>" (f/showType :<> f/FnAlias)))
    (let [m (ct/compile-error '(f/showType :<> f/SuspHandler?))]
      (is (str/includes? m "suspend functional types are not supported in 'typeOf'") "Kotlin's own limit, after the alias was expanded")
      (is (str/includes? m "suspend java.lang.StringBuilder.() -> kotlin.Any?")))
    (is (= "Pair<Int, Int>" (f/showType :<> (f/Twin Int)))))
  (testing "doc shows the alias"
    (let [d (with-out-str (clojure.repl/doc f/Params))]
      (is (str/includes? d "typealias Params = collections.Map<String, String?>") d))
    (let [d (with-out-str (clojure.repl/doc f/SuspHandler))]
      (is (str/includes? d "typealias SuspHandler = suspend") d))
    (let [d (with-out-str (clojure.repl/doc f/JList))]
      (is (str/includes? d "typealias JList = java.util.ArrayList<String>") d)))
  (testing "calling it as a constructor is a clear compile error"
    (doseq [form ['(f/Params) '(f/Params 1) '(f/FnAlias (fn [x] x)) '(f/SuspHandler)]]
      (let [m (ct/compile-error form)]
        (is (str/includes? m "type alias") (str form " " m))
        (is (str/includes? m "not a class") (str form " " m))))))

(deftest stdlib-aliases-are-default-names
  (doseq [[form expect] {'(ArrayList Int) "java.util.ArrayList" '(HashMap Int Int) "java.util.HashMap"
                         '(LinkedHashMap Int Int) "java.util.LinkedHashMap"
                         '(HashSet Int) "java.util.HashSet" '(LinkedHashSet Int) "java.util.LinkedHashSet"
                         'Exception "java.lang.Exception" 'RuntimeException "java.lang.RuntimeException"
                         'IllegalStateException "java.lang.IllegalStateException"
                         'IllegalArgumentException "java.lang.IllegalArgumentException"
                         'Error "java.lang.Error" 'StringBuilder "java.lang.StringBuilder"
                         'UnsupportedOperationException "java.lang.UnsupportedOperationException"}]
    (is (str/starts-with? (src form) expect) (str form)))
  (is (= "java.util.ArrayList<kotlin.Int>" (src '(ArrayList Int))))
  (is (= "java.util.HashMap<kotlin.String, kotlin.Int>" (src '(HashMap String Int))))
  (is (= "Exception" (f/showType :<> Exception)))
  (is (= "ArrayList<Int>" (f/showType :<> (ArrayList Int))))
  (is (str/includes? (err '(ArrayList)) "has 1 type parameter"))
  (testing "a class beats an alias of the same name; the existing names stay"
    (is (= "kotlin.collections.List<kotlin.Int>" (src '(List Int))))))

;; ---------------------------------------------------------------- kt/ref with an object

(deftest object-bound-reference
  (testing "a property of an object: a bound KProperty0"
    (let [p (kt/ref f/Registry size)]
      (is (instance? kotlin.reflect.KProperty0 p))
      (is (not (instance? kotlin.reflect.KMutableProperty0 p)))
      (is (= "size" (.getName ^kotlin.reflect.KProperty p)))
      (is (= 3 (.get ^kotlin.reflect.KProperty0 p)))
      (is (= 3 (p)) "also a Clojure function, with no receiver argument")
      (is (= 3 (.invoke ^kotlin.jvm.functions.Function0 p)))
      (is (identical? p (kt/ref f/Registry size)) "a constant")
      (is (identical? f/Registry (.getBoundReceiver ^kotlin.jvm.internal.CallableReference p)))))
  (testing "a var of an object: KMutableProperty0"
    (let [p (kt/ref f/Registry level)]
      (is (instance? kotlin.reflect.KMutableProperty0 p))
      (.set ^kotlin.reflect.KMutableProperty0 p 4)
      (is (= 4 (p)))
      (is (= 4 (f/level f/Registry)))
      (.set ^kotlin.reflect.KMutableProperty0 p 0)))
  (testing "a function of an object: a bound KFunction"
    (let [m (kt/ref f/Registry .lookup)]
      (is (instance? kotlin.reflect.KFunction m))
      (is (instance? kotlin.jvm.functions.Function1 m))
      (is (= "v:k" (m "k")))
      (is (= ["v:a" "v:b"] (mapv m ["a" "b"])))
      (is (= "lookup" (.getName ^kotlin.reflect.KFunction m)))
      ;; (see fixes_test) a kt error with the real count; the ArityException is its cause
      (is (instance? clojure.lang.ArityException (ex-cause (thrown #(m)))))
      (is (instance? clojure.lang.ArityException (ex-cause (thrown #(m 1 2)))))
      (is (str/includes? (ex-message (thrown #(m 1 2))) "takes 1 argument (k), got 2"))))
  (testing "a reference of an object is handed to Kotlin as a function value"
    (is (= 3 (f/applyProp (kt/ref f/Registry size)))))
  (testing "@JvmStatic member of an object"
    (is (= 0 ((kt/ref f/Knob stat))))
    (is (= 0 ((kt/ref f/Knob volume)))))
  (testing "an arbitrary bound reference stays an error"
    (doseq [form ['(let [u (f/User 1 "a")] (kt/ref u name)) '(kt/ref (f/User 1 "a") name) '(kt/ref f/Color.RED hex)]]
      (is (str/includes? (str (ct/compile-error form)) "bound references are not supported") (pr-str form)))))

;; ---------------------------------------------------------------- classes that cannot be called

(deftest no-public-constructor-messages
  (let [m (fn [form] (str (ct/compile-error form)))]
    (testing "a class with only non-public constructors"
      (doseq [form ['(t/Duration 5) '(t/Duration) '(t/Duration 5 6)]]
        (let [e (m form)]
          (is (str/includes? e "`Duration` has no public constructor") e)
          (is (not (str/includes? e "too many")) e)
          (is (not (str/includes? e "class Duration()")) e)
          (is (str/includes? e "Its companion offers: ") e)
          (is (str/includes? e "seconds") e)
          (is (str/includes? e "constructors are not public") e))))
    (testing "an abstract class"
      (doseq [form ['(f/AbstractThing) '(f/AbstractThing 1)]]
        (is (str/includes? (m form) "`AbstractThing` has no public constructor: it is an abstract class") (m form))))
    (testing "a sealed class"
      (is (str/includes? (m '(f/Sealed3)) "it is a sealed class") (m '(f/Sealed3))))
    (testing "an interface"
      (doseq [form ['(f/Job2) '(f/Job2 (fn [] 1)) '(f/Pred (fn [x] true))]]
        (is (str/includes? (m form) "has no public constructor: it is an interface") (m form)))
      (is (str/includes? (m '(f/Job2)) "kt/reify")))
    (testing "an enum class: the entries are named"
      (doseq [form ['(f/Color) '(f/Color "f00") '(f/Color 1 2)]]
        (let [e (m form)]
          (is (str/includes? e "`Color` has no public constructor: it is an enum class") e)
          (is (str/includes? e "Color.RED, Color.GREEN") e))))
    (testing "an object called as a function"
      (doseq [form ['(f/Registry) '(f/Registry 1) '(f/Registry "k")]]
        (is (str/includes? (m form) "`Registry` is an object, not a function") (m form))))
    (testing "an enum entry called as a function"
      (is (str/includes? (m '(f/Color.RED)) "`Color.RED` is an enum entry, not a function")))
    (testing "the dynamic path says the same for a class"
      (doseq [[v args] [[#'t/Duration [5]] [#'f/Color [1]] [#'f/AbstractThing []]]]
        (let [e (thrown #(rt/call-dyn v args {}))]
          (is (str/includes? (ex-message e) "has no public constructor") (ex-message e)))))))

;; ---------------------------------------------------------------- reified bridge with class-level generics

(deftest reified-bridge-class-level-generics
  (testing "a member that takes the class's T"
    (is (= 7 (f/.conv (f/GBox (long 5)) 7 :<> Long)))
    (is (nil? (f/.conv (f/GBox (long 5)) "s" :<> Long)))
    (is (= "s" (f/.conv (f/GBox (long 5)) "s" :<> String)))
    (is (nil? (f/.conv (f/GBox (long 5)) nil :<> String)) "T is nullable")
    (is (= 5 (f/.only (f/GBox (long 5)) :<> Long)) "a member that does not mention T keeps working"))
  (testing "a class-level type parameter with a bound"
    (let [b (f/NBox (long 5))]
      (is (= 7 (f/.conv b 7 :<> Long)))
      (is (= 5 (f/.back b :<> Long)))
      (is (nil? (f/.back b :<> String)))
      (is (= [1 2] (f/.keep b [1 2 3.5] :<> Long)) "List<T> parameter, List<R> result")))
  (testing "two class-level parameters, one with a bound that mentions itself"
    (is (= "x" (f/.mix (f/Pairy "x" "y") "x" "y" :<> String)))
    (is (= "y" (f/.mix (f/Pairy nil "y") nil "y" :<> String))))
  (testing "a member that a subclass inherits"
    (is (= "s" (f/.asR (f/SubG "t") "s" :<> String))))
  (testing "a reified extension on a generic receiver"
    (is (= "a" (f/.firstIs [1 "a" 2.5] :<> String)))
    (is (= 2.5 (f/.firstIs [1 "a" 2.5] :<> Double)))
    (is (nil? (f/.firstIs [] :<> String)))
    (is (= 1 (f/.pick {"a" 1} "a" :<> [Any Long])))
    (is (nil? (f/.pick {"a" 1} "a" :<> [Any String])))
    (is (= 1 (f/.pick {"a" 1} "a" :<> [Long Long]))))
  (testing "the call is static, through a bridge"
    (let [e (ct/expansions '(f/.conv (f/GBox (long 5)) 7 :<> Long))]
      (is (some #(re-find #"ckway\.bridge\.K_" (pr-str %)) (:static e)))
      (is (empty? (:dynamic e))))))

;; ---------------------------------------------------------------- two context parameters of the same type

(deftest same-type-context-parameters
  (testing "a non-reified function is a direct JVM call: each parameter gets its own argument, in both orders"
    (is (= "x|y" (f/.bothPlain (f/Lbl "x") (f/Lbl "y"))))
    (is (= "y|x" (f/.bothPlain (f/Lbl "y") (f/Lbl "x"))))
    (is (= "x|y" (rt/call-dyn #'f/.bothPlain [(f/Lbl "x") (f/Lbl "y")] {}))))
  (testing "reified, context parameters of different types: bound by position"
    (is (= "x|1|Int" (f/.mixed (f/Lbl "x") (f/Num 1) :<> Int)))
    (is (= "z|2|String" (f/.mixed (f/Lbl "z") (f/Num 2) :<> String))))
  (testing "reified, two of the same type: Kotlin itself binds both to the innermost `with` (checked with kotlinc: a
            hand-written with(x) { with(y) { both<Int>() } } gives \"y|y\"), so the call is refused, not made wrongly"
    (let [m (ct/compile-error '(f/.both (f/Lbl "x") (f/Lbl "y") :<> Int))]
      (is (str/includes? m "context parameters `a` and `b` have the same type `fx.Lbl`") m)
      (is (str/includes? m "Kotlin has no syntax to pass them apart") m))
    (is (str/includes? (str (ct/compile-error '(f/.both (f/Lbl "y") (f/Lbl "x") :<> Int))) "same type"))))

;; ---------------------------------------------------------------- erased generic positions and number width

(deftest known-type-argument-converts
  (testing "T exactly: parameter and result"
    (is (instance? Integer (f/sameT 1 :<> Int)))
    (is (instance? Long (f/sameT 1 :<> Long)))
    (is (= [Integer Integer] (mapv class (f/listOfT 1 2 :<> Int)))))
  (testing "function-typed parameters that mention T: arguments and result"
    (let [seen (atom nil) r (f/applyT 1 (fn [x] (reset! seen (class x)) (inc x)) :<> Int)]
      (is (instance? Integer r) "the adapter's Long result went to Kotlin as an Integer")
      (is (= Integer @seen)))
    (is (instance? Integer (f/twiceT 1 (fn [x] (inc x)) :<> Int)))
    (is (instance? Integer (f/suspT 1 (fn [x] (inc x)) :<> Int)) "suspend (T) -> T")
    (is (instance? Integer (f/nullT 1 (fn [x] (inc x)) :<> Int)) "(T?) -> T?")
    (is (instance? Integer (f/recvT 1 (fn [x] (inc x)) :<> Int)) "T.() -> T")
    (is (= 3 (f/useFn (fn [x] (inc x)) 2 :<> Int)) "Kotlin casts the result to Int"))
  (testing "a fun interface whose method has the interface's T"
    (let [r (f/viaConv (fn [x] (inc x)) 1 :<> Int)]
      (is (instance? Integer r))
      (is (= 2 r)))
    (is (= 3 (f/useConv (fn [x] (inc x)) 2 :<> Int))))
  (testing "vararg of T"
    (is (= [Integer Integer] (mapv class (f/varT 1 2 :<> Int))))
    (is (= [Long Long] (mapv class (f/varT 1 2 :<> Long))))
    (is (= [] (f/varT :<> Int))))
  (testing "a nil for a nullable T?"
    (is (nil? (f/nullT nil (fn [x] x) :<> Int)))))

(deftest unknown-type-argument-leaves-the-value-alone
  (testing "without :<> a Clojure integer in a T position stays a Long, a literal or not"
    (is (instance? Long (f/sameT (long 1))))
    (is (false? (f/isIntT (long 1))))
    (is (instance? Long (f/sameT 1)))
    (is (false? (f/isIntT 1)))
    (is (true? (f/isIntT 1 :<> Int)))
    (let [seen (atom nil)]
      (f/applyT 1 (fn [x] (reset! seen (class x)) (inc x)))
      (is (= Long @seen)))))

(deftest class-cast-exception-surfaces-with-the-kotlin-frame
  (testing "static path, dynamic path: not wrapped, the Kotlin frame is the first one"
    (doseq [e [(thrown #(f/boxedT (long 1))) (thrown #(rt/call-dyn #'f/boxedT [(long 1)] {}))]]
      (is (instance? ClassCastException e) (str (class e)))
      (is (str/includes? (ex-message e) "java.lang.Long cannot be cast to class java.lang.Integer") (ex-message e))
      (let [fr (first (.getStackTrace ^Throwable e))]
        (is (= "fx.HardeningKt" (.getClassName fr)))
        (is (= "boxedT" (.getMethodName fr)))
        (is (= "Hardening.kt" (.getFileName fr))))))
  (testing "out of an adapter: the Clojure function's Long reaches a Kotlin Int cast"
    (let [e (thrown #(f/useConv (fn [x] (inc x)) 2))
          e2 (thrown #(f/useFn (fn [x] (inc x)) 2))]
      (doseq [e [e e2]]
        (is (instance? ClassCastException e) (str (class e)))
        (is (= "fx.HardeningKt" (.getClassName (first (.getStackTrace ^Throwable e))))
            (pr-str (take 3 (.getStackTrace ^Throwable e))))))))

;; ---------------------------------------------------------------- objects are initialised by kt/require

(def ^:private h8-ns (create-ns 'ckway.hardening-h8))
(defn- ev8 [form] (binding [*ns* h8-ns] (eval form)))
(defn- root [^Throwable e] (loop [t e] (if-let [c (ex-cause t)] (recur c) t)))

(deftest failing-object-initialiser-does-not-fail-require
  (System/clearProperty "ckway.init.probe")
  (binding [*ns* h8-ns] (refer-clojure))
  (testing "an object is initialised when its package is required (the var holds the instance)"
    (is (nil? (System/getProperty "ckway.init.probe")))
    (binding [*ns* h8-ns] (kt/require '[fx.init :as i]))
    (is (= "1" (System/getProperty "ckway.init.probe")) "Probe was initialised by kt/require, once")
    (binding [*ns* h8-ns] (kt/require '[fx.init :as i]))
    (is (= "1" (System/getProperty "ckway.init.probe")) "not again")
    (is (= 1 (ev8 '(i/n i/Probe)))))
  (testing "the other vars of the package are made"
    (is (= 1 (ev8 '(i/ok))))
    (is (= 2 (ev8 '(i/y i/Good))))
    (is (= 5 (ev8 '(i/v (i/Plain 5)))))
    (is (some? (resolve 'ckway.pkg.fx.init/Bad)) "the var of the failing object exists"))
  (testing "using the failing object throws an error that names it and carries the cause: static path"
    (doseq [form ['(i/x i/Bad) '(i/.m i/Bad)]]
      (let [e (thrown #(ev8 form))]
        (is (some? e) (pr-str form))
        (is (str/includes? (ex-message e) "object `Bad` (fx.init.Bad) failed to initialise") (ex-message e))
        (is (str/includes? (ex-message e) "boom") (ex-message e))
        (is (:kt/error (ex-data e)))
        (is (instance? IllegalStateException (root e)))
        (is (= "boom" (ex-message (root e)))))))
  (testing "dynamic path"
    (let [e (thrown #(ev8 '(ckway.rt/call-dyn #'i/x [i/Bad] {})))]
      (is (str/includes? (ex-message e) "object `Bad` (fx.init.Bad) failed to initialise") (str e))
      (is (instance? IllegalStateException (root e)))))
  (testing "the failing var as a value says what it is"
    (is (str/includes? (str (ev8 'i/Bad)) "failed to initialise")))
  (testing "a second kt/require (the JVM class is now in an error state) gives the same error with the same cause"
    (binding [*ns* h8-ns] (kt/require '[fx.init :as i2]))
    (let [e (thrown #(ev8 '(i2/x i2/Bad)))]
      (is (str/includes? (ex-message e) "failed to initialise"))
      (is (= "boom" (ex-message (root e))) "the original cause, not NoClassDefFoundError")))
  (testing "a type form with the failing object still resolves"
    (is (= "fx.init.Bad" (ty/source (ty/resolve-form h8-ns 'i/Bad))))))

;; ---------------------------------------------------------------- the limits in doc/limits.md are real

(def ^:dynamic *dyn* :root)

(deftest limits
  (testing "a plain ThreadLocal is not visible in a suspend body (it runs on a virtual thread)"
    (let [tl (ThreadLocal.)]
      (.set tl "caller")
      (is (nil? (f/runIt (fn [x] (.get tl)))))
      (is (= "caller" (.get tl)))))
  (testing "set! on a var that is bound outside the body: not the body's thread"
    (let [e (thrown #(binding [*dyn* :outer] (f/runIt (fn [x] (set! *dyn* :inner)))))]
      (is (instance? IllegalStateException e))
      (is (str/includes? (ex-message e) "Can't set!")))
    (is (= :outer (binding [*dyn* :outer] (f/runIt (fn [x] *dyn*)))) "a read sees the binding")
    (is (= :c (f/runIt (fn [x] (binding [*dyn* :b] (set! *dyn* :c) *dyn*)))) "a binding made inside the body can be set!"))
  (testing "catch Exception in a body swallows the interrupt of a cancel: the body goes on"
    (let [sc (f/defaultScope)
          t0 (System/nanoTime)
          job (f/launchIt sc (fn [x] (try (Thread/sleep 10000) (catch Exception _ :swallowed)) (Thread/sleep 500) :done))]
      (Thread/sleep 100)
      (co/.cancel job)
      (loop [] (when-not (.isCompleted ^kotlinx.coroutines.Job job) (Thread/sleep 5) (recur)))
      (is (>= (/ (- (System/nanoTime) t0) 1e6) 550) "the job completed only after the rest of the body")
      (f/cancelScope sc)))
  (testing "a function type with more than 20 parameters: Clojure function is refused, a Kotlin function value works"
    (is (str/includes? (str (ct/compile-error '(f/useWide (fn [& xs] 1)))) "function types with more than 20 parameters is not supported yet"))
    (let [g (f/wideFn)]
      (is (= 21 (apply g (repeat 21 1))))
      (is (java.lang.reflect.Proxy/isProxyClass (class g)) "above 20 parameters it is a Proxy...")
      (is (instance? java.lang.reflect.UndeclaredThrowableException (thrown #(apply (f/wideThrowing) (repeat 21 1))))
          "...so a checked exception arrives wrapped (the fix for a checked exception covers up to 20 parameters)")))
  (testing "kt/reify: more than 20 parameters with `this`"
    (is (str/includes? (str (ct/compile-error '(kt/reify f/Wide20 (.w [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20] 1))))
                       "more than 20 parameters (with `this`)")))
  (testing "a constructor of an inner class"
    (is (str/includes? (str (ct/compile-error '(f/OuterI.In 1))) "inner-class constructors is not supported yet")))
  (testing "references to a suspend function and to a reified function"
    (is (str/includes? (str (ct/compile-error '(kt/ref f/step))) "suspend"))
    (is (str/includes? (str (ct/compile-error '(kt/ref f/typeName))) "reified")))
  (testing "an inline reified property"
    (is (str/includes? (str (ct/compile-error '(f/refName "x" :<> String))) "inline reified properties is not supported yet")))
  (testing "a reified call needs :<> and the static path"
    (is (str/includes? (str (ct/compile-error '(f/typeName))) "is `inline reified`"))))
