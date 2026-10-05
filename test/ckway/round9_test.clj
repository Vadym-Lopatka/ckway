(ns ckway.round9-test
  "Fixes after the spikes (http4k, Koin, Ktor). Batch A: argument binding and overload selection.
  A1: a property never wins silently over a function of the same name. A2: a trailing lambda. A3: a collection passed
  as a whole to a `vararg`, on the dynamic path. A4: overloads that differ only in the parameter types of a lambda.
  Batch B: declarations and function values. B1: a companion `operator fun invoke` is a constructor form. B2: `invoke` on
  a class that implements a Kotlin function type. B3: the class var is the receiver of an extension on a Companion.
  B4: a Clojure function that a `kt/reify` member returns is adapted to the declared type.
  Batch C: nil checks, type hints, one error text. C1: nil for a type parameter with a non-null bound. C2: a literal and an
  enum entry var have a static type. C3: the tag of a function is the type of a call of it. C4: a class that was made at
  run time as a `:<>` type argument. C5: nil for a function parameter of a Kotlin function value.
  The Kotlin shapes are in `test-fixtures/fx/Round9.kt`. Each case is tried on the static path (`eval` of the call),
  on the dynamic path (`rt/call-dyn`) and, where it matters, as a var value."
  (:require [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.bridge]
            [ckway.bridge.kotlinc]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.resolve :as r]
            [ckway.rt :as rt]
            [ckway.set]))

(kt/require '[fx.r9 :as d])

(defn- eval-here [form] (binding [*ns* (the-ns 'ckway.round9-test)] (eval form)))

(defn- outcome
  "The value of `(f)`, or {:error root-message} when it throws."
  [f]
  (try (f) (catch Throwable t {:error (ct/root-message t)})))

(defn- static [form] (outcome #(eval-here form)))
(defn- dynamic
  ([v pos] (dynamic v pos {}))
  ([v pos named] (outcome #(rt/call-dyn v (vec pos) named))))
(defn- as-value [v & args] (outcome #(apply @v args)))

(defn- error-has? [r & parts]
  (and (map? r) (string? (:error r)) (every? #(str/includes? (:error r) %) parts)))

(defn- compile-error
  "The message when the Clojure compiler rejects `form` (a form that is only compiled, not run), else nil."
  [form]
  (try (eval-here (list 'fn [] form)) nil
       (catch Throwable t (ct/root-message t))))

(def ^:private route (d/Route9 "x"))
(def ^:private box (d/Box9 "b"))
(def ^:private cfg (d/Cfg9))

;; ---------------------------------------------------------------- A1

;; `routes9(vararg list: Route9)` and `val Route9.routes9` are one var, and `(d/routes9 r)` fits both.

(deftest a1-a-function-and-a-property-of-one-name-are-ambiguous
  (testing "an extension property and a function: the hint makes the argument type certain (static path)"
    (let [m (compile-error '(d/routes9 ^fx.r9.Route9 (identity nil)))]
      (is (str/includes? (str m) "is ambiguous") (str m))))
  (testing "a member property and a function"
    (let [m (compile-error '(d/items9 ^fx.r9.Box9 (identity nil)))]
      (is (str/includes? (str m) "is ambiguous") (str m))))
  (testing "the dynamic path: the same error, the same text"
    (let [r (dynamic #'d/routes9 [route])]
      (is (error-has? r "kt: (routes9" "is ambiguous" "val fx.r9.Route9.routes9" "fun routes9(vararg list: fx.r9.Route9)"
                      "a function and a property are both named `routes9`") r))
    (is (error-has? (dynamic #'d/items9 [box]) "is ambiguous" "val fx.r9.Box9.items9" "fun items9(box: fx.r9.Box9)")))
  (testing "a local whose class only the compiler inferred: the run time decides, and it is ambiguous"
    (is (error-has? (static '(let [r (d/Route9 "x")] (d/routes9 r))) "is ambiguous" "routes9"))
    (is (error-has? (static '(let [b (d/Box9 "b")] (d/items9 b))) "is ambiguous" "items9")))
  (testing "the var as a value"
    (is (error-has? (as-value #'d/routes9 route) "is ambiguous")))
  (testing "a nested call whose result class is known"
    (is (error-has? (static '(d/routes9 (d/Route9 "y"))) "is ambiguous"))))

(deftest a1-the-message-names-the-ways-out
  (let [m (:error (dynamic #'d/routes9 [route]))]
    (is (str/includes? m "(routes9 :list ...)") "the function: name its parameter")
    ;; (D4) the class var as written: no alias of the package in the namespace that runs the test, so the full name
    (is (str/includes? m "((kt/ref fx.r9.Route9 routes9) x)") "the property: kt/ref"))
  (testing "a function without parameters and a property without receiver: no kt form names the property alone"
    (let [m (:error (dynamic #'d/zero9 []))]
      (is (str/includes? m "Java interop") m)
      (is (str/includes? m "(fx.r9.Round9Kt/getZero9)") m)
      (is (not (str/includes? m "kt/ref")) m))))

(deftest a1-the-ways-out-work
  (testing "the function by a named argument"
    (is (= "routes-fun:1" (d/routes9 :list [route])))
    (is (= "routes-fun:1" (dynamic #'d/routes9 [] {"list" [route]})))
    (is (= "items-fun:b" (d/items9 :box box)))
    (is (= "items-fun:b" (dynamic #'d/items9 [] {"box" box})))))

(deftest a1-the-property-by-a-reference
  (is (= ["routes-prop:x"] ((kt/ref d/Route9 routes9) route)))
  (is (= "b" ((kt/ref d/Box9 items9) box)))
  (testing "a top-level property of the same name as a function: the reference is ambiguous too"
    (is (str/includes? (str (compile-error '(kt/ref d/zero9))) "names 2 Kotlin declarations"))))

(deftest a1-nothing-else-changed
  (testing "only one of them fits: the property takes a Route9, the function a Box9 (static, dynamic, var)"
    (is (= "pick-prop" (d/pick9 route) (dynamic #'d/pick9 [route]) (as-value #'d/pick9 route)))
    (is (= "pick-fun" (d/pick9 box) (dynamic #'d/pick9 [box]) (as-value #'d/pick9 box)))
    (is (= "pick-prop" (eval-here '(let [^fx.r9.Route9 r (d/Route9 "x")] (d/pick9 r)))))
    (is (= "pick-fun" (eval-here '(let [^fx.r9.Box9 b (d/Box9 "b")] (d/pick9 b))))))
  (testing "a value that fits neither: still the no-fit error"
    (is (error-has? (static '(d/pick9 "s")) "no Kotlin declaration of `pick9` fits"))
    (is (error-has? (dynamic #'d/pick9 ["s"]) "no Kotlin declaration of `pick9` fits"))))

;; ---------------------------------------------------------------- A2

;; The usual binding leaves the LAST parameter without an argument, and it takes a function: the last positional
;; argument goes there, and the others are bound again without it.

(def ^:private lam (fn [] "z"))
(def ^:private lam1 (fn [x] "x"))
(def ^:private lam2 (fn [c s] nil))

(deftest a2-a-trailing-lambda
  (testing "skipped default before the lambda (Koin: module { })"
    (is (= "module9:false:x" (d/module9 (fn [m] "x"))))
    (is (= "module9:false:x" (dynamic #'d/module9 [lam1])))
    (is (= "module9:false:x" (as-value #'d/module9 lam1))))
  (testing "after a vararg (Ktor: status(vararg codes) { })"
    (is (= "only9:1,2" (d/.only9 ^fx.r9.Cfg9 cfg 1 2 (fn [c s] nil))))
    (is (= "only9:1,2" (d/.only9 cfg 1 2 (fn [c s] nil))))
    (is (= "only9:1,2" (dynamic #'d/.only9 [cfg 1 2 lam2])))
    (is (= "only9:" (d/.only9 ^fx.r9.Cfg9 cfg (fn [c s] nil))) "the vararg takes what is left: nothing"))
  (testing "defaults and a vararg before it"
    (is (= "mix9:1:2::z" (d/mix9 (fn [] "z")) (dynamic #'d/mix9 [lam])))
    (is (= "mix9:5:2:a,b:z" (d/mix9 5 2 "a" "b" (fn [] "z")) (dynamic #'d/mix9 [5 2 "a" "b" lam])))
    (is (= "mix9:5:2::z" (d/mix9 5 2 (fn [] "z")))))
  (testing "a function type with a receiver, a fun interface, a Java interface"
    (is (= "n:z" (d/build9 (fn [sb] (.append ^StringBuilder sb "z")))))
    (is (= "pr1" (d/rule9 (fn [x] (str "r" x))) ))
    (is (= "jrule9:true" (d/jrule9 (fn [x] true)))))
  (testing "the same through the dynamic path"
    (is (= "n:z" (dynamic #'d/build9 [(fn [sb] (.append ^StringBuilder sb "z"))])))
    (is (= "pr1" (dynamic #'d/rule9 [(fn [x] (str "r" x))])))
    (is (= "jrule9:true" (dynamic #'d/jrule9 [(fn [x] true)])))))

(deftest a2-a-candidate-that-fits-by-the-usual-binding-always-wins
  (testing "f9(x: Any) against f9(a: Int = 1, block: () -> String), called (f9 g)"
    (is (= "f9-any" (d/f9 (fn [] "z")) (dynamic #'d/f9 [lam]) (as-value #'d/f9 lam))))
  (testing "with an argument for `a`, only the trailing candidate fits: it is a call that was an error"
    (is (= "f9-trailing:2z" (d/f9 2 (fn [] "z")) (dynamic #'d/f9 [2 lam]))))
  (testing "a call that fits one overload by the usual binding: the other one is not looked at"
    (is (= "two9-int:35" (d/two9 3) (dynamic #'d/two9 [3])))
    (is (= "two9-int:34" (d/two9 3 4) (dynamic #'d/two9 [3 4]))))
  (testing "a call that fits no overload by the usual binding: the trailing one"
    (is (= "two9-trailing:7z" (d/two9 (fn [] "z")) (dynamic #'d/two9 [lam]))))
  (testing "an argument of unknown class: the usual candidate is still selected (a run-time check), as before"
    (is (= "f9-any" (eval-here '(let [g (identity (fn [] "z"))] (d/f9 g)))))))

(deftest a2-nothing-else-changed
  (testing "the lambda is named, or the usual binding already gives every parameter an argument"
    (is (= "module9:false:x" (d/module9 :declaration (fn [m] "x"))))
    (is (= "module9:true:x" (d/module9 true (fn [m] "x"))))
    (is (= "mix9:1:2::z" (d/mix9 :tail (fn [] "z"))))
    (is (= "mid9:z3" (d/mid9 (fn [] "z")))))
  (testing "no trailing lambda when the last parameter has a default, or when it is not a function"
    (is (error-has? (static '(d/noTrail9 (fn [] "z"))) "no Kotlin declaration of `noTrail9` fits" "`a` is int"))
    (is (error-has? (dynamic #'d/noTrail9 [lam]) "`a` is int")))
  (testing "no trailing lambda when the others cannot be bound without it"
    (is (error-has? (static '(d/need9 (fn [] "z"))) "missing required parameter `b`"))
    (is (error-has? (dynamic #'d/need9 [lam]) "missing required parameter `b`")))
  (testing "the argument is no function"
    (is (error-has? (static '(d/module9 5)) "missing required parameter `declaration`"))
    (is (error-has? (dynamic #'d/module9 [5]) "missing required parameter `declaration`"))
    (is (error-has? (dynamic #'d/module9 ["s"]) "missing required parameter `declaration`")))
  (testing "no positional argument: nothing to bind"
    (is (error-has? (static '(d/module9)) "missing required parameter `declaration`"))))

(deftest a2-the-error-says-what-works
  (testing "positionally, when a positional argument reaches the parameter"
    (is (error-has? (static '(d/need9 (fn [] "z"))) "Pass it positionally or as `:b`."))
    (is (error-has? (static '(d/Module9 "a" "b")) "too many arguments") "(another error: untouched)"))
  (testing "not after a skipped default, not after a vararg, not after a named argument"
    (let [m (:error (static '(d/module9)))]
      (is (str/includes? m "name it, `:declaration`") m)
      (is (not (str/includes? m "Pass it positionally")) m))
    (let [m (:error (static '(d/mix9 "x")))]
      (is (str/includes? m "missing required parameter `tail`") m)
      (is (str/includes? m "name it, `:tail`") m)
      (is (not (str/includes? m "Pass it positionally")) m))
    (is (str/includes? (:error (static '(d/mix9 :a 1))) "name it, `:tail`"))
    (is (str/includes? (:error (dynamic #'d/mix9 [5 2 "a" 7])) "name it, `:tail`"))))

;; ---------------------------------------------------------------- A3

;; `(d/pr9 :list [r])`: `pr9(vararg list: Pair9)` and `pr9(vararg list: Route9)`. The elements are seen at run time.

(def ^:private pair (d/Pair9 "p"))

(deftest a3-the-elements-of-a-collection-choose-at-run-time
  (testing "the collection is written in the call: its elements are values of unknown class at compile time"
    (is (= "pr9-routes:1" (d/pr9 :list [route])))
    (is (= "pr9-routes:2" (d/pr9 :list [route route])))
    (is (= "pr9-pairs:1" (d/pr9 :list [pair]))))
  (testing "a list, a seq, a set, a java.util.ArrayList"
    (is (= "pr9-routes:1" (d/pr9 :list (list route)) (d/pr9 :list (map identity [route])) (d/pr9 :list #{route})
           (d/pr9 :list (java.util.ArrayList. [route]))))
    (is (= "pr9-pairs:1" (d/pr9 :list (list pair)))))
  (testing "the dynamic path"
    (is (= "pr9-routes:1" (dynamic #'d/pr9 [] {"list" [route]})))
    (is (= "pr9-pairs:1" (dynamic #'d/pr9 [] {"list" [pair]}))))
  (testing "a parameter before the vararg"
    (is (= "a:pt9-pairs:1" (d/pt9 "a" :list [pair])))
    (is (= "a:pt9-routes:1" (d/pt9 "a" :list [route]))))
  (testing "the elements are known at compile time (a vector literal of calls): as before"
    (is (= "pr9-routes:1" (d/pr9 :list [(d/Route9 "q")])))))

(deftest a3-the-selection-is-not-kept-for-other-elements
  (testing "the same call site and the same class of the collection, other elements: the other overload"
    (let [f (fn [xs] (d/pr9 :list xs))]
      (is (= ["pr9-routes:1" "pr9-pairs:1" "pr9-routes:1" "pr9-pairs:2"]
             [(f [route]) (f [pair]) (f [route]) (f [pair pair])]))))
  (testing "on the dynamic path (the cache of the var)"
    (is (= ["pr9-routes:1" "pr9-pairs:1" "pr9-routes:2" "pr9-pairs:1"]
           [(dynamic #'d/pr9 [] {"list" [route]}) (dynamic #'d/pr9 [] {"list" [pair]})
            (dynamic #'d/pr9 [] {"list" [route route]}) (dynamic #'d/pr9 [] {"list" (list pair)})])))
  (testing "a failure is not kept either: an empty collection, then one with elements"
    (let [f (fn [xs] (outcome #(d/pr9 :list xs)))]
      (is (error-has? (f []) "is ambiguous"))
      (is (= "pr9-routes:1" (f [route])))
      (is (error-has? (f []) "is ambiguous")))))

(deftest a3-no-element-no-choice
  (testing "an empty collection, or elements that fit several candidates, stay ambiguous"
    (let [f (fn [xs] (outcome #(d/pr9 :list xs)))
          m (:error (f []))]
      (is (str/includes? m "is ambiguous") m)
      (is (str/includes? m "an empty collection") m)
      (is (str/includes? m "pass the elements positionally") (str/replace m "Way out: p" "Way out: p")))
    (is (error-has? (dynamic #'d/pr9 [] {"list" []}) "is ambiguous" "an empty collection"))
    (is (error-has? (dynamic #'d/any9 [] {"list" [route]}) "is ambiguous" "fit more than one candidate"))
    (is (error-has? (dynamic #'d/any9 [] {"list" [route route]}) "is ambiguous")))
  (testing "elements of both kinds fit no candidate: the no-fit error, not a guess"
    (is (error-has? (dynamic #'d/pr9 [] {"list" [route pair]}) "no Kotlin declaration of `pr9` fits"))
    (is (error-has? (dynamic #'d/pr9 [] {"list" [route nil]}) "no Kotlin declaration of `pr9` fits"))
    (is (error-has? (dynamic #'d/pr9 [] {"list" [1]}) "no Kotlin declaration of `pr9` fits")))
  (testing "elements that fit one candidate only, among candidates of which one takes everything"
    (is (= "any9-any:2" (dynamic #'d/any9 [] {"list" [route 1]})))))

(deftest a3-nothing-else-changed
  (testing "positional elements, an array: no collection is looked at"
    (is (= "pr9-routes:2" (d/pr9 route route) (dynamic #'d/pr9 [route route])))
    (is (= "pr9-pairs:1" (d/pr9 pair)))
    (is (= "pr9-routes:1" (d/pr9 :list (into-array fx.r9.Route9 [route]))))
    (is (= "pr9-pairs:1" (dynamic #'d/pr9 [] {"list" (into-array fx.r9.Pair9 [pair])}))))
  (testing "a collection for a vararg of one declaration: no selection depends on it"
    (is (= "mix9:1:2:a,b:z" (d/mix9 :rest ["a" "b"] :tail (fn [] "z"))))
    (is (= "mix9:1:2:a,b:z" (dynamic #'d/mix9 [] {"rest" ["a" "b"] "tail" lam})))))

;; ---------------------------------------------------------------- A4

;; `on9(h: (Call9) -> String)` and `on9(h: (Ctx9) -> String)` (the second is `@JvmName("on9Ctx")`): one JVM method,
;; so a value cannot choose between them; the hints on the parameters of a `fn` literal can (the static path).

(deftest a4-the-hints-of-a-lambda-choose
  (testing "a top-level function"
    (is (= "on9-call:x" (d/on9 (fn [^fx.r9.Call9 c] "x"))))
    (is (= "on9-ctx:x" (d/on9 (fn [^fx.r9.Ctx9 c] "x"))))
    (is (= "on9-ctx:x" (d/on9 (fn* [^fx.r9.Ctx9 c] "x"))))
    (is (= "on9-call:x" (d/on9 (fn named [^fx.r9.Call9 c] "x")))))
  (testing "two parameters, one hint is enough"
    (is (= "onBoth9-call:x" (d/onBoth9 (fn [^fx.r9.Call9 c n] "x"))))
    (is (= "onBoth9-ctx:x" (d/onBoth9 (fn [^fx.r9.Ctx9 c n] "x")))))
  (testing "a member, after a vararg, a suspend function type (Ktor)"
    (is (= "status-call:2" (d/.status ^fx.r9.Cfg9 cfg 1 2 (fn [^fx.r9.Call9 c s] nil))))
    (is (= "status-ctx:1" (d/.status ^fx.r9.Cfg9 cfg 1 (fn [^fx.r9.Ctx9 c s] nil))))
    (is (= "status-ctx:0" (d/.status ^fx.r9.Cfg9 cfg (fn [^fx.r9.Ctx9 c s] nil)))))
  (testing "a member of a class whose instance a typed fn parameter holds"
    (is (= "at9-call:x" (d/withHost9 (fn [host] (d/.at9 host (fn [^fx.r9.Call9 c] "x"))))))
    (is (= "at9-ctx:x" (d/withHost9 (fn [host] (d/.at9 host (fn [^fx.r9.Ctx9 c] "x"))))))))

(deftest a4-a-hint-fits-the-type-or-a-subtype
  (testing "a hint fits a parameter type when it is that class or extends it"
    (is (= "sup9-base:x" (d/sup9 (fn [^fx.r9.Base9 c] "x"))) "Base9 does not extend Derived9: only (Base9) -> String fits")
    (is (error-has? (static '(d/sup9 (fn [^fx.r9.Derived9 c] "x"))) "is ambiguous") "Derived9 fits both: no choice")))

(deftest a4-no-hint-no-choice-and-the-error-says-so
  (let [m (compile-error '(d/on9 (fn [c] "x")))]
    (is (str/includes? (str m) "is ambiguous") m)
    (testing "the cause is named: the parameter types of a lambda"
      (is (str/includes? m "They differ only in the parameter types of a lambda") m)
      (is (not (str/includes? m "the result type of a lambda")) m))
    (testing "the first way out is the hint"
      (is (str/includes? m "Way out: write the lambda as a `fn` with a type hint") m)
      (is (str/includes? m "(fn [^fx.r9.Call9 x] ...)") m)
      (is (< (.indexOf ^String m "type hint") (.indexOf ^String m "Java interop")) "the hint before Java interop")))
  (testing "a hint on a parameter that both candidates take is no help; the example hints the one that differs"
    (let [m (compile-error '(d/onBoth9 (fn [c n] "x")))]
      (is (str/includes? m "(fn [^fx.r9.Call9 x y] ...)") m)))
  (testing "a `#()` literal, or a function value that is no literal, has no hints"
    (is (str/includes? (str (compile-error '(d/on9 #(str %)))) "is ambiguous"))
    (is (error-has? (dynamic #'d/on9 [(fn [c] "x")]) "is ambiguous" "the parameter types of a lambda"))
    (is (error-has? (as-value #'d/on9 (fn [c] "x")) "is ambiguous"))))

(deftest a4-a-hint-never-makes-an-error
  (testing "a hint that fits neither candidate: the call stays ambiguous (it is not a no-fit error)"
    (is (str/includes? (str (compile-error '(d/on9 (fn [^String s] "x")))) "is ambiguous")))
  (testing "one candidate only: a hint on a lambda is not looked at"
    (is (nil? (compile-error '(d/module9 (fn [^String m] "x")))))
    (is (nil? (compile-error '(d/module9 (fn [^fx.r9.Module9 m] "x")))))))

(deftest a4-the-error-comes-at-compile-time-when-the-receiver-is-known
  (testing "a hinted receiver"
    (is (str/includes? (str (compile-error '(d/.at9 ^fx.r9.Host9 (identity nil) (fn [c] "x")))) "is ambiguous")))
  (testing "a typed fn parameter (the receiver class is the type of the function type: an upper bound)"
    (let [m (compile-error '(d/withHost9 (fn [host] (d/.at9 host (fn [c] "x")))))]
      (is (str/includes? (str m) "is ambiguous") m)
      (is (str/includes? m "the parameter types of a lambda") m))
    (let [m (compile-error '(d/withHost9 (fn [host] (d/.status (identity nil) 1 (fn [c s] nil)))))]
      (is (nil? m) "a receiver of unknown class: the run time decides")))
  (testing "a receiver whose class is unknown: the error comes when the code runs"
    (let [f (eval-here '(fn [x] (d/.at9 x (fn [c] "x"))))]
      (is (fn? f))
      (is (error-has? (outcome #(f (d/makeHost9))) "is ambiguous" "the parameter types of a lambda")))))

;; ================================================================ Batch B: declarations and function values

(defn- top-error
  "{:error message} of the exception that `(f)` throws, as it is: not the root cause (`outcome` gives that). The Clojure
  compiler's and the reflection's wrappers are taken off."
  [f]
  (try (f)
       (catch Throwable t
         (let [t (loop [t t]
                   (if (and (or (instance? clojure.lang.Compiler$CompilerException t)
                                (instance? java.lang.reflect.InvocationTargetException t))
                            (.getCause t))
                     (recur (.getCause t))
                     t))]
           {:error (ex-message t)}))))

;; ---------------------------------------------------------------- B1

;; Kotlin: `Req9("GET", "/a")` calls `operator fun invoke` of the companion when no constructor fits.

(deftest b1-a-companion-invoke-as-a-constructor-form
  (testing "an interface: two invoke overloads (static path, dynamic path, var as a value, the old form)"
    (is (= "GET /a" (.getText (d/Req9 "GET" "/a"))))
    (is (= "GET /b" (.getText (d/Req9 "/b"))))
    (is (= "GET /a" (.getText ^fx.r9.Req9 (dynamic #'d/Req9 ["GET" "/a"]))))
    (is (= "GET /b" (.getText ^fx.r9.Req9 (dynamic #'d/Req9 ["/b"]))))
    (is (= "PUT /z" (.getText ^fx.r9.Req9 (as-value #'d/Req9 "PUT" "/z"))))
    (is (= ["GET /1" "GET /2"] (mapv #(.getText ^fx.r9.Req9 %) (map d/Req9 ["/1" "/2"]))))
    (is (= "GET /a" (.getText (d/.invoke d/Req9 "GET" "/a"))) "(d/.invoke d/Req9 ...) still works"))
  (testing "a named argument; the receiver (the class var) shifts nothing"
    (is (= "GET /n" (.getText (d/Req9 :uri "/n"))))
    (is (= "PATCH /n" (.getText (d/Req9 "PATCH" :uri "/n"))))
    (is (= "GET /n" (.getText ^fx.r9.Req9 (dynamic #'d/Req9 [] {"uri" "/n"})))))
  (testing "the result is typed: no reflection on it"
    (let [warnings (java.io.StringWriter.)]
      (binding [*err* warnings *warn-on-reflection* true]
        (eval-here '(.getText (d/Req9 "GET" "/w"))))
      (is (not (str/includes? (str warnings) "Reflection warning")) (str warnings))))
  (testing "a named companion, an abstract class"
    (is (= "factory:3" (.getTag (d/Named9 3))))
    (is (= "factory:3" (.getTag ^fx.r9.Named9 (dynamic #'d/Named9 [3]))))
    (is (= "abs:s" (.getTag (d/Abs9 "s"))))
    (is (= "abs:s" (.getTag ^fx.r9.Abs9 (dynamic #'d/Abs9 ["s"])))))
  (testing "an extension invoke on the Companion that the package declares"
    (is (= "ext-invoke:4" (d/Ext9 4) (dynamic #'d/Ext9 [4]) (as-value #'d/Ext9 4)))
    (is (= "ext" (.getTag (d/Ext9))) "the constructor of Ext9 still works")))

(deftest b1-a-constructor-that-fits-always-wins
  (testing "primary and secondary constructor, and an invoke that takes the same Int"
    (is (= "x" (.getTag (d/Both9 "x"))))
    (is (= "ctor-int:3" (.getTag (d/Both9 3))))
    (is (= "ctor-int:3" (.getTag ^fx.r9.Both9 (dynamic #'d/Both9 [3]))))
    (is (= "ctor-int:3" (.getTag ^fx.r9.Both9 (as-value #'d/Both9 3)))))
  (testing "no constructor fits: the invoke that does"
    (is (= "invoke-flag:true" (.getTag (d/Both9 true))))
    (is (= "invoke-flag:false" (.getTag ^fx.r9.Both9 (dynamic #'d/Both9 [false]))))
    (is (= "invoke-two:ab" (.getTag (d/Both9 "a" "b"))))
    (is (= "invoke-two:ab" (.getTag ^fx.r9.Both9 (dynamic #'d/Both9 ["a" "b"]))))))

(deftest b1-the-error-lists-both-kinds
  (testing "an interface: no constructor, and no invoke that fits (static: a compile error)"
    (let [m (compile-error '(d/Req9 1 2 3))]
      (is (str/includes? (str m) "(Req9 1 2 3) fits no constructor and no `operator fun invoke` of the companion object") m)
      (is (str/includes? m "As a constructor:") m)
      (is (str/includes? m "`Req9` has no public constructor: it is an interface") m)
      (is (str/includes? m "As a companion `invoke`") m)
      (is (str/includes? m "operator fun fx.r9.Req9.Companion.invoke(method: String, uri: String): fx.r9.Req9") m)
      (is (str/includes? m "operator fun fx.r9.Req9.Companion.invoke(uri: String): fx.r9.Req9") m)))
  (testing "the dynamic path: the same error"
    (is (error-has? (dynamic #'d/Req9 [1 2 3]) "fits no constructor and no `operator fun invoke`" "As a constructor:"
                    "operator fun fx.r9.Req9.Companion.invoke(uri: String)"))
    (is (error-has? (dynamic #'d/Req9 [1]) "(Req9 1) fits no constructor" "`uri` is String but got Long")))
  (testing "a class with constructors: they are listed too, with the reason"
    (let [r (dynamic #'d/Both9 [1.5])]
      (is (error-has? r "(Both9 1.5) fits no constructor and no `operator fun invoke`" "class Both9(tag: String)"
                      "class Both9(n: Int)" "operator fun fx.r9.Both9.Companion.invoke(flag: Boolean)"
                      "the class var is the first argument here") r)))
  (testing "an invoke of an extension that fits the arguments but not the class var: not a candidate"
    (is (error-has? (dynamic #'d/Ext9 ["s"]) "(Ext9 \"s\") fits no constructor" "operator fun fx.r9.Ext9.Companion.invoke(x: Int)"))))

(deftest b1-nothing-else-changed
  (testing "an invoke that is no operator is not called as a constructor form"
    (let [r (static '(d/NoOp9 1))]
      (is (error-has? r "no Kotlin declaration of `NoOp9` fits") r)
      (is (not (str/includes? (:error r) "companion")) r))
    (is (= "noop:1" (.getTag (d/.invoke d/NoOp9 1))) "the member call by name still works")
    (is (= "p" (.getTag (d/NoOp9 "p")))))
  (testing "a class with a companion without invoke: the error is the old one"
    (is (error-has? (static '(d/Plain9 1 2)) "too many arguments"))
    (is (not (str/includes? (:error (static '(d/Plain9 1 2))) "companion object")))
    (is (= "p" (.getTag (d/Plain9 "p"))))
    (is (= "other:1" (.getTag (d/.other d/Plain9 1)))))
  (testing "an interface without a companion invoke: the old message"
    (is (error-has? (static '(d/Router9 "x")) "has no public constructor: it is an interface"))
    (is (not (str/includes? (:error (static '(d/Router9 "x"))) "fits no constructor"))))
  (testing "an ambiguity between the constructors is not hidden by the fallback"
    (is (error-has? (static '(d/Req9 :uri)) "names a parameter but has no value"))))

(deftest b1-an-object-with-invoke
  (testing "`Obj9(1)` is `Obj9.invoke(1)`: the var holds the instance and the call form is the invoke"
    (is (= "obj-invoke:5" (d/Obj9 5)))
    (is (= "obj-invoke:5" (d/.invoke d/Obj9 5)))
    (is (= "obj-plain" (d/.plain d/Obj9)))
    (is (instance? fx.r9.Obj9 d/Obj9)))
  (testing "an extension invoke on the object"
    (is (= "objext-invoke:q" (d/ObjExt9 "q"))))
  (testing "an invoke that does not fit: the error is the one of the call of `.invoke`"
    (is (error-has? (static '(d/Obj9 "no")) "no Kotlin declaration of `.invoke` fits")))
  (testing "an object without an invoke: the old error"
    (is (error-has? (static '(d/NoInv9 1)) "`NoInv9` is an object, not a function: it cannot be called"))))

;; ---------------------------------------------------------------- B2

;; `interface Router9 : (String) -> String`: the class inherits `operator fun invoke(p1: String): String`.

(kt/require '[fx.r9b :as b9])

(def ^:private router (d/router9 "r"))
(def ^:private calc (d/calc9))

(defn- reflection-warnings
  "What the compiler says about reflection while it compiles `form`."
  [form]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w *warn-on-reflection* true] (eval-here form))
    (str w)))

(deftest b2-invoke-on-a-class-that-implements-a-function-type
  (testing "an unhinted value: the dynamic path"
    (is (= "routed:x:r" (d/.invoke router "x")))
    (is (= "routed:x:r" (dynamic #'d/.invoke [router "x"])))
    (is (= "routed:x:r" (as-value #'d/.invoke router "x")))
    (is (= "routed:y:r" (apply d/.invoke [router "y"]))))
  (testing "a hint, or a call that gives the class: the static path"
    (is (= "routed:h:r" (eval-here '(d/.invoke ^fx.r9.Router9 ckway.round9-test/r9router "h"))))
    (is (= "routed:z:x" (eval-here '(let [r (d/router9 "x")] (d/.invoke r "z")))))
    (is (= "routed:z:x" (eval-here '(d/.invoke (d/router9 "x") "z"))))
    (is (not (str/includes? (reflection-warnings '(let [r (d/router9 "x")] (d/.invoke r "z"))) "Reflection warning"))))
  (testing "two parameters, and an Int that a Clojure integer gives (the width is the one of the declared type)"
    (is (= 42 (d/.invoke calc 6 7) (dynamic #'d/.invoke [calc 6 7]) (eval-here '(d/.invoke (d/calc9) 6 7)))))
  (testing "a function type that returns a function: a Clojure function"
    (let [f (d/.invoke (d/curry9) "a")]
      (is (fn? f))
      (is (= "curry:a:b" (f "b")))))
  (testing "a function type that returns Unit: nil"
    (let [log (StringBuilder.)]
      (is (nil? (d/.invoke (d/eff9 log))))
      (is (= "eff;" (str log)))))
  (testing "a suspend function type: the call gives the result"
    (is (= "s-routed:s" (d/.invoke (d/sRouter9) "s") (dynamic #'d/.invoke [(d/sRouter9) "s"])))
    (is (= "s-routed:s" (eval-here '(d/.invoke (d/sRouter9) "s")))))
  (testing "a function argument is adapted, and a value class is the box itself"
    (is (= "w:in" (d/.invoke (d/apply9) (fn [s] (str "w:" s)))))
    (is (= 5 (d/idInt9 (d/.invoke (d/idFn9) (d/idOf9 4)))))))

(deftest b2-a-class-of-another-package
  ;; The package of the var has no class that implements this function type: the generic `invoke(p1: P1): R` of `Function1`
  ;; is the member (its type arguments are not seen: a Clojure integer for an `Int` stays a Long, as everywhere at a type parameter)
  (let [far (b9/far9)]
    (is (= "far:x" (d/.invoke far "x") (dynamic #'d/.invoke [far "x"]) (b9/.invoke far "x")))
    (is (= "far:x" (eval-here '(d/.invoke (fx.r9b.Far9.) "x")))))
  (testing "an object of a class that is in no package"
    (let [f (reify kotlin.jvm.functions.Function1 (invoke [_ x] (str "r:" x)))]
      (is (= "r:x" (d/.invoke f "x") (dynamic #'d/.invoke [f "x"])))))
  (testing "a function that Kotlin returned, wrapped as a Clojure function: the call still goes to the Kotlin function"
    (let [g (d/adder9 10)]
      (is (= 11 (d/.invoke g (int 1))))))
  (testing "a receiver that is no function"
    (is (error-has? (dynamic #'d/.invoke ["s" "x"]) "no Kotlin declaration of `.invoke` fits"))))

(deftest b2-the-usual-argument-checks
  (testing "the number of arguments"
    (is (error-has? (dynamic #'d/.invoke [router "a" "b"]) "no Kotlin declaration of `.invoke` fits"))
    (is (error-has? (dynamic #'d/.invoke [calc 3]) "no Kotlin declaration of `.invoke` fits")))
  (testing "nil for a non-null parameter, a value of another class"
    (let [r (dynamic #'d/.invoke [router nil])]
      (is (error-has? r "no Kotlin declaration of `.invoke` fits" "`nil` passed to non-nullable `p1`") r))
    (let [r (dynamic #'d/.invoke [router 1.5])]
      (is (error-has? r "operator fun fx.r9.Router9.invoke(p1: String): String" "`p1` is String but got Double") r)))
  (testing "a value class parameter takes the object, not the underlying value"
    (is (error-has? (dynamic #'d/.invoke [(d/idFn9) 4]) "`p1` is Id9 but got Long")))
  (testing "a receiver that is no function"
    (is (error-has? (dynamic #'d/.invoke ["s" "x"]) "no Kotlin declaration of `.invoke` fits"))))

(deftest b2-the-declaration-is-listed
  (let [sigs (map :signature (filter :fn-supertype (:kt/decls (meta #'d/.invoke))))]
    (is (some #(str/includes? % "operator fun fx.r9.Router9.invoke(p1: String): String  [from (String) -> String]") sigs))
    (is (some #(str/includes? % "operator suspend fun fx.r9.SRouter9.invoke(p1: String): String  [from suspend (String) -> String]") sigs))
    (is (some #(str/includes? % "operator fun fx.r9.Calc9.invoke(p1: Int, p2: Int): Int  [from (Int, Int) -> Int]") sigs))
    (is (some #(str/includes? % "operator fun fx.r9.Eff9.invoke(): Unit  [from () -> Unit]") sigs))))

(deftest b2-nothing-else-changed
  (testing "another invoke of the package fits as well: a member, an extension, and one for another class"
    (is (= "doer:x" (d/.invoke (d/doer9) "x") (dynamic #'d/.invoke [(d/doer9) "x"])))
    (is (= "doer-ext:12" (d/.invoke (d/doer9) 1 2)))
    (is (= "ext-int:5" (d/.invoke router 5) (dynamic #'d/.invoke [router 5])) "the extension of Router9 with an Int")
    (is (= "routed:5:r" (d/.invoke router "5")) "the inherited member with a String"))
  (testing "companion invokes are still members of the same var"
    (is (= "GET /a" (.getText (d/.invoke d/Req9 "GET" "/a"))))
    (is (= "obj-invoke:1" (d/.invoke d/Obj9 1))))
  (testing "a class that writes its own override: the same call"
    (is (= "routed:o:r" (d/.invoke router "o"))))
  (testing "a package that no class declares an invoke in has no `.invoke` var"
    (kt/require '[fx.r5far :as far5])
    (is (nil? (ns-resolve (the-ns 'ckway.pkg.fx.r5far) (symbol ".invoke"))))))

(def r9router router)

;; ---------------------------------------------------------------- B3

;; `val Prop9.Companion.zero9`, `var Prop9.Companion.level9`: the class var is the receiver, as it is for the members of a
;; companion. An extension FUNCTION on a Companion takes it too.

(deftest b3-an-extension-property-on-a-companion
  (testing "read: static path, dynamic path, the var as a value"
    (is (= "zero" (.getTag (d/zero9 d/Prop9))))
    (is (= "zero" (.getTag ^fx.r9.Prop9 (dynamic #'d/zero9 [d/Prop9]))))
    (is (= "zero" (.getTag ^fx.r9.Prop9 (as-value #'d/zero9 d/Prop9)))))
  (testing "a local that holds the class var"
    (is (= "zero" (.getTag (let [c d/Prop9] (d/zero9 c))))))
  (testing "the Companion object itself still works"
    (is (= "zero" (.getTag (d/zero9 fx.r9.Prop9/Companion))))
    (is (= "zero" (.getTag ^fx.r9.Prop9 (dynamic #'d/zero9 [fx.r9.Prop9/Companion])))))
  (testing "a property of the Companion that returns nil"
    (is (nil? (d/nullable9 d/Prop9)))
    (is (nil? (dynamic #'d/nullable9 [d/Prop9]))))
  (testing "a named companion"
    (is (= "factory-prop" (d/made9 d/Fac9) (dynamic #'d/made9 [d/Fac9]) (as-value #'d/made9 d/Fac9)))
    (is (= "factory-prop" (d/made9 fx.r9.Fac9/Factory)))))

(deftest b3-write-an-extension-property-on-a-companion
  (testing "kt/set! with the class var: static path"
    (is (= 5 (kt/set! (d/level9 d/Prop9) 5)))
    (is (= 5 (d/level9 d/Prop9)))
    (is (= 6 (let [c d/Prop9] (kt/set! (d/level9 c) 6))) "a local")
    (is (= 6 (d/level9 d/Prop9))))
  (testing "dynamic path"
    (is (= 7 (ckway.set/set-dyn #'d/level9 [d/Prop9] 7)))
    (is (= 7 (d/level9 d/Prop9) (dynamic #'d/level9 [d/Prop9]))))
  (testing "the Companion object itself"
    (is (= 8 (kt/set! (d/level9 fx.r9.Prop9/Companion) 8)))
    (is (= 8 (d/level9 d/Prop9))))
  (testing "a named companion"
    (is (= "m1" (kt/set! (d/mark9 d/Fac9) "m1")))
    (is (= "m1" (d/mark9 d/Fac9) (d/mark9 fx.r9.Fac9/Factory))))
  (testing "a val has no setter: the error is the old one"
    (let [m (compile-error '(kt/set! (d/made9 d/Fac9) "x"))]
      (is (str/includes? (str m) "is read-only: a `val` has no setter") m))))

(deftest b3-an-extension-function-on-a-companion
  (is (= "ext-fun:1" (d/.build9 d/Ext9 1) (dynamic #'d/.build9 [d/Ext9 1]) (d/.build9 fx.r9.Ext9/Companion 1)))
  (is (= "ext-fun:1" (let [c d/Ext9] (d/.build9 c 1))) "a local")
  (is (= "factory-fun:1" (d/.make9 d/Fac9 1) (dynamic #'d/.make9 [d/Fac9 1]))))

(deftest b3-the-wrong-receiver-is-an-error
  (testing "the class var of another class, a string"
    (let [r (dynamic #'d/zero9 [d/Mem9])]
      (is (error-has? r "no Kotlin declaration of `zero9` fits" "val fx.r9.Prop9.Companion.zero9: fx.r9.Prop9"
                      "`receiver` expects the class var of fx.r9.Prop9") r))
    (is (error-has? (dynamic #'d/zero9 ["s"]) "no Kotlin declaration of `zero9` fits"))
    (is (error-has? (static '(d/zero9 "s")) "no Kotlin declaration of `zero9` fits")))
  (testing "set!"
    (is (error-has? (static '(kt/set! (d/level9 d/Mem9) 1)) "no Kotlin declaration of `level9` fits"))
    (is (error-has? (outcome #(ckway.set/set-dyn #'d/level9 ["s"] 1)) "no Kotlin declaration of `level9` fits"))))

(deftest b3-nothing-else-changed
  (testing "a property of a companion (a member): the class var is the receiver"
    (is (= 42 (d/answer9 d/Mem9) (dynamic #'d/answer9 [d/Mem9])))
    (is (= 3 (kt/set! (d/counter9 d/Mem9) 3)))
    (is (= 3 (d/counter9 d/Mem9))))
  (testing "a property of an ordinary class and an extension property of it"
    (let [m (d/Mem9)]
      (is (= 7 (d/inst9 m) (dynamic #'d/inst9 [m])))
      (is (= 8 (d/extra9 m) (dynamic #'d/extra9 [m])))))
  (testing "nil is no receiver of an extension on a Companion"
    (is (error-has? (static '(d/.build9 nil 1)) "`nil` passed to non-nullable `receiver`"))))

;; ---------------------------------------------------------------- B4

;; The value that a `kt/reify` member returns is adapted to the declared Kotlin return type, as an argument is adapted to a
;; parameter type; a value that cannot be that type is a `kt:` error that names the member, at the point of return.

(defmacro ^:private gen
  "A `fx.r9.Gen9` with the members that are written."
  [& members]
  `(kt/reify d/Gen9 ~@members))

(deftest b4-a-function-that-a-member-returns
  (testing "function types: no parameter, one, two, nullable (nil is a value), a curried one"
    (is (= 5 (d/callFn1 (gen (.fn1 [_] (fn [x] (inc x)))) 4)))
    (is (= "zero" (d/callFn0 (gen (.fn0 [_] (fn [] "zero"))))))
    (is (= 7 (d/callFn2 (gen (.fn2 [_] (fn [a b] (+ a b)))) 3 4)))
    (is (= "6" (d/callNullable (gen (.fnNullable [_] (fn [x] (* 2 x)))))))
    (is (= "null" (d/callNullable (gen (.fnNullable [_] nil)))))
    (is (= 7 (d/callCurried (gen (.curried [_] (fn [a] (fn [b] (+ a b))))) 3 4))))
  (testing "a function type with a receiver, a suspend function type"
    (is (= "a1" (d/callFnRecv (gen (.fnRecv [_] (fn [s x] (str s x)))) "a" 1)))
    (is (= 105 (d/callFnSusp (gen (.fnSusp [_] (fn [x] (+ x 100)))) 5))))
  (testing "a fun interface, a Java single-method interface (a Kotlin one and a Java one)"
    (is (= "sam7" (d/callSam (gen (.sam [_] (fn [x] (str "sam" x)))) 7)))
    (is (= "j!" (d/callJavaSam (gen (.javaSam [_] (fn [s] (str s "!")))) "j")))
    (let [sb (StringBuilder.)]
      (is (= "ran" (d/callRunnable (gen (.runnable [_] (fn [] (.append sb "ran")))) sb)))))
  (testing "a property getter"
    (is (= 12 (d/callProp (gen (prop [_] (fn [x] (* x 3)))) 4)))
    (is (= "sp4" (d/callSprop (gen (sprop [_] (fn [x] (str "sp" x)))) 4))))
  (testing "the same calls through the dynamic path"
    (is (= 5 (dynamic #'d/callFn1 [(gen (.fn1 [_] (fn [x] (inc x)))) 4])))
    (is (= "sam7" (dynamic #'d/callSam [(gen (.sam [_] (fn [x] (str "sam" x)))) 7])))))

(deftest b4-the-declared-type-decides-the-other-adaptations
  (testing "number width and Unit (as for an argument)"
    (is (= "5" (d/callNum (gen (.num [_] 5)))))
    (is (= "5" (d/callShort (gen (.short9 [_] 5)))))
    (is (= "5.0" (d/callDouble (gen (.double9 [_] 5)))))
    (is (= "kotlin.Unit" (d/callUnit (gen (.unit [_] 5)))))
    (is (= "kotlin.Unit" (d/callUnit (gen (.unit [_] nil)))))
    (is (= "9" (d/callNprop (gen (nprop [_] 9))))))
  (testing "a value class is the object, and Any and String pass unchanged"
    (is (= 5 (d/callId (gen (.id [_] (d/idOf9 5))))))
    (is (= "[1 2]" (d/callAny (gen (.any [_] [1 2])))))
    (is (= "s" (d/callStr (gen (.str [_] "s")))))))

(deftest b4-a-value-that-is-already-right-passes-unchanged
  (testing "a Kotlin function object, a fun interface object, a Java object: the same object (no wrapper)"
    (let [f (reify kotlin.jvm.functions.Function1 (invoke [_ x] (inc x)))
          s (kt/reify d/Sam9 (.go9 [_ x] "s"))
          r (reify Runnable (run [_]))]
      (is (identical? f (.fn1 ^fx.r9.Gen9 (gen (.fn1 [_] f)))))
      (is (identical? s (.sam ^fx.r9.Gen9 (gen (.sam [_] s)))))
      (is (identical? r (.runnable ^fx.r9.Gen9 (gen (.runnable [_] r)))))
      (is (= 2 (d/callFn1 (gen (.fn1 [_] f)) 1))))
    (let [id (d/idOf9 3)]
      (is (= id (d/callIdObj (gen (.id [_] id)))) "a value class object (the JVM returns its underlying value: Kotlin boxes it again)")))
  (testing "a Kotlin function that a call returned (a Clojure function that wraps it) goes back as the original"
    (let [g (d/adder9 10)]
      (is (fn? g))
      (is (identical? (rt/own g) (.fn1 ^fx.r9.Gen9 (gen (.fn1 [_] g)))))
      (is (= 11 (d/callFn1 (gen (.fn1 [_] g)) 1)))))
  (testing "nil for a nullable function type stays nil"
    (is (nil? (.fnNullable ^fx.r9.Gen9 (gen (.fnNullable [_] nil)))))))

(deftest b4-the-error-names-the-member
  (testing "not a function where a function type is declared"
    (let [r (top-error #(d/callFn1 (gen (.fn1 [_] "notfn")) 4))]
      (is (error-has? r "kt: the result of the kt/reify member `Gen9.fn1` ((Int) -> Int) is wrong:" "expected a function") r))
    (let [r (top-error #(d/callSam (gen (.sam [_] 5)) 4))]
      (is (error-has? r "the kt/reify member `Gen9.sam`" "expected a function" "fx.r9.Sam9" "Long") r))
    (let [r (top-error #(.run ^Runnable (.runnable ^fx.r9.Gen9 (gen (.runnable [_] "x")))))]
      (is (error-has? r "the kt/reify member `Gen9.runnable`" "expected a function") r)))
  (testing "nil where the type is not nullable"
    (let [r (top-error #(d/callFn1 (gen (.fn1 [_] nil)) 4))]
      (is (error-has? r "the kt/reify member `Gen9.fn1`" "got nil" "the Kotlin type is not nullable") r)))
  (testing "a property getter"
    (let [r (top-error #(d/callProp (gen (prop [_] 5)) 4))]
      (is (error-has? r "the kt/reify member `Gen9.prop`" "expected a function") r)))
  (testing "a number of another width class, a value that is no value class, no String"
    (is (error-has? (top-error #(d/callNum (gen (.num [_] 5.5)))) "the kt/reify member `Gen9.num` (Int)" "got java.lang.Double 5.5"))
    (is (error-has? (top-error #(d/callId (gen (.id [_] 5)))) "the kt/reify member `Gen9.id`" "expected fx.r9.Id9"))
    (is (error-has? (top-error #(d/callStr (gen (.str [_] 5)))) "the kt/reify member `Gen9.str` (String)" "expected String")))
  (testing "a Java member"
    (let [r (top-error #(.run ^Runnable (.task ^pj.JRet9 (kt/reify pj.JRet9 (task [_] "x")))))]
      (is (error-has? r "the kt/reify member `JRet9.task`" "expected a function" "java.lang.Runnable") r))
    (is (error-has? (top-error #(.number ^pj.JRet9 (kt/reify pj.JRet9 (number [_] 5.5)))) "the kt/reify member `JRet9.number`")))
  (testing "an error in the body of the member is not changed"
    (let [r (top-error #(d/callFn1 (gen (.fn1 [_] (throw (ex-info "kt: body" {:kt/error true})))) 4))]
      (is (= "kt: body" (:error r))))))

(deftest b4-a-java-interface
  (testing "the Java single-method interfaces that a Java member returns"
    (let [sb (StringBuilder.)
          r (kt/reify pj.JRet9
              (task [_] (fn [] (.append sb "ran")))
              (supplier [_] (fn [] "sup"))
              (op [_] (fn [x] (inc x)))
              (number [_] 5)
              (text [_] "t"))]
      (.run (.task r))
      (is (= "ran" (str sb)))
      (is (= "sup" (.get (.supplier r))))
      (is (= 5 (.applyAsInt (.op r) 4)))
      (is (= 5 (.number r)))
      (is (= "t" (.text r)))))
  (testing "nil is a value of a Java type"
    (is (nil? (.task ^pj.JRet9 (kt/reify pj.JRet9 (task [_] nil)))))))

;; `fun interface Flt9 : (Handler9) -> Handler9` (http4k `Filter`): the member is the `invoke` of the function type

(deftest b4-a-fun-interface-that-extends-a-function-type
  (testing "the member returns a Clojure function: it is adapted to the Function1 that Kotlin wants"
    (is (= "f:base:q" (d/runFlt9 (kt/reify d/Flt9 (.invoke [_ nxt] (fn [s] (str "f:" (nxt s))))) "q"))))
  (testing "the parameter is a Clojure function too"
    (is (= "f:base:q" (d/runFlt9 (kt/reify d/Flt9 (.invoke [_ nxt] (fn [s] (str "f:" (.invoke ^kotlin.jvm.functions.Function1 nxt s))))) "q"))
        "and it is still a Function1"))
  (testing "the member returns what it was given: the same Kotlin function"
    (is (= "base:q" (d/runFlt9 (kt/reify d/Flt9 (.invoke [_ nxt] nxt)) "q"))))
  (testing "a value that is no function"
    (let [r (top-error #(d/runFlt9 (kt/reify d/Flt9 (.invoke [_ nxt] 5)) "q"))]
      (is (error-has? r "the kt/reify member `Flt9.invoke` ((String) -> String)" "expected a function") r))))

;; a Clojure function that Kotlin calls as a lambda whose RESULT is a function type: the function it returns is adapted too

(deftest b4-the-result-of-a-lambda-that-is-a-function-type
  (testing "a function type, a fun interface, a suspend function type, a number, Unit"
    (is (= 5 (d/lam9 (fn [a] (fn [b] (+ a b))))))
    (is (= "s45" (d/lamSam9 (fn [a] (fn [x] (str "s" a x))))))
    (is (= 11 (d/lamSusp9 (fn [a] (fn [b] (+ a b))))))
    (is (= "5" (d/lamNum9 (fn [a] 5))))
    (is (= "kotlin.Unit" (d/lamUnit9 (fn [a] 5)))))
  (testing "the dynamic path"
    (is (= 5 (dynamic #'d/lam9 [(fn [a] (fn [b] (+ a b)))])))
    (is (= "s45" (dynamic #'d/lamSam9 [(fn [a] (fn [x] (str "s" a x)))]))))
  (testing "a value that cannot be the type is a kt error that names the Kotlin type of the function"
    (let [r (top-error #(d/lam9 (fn [a] "notfn")))]
      (is (error-has? r "kt: expected a function for a parameter of type kotlin.jvm.functions.Function1, got java.lang.String") r))
    (let [r (top-error #(d/lam9 (fn [a] nil)))]
      (is (= "kt: nil where Kotlin expects a non-null function ((Int) -> Int)" (:error r)) r))
    (let [r (top-error #(d/lamNum9 (fn [a] 5.5)))]
      (is (error-has? r "got java.lang.Double 5.5") r)))
  (testing "a function that Kotlin returned goes back from a lambda unchanged"
    (is (= 15 (d/lam9 (fn [a] (d/adder9 (+ a 10))))))))

;; ================================================================ Batch C: nil checks, type hints, one error text

(defn- paths
  "How the compiler takes the call `form`: {:value v-or-{:error ..} :static n :dynamic n :warned? bool}: the number of
  calls that the static path expanded, the number that went to the dynamic path (`call-dyn`) and whether the Clojure compiler
  printed a reflection warning. The call is compiled with `*warn-on-reflection*` on, in this namespace."
  [form]
  (let [n-static (atom 0) dyn (atom 0) w (java.io.StringWriter.)
        emit r/emit dynf r/dynamic-form
        v (with-redefs [r/emit (fn [p] (swap! n-static inc) (emit p))
                        r/dynamic-form (fn [v parsed] (swap! dyn inc) (dynf v parsed))]
            (binding [*warn-on-reflection* true *err* w]
              (static form)))]
    {:value v :static @n-static :dynamic @dyn :warned? (str/includes? (str w) "Reflection warning")}))

;; ---------------------------------------------------------------- C1

;; Koin: `fun <T : Any> getProperty(key: String, defaultValue: T): T`. A nil is checked against the bounds of the type
;; parameter. `T : Any` takes no nil, plain `T`, `T?` and `T : Foo?` do.

(deftest c1-nil-for-a-type-parameter-with-a-non-null-bound
  (testing "a nil literal: a compile error on the static path"
    (is (error-has? (static '(d/nnBound9 "k" nil)) "kt: no Kotlin declaration of `nnBound9` fits (nnBound9 \"k\" nil)"
                    "fun <T : Any> nnBound9(key: String, d: T): String" "`nil` passed to non-nullable `d`"))
    (is (error-has? (static '(d/tAny9 nil)) "`nil` passed to non-nullable `d`"))
    (is (error-has? (static '(d/twoBounds9 nil)) "`nil` passed to non-nullable `d`") "several bounds, one is not nullable"))
  (testing "the dynamic path says the same"
    (is (error-has? (dynamic #'d/nnBound9 ["k" nil]) "no Kotlin declaration of `nnBound9` fits" "`nil` passed to non-nullable `d`"))
    (is (error-has? (dynamic #'d/tAny9 [nil]) "`nil` passed to non-nullable `d`"))
    (is (error-has? (as-value #'d/tAny9 nil) "`nil` passed to non-nullable `d`") "the var as a value"))
  (testing "a nil that only the run time knows (the static path cannot see it): a kt error, not Kotlin's NullPointerException"
    (let [r (static '(d/nnBound9 "k" (identity nil)))]
      (is (error-has? r "kt: (d/nnBound9 \"k\" (identity nil)): nil where Kotlin expects a non-null value of the type parameter `T : Any`"
                      "the argument `d` (T)" "Kotlin: fun <T : Any> nnBound9(key: String, d: T): String") r))
    (let [r (static '(let [v (identity nil)] (d/tAny9 v)))]
      (is (error-has? r "nil where Kotlin expects a non-null value of the type parameter `T : Any`") r))
    (let [r (static '(let [v (identity nil)] (d/twoBounds9 v)))]
      (is (error-has? r "the type parameter `T : Any`") r)))
  (testing "a value that is not nil passes, on every path"
    (is (= "nnBound9:v" (static '(d/nnBound9 "k" "v"))))
    (is (= "nnBound9:v" (static '(let [v (identity "v")] (d/nnBound9 "k" v)))))
    (is (= "nnBound9:v" (dynamic #'d/nnBound9 ["k" "v"])))
    (is (= "tAny9:7" (static '(d/tAny9 7))))
    (is (= "tAny9:7" (static '(let [v (identity 7)] (d/tAny9 v)))))
    (is (= "twoBounds9:3" (static '(d/twoBounds9 3))))
    (is (= "tAny9:7" (as-value #'d/tAny9 7)))))

(deftest c1-nil-is-legal-where-the-bound-allows-it
  (testing "plain T (the bound is Any?), T?, T : Number?"
    (is (= "plainT9:null" (static '(d/plainT9 nil))))
    (is (= "plainT9:null" (static '(let [v (identity nil)] (d/plainT9 v)))))
    (is (= "plainT9:null" (dynamic #'d/plainT9 [nil])))
    (is (= "plainT9:null" (as-value #'d/plainT9 nil)))
    (is (= "qT9:null" (static '(d/qT9 nil))))
    (is (= "qT9:null" (static '(let [v (identity nil)] (d/qT9 v)))))
    (is (= "qT9:null" (dynamic #'d/qT9 [nil])))
    (is (= "nullBound9:null" (static '(d/nullBound9 nil))))
    (is (= "nullBound9:null" (static '(let [v (identity nil)] (d/nullBound9 v)))))
    (is (= "nullBound9:null" (dynamic #'d/nullBound9 [nil]))))
  (testing "T that is given as a nullable type with `:<>`"
    (is (= "typed9:null" (static '(d/typed9 nil :<> String?))))
    (is (= "typedNn9:null" (static '(d/typedNn9 nil :<> String)))))
  (testing "a nullable type argument is not within the bound `T : Any`: Kotlin would not compile it, nor does kt"
    (is (error-has? (static '(d/tAny9 nil :<> String?)) "`nil` passed to non-nullable `d`"))))

(deftest c1-a-type-parameter-of-a-class
  (testing "class Box<T : Any> { fun put(x: T) }"
    (is (error-has? (static '(d/.put (d/BoxNn9) nil)) "no Kotlin declaration of `.put` fits" "`nil` passed to non-nullable `x`"))
    (is (error-has? (dynamic #'d/.put [(d/BoxNn9) nil]) "`nil` passed to non-nullable `x`"))
    (let [r (static '(d/.put (d/BoxNn9) (identity nil)))]
      (is (error-has? r "nil where Kotlin expects a non-null value of the type parameter `T : Any`" "the argument `x` (T)") r))
    (is (= "BoxNn9.put:1" (static '(d/.put (d/BoxNn9) 1))))
    (is (= "BoxNn9.put:1" (dynamic #'d/.put [(d/BoxNn9) 1]))))
  (testing "a method of such a class has its own type parameter too"
    (is (error-has? (static '(d/.mix (d/BoxNn9) 1 nil)) "`nil` passed to non-nullable `y`"))
    (is (error-has? (static '(d/.mix (d/BoxNn9) nil 1)) "`nil` passed to non-nullable `x`"))
    (is (= "BoxNn9.mix" (static '(d/.mix (d/BoxNn9) 1 2)))))
  (testing "class Box<T> and Box<T : Any> { fun put(x: T?) } take nil"
    (is (= "BoxPl9.put:null" (static '(d/.put (d/BoxPl9) nil))))
    (is (= "BoxPl9.put:null" (static '(d/.put (d/BoxPl9) (identity nil)))))
    (is (= "BoxPl9.put:null" (dynamic #'d/.put [(d/BoxPl9) nil])))
    (is (= "BoxQ9.put:null" (static '(d/.put (d/BoxQ9) nil))))
    (is (= "BoxQ9.put:null" (dynamic #'d/.put [(d/BoxQ9) nil])))))

(deftest c1-a-vararg-element
  (testing "vararg xs: T with T : Any: a nil element is an error"
    (is (error-has? (static '(d/varNn9 1 nil)) "no Kotlin declaration of `varNn9` fits" "`nil` passed to non-nullable `xs`"))
    (is (error-has? (dynamic #'d/varNn9 [1 nil]) "`nil` passed to non-nullable `xs`"))
    (let [r (static '(d/varNn9 1 (identity nil)))]
      (is (error-has? r "nil where Kotlin expects a non-null value of the type parameter `T : Any`" "a vararg element") r))
    (is (= "varNn9:2" (static '(d/varNn9 1 2))))
    (is (= "varNn9:0" (static '(d/varNn9))))
    (is (= "varNn9:2" (dynamic #'d/varNn9 [1 2]))))
  (testing "vararg xs: T (plain T) takes a nil element (before, kt refused it)"
    (is (= "varPl9:2" (static '(d/varPl9 1 nil))))
    (is (= "varPl9:2" (static '(d/varPl9 1 (identity nil)))))
    (is (= "varPl9:2" (dynamic #'d/varPl9 [1 nil]))))
  (testing "vararg xs: T? takes a nil element"
    (is (= "varNnList9:2" (static '(d/varNnList9 1 nil))))
    (is (= "varNnList9:2" (dynamic #'d/varNnList9 [1 nil])))))

(deftest c1-overload-selection
  (testing "a nil does not make the candidate with T : Any applicable: the Int? overload is the only one"
    (is (= "ovNil9-int?" (static '(d/ovNil9 nil))))
    (is (= "ovNil9-int?" (static '(let [v (identity nil)] (d/ovNil9 v)))))
    (is (= "ovNil9-int?" (dynamic #'d/ovNil9 [nil])))
    (is (= "ovNil9-int?" (as-value #'d/ovNil9 nil))))
  (testing "a value that is not nil: the usual rule (the candidate without type parameters first)"
    (is (= "ovNil9-any" (static '(d/ovNil9 "s"))))
    (is (= "ovNil9-any" (dynamic #'d/ovNil9 ["s"]))))
  (testing "with no other candidate the nil is an error"
    (is (error-has? (static '(d/ovNil9b nil)) "`nil` passed to non-nullable `x`"))))

(deftest c1-nothing-else-changed
  (testing "a nil receiver and a parameter of a plain non-null type: as before"
    (is (error-has? (static '(d/plainStr9 nil)) "`nil` passed to non-nullable `s`"))
    (is (error-has? (dynamic #'d/plainStr9 [nil]) "`nil` passed to non-nullable `s`"))
    (is (= "plainStr9:a" (static '(d/plainStr9 "a")))))
  (testing "the guard does not touch a literal: it is not wrapped, and a literal is never nil"
    (is (= "tAny9:5" (static '(d/tAny9 5))))
    (is (= "tAny9:x" (static '(d/tAny9 "x"))))
    (is (= "varNn9:3" (static '(d/varNn9 1 "a" 2.5))))))

;; ---------------------------------------------------------------- C2

;; http4k: `(r/.bind "/health" h/Method.GET)` printed "Reflection warning ... can't be resolved statically". The string
;; literal was never the cause: the enum entry var `h/Method.GET` had no static type. A literal has one in every position.

(deftest c2-an-enum-entry-var-has-a-static-type
  (testing "the call of the http4k shape: a string literal receiver and an enum entry var"
    (let [r (paths '(d/.bind9 "x" d/Verb9.GET))]
      (is (= "bind9-String:x:GET" (:value r)))
      (is (pos? (:static r)))
      (is (zero? (:dynamic r)) "no call-dyn in the expansion")
      (is (not (:warned? r)) "no reflection warning")))
  (testing "the same selection as the dynamic path and the var as a value make: the String receiver, not CharSequence"
    (is (= "bind9-String:x:GET" (dynamic #'d/.bind9 ["x" d/Verb9.GET])))
    (is (= "bind9-String:x:GET" (as-value #'d/.bind9 "x" d/Verb9.GET))))
  (testing "an enum entry with a body (a subclass of the enum)"
    (let [r (paths '(d/.bind9 "x" d/Verb9.POST))]
      (is (= "bind9-String:x:POST" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r))))
  (testing "an enum entry var as the receiver, and as an argument that chooses between overloads"
    (let [r (paths '(d/.on9 d/Verb9.GET "a"))]
      (is (= "on9-Verb:GET:a" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (let [r (paths '(d/.on9 d/Verb9.POST "a"))]
      (is (= "on9-Verb:POST:a" (:value r)))
      (is (and (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (let [r (paths '(d/pickV9 d/Verb9.POST))]
      (is (= "pickV9-Verb" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (is (= "pickV9-Verb" (dynamic #'d/pickV9 [d/Verb9.GET])))))

(deftest c2-a-literal-has-a-static-type-in-every-position
  (doseq [[form want] [['(d/.ss9 "s") "ss9-String"]
                       ['(d/.cc9 \a) "cc9:a"]
                       ['(d/.bb9 true) "bb9:true"]
                       ['(d/.ll9 1) "ll9-Int:1"]
                       ['(d/.ll9 5000000000) "ll9-Long:5000000000"]
                       ['(d/.dd9 1.5) "dd9:1.5"]
                       ['(d/pickC9 \a) "pickC9-Char"]
                       ['(d/pickB9 false) "pickB9-Boolean"]
                       ['(d/pickV9 "s") "pickV9-String"]
                       ['(d/pickO9 d/Single9) "pickO9-Single9"]]]
    (let [r (paths form)]
      (is (= want (:value r)) (pr-str form))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str form r)))))

(deftest c2-nothing-else-changed
  (testing "a call that was static selects the same declaration"
    (is (= "ss9-CharSequence" (static '(let [^CharSequence s "s"] (d/.ss9 s)))))
    (is (= "bind9-Int:x:3" (static '(d/.bind9 "x" 3)))))
  (testing "an argument of unknown type is still the dynamic path with its warning"
    (let [r (paths '(let [v (identity d/Verb9.GET)] (d/pickV9 v)))]
      (is (= "pickV9-Verb" (:value r))))
    (let [r (paths '(d/.bind9 (str "x" (rand-int 1)) d/Verb9.GET))]
      (is (= "bind9-String:x0:GET" (:value r)))))
  (testing "a Java static field is still untyped for kt (a call with only that argument is a run-time cast)"
    (is (= "pickV9-Verb" (static '(d/pickV9 fx.r9.Verb9/GET))))))

;; ---------------------------------------------------------------- C3

;; Ktor: `(defn u ^String [s] ...)` then `(creq/.get client (u "/x"))` warned, `^String (u "/x")` did not. The Clojure
;; compiler takes the tag of the arglist, else of the var, for a call of a var; kt does the same.

(defn u9 [s] (str "u" s))
(defn us9 ^String [s] (str "u" s))
(defn ucs9 ^CharSequence [s] (str "u" s))
(defn ubytes9 ^bytes [s] (byte-array 1))
(defn ubq9 ^"[B" [s] (byte-array 1))
(defn ul9 ^long [s] 1)
(defn ud9 ^double [s] 1.5)
(defn ^String uvar9 [s] (str "u" s))
(defn ^java.lang.String ufq9 [s] (str "u" s))
(defn uvarq9 {:tag 'java.lang.String} [s] (str "u" s))
(defn uvar-arity9 (^String [] "a") (^String [x & more] "b"))
(defn uarity9 (^String [x] "one") ([x y] 2))
(defn bad9 ^String [s] 1)
(defmacro umac9 ^String [s] `(str "u" ~s))

(deftest c3-the-tag-of-a-function-is-the-static-type-of-a-call
  (testing "the arglist tag: a direct call, no reflection warning"
    (let [r (paths '(d/tag9 (us9 "x")))]
      (is (= "tag9-String" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r))))
  (testing "the tag decides like a hint that you wrote: a CharSequence tag chooses the CharSequence overload"
    (let [r (paths '(d/tag9 (ucs9 "x")))]
      (is (= "tag9-CharSequence" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (is (= "tag9-CharSequence" (static '(d/tag9 ^CharSequence (u9 "x")))) "the same as a hint on the form"))
  (testing "the tag of the var, in its forms"
    (doseq [f ['(d/tag9 (uvar9 "x")) '(d/tag9 (ufq9 "x")) '(d/tag9 (uvarq9 "x"))]]
      (let [r (paths f)]
        (is (= "tag9-String" (:value r)) (pr-str f))
        (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str f r)))))
  (testing "an array: ^bytes and ^\"[B\""
    (doseq [f ['(d/tagB9 (ubytes9 "x")) '(d/tagB9 (ubq9 "x"))]]
      (let [r (paths f)]
        (is (= "tagB9-ByteArray" (:value r)) (pr-str f))
        (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str f r)))))
  (testing "primitives: ^long and ^double"
    (let [r (paths '(d/tagL9 (ul9 "x")))]
      (is (= "tagL9-Long" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (let [r (paths '(d/tagD9 (ud9 "x")))]
      (is (= "tagD9-Double" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r))))
  (testing "the arglist that takes this number of arguments, a variadic one included"
    (doseq [f ['(d/tag9 (uvar-arity9)) '(d/tag9 (uvar-arity9 1 2 3)) '(d/tag9 (uarity9 1))]]
      (let [r (paths f)]
        (is (= "tag9-String" (:value r)) (pr-str f))
        (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str f r))))
    (testing "an arity without a tag has none (Clojure takes none either)"
      (let [r (paths '(d/tag9 (uarity9 1 2)))]
        (is (= "tag9-Int" (:value r)))
        (is (pos? (:dynamic r)) "unknown type: the dynamic path"))))
  (testing "the tag of a function of clojure.core (str, subs)"
    (doseq [f ['(d/tag9 (str 1 2)) '(d/tag9 (subs "abc" 1))]]
      (let [r (paths f)]
        (is (= "tag9-String" (:value r)) (pr-str f))
        (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str f r)))))
  (testing "the tag is a type for the receiver too"
    (let [r (paths '(d/.bind9 (us9 "x") d/Verb9.GET))]
      (is (= "bind9-String:ux:GET" (:value r)))
      (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))
    (let [r (paths '(d/.bind9 (ucs9 "x") d/Verb9.GET))]
      (is (= "bind9-CharSequence:ux:GET" (:value r)))
      (is (and (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))))

(deftest c3-nothing-else-changed
  (testing "a function without a tag, a local function, a macro: unknown, the dynamic path"
    (doseq [f ['(d/tag9 (u9 "x")) '(d/tag9 (umac9 "x")) '(let [g (fn [s] (str "u" s))] (d/tag9 (g "x")))]]
      (let [r (paths f)]
        (is (= "tag9-String" (:value r)) (pr-str f))
        (is (pos? (:dynamic r)) (pr-str f r)))))
  (testing "a hint on the form wins, and it was always static"
    (let [r (paths '(d/tag9 ^CharSequence (us9 "x")))]
      (is (= "tag9-CharSequence" (:value r)))
      (is (and (zero? (:dynamic r)) (not (:warned? r))) (pr-str r)))))

(deftest c3-a-wrong-tag-is-a-kt-error
  (testing "a tag that is wrong: the existing `kt:` error at run time, not ClassCastException"
    (let [r (static '(d/tag9 (bad9 "x")))]
      (is (error-has? r "kt: (d/tag9 (bad9 \"x\")): the argument `x` (String) is java.lang.Long 1"
                      "the Kotlin declaration that was selected needs java.lang.String" "Kotlin: fun tag9(") r)))
  (testing "the same on another call"
    (let [r (static '(d/tagB9 (bad9 "x")))]
      (is (error-has? r "kt: (d/tagB9 (bad9 \"x\"))") r))))

(deftest c3-the-declared-type-of-a-kt-call-flows
  (testing "a property read, an extension function, a companion property: the Kotlin return type is the static type"
    (doseq [[f want] [['(d/nested9 (d/path (d/Rq9 "/p" 3))) "nested9-String"]
                      ['(d/nested9 (d/code (d/Rq9 "/p" 3))) "nested9-Int"]
                      ['(d/nested9 (d/.pathOf9 (d/Rq9 "/p" 3))) "nested9-String"]
                      ['(d/nested9 (d/NAME d/Holder9)) "nested9-String"]
                      ['(d/nested9 (d/CODE d/Holder9)) "nested9-Int"]
                      ['(let [r (d/Rq9 "/p" 3)] (d/nested9 (d/path r))) "nested9-String"]
                      ['(d/nested9 (d/params9 ^fx.r9.RCall9 (d/RCall9))) "nested9-Int"]
                      ['(d/nested9 (d/params9 ^fx.r9.Call9c (d/RCall9))) "nested9-String"]]]
      (let [r (paths f)]
        (is (= want (:value r)) (pr-str f))
        (is (and (pos? (:static r)) (zero? (:dynamic r)) (not (:warned? r))) (pr-str f r))))))

;; ---------------------------------------------------------------- C4

;; Koin: `:<> spike.koin.domain.ProductStore` is the interface of a `defprotocol`. It lives in a DynamicClassLoader: the
;; JVM has it, the Kotlin compiler cannot read it. kt stops before the compiler runs.

(kt/require '[fx :as h9])

(definterface Probe9 (^String foo []))
(defprotocol ProbeP9 (probe-p9 [x]))
(defrecord ProbeR9 [a])
(deftype ProbeT9 [a])

(deftest c4-a-class-made-at-run-time-as-a-type-argument
  (testing "definterface, defprotocol, defrecord, deftype: a kt error that names the class, the cause and the two ways out"
    (doseq [[form cls] [['(h9/typeOfName :<> ckway.round9_test.Probe9) "ckway.round9_test.Probe9"]
                        ['(h9/typeOfName :<> ckway.round9_test.ProbeP9) "ckway.round9_test.ProbeP9"]
                        ['(h9/typeOfName :<> ckway.round9_test.ProbeR9) "ckway.round9_test.ProbeR9"]
                        ['(h9/typeOfName :<> ckway.round9_test.ProbeT9) "ckway.round9_test.ProbeT9"]]]
      (let [r (static form)]
        (is (error-has? r (str "kt: " (pr-str form) ": the class `" cls "` in `:<>` was made at run time")
                        "`defprotocol`" "`definterface`" "`deftype`" "`defrecord`" "`gen-class`" "`kt/reify`"
                        "no class file on the class path" "1. AOT-compile the namespace that defines it"
                        "2. Use an overload that takes a `KClass`" "(kt/ref ") (pr-str form r))
        (is (not (str/includes? (:error r) "unresolved reference")) "the compiler did not run"))))
  (testing "a type argument inside another type argument"
    (is (error-has? (static '(h9/typeOfName :<> (List ckway.round9_test.Probe9))) "the class `ckway.round9_test.Probe9` in `:<>`")))
  (testing "it is found before the compiler runs: with no compiler on the class path the error is the same"
    (with-redefs [ckway.bridge.kotlinc/compile-source (fn [& _] (throw (ex-info "the compiler ran" {})))]
      (is (error-has? (static '(h9/typeOfName :<> ckway.round9_test.Probe9)) "was made at run time")))))

(deftest c4-nothing-else-changed
  (testing "a class with a class file (the JDK, a Kotlin class of a jar or directory) is compiled as before"
    (is (= "String" (static '(h9/typeOfName :<> String))))
    (is (= "UUID" (static '(h9/typeOfName :<> java.util.UUID))))
    (is (= "Route9" (static '(h9/typeOfName :<> fx.r9.Route9))))
    (is (= "List" (static '(h9/typeOfName :<> (List fx.r9.Route9))))))
  (testing "a call that is not reified takes any class in `:<>`: no bridge, no compiler"
    (is (= "typed9:null" (static '(d/typed9 nil :<> String?))))))

(deftest c4-a-stored-bridge-loads-when-the-class-exists-at-run-time
  (testing "the check runs only when the compiler is about to run: an installed bridge is used as it is"
    (let [ran (atom 0)
          spec {:readable "c4probe9" :identity "c4-probe-9" :stamp-classes []
                :source (fn [cname]
                          (let [simple (subs cname (inc (.lastIndexOf ^String cname ".")))]
                            (str "package ckway.bridge\nclass " simple " { fun call(): String = \"c4\" }\n")))
                :what "the C4 probe" :before-compile #(swap! ran inc)}
          b (requiring-resolve 'ckway.bridge/kotlin-bridge-class)]
      (is (class? (b spec)))
      (let [first-ran @ran]
        (is (class? (b spec)) "the second time: installed")
        (is (= first-ran @ran) "no compilation, so no check")))))

(def ^:private aot-proto-source
  "(ns aot.proto9
  (:require [ckway.core :as kt]))

(kt/require '[fx :as f])

(defprotocol Store9 (put9 [s]))

(defn run [] (f/typeOfName :<> aot.proto9.Store9))
")

(deftest c4-aot-compiled-namespace-works
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "kt-c4" (make-array java.nio.file.attribute.FileAttribute 0)))
        src (io/file root "src") classes (io/file root "classes")
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "proto9.clj") aot-proto-source)
        base-cp (System/getProperty "java.class.path")
        sep java.io.File/pathSeparator
        compile-cp (str/join sep [base-cp (.getPath src) (.getPath classes)])
        run-cp (str/join sep [base-cp (.getPath classes)])
        clj (fn [cp code] (clojure.java.shell/sh "clojure" "-Scp" cp "-M" "-e" code))
        c (clj compile-cp (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.proto9))"))]
    (is (zero? (:exit c)) (str (:err c) (:out c)))
    (is (.exists (io/file classes "aot" "proto9" "Store9.class")) "the protocol interface is a class file")
    (let [r (clj run-cp "(require 'aot.proto9) (println (aot.proto9/run))")]
      (is (zero? (:exit r)) (:err r))
      (is (= "Store9" (str/trim (:out r)))))))

;; ---------------------------------------------------------------- C5

;; `->kotlin` (batch B) gives a kt error for nil where a Kotlin function VALUE (a Clojure function made by `<-kotlin`)
;; takes a function that is not nullable. A nullable function type, a Java single-method interface, a `fun interface`
;; and every other type pass nil as before.

(deftest c5-nil-for-a-function-parameter-of-a-function-value
  (testing "a function type that is not nullable: nil is a kt error that says which argument"
    (let [r (top-error #((d/hofNn9) nil))]
      (is (error-has? r "kt: argument 1 of the Kotlin function ((Int) -> Int) -> String:" "nil where Kotlin expects a non-null function ((Int) -> Int)") r))
    (is (error-has? (top-error #((d/hofAliasNn9) nil)) "nil where Kotlin expects a non-null function ((Int) -> Int)"))
    (is (error-has? (top-error #((d/hofSuspNn9) nil)) "nil where Kotlin expects a non-null function (suspend (Int) -> Int)"))
    (let [r (top-error #((d/hofFiNn9) nil))]
      (is (error-has? r "nil where Kotlin expects a non-null function") r))
    (let [r (top-error #((d/hofJavaNn9) nil))]
      (is (error-has? r "nil where Kotlin expects a non-null function") r)))
  (testing "a function type that is nullable: nil passes, a function passes"
    (is (= "hofN9:null" ((d/hofN9) nil)))
    (is (= "hofN9:2" ((d/hofN9) (fn [x] (inc x)))))
    (is (= "hofAliasN9:null" ((d/hofAliasN9) nil)))
    (is (= "hofAliasN9:2" ((d/hofAliasN9) inc)))
    (is (= "hofSuspN9:null" ((d/hofSuspN9) nil)))
    (is (= "hofSuspN9:fn" ((d/hofSuspN9) (fn [x] x)))))
  (testing "a nullable fun interface, a nullable Java single-method interface"
    (is (= "hofFiN9:null" ((d/hofFiN9) nil)))
    (is (= "hofFiN9:y2" ((d/hofFiN9) (fn [x] (str "y" x)))))
    (is (= "hofJavaN9:null" ((d/hofJavaN9) nil)))
    (is (= "hofJavaN9:x!" ((d/hofJavaN9) (fn [x] (str x "!")))))
    (is (= "hofRun9:null" ((d/hofRun9) nil)))
    (is (= "hofRun9:ran" ((d/hofRun9) (fn [] 1)))))
  (testing "a function that is not nil passes where nil does not"
    (is (= "hofNn9:2" ((d/hofNn9) inc)))
    (is (= "hofAliasNn9:2" ((d/hofAliasNn9) inc)))
    (is (= "hofSuspNn9:fn" ((d/hofSuspNn9) (fn [x] x))))
    (is (= "hofFiNn9:y2" ((d/hofFiNn9) (fn [x] (str "y" x)))))
    (is (= "hofJavaNn9:x!" ((d/hofJavaNn9) (fn [x] (str x "!"))))))
  (testing "the other parameter types of a function value: as before"
    (is (= "hofInt9:null:null" ((d/hofInt9) nil nil)))
    (is (= "hofInt9:1:a" ((d/hofInt9) 1 "a")))
    (is (error-has? (top-error #((d/hofIntNn9) nil "a")) "nil where Kotlin expects a non-null Int"))
    (is (error-has? (top-error #((d/hofIntNn9) 1 nil)) "nil where Kotlin expects a non-null String"))
    (is (= "hofAny9:null" ((d/hofAny9) nil)))
    (is (= "hofTNn9:null" ((d/hofTNn9) nil)))
    (is (= "hofT9:null" ((d/hofT9) nil)))))

;; ================================================================ Batch D: one regression of B2, and findings of the second round of spikes

;; ---------------------------------------------------------------- D1

;; http4k: `interface LensExtractor<in IN, out OUT> : (IN) -> OUT { override operator fun invoke(target: IN): OUT }`. The
;; declared `invoke` OVERRIDES the one of the function type: one member, with its own parameter names and types. B2 gave
;; both, and `(l/.invoke lens req)` was "ambiguous". A class with no `invoke` of its own keeps the synthesized one.

(def ^:private ext-path (d/extPath9))
(def ^:private ext-bi (d/extBi9))
(def ^:private ext-int (d/extD9))
(def ^:private sub-only (d/subOnly9))
(def ^:private sub-none (d/subNone9))
(def ^:private gen-own (d/genOwn9))
(def ^:private gen-none (d/genNone9))
(def ^:private fi-own (d/fiOwn9))
(def ^:private ext-far (b9/extFar9))

(defn- sigs-of [v owner]
  (->> (:kt/decls (meta v)) (filter #(= owner (:owner %))) (filter #(= "invoke" (:name %))) (map :signature)))

(deftest d1-declared-override-in-the-same-interface
  (testing "the object of an anonymous class: the dynamic path"
    (is (= 3 (d/.invoke ext-int "abc")))
    (is (= 3 (dynamic #'d/.invoke [ext-int "abc"])))
    (is (= 3 (as-value #'d/.invoke ext-int "abc"))))
  (testing "a hint on the interface: the static path"
    (is (= 3 (eval-here '(d/.invoke ^fx.r9.ExtD9 (d/extD9) "abc"))))
    (is (= 3 (eval-here '(let [e (d/extD9)] (d/.invoke e "abc")))))
    (is (not (str/includes? (reflection-warnings '(let [e (d/extD9)] (d/.invoke e "abc"))) "Reflection warning"))))
  (testing "one declaration of `invoke` for the interface: the declared one, with its own parameter name"
    (let [s (sigs-of #'d/.invoke "fx.r9.ExtD9")]
      (is (= 1 (count s)) (pr-str s))
      (is (str/includes? (first s) "invoke(target: IN): OUT") (pr-str s))
      (is (not-any? #(str/includes? % "[from") s)))))

(deftest d1-override-in-a-subclass-and-through-the-hierarchy
  (testing "abstract class in between, an override with a more specific JVM signature (and its bridge)"
    (is (= "path:x" (d/.invoke ext-path "x") (dynamic #'d/.invoke [ext-path "x"])))
    (is (= "path:x" (eval-here '(d/.invoke ^fx.r9.ExtPath9 (d/extPath9) "x"))))
    (is (= "path:x" (eval-here '(d/.invoke ^fx.r9.ExtBaseD9 (d/extPath9) "x"))))
    (is (= "path:x" (eval-here '(d/.invoke ^fx.r9.ExtD9 (d/extPath9) "x")))))
  (testing "a subclass that adds another `invoke` of another arity (http4k BiDiLens)"
    (is (= "bi:x" (d/.invoke ext-bi "x") (eval-here '(d/.invoke ^fx.r9.ExtBi9 (d/extBi9) "x"))))
    (is (= "inject:v:t" (d/.invoke ext-bi "v" "t") (eval-here '(d/.invoke ^fx.r9.ExtBi9 (d/extBi9) "v" "t")))))
  (testing "the plain interface keeps the invoke of its function type, a subclass with its own `invoke` has that one"
    (is (= "sub-none:a" (d/.invoke sub-none "a") (eval-here '(d/.invoke ^fx.r9.PlainD9 (d/subNone9) "a"))))
    (is (= "sub-only:a" (d/.invoke sub-only "a") (dynamic #'d/.invoke [sub-only "a"])
           (eval-here '(d/.invoke ^fx.r9.SubOnly9 (d/subOnly9) "a"))))
    (is (= "sub-only:a" (eval-here '(d/.invoke ^fx.r9.PlainD9 (d/subOnly9) "a"))))
    (is (some #(str/includes? % "PlainD9.invoke(p1: String): String  [from (String) -> String]") (sigs-of #'d/.invoke "fx.r9.PlainD9")))
    (let [s (sigs-of #'d/.invoke "fx.r9.SubOnly9")]
      (is (= 1 (count s)) (pr-str s))
      (is (str/includes? (first s) "invoke(name: String): String") (pr-str s)))))

(deftest d1-generic-class-and-fun-interface
  (testing "type parameters in the function type: an own invoke, none"
    (is (= "gen-own:5" (d/.invoke gen-own "5") (dynamic #'d/.invoke [gen-own "5"])))
    (is (= "gen-own:5" (eval-here '(d/.invoke ^fx.r9.GenOwn9 (d/genOwn9) "5"))))
    (is (= "gen-own:5" (eval-here '(d/.invoke ^fx.r9.GenD9 (d/genOwn9) "5"))))
    (is (= "gen-none:6" (d/.invoke gen-none "6") (eval-here '(d/.invoke ^fx.r9.GenD9 (d/genNone9) "6")))))
  (testing "the declaration of the abstract class that declares none keeps the function type's invoke"
    (is (some #(str/includes? % "fx.r9.GenD9.invoke(p1: Any?): Any?  [from (IN) -> OUT]") (sigs-of #'d/.invoke "fx.r9.GenD9"))
        (pr-str (sigs-of #'d/.invoke "fx.r9.GenD9")))
    (is (= 1 (count (sigs-of #'d/.invoke "fx.r9.GenOwn9")))))
  (testing "a fun interface that extends a function type and declares the member"
    (is (= 30 (d/.invoke fi-own "abc") (dynamic #'d/.invoke [fi-own "abc"]) (eval-here '(d/.invoke ^fx.r9.FiOwn9 (d/fiOwn9) "abc"))))
    (is (= 1 (count (sigs-of #'d/.invoke "fx.r9.FiOwn9"))))
    (is (str/includes? (first (sigs-of #'d/.invoke "fx.r9.FiOwn9")) "invoke(text: String): Int"))))

(deftest d1-suspend-function-type-with-an-own-override
  (let [s (d/sExt9)]
    (is (= "s-ext:q" (d/.invoke s "q") (dynamic #'d/.invoke [s "q"]) (eval-here '(d/.invoke ^fx.r9.SExt9 (d/sExt9) "q"))))
    (is (= 1 (count (sigs-of #'d/.invoke "fx.r9.SExt9"))))
    (is (str/includes? (first (sigs-of #'d/.invoke "fx.r9.SExt9")) "suspend operator fun fx.r9.SExt9<IN, OUT>.invoke(target: IN): OUT"))))

(deftest d1-another-package
  (testing "the package of the class has the declared member"
    (is (= "ext-far:x" (b9/.invoke ext-far "x") (eval-here '(b9/.invoke ^fx.r9b.ExtFarImpl9 (b9/extFar9) "x")))))
  (testing "a package that does not know the class: the generic `Function1.invoke`, as in B2"
    (is (= "ext-far:x" (d/.invoke ext-far "x") (dynamic #'d/.invoke [ext-far "x"])))))

(deftest d1-nothing-else-changed
  (testing "a class with no own invoke: the synthesized member and the generic one, as in B2"
    (is (= "routed:x:r" (d/.invoke router "x")))
    (is (= 42 (d/.invoke calc 6 7)))
    (is (error-has? (dynamic #'d/.invoke [router 1.5]) "`p1` is String but got Double")))
  (testing "the checks of the declared member: the declared parameter type"
    (is (error-has? (dynamic #'d/.invoke [sub-only 1.5]) "`name` is String but got Double"))))

(deftest d1-kt-reify-member-with-a-function-parameter
  ;; Rule 6: a Kotlin function value that Clojure gets is a Clojure function. The parameter `nxt` of a `kt/reify` member is
  ;; a Clojure function (also when the member is inherited from a function type): `(nxt x)` works.
  (let [seen (atom nil)
        m (kt/reify d/Mw9 (.invoke [_ nxt] (reset! seen nxt) (fn [s] (str "mw:" (nxt s)))))]
    (is (= "mw:core:q" (d/runMw9 m "q")))
    (is (fn? @seen))
    (is (instance? clojure.lang.IFn @seen))
    (is (= "core:z" (@seen "z")))
    (is (= "mw:core:q" (d/runMw9 (kt/reify d/Mw9 (.invoke [_ nxt] (fn [s] (str "mw:" (d/.invoke nxt s))))) "q"))
        "`.invoke` of the parameter: the dynamic path (a function type has no class for the static path)")))

;; ---------------------------------------------------------------- D2

;; kotlinx.coroutines: `suspend fun receiveCatching(): ChannelResult<E>` (`ChannelResult` is a value class over `Any?`). The
;; JVM result is `Object`: the object when the call suspended and was resumed, the underlying value when it returned at once
;; (a class over a reference type). The result of a suspend call is the value-class object, as for a non-suspend call.

(def ^:private host (d/SvHost9 3))

(defn- sv-cls [x] (some-> x class .getName))

(deftest d2-a-suspend-member-gives-the-value-class-object
  (testing "over a primitive, a reference type and Any?; suspended and not; the dynamic path"
    (is (= ["fx.r9.SvInt9" 5] [(sv-cls (d/.int9 host 2)) (d/intOf9 (d/.int9 host 2))]))
    (is (= "fx.r9.SvInt9" (sv-cls (d/.intNow9 host 2))))
    (is (= "fx.r9.SvDbl9" (sv-cls (d/.dbl9 host))))
    (is (= "fx.r9.SvStr9" (sv-cls (d/.str9 host))))
    (is (= "str:host3" (d/.plain9 (d/.str9 host))))
    (is (= "fx.r9.SvAny9" (sv-cls (d/.any9 host "a"))))
    (is (= "a" (d/anyOf9 (d/.any9 host "a"))))
    (is (nil? (d/anyOf9 (d/.any9 host nil))))
    (is (= "fx.r9.SvNn9" (sv-cls (d/.nn9 host "z"))))
    (is (= 15 (d/intOf9 (d/.withDef9 host))) "a default value (the `$default` synthetic)")
    (is (= 6 (d/intOf9 (d/.withDef9 host 2))))))

(deftest d2-the-static-path
  (testing "a hint on the receiver: a direct call, the result has its static type"
    (is (= 1005 (eval-here '(d/.plain9 (d/.int9 ^fx.r9.SvHost9 ckway.round9-test/sv-host 2)))))
    (is (= "str:host3" (eval-here '(d/.plain9 (d/.str9 ^fx.r9.SvHost9 ckway.round9-test/sv-host)))))
    (is (= "any:q" (eval-here '(d/.plain9 (d/.extAny9 ^fx.r9.SvHost9 ckway.round9-test/sv-host "q")))))
    (is (= "any:x" (eval-here '(d/.plain9 (d/topAny9 "x")))))
    (is (= 1004 (eval-here '(d/.plain9 (d/top9 4)))))
    (is (= "fx.r9.SvStr9" (sv-cls (eval-here '(d/.str9 ^fx.r9.SvHost9 ckway.round9-test/sv-host)))))
    (is (not (str/includes? (reflection-warnings '(d/.plain9 (d/.str9 ^fx.r9.SvHost9 ckway.round9-test/sv-host))) "Reflection warning")))))

(def sv-host host)

(deftest d2-an-extension-and-a-top-level-function
  (is (= 301 (d/intOf9 (d/.ext9 host 1))))
  (is (= "fx.r9.SvAny9" (sv-cls (d/.extAny9 host "q"))))
  (is (= "q" (d/anyOf9 (d/.extAny9 host "q"))))
  (is (= 4 (d/intOf9 (d/top9 4))))
  (is (= "x" (d/anyOf9 (d/topAny9 "x"))))
  (is (= 4 (d/intOf9 (dynamic #'d/top9 [4]))))
  (is (= "x" (d/anyOf9 (dynamic #'d/topAny9 ["x"]))))
  (is (= "fx.r9.SvStr9" (sv-cls (as-value #'d/.str9 host)))))

(deftest d2-nullable-results
  (is (nil? (d/.nIntOrNull9 host false)))
  (is (= 3 (d/intOf9 (d/.nIntOrNull9 host true))))
  (is (nil? (d/.nStrOrNull9 host false)))
  (is (= "fx.r9.SvStr9" (sv-cls (d/.nStrOrNull9 host true))))
  (is (nil? (d/.nAnyOrNull9 host false)))
  (testing "a box over a null is not nil"
    (let [r (d/.nAnyOrNull9 host true)]
      (is (some? r))
      (is (nil? (d/anyOf9 r)))))
  (is (nil? (d/topNull9)))
  (is (nil? (eval-here '(d/.nStrOrNull9 ^fx.r9.SvHost9 ckway.round9-test/sv-host false))))
  (is (= "fx.r9.SvStr9" (sv-cls (eval-here '(d/.nStrOrNull9 ^fx.r9.SvHost9 ckway.round9-test/sv-host true))))))

(deftest d2-a-generic-result-is-not-boxed-twice
  (testing "a type parameter in the result: the object that went in comes out, nothing is added"
    (let [v (d/SvInt9 1)]
      (is (identical? v (d/sId9 v)))
      (is (= 1 (d/intOf9 (d/sId9 v)))))
    (let [v (d/SvAny9 "w")]
      (is (identical? v (d/sIdNow9 v)))
      (is (= "w" (d/anyOf9 (d/sIdNow9 v)))))
    (is (= 5 (d/sId9 5)))
    (is (= "s" (d/sIdNow9 "s")))
    (is (= 1 (d/sFirst9 [1 2])))
    (is (= "fx.r9.SvStr9" (sv-cls (d/sIdNow9 (d/SvStr9 "q")))))
    (is (= "fx.r9.SvInt9" (sv-cls (eval-here '(d/sIdNow9 (d/SvInt9 2) :<> fx.r9.SvInt9)))))))

(deftest d2-kotlin-result-and-duration
  (is (true? (d/resultOk9 (d/sResult9 true))))
  (is (false? (d/resultOk9 (d/sResult9 false))))
  (is (= "kotlin.Result" (sv-cls (d/sResult9 false))))
  (is (= "kotlin.Result" (sv-cls (d/sResultStr9))) "a Success that returned at once is unboxed on the JVM")
  (is (= "kotlin.Result" (sv-cls (d/sResultN9 true))))
  (is (nil? (d/sResultN9 false)))
  (is (= "kotlin.time.Duration" (sv-cls (d/sDur9))))
  (is (= "kotlin.time.Duration" (sv-cls (d/sDurN9 true))))
  (is (nil? (d/sDurN9 false)))
  (is (= "kotlin.Result" (sv-cls (dynamic #'d/sResult9 [true])))))

(deftest d2-the-other-way-a-suspend-lambda-written-in-clojure
  (testing "the lambda returns the object: the call that runs it unboxes as Kotlin expects"
    (is (= 4 (d/runSvInt9 (fn [n] (d/SvInt9 n)))))
    (is (= "got:4" (d/runSvAny9 (fn [n] (d/SvAny9 n)))))
    (is (= "got:null" (d/runSvAny9 (fn [n] (d/SvAny9 nil)))))
    (is (= "got:4" (d/runSvIntN9 (fn [n] (d/SvInt9 n)))))
    (is (= "got:null" (d/runSvIntN9 (fn [n] nil))))
    (is (= 2000 (d/runSvDur9 (fn [] (d/sDur9)))))
    (is (= "ok:7" (d/runSvRes9 (fn [] (d/sResult9 true)))))
    (is (= "fail:res-boom" (d/runSvRes9 (fn [] (d/sResult9 false))))))
  (testing "a value that is the underlying value is a kt error"
    (is (error-has? (top-error #(d/runSvInt9 (fn [n] n))) "fx.r9.SvInt9" "A value class is always the object"))
    (is (error-has? (top-error #(d/runSvAny9 (fn [n] n))) "fx.r9.SvAny9" "A value class is always the object"))
    (is (error-has? (top-error #(d/runSvInt9 (fn [n] nil))) "nil where Kotlin expects a non-null fx.r9.SvInt9")))
  (testing "a suspend function value of Kotlin, called from Clojure and passed back"
    (is (= 6 (d/runSvInt9 (fn [n] (d/top9 (+ n 2))))))))

(deftest d2-a-kt-reify-member-that-returns-a-value-class
  (let [i (kt/reify d/SvIface9
            (.give9 [_ n] (d/SvAny9 (* n 2)))
            (.giveN9 [_ n] (when (pos? n) (d/SvInt9 n))))]
    (is (= "give:6:1:null" (d/useSvIface9 i)))))

(deftest d2-nothing-else-changed
  (testing "a non-suspend call that returns a value class: boxed as before"
    (is (= "fx.r9.SvInt9" (sv-cls (d/SvInt9 1))))
    (is (= 2 (d/intOf9 (d/.plusOne9 (d/SvInt9 1)))))
    (is (= 1001 (d/.plain9 (d/SvInt9 1)))))
  (testing "a suspend function that returns no value class"
    (is (= 11 (d/sId9 11)))))

;; ---------------------------------------------------------------- D3

;; `fun welcome(name: String)` and `(let [v (identity nil)] (s/welcome v))`: Kotlin's own check threw
;; `NullPointerException: Parameter specified as non-null is null`. A nil LITERAL was a compile error. A nil that only the
;; run time knows is a `kt:` error too, for every parameter and receiver that is not nullable (a plain `nil?` test in the
;; expansion). A nullable parameter, a nullable receiver and a value that cannot be nil have no check.

(defmacro ^:private expansion-of
  "The expansion of the kt calls in `form`, as a string; the locals of the surrounding code are known to it."
  [form]
  (let [v (resolve (first form))]
    (pr-str (clojure.walk/macroexpand-all (apply (:inline (meta v)) (rest form))))))

(deftest d3-nil-from-a-variable-at-a-non-null-parameter
  (testing "a String parameter"
    (let [r (static '(let [v (identity nil)] (d/welcome9 v)))]
      (is (error-has? r "kt: (d/welcome9 v): nil where Kotlin expects a non-null String (the argument `name` (String))"
                      "Kotlin: fun welcome9(name: String): String") r)))
  (testing "not a NullPointerException, and a value that is not nil still works"
    (is (= "welcome:w" (static '(let [v (identity "w")] (d/welcome9 v)))))
    (is (= "welcome:w" (static '(let [^String v (identity "w")] (d/welcome9 v))))))
  (testing "a hinted nil is still nil"
    (is (error-has? (static '(let [^String v (identity nil)] (d/welcome9 v))) "nil where Kotlin expects a non-null String (the argument `name`")))
  (testing "the second and the third parameter: the one that is nullable takes nil"
    (is (error-has? (static '(let [v (identity nil)] (d/twoNn9 "a" nil v))) "nil where Kotlin expects a non-null Any (the argument `c` (Any))"))
    (is (error-has? (static '(let [v (identity nil)] (d/twoNn9 v nil "c"))) "non-null String (the argument `a` (String))"))
    (is (= "twoNn9:a:null:c" (static '(let [v (identity nil)] (d/twoNn9 "a" v "c"))))))
  (testing "a collection and a function parameter"
    (is (error-has? (static '(let [v (identity nil)] (d/lenOfNn9 v))) "nil where Kotlin expects a non-null" "(the argument `xs`"))
    (is (error-has? (static '(let [v (identity nil)] (d/fnNn9 v))) "nil where Kotlin expects a non-null (String) -> String (the argument `f`")))
  (testing "a parameter that has a default value, named and positional"
    (is (error-has? (static '(let [v (identity nil)] (d/defNn9 :b v))) "non-null String (the argument `b` (String))"))
    (is (= "defNn9:d:x" (static '(let [v (identity "x")] (d/defNn9 :b v))))))
  (testing "an overloaded function, when the other overload takes an Int the call is still a choice by the types"
    (is (= "ovNn9-String" (static '(let [^String v (identity "s")] (d/ovNn9 v)))))
    (is (error-has? (static '(let [^String v (identity nil)] (d/ovNn9 v))) "nil where Kotlin expects a non-null String")))
  (testing "a primitive parameter: the message it always had"
    (is (error-has? (static '(let [v (identity nil)] (d/primNn9 v 1.0 true))) "nil where Kotlin expects a non-null Int"))))

(deftest d3-nil-receiver
  (testing "an extension, a member and an extension on Any"
    (is (error-has? (static '(let [v (identity nil)] (d/.shoutNn9 ^String v))) "kt: (d/.shoutNn9 v): nil where Kotlin expects a non-null String (the receiver)"))
    (is (error-has? (static '(let [v (identity nil)] (d/.hi9 ^fx.r9.Nn3Box9 v "x"))) "nil where Kotlin expects a non-null fx.r9.Nn3Box9 (the receiver)"))
    (is (error-has? (static '(let [v (identity nil)] (d/.tagOfNn9 ^Object v))) "non-null Any (the receiver)")))
  (testing "a receiver that is nullable takes nil"
    (is (= "shoutN9:null" (static '(let [v (identity nil)] (d/.shoutN9 ^String v)))))
    (is (= "shoutN9:x" (static '(let [v (identity "x")] (d/.shoutN9 ^String v)))))
    (is (= "idRecv9:null" (static '(let [v (identity nil)] (d/.idRecv9 ^Object v))))))
  (testing "a value that is not nil"
    (is (= "shoutNn9:X" (static '(let [^String v (identity "x")] (d/.shoutNn9 v)))))
    (is (= "b hi y" (static '(let [^fx.r9.Nn3Box9 b (d/Nn3Box9 "b") y (identity "y")] (d/.hi9 b y)))))))

(deftest d3-the-dynamic-path-and-reify-have-the-same-answer
  (is (error-has? (dynamic #'d/welcome9 [nil]) "nil"))
  (testing "a kt/reify member has no check of its own: the call through Kotlin that takes the interface is guarded by the caller"
    (let [s (kt/reify d/Sink9 (.put9 [_ x] (str "put:" x)))]
      (is (= "put:q" (d/useSink9 s "q")))
      (is (error-has? (static '(let [s (kt/reify d/Sink9 (.put9 [_ x] "no-check")) v (identity nil)] (d/useSink9 s v)))
                      "nil where Kotlin expects a non-null String (the argument `x` (String))"))
      (is (error-has? (static '(let [^fx.r9.Sink9 s (kt/reify d/Sink9 (.put9 [_ x] "no-check")) v (identity nil)] (d/.put9 s v)))
                      "nil where Kotlin expects a non-null String (the argument `s` (String))")))))

(deftest d3-the-expansion-has-a-plain-nil-check-where-nil-is-possible
  (testing "a local of unknown value: a check, with the error call only in the failing branch"
    (let [e (let [v (identity "x")] (expansion-of (d/welcome9 v)))]
      (is (str/includes? e "(if (clojure.core/nil? ") e)
      (is (str/includes? e "ckway.rt/nn-fail") e)
      (is (not (str/includes? e "ckway.rt/nn-arg")) "no call of a library function on the way of a value that is not nil")))
  (testing "no check for a literal, a number, a call of Kotlin with a non-null result, a constructor and a fn literal"
    (is (not (str/includes? (expansion-of (d/welcome9 "x")) "nn-fail")))
    (is (not (str/includes? (expansion-of (d/welcome9 (d/sNn9))) "nn-fail")))
    (is (not (str/includes? (expansion-of (d/.hi9 (d/Nn3Box9 "b") "y")) "nn-fail")))
    (is (not (str/includes? (expansion-of (d/fnNn9 (fn [s] s))) "nn-fail")))
    (is (not (str/includes? (let [n (int 4)] (expansion-of (d/primNn9 n 1.0 true))) "nn-fail"))))
  (testing "a nullable parameter and a nullable receiver have no check"
    (is (not (str/includes? (let [v (identity nil)] (expansion-of (d/twoNn9 "a" v "c"))) "nn-fail")))
    (is (not (str/includes? (let [v (identity nil)] (expansion-of (d/.shoutN9 ^String v))) "nn-fail")))))

(deftest d3-nothing-else-changed
  (testing "a nil literal is a compile error as before"
    (is (error-has? {:error (compile-error '(d/welcome9 nil))} "no Kotlin declaration of `welcome9` fits"))
    (is (error-has? {:error (compile-error '(d/welcome9 nil))} "welcome9")))
  (testing "the type parameter with a bound keeps its text"
    (is (error-has? (static '(let [v (identity nil)] (d/ovNil9b v))) "nil where Kotlin expects a non-null value of the type parameter `T : Any`")))
  (testing "a Clojure value of a wrong class is still the old error"
    (is (error-has? (static '(let [v (identity 5.5)] (d/welcome9 v))) "`name`" "String"))))

;; ---------------------------------------------------------------- D4

;; The way out for "a function and a property" named `X` for the class: `((kt/ref X routes) x)`. Nobody can type `X`: it is the
;; class var, as the caller writes it (`r/RoutingHandler`: the alias that the calling namespace uses for the package), else the
;; full name. The function way out has the alias of the var too.

(defn- in-scratch-ns
  "Run `f` with `*ns*` a namespace that has the aliases `aliases` ({alias package}) for packages that `kt/require` made."
  [aliases f]
  (let [n (create-ns (gensym "ckway.d4-scratch"))]
    (try
      (doseq [[a pkg] aliases] (.addAlias n a (the-ns (symbol (str "ckway.pkg." pkg)))))
      (binding [*ns* n] (f))
      (finally (remove-ns (ns-name n))))))

(deftest d4-the-class-var-is-written-as-the-caller-writes-it
  (testing "the class is in the package of the var, the caller has an alias: `d/Route9`"
    (let [m (:error (in-scratch-ns {'q "fx.r9"} #(dynamic #'d/routes9 [route])))]
      (is (str/includes? m "((kt/ref q/Route9 routes9) x)") m)
      (is (str/includes? m "(q/routes9 :list ...)") m)))
  (testing "the compile error of the static path: the alias of the namespace being compiled"
    (let [m (compile-error '(d/routes9 ^fx.r9.Route9 (identity nil)))]
      (is (str/includes? (str m) "((kt/ref d/Route9 routes9) x)") m)
      (is (str/includes? (str m) "(d/routes9 :list ...)") m)))
  (testing "no alias for the package: the full name"
    (let [m (:error (in-scratch-ns {} #(dynamic #'d/routes9 [route])))]
      (is (str/includes? m "((kt/ref fx.r9.Route9 routes9) x)") m)
      (is (str/includes? m "(routes9 :list ...)") m)))
  (testing "a member property of a class and a function: the same"
    (let [m (compile-error '(d/items9 ^fx.r9.Box9 (identity nil)))]
      (is (str/includes? (str m) "((kt/ref d/Box9 items9) x)") m)))
  (testing "no X in the text"
    (is (not (str/includes? (str (compile-error '(d/routes9 ^fx.r9.Route9 (identity nil)))) " X ")))))

(deftest d4-the-class-is-in-another-package-than-the-var
  (let [call (fn [] (:error (dynamic #'b9/ambo9 [route])))]
    (testing "the caller requires both packages"
      (let [m (in-scratch-ns {'q "fx.r9b" 'p "fx.r9"} call)]
        (is (str/includes? m "((kt/ref p/Route9 ambo9) x)") m)
        (is (str/includes? m "(q/ambo9 :list ...)") m)))
    (testing "the caller has no alias for the package of the class: the full name"
      (let [m (in-scratch-ns {'q "fx.r9b"} call)]
        (is (str/includes? m "((kt/ref fx.r9.Route9 ambo9) x)") m)
        (is (str/includes? m "(q/ambo9 :list ...)") m)))))

(deftest d4-the-ways-out-work
  (is (= "ambo-fun:1" (b9/ambo9 :list [route])))
  (is (= ["ambo-prop:x"] ((kt/ref d/Route9 ambo9) route)) "the property, as the message writes it")
  (is (= ["routes-prop:x"] ((kt/ref d/Route9 routes9) route))))

(deftest d4-nothing-else-changed
  (testing "a function without parameters and a property without receiver: still no kt form for the property"
    (let [m (:error (dynamic #'d/zero9 []))]
      (is (str/includes? m "Java interop") m)
      (is (not (str/includes? m "kt/ref")) m)))
  (testing "the first line and the candidates"
    (let [m (:error (dynamic #'d/routes9 [route]))]
      (is (str/includes? m "is ambiguous. Candidates:"))
      (is (str/includes? m "val fx.r9.Route9.routes9")))))

;; ---------------------------------------------------------------- D5

;; Ktor `embeddedServer(factory, port = 80, host = ..., watchPaths = ..., module)` and `embeddedServer(factory, environment = ...,
;; configure = ..., module = ...)`. `(serve9 "f" (:port m) (:host m) (fn ...))`: the types of `(:port m)` are unknown. The
;; second declaration fits by the usual binding (its parameters would take the three values), the first only with a trailing
;; lambda. The static path took the second: a `kt:` error at run time. When the types do not decide, the VALUES do.

(def ^:private m5 {:port 8 :host "h" :env (d/Env9 "e")})

(deftest d5-unknown-types-the-values-choose-the-trailing-lambda-too
  (testing "port and host from a map, the module last: the first declaration, by the trailing lambda"
    (is (= "serve9-port:f:8:h:m" (static '(let [m {:port 8 :host "h"}] (d/serve9 "f" (:port m) (:host m) (fn [] "m"))))))
    (is (= "serve9-port:f:8:h:m" (dynamic #'d/serve9 ["f" 8 "h" (fn [] "m")]))))
  (testing "an environment from a map: the second declaration, by the usual binding"
    (is (= "serve9-env:f:e:cfg:m"
           (static '(let [m {:env (ckway.round9-test/env5)}] (d/serve9 "f" (:env m) (fn [] "cfg") (fn [] "m"))))))
    (is (= "serve9-env:f:e:cfg:mod" (static '(let [m {:env (ckway.round9-test/env5)}] (d/serve9 "f" (:env m) (fn [] "cfg")))))))
  (testing "the literals and the typed arguments: as before"
    (is (= "serve9-port:f:0:h:m" (static '(d/serve9 "f" 0 "h" (fn [] "m")))))
    (is (= "serve9-port:f:8:h:m" (static '(let [m {:port 8 :host "h"}] (d/serve9 "f" (long (:port m)) ^String (:host m) (fn [] "m"))))))
    (is (= "serve9-env:f:e:cfg:m" (static '(d/serve9 "f" (d/Env9 "e") (fn [] "cfg") (fn [] "m"))))))
  (testing "the reflection warning says that the call is dynamic, and the typed calls have none"
    (is (str/includes? (reflection-warnings '(let [m {:port 8 :host "h"}] (d/serve9 "f" (:port m) (:host m) (fn [] "m"))))
                       "can't be resolved statically"))
    (is (not (str/includes? (reflection-warnings '(let [m {:port 8 :host "h"}] (d/serve9 "f" (long (:port m)) ^String (:host m) (fn [] "m"))))
                            "Reflection warning")))
    (is (not (str/includes? (reflection-warnings '(d/serve9 "f" 0 "h" (fn [] "m"))) "Reflection warning")))))

(defn env5 [] (d/Env9 "e"))

(deftest d5-the-error-of-the-run-time-selection
  (testing "values that fit no declaration: the usual no-fit error"
    (is (error-has? (static '(let [m {:port "x" :host 1}] (d/serve9 "f" (:port m) (:host m) (fn [] "m"))))
                    "no Kotlin declaration of `serve9` fits"))))

(deftest d5-nothing-else-changed
  (testing "two overloads that both fit the usual binding: the values choose (the dynamic path, as before)"
    (is (= "pickU9-String" (static '(let [m {:a "s"}] (d/pickU9 (:a m) 1)))))
    (is (= "pickU9-Env" (static '(let [m {:a (ckway.round9-test/env5)}] (d/pickU9 (:a m) 1)))))
    (is (= "pickU9-Env" (static '(d/pickU9 (d/Env9 "e") 1)))))
  (testing "a trailing lambda with known types: the same as in A2"
    (is (= "serve9-port:f:80:0.0.0.0:m" (static '(d/serve9 "f" (fn [] "m")))))))

;; ---------------------------------------------------------------- D6

;; Ktor: `fun <P, B : Any, F : Any> P.install(plugin: Plugin<P, B, F>, configure: B.() -> Unit)` and `(sp/StatusPages)` of the type
;; `ApplicationPlugin<StatusPagesConfig>`, a subtype of `Plugin<Application, StatusPagesConfig, PluginInstance>`: Kotlin knows
;; that `B` is `StatusPagesConfig`, so `cfg` of `(fn [cfg] ...)` has that type. In kt it had none, the inner call went the
;; dynamic path, and the hints of its lambda (A4) were not seen. The type that the other arguments fix is the upper bound of the
;; parameter now. It is read from the declared Kotlin type of an argument that is a call of a kt var (also a property).

(deftest d6-the-lambda-parameter-has-the-type-that-another-argument-fixes
  (testing "the receiver of the inner call is typed: the ambiguity is a compile error, and a hint on the inner lambda chooses"
    (is (str/includes? (str (compile-error '(d/.install6 ^fx.r9.AppHost6 (identity nil) (d/appPlug6)
                                                         (fn [cfg] (d/.on6 cfg (fn [c] "x"))))))
                       "is ambiguous"))
    (is (= "install6:on6-call:x" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlug6)
                                                          (fn [cfg] (d/.on6 cfg (fn [^fx.r9.Call9 c] "x")))))))
    (is (= "install6:on6-ctx:y" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlug6)
                                                         (fn [cfg] (d/.on6 cfg (fn [^fx.r9.Ctx9 c] "y"))))))))
  (testing "no reflection warning for the inner call"
    (is (not (str/includes? (reflection-warnings '(fn [^fx.r9.AppHost6 h]
                                                    (d/.install6 h (d/appPlug6) (fn [cfg] (d/.on6 cfg (fn [^fx.r9.Call9 c] "x"))))))
                            "Reflection warning"))))
  (testing "a receiver of unknown type does not matter when one declaration is left: the call is static"
    (is (str/includes? (str (compile-error '(fn [h] (d/.install6 h (d/appPlug6) (fn [cfg] (d/.on6 cfg (fn [c] "x"))))))) "is ambiguous"))))

(deftest d6-the-class-of-the-parameter-is-an-upper-bound-only
  (testing "a hint that the user wrote on the parameter wins"
    (is (= "install6:on6-call:x" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlug6)
                                                          (fn [^fx.r9.CfgA6 cfg] (d/.on6 cfg (fn [^fx.r9.Call9 c] "x")))))))
    (is (str/includes? (str (compile-error '(d/.install6 ^fx.r9.AppHost6 (identity nil) (d/appPlug6)
                                                         (fn [^fx.r9.CfgA6 cfg] (d/.on6 cfg (fn [c] "x"))))))
                       "is ambiguous")))
  (testing "the value of the parameter is the object that the plugin made"
    (is (= "install6:other6" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlugB6)
                                                      (fn [cfg] (d/.other6 cfg))))))
    (is (nil? (compile-error '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlugB6) (fn [cfg] (d/.other6 cfg)))))))
  (testing "the configure parameter is typed with another plugin: the type of that plugin"
    (is (= "install6:other6" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlugB6) (fn [^fx.r9.CfgB6 cfg] (d/.other6 cfg))))))))

(deftest d6-no-type-where-the-arguments-do-not-fix-one
  (testing "two arguments that give different types: none (Kotlin takes the common supertype)"
    (let [m (compile-error '(d/both6 (d/appPlug6) (d/appPlugB6) (fn [cfg] (d/.on6 cfg (fn [c] "x")))))]
      (is (nil? m) m)))
  (testing "a star projection: none"
    (is (nil? (compile-error '(d/.install6 ^fx.r9.AppHost6 (identity nil) (d/starPlug6) (fn [cfg] (d/.on6 cfg (fn [c] "x")))))))
    (is (str/includes? (reflection-warnings '(fn [^fx.r9.AppHost6 h] (d/.install6 h (d/starPlug6) (fn [cfg] (d/.on6 cfg (fn [c] "x"))))))
                       "can't be resolved statically")))
  (testing "a local: only the class is known, so no type argument"
    (is (nil? (compile-error '(let [p (d/appPlug6)] (d/.install6 ^fx.r9.AppHost6 (identity nil) p (fn [cfg] (d/.on6 cfg (fn [c] "x"))))))))
    (is (str/includes? (reflection-warnings '(fn [^fx.r9.AppHost6 h] (let [p (d/appPlug6)] (d/.install6 h p (fn [cfg] (d/.on6 cfg (fn [c] "x")))))))
                       "can't be resolved statically")))
  (testing "a type parameter that no argument fixes, and one in a nested position"
    (is (nil? (compile-error '(d/unfixed6 (fn [cfg] (d/.on6 cfg (fn [c] "x"))) (fn [] (identity nil))))))
    (is (nil? (compile-error '(d/list6 [(d/appPlug6)] (fn [cfg] (d/.on6 cfg (fn [c] "x"))))))))
  (testing "the calls run, the run time decides where the compiler did not"
    (is (= "both6:on6-call:x" (eval-here '(d/both6 (d/appPlug6) (d/appPlug6) (fn [cfg] (d/.on6 cfg (fn [^fx.r9.Call9 c] "x")))))))
    (testing "the hints of the inner lambda are not seen on the dynamic path (cfg has no type): ambiguous at run time"
      (is (error-has? (static '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/starPlug6) (fn [cfg] (d/.on6 cfg (fn [^fx.r9.Ctx9 c] "z")))))
                      "is ambiguous"))
      (is (= "install6:on6-ctx:z" (static '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/starPlug6) (fn [^fx.r9.CfgA6 cfg] (d/.on6 cfg (fn [^fx.r9.Ctx9 c] "z"))))))
          "a hint on the parameter helps")))
  (testing "no configure lambda: the default"
    (is (= "install6:none" (eval-here '(d/.install6 ^fx.r9.AppHost6 (d/makeHost6) (d/appPlug6)))))))

(deftest d6-nothing-else-changed
  (testing "a fn literal at a function type whose parameter is a plain class: as before"
    (is (= "on9-call:x" (d/on9 (fn [^fx.r9.Call9 c] "x"))))
    (is (str/includes? (str (compile-error '(d/withHost9 (fn [host] (d/.at9 host (fn [c] "x")))))) "is ambiguous")))
  (testing "supertype-args"
    (is (= ["fx/r9/AppHost6" "fx/r9/CfgA6" "fx/r9/Inst6"]
           (mapv :class (ckway.meta/supertype-args "fx/r9/AppPlug6" [{:class "fx/r9/CfgA6" :args [] :nullable? false}] "fx/r9/Plug6"))))
    (is (nil? (ckway.meta/supertype-args "fx/r9/AppPlug6" [{:class "fx/r9/CfgA6" :args []}] "fx/r9/Cfg9")))
    (is (nil? (ckway.meta/supertype-args "fx/r9/AppPlug6" [] "fx/r9/Plug6")) "a wrong number of arguments")))
