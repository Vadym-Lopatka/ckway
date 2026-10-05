(ns ckway.call-test
  (:require [clojure.repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [ckway.core :as kt]
            [ckway.resolve :as r]
            [ckway.rt :as rt])
  (:import [java.io StringWriter]))

(kt/require '[fx :as f] '[fx.other :as o] '[kotlin.time :as t] '[kotlin :as kk])

;; ---------------------------------------------------------------- helpers

(defmacro both
  "Assert `expected` for the call on the static path (as written) and on the dynamic path
  (ckway.rt/call-dyn with the same arguments)."
  [expected f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))]
    `(do (is (= ~expected (~f ~@args)) (str "static " '~f))
         (is (= ~expected (rt/call-dyn (var ~f) [~@pos] ~nm)) (str "dynamic " '~f)))))

(defn root-message [^Throwable t]
  (ex-message (loop [t t] (if-let [c (.getCause t)] (recur c) t))))

(defn- eval-here [form]
  (binding [*ns* (the-ns 'ckway.call-test)] (eval form)))

(defn eval-here-fn
  "The function that `form` (a `fn` form) evaluates to, compiled in the test namespace."
  [form]
  (eval-here form))

(defn compile-error
  "Message of the error that compiling/evaluating `form` throws, or nil."
  [form]
  (try (eval-here form) nil (catch Throwable t (root-message t))))

(defn reflection-output
  "What the compiler prints to *err* while compiling `form` with *warn-on-reflection* on."
  [form]
  (let [w (StringWriter.)]
    (binding [*warn-on-reflection* true *err* w] (eval-here form))
    (str w)))

(defn inline-expansion
  "Fully macroexpanded static-path expansion of the kt call `form` (its own level only)."
  [form]
  (binding [*ns* (the-ns 'ckway.call-test)]
    (let [v (ns-resolve 'ckway.call-test (first form))]
      (walk/macroexpand-all (apply (:inline (meta v)) (rest form))))))

(defn expansions
  "The forms that the static path emits while the compiler compiles `form` (every kt call in it,
  also nested ones, with the real compile-time environment), and the dynamic-path forms.
  => {:static [form ...] :dynamic [form ...] :warnings text}"
  [form]
  (let [static (atom []) dyn (atom [])
        emit r/emit dynf r/dynamic-form
        w (StringWriter.)]
    (with-redefs [r/emit (fn [p] (let [x (emit p)] (swap! static conj x) x))
                  r/dynamic-form (fn [v parsed] (let [x (dynf v parsed)] (swap! dyn conj x) x))]
      (binding [*warn-on-reflection* true *err* w]
        (eval-here form)))
    {:static @static :dynamic @dyn :warnings (str w)}))

;; ---------------------------------------------------------------- 2. values, static and dynamic

(deftest top-level-functions
  (both "Hello, Bob!" f/greet "Bob")
  (both "Hello, Bob?" f/greet "Bob" :punct "?")
  (both "Yo, Bob?" f/greet "Bob" "Yo" "?")
  (both "Yo, Bob!" f/greet "Bob" :greeting "Yo")
  (both "Hello, Bob!" f/greet :name "Bob")
  (both 12 f/twoLongs 1 2)
  (both "string:1" f/eq "a" 1)
  (both "int:2" f/eq 1 2)
  (both 42 f/answer)
  (both "Hi x" f/.ctxHello "Hi" "x")
  (both 3 f/multiA 2)
  (both 4 f/multiB 2))

(deftest primitives-and-defaults
  (both "1.5 a 1 2 3.0 true 4 5" f/prims)
  (both "1.5 z 1 2 3.0 false 1 5" f/prims :l 1 :flag false :c \z)
  (both "2.5 a 1 2 3.0 true 4 5" f/prims 2.5)
  (both "1.5 a 7 2 3.0 true 4 5" f/prims :b 7)
  (both "1.5 a 1 2 3.0 true 4 9" f/prims :i 9)
  (testing "33 parameters, two masks"
    (both (str "5," (str/join "," (range 2 33)) ",7") f/many :a33 7 :a1 5)
    (both (str/join "," (range 1 34)) f/many)))

(deftest varargs
  (both "a,b,c" f/join "a" "b" "c")
  (both "" f/join)
  (both "x,y" f/join :parts ["x" "y"])
  (both "a-b" f/joinSep "a" "b" :sep "-")
  (both "a,b" f/joinSep "a" "b")
  (both "p|q" f/joinSep :parts ["p" "q"] :sep "|")
  (both "" f/joinSep :sep "|"))

(deftest overloads-and-nullables
  (both nil f/nick nil)
  (both "x" f/nick " x ")
  (both 5 f/nickLen 4)
  (both nil f/nickLen nil)
  (testing "an Int and a Long overload: a run-time Long value is a Long, an Integer is an Int"
    (is (= "long" (rt/call-dyn #'f/amb [1] {})))
    (is (= "int" (rt/call-dyn #'f/amb [(int 1)] {})))))

(deftest unit-and-top-level-state
  (let [before (f/sideEffect)]
    (is (nil? (f/ping)))
    (is (nil? (rt/call-dyn #'f/ping [] {})))
    (is (= (+ before 2) (f/sideEffect)))))

(deftest extensions
  (both "AB!" f/.shout "ab")
  (both "AB!!!" f/.shout "ab" :times 3)
  (both 3 f/wordCount "a b c")
  (let [u (f/User 1 "ann")]
    (both "a" f/.initial u)
    (both "ANN" f/upper u)
    (both "ANN!" o/.shoutName u)))

(deftest classes-and-members
  (let [u (f/User 7 "bob")]
    (testing "constructors: defaults, secondary, named"
      (is (= [7 "bob" nil] ((juxt #(f/id %) #(f/name %) #(f/email %)) u)))
      (is (= [0 "ann" nil] (let [x (f/User "ann")] [(f/id x) (f/name x) (f/email x)])))
      (is (= "e@x" (f/email (f/User 1 :email "e@x"))))
      (is (= [1 "anon"] (let [x (rt/call-dyn #'f/User [1] {})] [(f/id x) (f/name x)])))
      (is (= "a@b" (f/email (rt/call-dyn #'f/User [1] {"email" "a@b"}))))
      (is (= "zed" (f/name (rt/call-dyn #'f/User ["zed"] {})))))
    (both "Hi bob" f/.greet u)
    (both "Yo bob" f/.greet u "Yo")
    (both "Yo bob" f/.greet u :greeting "Yo")
    (both "7 5" f/.ov u 7)
    (both "7 1" f/.ov u 7 1)
    (testing "a property and a method with the same name"
      (both "p" f/path u)
      (both "p:x" f/.path u "x"))
    (both "nick" f/nickname u)
    (both true f/.same u u)
    (is (= "Pt(x=1, y=5)" (str (f/.copy (f/Pt 1) :y 5))))
    (is (= "Pt(x=9, y=2)" (str (rt/call-dyn #'f/.copy [(f/Pt 1 2)] {"x" 9}))))
    (both 3 f/x (f/Pt 3))))

(deftest objects-companions-nested-enums
  (both "v:k" f/.lookup f/Registry "k")
  (both 3 f/size f/Registry)
  (both 0 f/level f/Registry)
  (both "made" f/.make f/WithCompanion)
  (both "wc" f/NAME f/WithCompanion)
  (both 7 f/other f/WithCompanion)
  (both 8 f/.twice (f/Outer.Inner 4))
  (both "f00" f/hex f/Color.RED)
  (is (= "RED" (str f/Color.RED)))
  (testing "defaulted constructor of a data class"
    (is (= "Pt(x=1, y=0)" (str (f/Pt 1))))
    (is (= "Pt(x=1, y=0)" (str (rt/call-dyn #'f/Pt [1] {}))))))

;; ---------------------------------------------------------------- 3. static path proof

(def ^:private proofs
  [["greet$default" '(f/greet "Bob")]
   ["shout$default" '(f/.shout "ab")]
   ["getWordCount" '(f/wordCount "a b")]
   ["copy$default" '(let [^fx.Pt p (f/Pt 1)] (f/.copy p :y 2))]
   ["lookup" '(f/.lookup f/Registry "k")]
   ["-Companion" '(f/.make f/WithCompanion)]
   ["(new fx.Pt" '(f/Pt 1)]])

(deftest static-path-is-direct-interop
  (testing "control: the capture sees a real reflection warning"
    (is (str/includes? (reflection-output '(fn [s] (.length s))) "Reflection warning")))
  (doseq [[needle form] proofs]
    (testing (pr-str form)
      (let [expansion (inline-expansion (if (= 'let (first form)) (last form) form))]
        (is (str/includes? (pr-str expansion) needle) (pr-str expansion))
        (is (not (str/includes? (pr-str expansion) "call-dyn")))
        (is (= "" (reflection-output form)) (pr-str form))))))

(deftest macroexpansions-for-the-report
  (doseq [[_ form] proofs]
    (println "EXPANSION" (pr-str form) "=>" (pr-str (inline-expansion (if (= 'let (first form)) (last form) form))))))

(deftest unknown-types-use-the-dynamic-path
  (testing "several candidates by name and arity, receiver type unknown: dynamic call + warning"
    (let [out (reflection-output '(fn [a] (f/.equals a a)))]
      (is (str/includes? out "Reflection warning"))
      (is (str/includes? out ".equals"))))
  (testing "one candidate: static even when the type is unknown"
    (is (= "" (reflection-output '(fn [u] (f/.initial u))))))
  (testing "it works"
    (is (= "int:3" ((eval-here '(fn [a] (f/eq a 3))) 5)))
    (is (= "string:3" ((eval-here '(fn [a] (f/eq a 3))) "s")))))

;; ---------------------------------------------------------------- 4. argument errors

(deftest named-argument-errors
  (testing "unknown name"
    (let [m (compile-error '(f/greet "Bob" :nope 1))]
      (is (str/includes? m "unknown parameter name `nope`"))
      (is (str/includes? m "fun greet(name: String, greeting: String = ..., punct: String = ...): String"))))
  (testing "name given twice"
    (let [m (compile-error '(f/greet "Bob" :punct "?" :punct "!"))]
      (is (str/includes? m "parameter `punct` given twice"))
      (is (str/includes? m "fun greet("))))
  (testing "positional argument also given by name"
    (is (str/includes? (compile-error '(f/greet "Bob" :name "x")) "parameter `name` given twice")))
  (testing "positional after named"
    (let [m (compile-error '(f/greet :greeting "Yo" "Bob"))]
      (is (str/includes? m "positional argument \"Bob\" follows a named argument"))
      (is (str/includes? m "fun greet("))))
  (testing "keyword without value"
    (is (str/includes? (compile-error '(f/greet "Bob" :punct)) "has no value")))
  (testing "missing required parameter"
    (let [m (compile-error '(f/greet :punct "?"))]
      (is (str/includes? m "missing required parameter `name`"))
      (is (str/includes? m "fun greet("))))
  (testing "too many arguments"
    (is (str/includes? (compile-error '(f/greet "a" "b" "c" "d")) "too many arguments")))
  (testing "missing receiver"
    (is (str/includes? (compile-error '(f/.shout)) "expects 1 receiver")))
  (testing "nil literal at a non-nullable parameter"
    (let [m (compile-error '(f/greet nil))]
      (is (str/includes? m "`nil` passed to non-nullable `name`"))
      (is (str/includes? m "fun greet("))))
  (testing "wrong literal type"
    (is (str/includes? (compile-error '(f/greet 5)) "`name` is String but got Long")))
  (testing "the same errors at run time"
    (is (str/includes? (root-message (try (rt/call-dyn #'f/greet ["Bob"] {"nope" 1}) (catch Throwable t t)))
                       "unknown parameter name `nope`"))
    (is (str/includes? (root-message (try (rt/call-dyn #'f/greet [] {}) (catch Throwable t t)))
                       "missing required parameter `name`"))))

;; ---------------------------------------------------------------- 5. no guess

(deftest ambiguity-is-an-error
  (testing "a function f(x: Box) and a property Box.f are the same call: documented collision"
    (let [b (f/Box "v")
          m (compile-error '(let [^fx.Box b (f/Box "v")] (f/f b)))]
      (is (str/includes? m "ambiguous"))
      (is (str/includes? m "fun f(x: fx.Box): String"))
      (is (str/includes? m "val fx.Box.f: String"))
      (testing "with an unknown type it fails at run time with the candidates"
        (let [m3 (root-message (try ((eval-here '(fn [x] (f/f x))) b) (catch Throwable t t)))]
          (is (str/includes? m3 "ambiguous"))
          (is (str/includes? m3 "val fx.Box.f: String"))))))
  (testing "no declaration fits at all"
    (let [m (compile-error '(f/.shout 5))]
      (is (str/includes? m "no Kotlin declaration of `.shout` fits")))))

(deftest member-wins-over-extension
  (is (= "member" (let [^fx.Dup d (f/Dup)] (f/.who d))))
  (is (= "member" (rt/call-dyn #'f/.who [(f/Dup)] {}))))

(deftest require-twice-refreshes
  (kt/require '[fx :as f])
  (is (= "Hello, Bob!" (f/greet "Bob")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"kt/require expects \[package :as alias\]"
                        (kt/require '[fx]))))

;; ---------------------------------------------------------------- S-F: compile-time class Object (or only an upper bound)

(deftest uninformative-compile-time-classes
  (testing "a local of class Object (doseq, fn parameter, first, get) is 'type unknown', not a mismatch"
    (is (= [2 0] (let [r (atom [])] (doseq [g [inc dec]] (swap! r conj (f/apply1 g 1))) @r))
        "doseq over functions")
    (is (= [2 3] (mapv (fn [n] (f/apply1 inc n)) [1 2])) "Int parameter")
    (is (= ["Hello, a!"] (mapv (fn [s] (f/greet s)) ["a"])) "String parameter")
    (is (= (f/Uid 2) (f/nextUid (first [(f/Uid 1)]))) "value class parameter")
    (is (= "uid:1" (f/.describe (first [(f/Uid 1)]))) "receiver")
    (is (= "a,b" (f/join (first ["a"]) (first ["b"]))) "vararg elements")
    (is (= (f/Uid 2) ((fn [m] (f/nextUid (get m :u))) {:u (f/Uid 1)})) "result of get")
    (is (= 3 (loop [i 0 acc 0] (if (< i 3) (recur (inc i) (f/apply1 inc acc)) acc)))
        "a loop variable that Clojure types as Number")
    (is (= 3 (loop [i 0 acc 0] (if (< i 3) (recur (inc i) (f/apply1 inc (f/apply1 dec (inc acc)))) acc)))))
  (testing "one candidate: the static path, with a run-time check (no dynamic path, no warning)"
    (doseq [form ['(fn [g] (f/apply1 g 1)) '(fn [n] (f/apply1 inc n)) '(fn [s] (f/greet s))
                  '(fn [u] (f/nextUid u)) '(fn [^Number n] (f/apply1 inc n))]]
      (let [x (expansions form)]
        (is (seq (:static x)) (pr-str form))
        (is (empty? (:dynamic x)) (pr-str form))
        (is (= "" (:warnings x)) (pr-str form)))))
  (testing "several candidates: the dynamic path decides by the run-time class"
    (let [x (expansions '(fn [n] (f/amb n)))]
      (is (seq (:dynamic x))))
    (is (= "long" ((eval-here '(fn [n] (f/amb n))) 1)))
    (is (= "int" ((eval-here '(fn [n] (f/amb n))) (int 1)))))
  (testing "wrong values are still errors, at run time"
    ;; (see fixes_test) the cast of an argument of unknown type is a kt error, not a ClassCastException
    (let [e (try ((eval-here '(fn [s] (f/greet s))) 5) (catch Throwable t t))]
      (is (not (instance? ClassCastException e)))
      (is (:kt/error (ex-data e)))
      (is (str/includes? (ex-message e) "the argument `name` (String) is java.lang.Long 5")))
    (is (str/includes? (str (ex-message (try ((eval-here '(fn [n] (f/apply1 inc n))) "x") (catch Throwable t t)))) "kt:")))
  (testing "a known, final, unrelated class is still a compile-time mismatch"
    (is (str/includes? (str (compile-error '(fn [^Long n] (f/greet n)))) "is String but got Long"))
    (is (str/includes? (str (compile-error '(f/greet 5))) "kt:")))
  (testing "Int and Long overloads: both could fit a Number, so the dynamic path decides"
    (is (seq (:dynamic (expansions '(fn [^Number n] (f/amb2 n 1))))))
    (is (= "long2" ((eval-here '(fn [^Number n] (f/amb2 n 1))) 1)))))

;; ---------------------------------------------------------------- 6. not supported yet

(defn- not-supported? [m feature]
  (and (string? m) (str/starts-with? m "kt: ") (str/includes? m (str feature " is not supported yet ("))))

(deftest not-supported-yet
  (testing "suspend functions are supported (see ckway.suspend-test)"
    (both "slow1" f/slow 1))
  (testing "an object that already implements the function type or interface is fine"
    (is (= 2 (f/useCb (reify fx.Cb (call [_ x] (inc x))) 1)))
    (is (= 3 (f/applyTwice (reify kotlin.jvm.functions.Function1 (invoke [_ x] (int (inc x)))) 1))))
  (testing "inline reified and :<> (`:<>` is supported, see ckway.reified-test)"
    (is (str/includes? (compile-error '(f/typeName)) "is `inline reified`: it needs its type arguments"))
    (is (= "String" (f/typeName :<> String))))
  (testing "calling an interface / enum class"
    (is (str/includes? (compile-error '(f/Cb)) "has no public constructor"))
    (is (str/includes? (compile-error '(f/Color)) "has no public constructor"))))

;; ---------------------------------------------------------------- 7. vars as values

(deftest vars-as-values
  (is (= ["A!" "B!"] (map f/.shout ["a" "b"])))
  (is (= "Hello, Bob!" (apply f/greet ["Bob"])))
  (is (= "Hello, Bob?" (apply f/greet ["Bob" "Hello" "?"])))
  (is (= ["Hello, A!" "Hello, B!"] (map (partial f/greet) ["A" "B"])))
  (is (str/includes? (with-out-str (clojure.repl/doc f/greet))
                     "fun greet(name: String, greeting: String = ..., punct: String = ...): String"))
  (is (str/includes? (:doc (meta #'f/.shout)) "fun String.shout(times: Int = ...): String"))
  (is (= '([name greeting punct]) (:arglists (meta #'f/greet))))
  (is (fn? (:inline (meta #'f/greet)))))

(deftest keywords-are-always-names
  (testing "a keyword literal names a parameter, there is no heuristic"
    (is (str/includes? (compile-error '(f/join :a "b")) "unknown parameter name `a`"))))

;; ---------------------------------------------------------------- integer literals, inherited members, companion receivers, nested typing

(deftest integer-literal-follows-kotlin
  (testing "a literal that fits Int is an Int (static path)"
    (is (= "int" (f/amb 1)))
    (is (= "int" (f/amb -5)))
    (is (= "int" (f/amb 2147483647))))
  (testing "a literal that does not fit Int is a Long"
    (is (= "long" (f/amb 2147483648)))
    (is (= "long" (f/amb -2147483649))))
  (testing "1N, a cast, a primitive hint and a hinted local say Long (or Int for `int`)"
    (is (= "long" (f/amb 1N)))
    (is (= "long" (f/amb (long 1))))
    (is (= "long" (let [x (long 1)] (f/amb x))))
    (is (= "long" (let [^Long x (Long/valueOf 1)] (f/amb x))))
    (is (= "int" (f/amb (int 1))))
    (is (= "int" (let [^Integer x (Integer/valueOf 1)] (f/amb x)))))
  (testing "a run-time value uses its exact class (unknown type, so the dynamic path)"
    (let [g (eval-here '(fn [x] (f/amb x)))]
      (is (= "long" (g 1)))
      (is (= "long" (g (Long/valueOf 1))))
      (is (= "int" (g (int 1))))))
  (testing "a call that takes the dynamic path because of another argument keeps the literal's Kotlin type"
    (let [g (eval-here '(fn [x] [(f/amb2 1 x) (f/amb2 5000000000 x) (f/amb2 1 :b x) (f/amb2 1N x)]))]
      (is (seq (:dynamic (expansions '(fn [x] (f/amb2 1 x))))))
      (is (= ["int2" "long2" "int2" "long2"] (g "s")))))
  (testing "dynamic path: same by class"
    (is (= "long" (rt/call-dyn #'f/amb [1] {})))
    (is (= "int" (rt/call-dyn #'f/amb [(int 1)] {}))))
  (testing "with one overload a literal still widens (Int literal to a Long parameter)"
    (both 12 f/twoLongs 1 2))
  (testing "a literal that does not fit an Int parameter is an error"
    (let [m (compile-error '(f/nickLen 5000000000))]
      (is (str/includes? m "the argument `n` (Int?) is 5000000000, which is out of range for Int") m)))
  (testing "a floating literal is a Double"
    (is (= "double" (f/fl 1.5)))
    (is (= "float" (f/fl (float 1.5))))
    (is (= "double" (rt/call-dyn #'f/fl [1.5] {})))
    (is (= "float" (rt/call-dyn #'f/fl [(float 1.5)] {})))))

(deftest inherited-members
  (let [sub (o/Sub) deleg (f/Deleg (f/NamedImpl)) impl (f/BaseImpl)]
    (testing "interface member, member with a body in the interface, property with a body"
      (both "sub-hello" f/.hello sub)
      (both "base-impl" f/.hello impl)
      (both "hi" f/.hello deleg)
      (both "bye" f/.bye sub)
      (both "bye" f/.bye deleg)
      (both "label" f/label sub))
    (testing "through an abstract class in another package, also with defaults"
      (both "shared:sub-hello" o/.shared sub)
      (both "sub" o/tag sub)
      (both "sub:all" o/.greetAll sub)
      (both "sub:x" o/.greetAll sub "x")
      (both "all-base-impl" f/.greetAll impl)
      (both "own" o/.own sub))
    (testing "from a Kotlin interface in another jar (kotlin-stdlib)"
      (both 7 f/value (f/MyLazy))
      (both true f/.isInitialized (f/MyLazy)))
    (testing "the call is virtual and direct on the static path"
      (let [e (pr-str (inline-expansion '(o/.hello ^fx.other.Sub s)))]
        (is (str/includes? e "(hello") e)
        (is (not (str/includes? e "call-dyn"))))
      (is (= "" (reflection-output '(fn [^fx.other.Sub s] (o/.hello s))))))
    (testing "the same inherited declaration in two namespaces is the same call"
      (is (= (:kt/decls (meta #'f/.hello)) (:kt/decls (meta #'o/.hello))))
      (is (= (:kt/decls (meta #'f/.shared)) (:kt/decls (meta #'o/.shared))))
      (let [norm (fn [x] (-> (pr-str x) (str/replace #"\(quote \{:(?:ns|call) [^}]*\}\)" "SITE") (str/replace #"\d+" "N")))]
        (is (= (norm (inline-expansion '(f/.hello x))) (norm (inline-expansion '(o/.hello x)))))))))

(deftest companion-receiver-is-checked
  (testing "known type: compile error"
    (doseq [form ['(f/.make "s") '(f/.make f/User) '(f/.make (f/User 1)) '(f/.make 1)]]
      (is (str/includes? (compile-error form) "expects the class var of fx.WithCompanion") (pr-str form))))
  (testing "nil is an error too"
    (is (str/includes? (compile-error '(f/.make nil)) "`nil` passed to non-nullable")))
  (testing "unknown type: the dynamic path checks at run time"
    (let [call (eval-here '(fn [x] (f/.make x)))]
      (is (= "made" (call f/WithCompanion)))
      (doseq [bad ["s" f/User 3 nil]]
        (is (some? (re-find #"expects the class var of fx.WithCompanion|nil. passed to non-nullable"
                            (str (root-message (try (call bad) (catch Throwable t t))))))
            (pr-str bad)))
      (is (str/includes? (:warnings (expansions '(fn [x] (f/.make x)))) "dynamic path"))))
  (testing "the class var itself stays static"
    (let [x (expansions '(fn [] (f/.make f/WithCompanion)))]
      (is (empty? (:dynamic x)))
      (is (= "" (:warnings x))))))

(deftest nested-call-typing
  (testing "the Kotlin return type of an inner kt call types the outer argument or receiver"
    (doseq [[form v] [['(f/.initial (f/User 1)) "a"]
                      ['(-> (f/Pt 1) (f/.copy :y 5) (f/x)) 1]
                      ['(f/.who (f/Dup)) "member"]
                      ['(f/.hello (f/BaseImpl)) "base-impl"]
                      ['(f/.hello (f/Deleg (f/NamedImpl))) "hi"]]
            :let [x (expansions `(fn [] ~form))]]
      (testing (pr-str form)
        (is (seq (:static x)))
        (is (empty? (:dynamic x)) (pr-str (:dynamic x)))
        (is (not (str/includes? (:warnings x) "Reflection warning")) (:warnings x))
        (is (not (str/includes? (pr-str (:static x)) "call-dyn")))
        (println "EXPANSION" (pr-str form) "=>" (str/join " | " (map pr-str (:static x))))
        (is (= v ((eval-here `(fn [] ~form)))) (pr-str form)))))
  (testing "control: without the type the same call is dynamic"
    (let [x (expansions '(fn [d] (f/.who d)))]
      (is (seq (:dynamic x)))
      (is (str/includes? (:warnings x) "using the dynamic path"))))
  (testing "an inner error is reported when the inner call is compiled"
    (is (str/includes? (compile-error '(f/.initial (f/User :nope 1))) "unknown parameter name `nope`"))))
