(ns ckway.round9-test
  "Fixes after the spikes (http4k, Koin, Ktor). Batch A: argument binding and overload selection.
  A1: a property never wins silently over a function of the same name. A2: a trailing lambda. A3: a collection passed
  as a whole to a `vararg`, on the dynamic path. A4: overloads that differ only in the parameter types of a lambda.
  Batch B: declarations and function values. B1: a companion `operator fun invoke` is a constructor form. B2: `invoke` on
  a class that implements a Kotlin function type. B3: the class var is the receiver of an extension on a Companion.
  B4: a Clojure function that a `kt/reify` member returns is adapted to the declared type.
  The Kotlin shapes are in `test-fixtures/fx/Round9.kt`. Each case is tried on the static path (`eval` of the call),
  on the dynamic path (`rt/call-dyn`) and, where it matters, as a var value."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
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
    (is (str/includes? m "((kt/ref X routes9) x)") "the property: kt/ref")
    (is (str/includes? m "`fx.r9.Route9`") "the class var that X stands for"))
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

