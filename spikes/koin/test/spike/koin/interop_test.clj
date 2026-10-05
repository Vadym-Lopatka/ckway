(ns spike.koin.interop-test
  "Pins the ckway fixes that this spike asked for. If ckway changes back, a test here fails."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [spike.koin.domain :as d])
  (:import (java.io StringWriter)))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core :as k]
            '[org.koin.core.module :as m]
            '[org.koin.core.qualifier :as q]
            '[org.koin.core.definition :as df])

(defn- root-cause-message [^Throwable e]
  (ex-message (loop [e e] (if-let [c (ex-cause e)] (recur c) e))))

(defn- eval-in-ns
  "Evals `form` in this namespace. Gives {:value v} or {:error message}, and the text that the compiler wrote to *err*."
  [form]
  (let [err (StringWriter.)]
    (binding [*ns* (the-ns 'spike.koin.interop-test)
              *warn-on-reflection* true
              *err* err]
      (assoc (try {:value (eval form)} (catch Throwable e {:error (root-cause-message e)}))
             :err (str err)))))

;; Rule 4: the trailing lambda. Kotlin: module { ... }, single<T> { ... }, scoped<T> { ... }, koinApplication { ... }

(deftest trailing-lambda-positional
  (testing "module"
    ;; Kotlin: module { }
    (is (instance? org.koin.core.module.Module (dsl/module (fn [_m] nil)))))
  (testing "single, factory, with and without a name"
    (let [mod (dsl/module (fn [mdl]
                            ;; Kotlin: single<String> { "a" }
                            (m/.single mdl (fn [_ _] "a") :<> String)
                            ;; Kotlin: single<StringBuilder>(named("b")) { StringBuilder("b") }
                            (m/.single mdl (q/named "b") (fn [_ _] (StringBuilder. "b")) :<> StringBuilder)
                            ;; Kotlin: factory<Long> { 1L }
                            (m/.factory mdl (fn [_ _] 1) :<> Long)))
          app (dsl/koinApplication (fn [^org.koin.core.KoinApplication a] (k/.modules a ^org.koin.core.module.Module mod)))
          koin (k/koin app)]
      (try
        (is (= "a" (k/.get koin :<> String)))
        (is (= "b" (str (k/.get koin (q/named "b") :<> StringBuilder))))
        (is (= 1 (k/.get koin :<> Long)))
        (finally (k/.close app)))))
  (testing "scoped"
    (let [sname (q/named "s")
          mod (dsl/module (fn [mdl]
                            (m/.scope mdl sname (fn [s] (dsl/.scoped s (fn [_ _] (StringBuilder.)) :<> StringBuilder)))))
          app (dsl/koinApplication (fn [a] (k/.modules a ^org.koin.core.module.Module mod)))]
      (try (is (some? (k/.createScope (k/koin app) "s1" sname)))
           (finally (k/.close app)))))
  (testing "koinApplication takes the lambda by position, and the receiver of the lambda is typed"
    (let [{:keys [value err]} (eval-in-ns '(dsl/koinApplication (fn [a] (k/.properties a {"a" "b"}))))]
      (is (instance? org.koin.core.KoinApplication value))
      (is (not (re-find #"Reflection warning|resolved statically" err)) err)
      (k/.close ^org.koin.core.KoinApplication value))))

(deftest type-arguments-go-last
  ;; `:<>` ends the call. Before it, all positional arguments.
  (let [{:keys [error]} (eval-in-ns '(m/.single (dsl/module (fn [_] nil)) :<> String (fn [_ _] "x")))]
    (is (re-find #"positional argument \(fn \[_ _\] \"x\"\) follows a named argument" (str error)))))

(deftest a-defaulted-lambda-is-still-named
  ;; `Koin.get`'s `parameters` has a default, so the positional lambda is not bound to it.
  (let [{:keys [error]} (eval-in-ns '(k/.get ^org.koin.core.Koin (k/koin (dsl/koinApplication (fn [_] nil)))
                                             (fn [] nil) :<> String))]
    (is (re-find #"`qualifier` is Qualifier but got AFunction" (str error)))))

;; Rule 5: a class that Clojure made at run time

(deftest protocol-as-type-argument-is-a-clear-error
  (let [{:keys [error]} (eval-in-ns '(m/.single (dsl/module (fn [_] nil)) (fn [_ _] nil)
                                                :<> spike.koin.domain.ProductStore))]
    (is (str/includes? (str error) "the class `spike.koin.domain.ProductStore` in `:<>` was made at run time"))
    (is (str/includes? (str error) "1. AOT-compile the namespace that defines it"))
    (is (str/includes? (str error) "2. Use an overload that takes a `KClass`"))
    (is (not (str/includes? (str error) "unresolved reference")) "the Kotlin compiler did not run")))

;; Rule 7: nil for `T : Any`

(deftest nil-for-a-bounded-type-parameter
  (let [app (dsl/koinApplication (fn [_] nil))
        koin (k/koin app)
        nilv (identity nil)]
    (try
      ;; Kotlin: koin.getProperty("no.such", null)   -- does not compile in Kotlin
      (let [msg (try (k/.getProperty koin "no.such" nilv) (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? msg "nil where Kotlin expects a non-null value of the type parameter `T : Any`"))
        (is (str/includes? msg "`defaultValue`")))
      (is (nil? (k/.getProperty koin "no.such")) "the one-argument form gives nil")
      (finally (k/.close app)))))

;; Rule 7: return hints and enum entries give a static type

(defn- named-string ^org.koin.core.qualifier.Qualifier [s] (q/named ^String s))
(defn- text ^String [x] (str x))

(deftest hints
  (testing "the ^String tag of a defn is the static type of the call"
    (let [{:keys [value err]} (eval-in-ns '(q/named (text "a")))]
      (is (some? value))
      (is (not (re-find #"Reflection warning|resolved statically" err)) err)))
  (testing "a call with a defn that is tagged with a Kotlin class is a static receiver"
    (let [{:keys [value err]} (eval-in-ns '(q/value (named-string "x")))]
      (is (= "x" value))
      (is (not (re-find #"Reflection warning|resolved statically" err)) err)))
  (testing "an enum entry var has a static type: named(Enum) is chosen with no hint"
    (let [{:keys [value err]} (eval-in-ns '(q/named df/Kind.Singleton))]
      (is (some? value))
      (is (not (re-find #"Reflection warning|resolved statically" err)) err))))
