(ns spike.koin.forms-test
  "Each Koin form that the spike uses, called straight through `kt`, in an isolated container.
  Every form has its Kotlin line above it. The reified calls (`:<>`) need the `:kotlinc` alias or a stored bridge."
  (:require [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [spike.koin.di :as di]
            [spike.koin.domain :as d])
  (:import (org.koin.core.error ClosedScopeException DefinitionOverrideException NoDefinitionFoundException)))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core :as k]
            '[org.koin.core.module :as m]
            '[org.koin.core.scope :as sc]
            '[org.koin.core.parameter :as p]
            '[org.koin.core.qualifier :as q]
            '[kotlin :as kot])

;; Kotlin: String::class, ProductStore::class
(def ^kotlin.reflect.KClass string-class (kt/ref String class))
(def ^kotlin.reflect.KClass sb-class (kt/ref StringBuilder class))
(def ^kotlin.reflect.KClass store-class (di/protocol-class d/ProductStore))

(defmacro with-koin
  "Kotlin: val app = koinApplication { modules(...) }; try { ... } finally { app.close() }
  `bindings` is [koin-sym module ...]. The properties are optional, as a map."
  [[sym props & modules] & body]
  `(let [app# (dsl/koinApplication
               :appDeclaration
               (fn [^org.koin.core.KoinApplication a#]
                 (k/.modules a# ^java.util.List (vector ~@modules))
                 (let [props# ~props]
                   (when (some? props#) (k/.properties a# ^java.util.Map props#)))))
         ~sym (k/koin app#)]
     (try ~@body (finally (k/.close app#)))))

(defmacro module [& body]
  `(dsl/module :moduleDeclaration (fn [~(with-meta 'm {:tag 'org.koin.core.module.Module})] ~@body)))

;; -------------------------------------------------------------------------------------------------
;; Reified calls: `single<T> { }`, `get<T>()`, `getOrNull<T>()`, `inject<T>()`

(deftest reified-single-and-get
  ;; Kotlin: val mod = module { single<String> { "hello" } ; factory<StringBuilder> { StringBuilder("x") } }
  (let [mod (module (m/.single m :definition (fn [_ _] "hello") :<> String)
                    (m/.factory m :definition (fn [_ _] (StringBuilder. "x")) :<> StringBuilder))]
    (with-koin [koin nil mod]
      ;; Kotlin: koin.get<String>()
      (is (= "hello" (k/.get koin :<> String)))
      ;; Kotlin: koin.getOrNull<String>()
      (is (= "hello" (k/.getOrNull koin :<> String)))
      ;; Kotlin: koin.getOrNull<Long>()    -- no definition: null, no exception
      (is (nil? (k/.getOrNull koin :<> Long)))
      ;; Kotlin: koin.get<Long>()
      (is (thrown? NoDefinitionFoundException (k/.get koin :<> Long)))
      ;; Kotlin: val lazy: Lazy<String> by koin.inject<String>()
      (let [lazy (k/.inject koin :<> String)]
        (is (= "hello" (kot/value lazy))))
      ;; Kotlin: koin.injectOrNull<Long>()
      (is (nil? (kot/value (k/.injectOrNull koin :<> Long))))
      (testing "single is one object, factory is a new object each time"
        (is (identical? (k/.get koin :<> String) (k/.get koin :<> String)))
        (is (not (identical? (k/.get koin :<> StringBuilder) (k/.get koin :<> StringBuilder))))))))

(deftest non-reified-twins-take-a-kclass
  (let [mod (module (m/.single m :definition (fn [_ _] "hello") :<> String))]
    (with-koin [koin nil mod]
      ;; Kotlin: koin.get(String::class)
      (is (= "hello" (k/.get koin string-class)))
      ;; Kotlin: koin.getOrNull(String::class)
      (is (= "hello" (k/.getOrNull koin string-class)))
      ;; Kotlin: koin.getOrNull(Long::class)
      (is (nil? (k/.getOrNull koin (kt/ref Long class))))
      (testing "a java.lang.Class is not a KClass: kt says so"
        (is (re-find #"needs kotlin.reflect.KClass"
                     (try (k/.get koin String) (catch clojure.lang.ExceptionInfo e (ex-message e)))))))))

(deftest get-inside-a-definition
  ;; Kotlin: module { single<String>(named("name")) { "Ann" }
  ;;                  single<Int>   { get<String>(named("name")).length } }
  (let [mod (module (m/.single m :qualifier (q/named "name") :definition (fn [_ _] "Ann") :<> String)
                    (m/.single m :definition (fn [^org.koin.core.scope.Scope s _]
                                               ;; Kotlin: get<String>(named("name")).length
                                               (count (sc/.get s (q/named "name") :<> String)))
                               :<> Int))]
    (with-koin [koin nil mod]
      (is (= 3 (k/.get koin :<> Int))))))

;; -------------------------------------------------------------------------------------------------
;; What type is a Clojure thing registered under?

(deftest the-protocol-interface-is-a-key
  (let [store (d/memory-store)
        mod (module
             ;; Kotlin: single<ProductStore> { store }   -- reified: NOT possible, see FINDINGS 4
             ;; With the class as a value it works:
             ;; Kotlin: single(ProductStore::class) { store }
             (di/single-of m store-class (fn [_ _] store)))]
    (with-koin [koin nil mod]
      (is (identical? store (k/.get koin store-class)))
      (is (identical? store (k/.getOrNull koin store-class))))))

(declare aot-interface?)

(defn- compiler-present? []
  (try (Class/forName "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler") true
       (catch ClassNotFoundException _ false)))

(deftest reified-with-a-protocol-interface-does-not-compile
  ;; Kotlin: single<ProductStore> { store }
  ;; The interface is made by `defprotocol` at run time: it is not a file on the class path, so the
  ;; Kotlin compiler (which ckway runs for a `:<>` call) cannot name it.
  (when-not (aot-interface?) ; with AOT it compiles: see the last test
   (let [msg (try (eval '(do (ckway.core/require '[org.koin.core.module :as m])
                            (m/.single (org.koin.dsl.ModuleDSLKt/module false (fn [_] nil))
                                       :definition (fn [_ _] nil)
                                       :<> spike.koin.domain.ProductStore)))
                 (catch Throwable e (ex-message (or (ex-cause e) e))))]
    (if (compiler-present?)
      (do (is (re-find #"Kotlin compiler rejected" (str msg)))
          (is (re-find #"unresolved reference 'domain'" (str msg))))
      ;; no compiler (the run without :kotlinc): a new `:<>` call cannot be compiled at all
      (is (re-find #"needs the Kotlin compiler" (str msg)))))))

(deftest any-plus-bind-is-the-public-api-route
  ;; Kotlin: single<Any> { store } bind ProductStore::class
  ;; Reified `Any` is fine. `bind` takes a KClass, so the protocol is a secondary type.
  (let [store (d/memory-store)
        mod (module (-> (m/.single m :definition (fn [_ _] store) :<> Object)
                        (dsl/.bind store-class)))]
    (with-koin [koin nil mod]
      (is (identical? store (k/.get koin store-class)))
      (testing "the primary type is Any, so it is also found as Any"
        (is (identical? store (k/.get koin :<> Object)))))))

(deftest any-plus-bind-two-definitions-share-the-key-any
  ;; Both definitions have the primary key (Any, no name). The second one replaces the first as `Any`.
  ;; Each still has its own `bind`, so both stay reachable by their interface. In one module, or when
  ;; overriding is allowed (the default), Koin does not complain. With allowOverride(false) and the
  ;; two definitions in two modules, it does. This is why `di/single-of` keys by the protocol itself.
  (let [store (d/memory-store)
        other (reify Runnable (run [_]))
        mod-a (module (-> (m/.single m :definition (fn [_ _] store) :<> Object) (dsl/.bind store-class)))
        mod-b (module (-> (m/.single m :definition (fn [_ _] other) :<> Object) (dsl/.bind (kt/ref Runnable class))))]
    (with-koin [koin nil mod-a mod-b]
      (is (identical? store (k/.get koin store-class)))
      (is (identical? other (k/.get koin (kt/ref Runnable class))))
      (is (identical? other (k/.get koin :<> Object)) "the last one won the key Any"))
    (is (thrown? DefinitionOverrideException
                 (dsl/koinApplication
                  :appDeclaration
                  (fn [^org.koin.core.KoinApplication a]
                    (k/.allowOverride a false)
                    (k/.modules a ^java.util.List [mod-a mod-b])))))))

;; -------------------------------------------------------------------------------------------------
;; Qualifiers and parameters

(deftest named-qualifiers
  ;; Kotlin: module { single<String>(named("a")) { "A" }; single<String>(named("b")) { "B" } }
  (let [mod (module (m/.single m :qualifier (q/named "a") :definition (fn [_ _] "A") :<> String)
                    (m/.single m :qualifier (q/named "b") :definition (fn [_ _] "B") :<> String))]
    (with-koin [koin nil mod]
      ;; Kotlin: koin.get<String>(named("a"))
      (is (= "A" (k/.get koin (q/named "a") :<> String)))
      (is (= "B" (k/.get koin (q/named "b") :<> String)))
      ;; Kotlin: koin.get(String::class, named("b"))
      (is (= "B" (k/.get koin string-class (q/named "b"))))
      (is (nil? (k/.getOrNull koin (q/named "c") :<> String)))
      (is (nil? (k/.getOrNull koin :<> String)) "a definition with a name is not found without it")
      (let [e (try (k/.get koin (q/named "c") :<> String) (catch NoDefinitionFoundException e e))]
        (is (re-find #"qualifier 'c'" (ex-message e)))))))

(deftest definition-parameters
  ;; Kotlin: module { factory<StringBuilder> { params -> StringBuilder("Hello " + params.get<String>() + " " + params.get<Int>()) } }
  ;; (Not `factory<String>`: Koin gives back a parameter that has the type that was asked for.)
  (let [mod (module (m/.factory m :definition (fn [_ ^org.koin.core.parameter.ParametersHolder ps]
                                                ;; Kotlin: params.get<String>() ; params.get<Int>()
                                                (StringBuilder. (str "Hello " (p/.get ps :<> String) " " (p/.get ps :<> Int))))
                                :<> StringBuilder)
                    (m/.factory m :qualifier (q/named "idx")
                                :definition (fn [_ ^org.koin.core.parameter.ParametersHolder ps]
                                              ;; Kotlin: params[0]
                                              (StringBuilder. (str "first=" (p/.get ps 0) " size=" (p/.size ps))))
                                :<> StringBuilder))]
    (with-koin [koin nil mod]
      ;; Kotlin: koin.get<StringBuilder> { parametersOf("Ann", 3) }
      (is (= "Hello Ann 3" (str (k/.get koin :parameters (fn [] (p/parametersOf "Ann" (int 3))) :<> StringBuilder))))
      ;; Kotlin: koin.get(StringBuilder::class, named("idx")) { parametersOf("x", "y") }
      (is (= "first=x size=2" (str (k/.get koin sb-class (q/named "idx") (fn [] (p/parametersOf "x" "y"))))))
      (testing "a missing parameter is a Koin error"
        (is (thrown? Exception (k/.get koin :<> StringBuilder)))))))

;; -------------------------------------------------------------------------------------------------
;; Properties

(deftest properties
  (with-koin [koin {"name" "catalog" "limit" 10 "ratio" 0.5} (module)]
    ;; Kotlin: koin.getProperty("name")
    (is (= "catalog" (k/.getProperty koin "name")))
    ;; Kotlin: koin.getProperty("missing")
    (is (nil? (k/.getProperty koin "missing")))
    ;; Kotlin: koin.getProperty("missing", "dflt")
    (is (= "dflt" (k/.getProperty koin "missing" "dflt")))
    (is (= 10 (k/.getProperty koin "limit")))
    ;; Kotlin: koin.setProperty("name", "other")
    (k/.setProperty koin "name" "other")
    (is (= "other" (k/.getProperty koin "name")))
    ;; Kotlin: koin.deleteProperty("name")
    (k/.deleteProperty koin "name")
    (is (nil? (k/.getProperty koin "name")))))

(deftest properties-inside-a-definition
  ;; Kotlin: module { single<String> { getProperty("greeting") + "!" } }
  (let [mod (module (m/.single m :definition (fn [^org.koin.core.scope.Scope s _]
                                               (str (sc/.getProperty s "greeting") "!"))
                               :<> String))]
    (with-koin [koin {"greeting" "hi"} mod]
      (is (= "hi!" (k/.get koin :<> String))))))

;; -------------------------------------------------------------------------------------------------
;; Scopes, declare, getAll, close

(deftest scopes
  ;; Kotlin: module { scope(named("s")) { scoped<StringBuilder> { StringBuilder() } } }
  (let [sname (q/named "s")
        mod (module (m/.scope m sname
                              (fn [^org.koin.dsl.ScopeDSL s]
                                (dsl/.scoped s :definition (fn [_ _] (StringBuilder.)) :<> StringBuilder))))]
    (with-koin [koin nil mod]
      ;; Kotlin: val s1 = koin.createScope("s1", named("s"))
      (let [s1 (k/.createScope koin "s1" sname)
            s2 (k/.createScope koin "s2" sname)]
        (is (identical? (sc/.get s1 :<> StringBuilder) (sc/.get s1 :<> StringBuilder)))
        (is (not (identical? (sc/.get s1 :<> StringBuilder) (sc/.get s2 :<> StringBuilder))))
        (testing "a scoped definition is not in the root scope"
          (is (thrown? Exception (k/.get koin :<> StringBuilder))))
        ;; Kotlin: koin.getScope("s1")
        (is (= "s1" (sc/id (k/.getScope koin "s1"))))
        ;; Kotlin: s1.close()
        (sc/.close s1)
        (is (true? (sc/closed s1)))
        (is (thrown? ClosedScopeException (sc/.get s1 :<> StringBuilder)))
        (is (false? (sc/closed s2)))
        (sc/.close s2)))))

(deftest declare-and-get-all
  (with-koin [koin nil (module)]
    ;; Kotlin: koin.declare("late", named("late"))
    (k/.declare koin "late" (q/named "late") :<> String)
    (is (= "late" (k/.get koin (q/named "late") :<> String)))
    ;; Kotlin: koin.getAll<String>()
    (is (= ["late"] (vec (k/.getAll koin :<> String))))))

(deftest on-close-callback
  ;; Kotlin: module { single<String> { "x" } onClose { closed += it } }
  (let [closed (atom [])
        mod (module (di/on-close (m/.single m :definition (fn [_ _] "x") :<> String)
                                 (fn [v] (swap! closed conj v))))]
    ;; Kotlin: val app = koinApplication { modules(mod) }
    (let [app (dsl/koinApplication :appDeclaration (fn [^org.koin.core.KoinApplication a] (k/.modules a ^org.koin.core.module.Module mod)))
          koin (k/koin app)]
      (k/.get koin :<> String)
      (is (= [] @closed))
      ;; Kotlin: app.close()
      (k/.close app)
      (is (= ["x"] @closed)))))

(deftest close-on-koin-itself
  ;; Kotlin: koin.close()
  (let [app (dsl/koinApplication :appDeclaration (fn [^org.koin.core.KoinApplication a] (k/.modules a ^org.koin.core.module.Module (module))))
        koin (k/koin app)]
    (k/.close koin)
    (is (thrown? ClosedScopeException (k/.get koin :<> String)))
    (is (nil? (k/.getOrNull koin :<> String)) "getOrNull does not throw on a closed container")))

(deftest on-close-is-called-also-for-an-instance-never-made
  ;; Koin calls the onClose of every single when the container closes; the instance is then null.
  (let [closed (atom [])
        mod (module (di/on-close (m/.single m :definition (fn [_ _] "x") :<> String)
                                 (fn [v] (swap! closed conj v))))
        app (dsl/koinApplication :appDeclaration (fn [^org.koin.core.KoinApplication a] (k/.modules a ^org.koin.core.module.Module mod)))]
    (k/.close app)
    (is (= [nil] @closed))))

(defn- aot-interface?
  "True when the interface of the protocol is a class file (AOT-compiled, `target/classes` on the class path)."
  []
  (not (instance? clojure.lang.DynamicClassLoader (.getClassLoader ^Class (:on-interface d/ProductStore)))))

(deftest with-aot-the-protocol-is-a-reified-type
  ;; Kotlin: single<ProductStore> { store }; koin.get<ProductStore>()
  ;; Only with the alias :aot and `(compile 'spike.koin.domain)` into target/classes. Skipped otherwise.
  (when (and (aot-interface?) (compiler-present?))
    (is (= :ok (binding [*ns* (the-ns 'spike.koin.forms-test)] (eval '(let [store (d/memory-store)
                            mod (dsl/module :moduleDeclaration
                                            (fn [^org.koin.core.module.Module m]
                                              (m/.single m :definition (fn [_ _] store) :<> spike.koin.domain.ProductStore)))
                            app (dsl/koinApplication (fn [^org.koin.core.KoinApplication a]
                                                       (k/.modules a ^org.koin.core.module.Module mod)))]
                        (try (when (identical? store (k/.get (k/koin app) :<> spike.koin.domain.ProductStore)) :ok)
                             (finally (k/.close app))))))))))
