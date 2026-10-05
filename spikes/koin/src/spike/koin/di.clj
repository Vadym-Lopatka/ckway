(ns spike.koin.di
  "Small helpers over the Koin DSL, written with ckway.

  Why they exist: Koin keys a definition by a Kotlin class. The `single<T> { }` of Koin is
  `inline reified`, so `T` must be a class that the Kotlin compiler can see. The interface of a
  Clojure protocol is made at run time, so the compiler cannot see it. `single-of` and
  `factory-of` take the class as a value (a `KClass`) and so work for a protocol."
  (:require [ckway.core :as kt]))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core.module :as m]
            '[org.koin.core.definition :as df]
            '[org.koin.core.instance :as inst]
            '[org.koin.core.qualifier :as q]
            '[kotlin.jvm :as kjvm])

(defn protocol-class
  "The Kotlin class (`KClass`) of a Clojure protocol. Koin uses it as the key of a definition."
  [protocol]
  ;; Kotlin: ProductStore::class
  (kjvm/kotlin ^Class (:on-interface protocol)))

;; Koin names its root scope "_root_". A definition outside a `scope { }` belongs to it.
;; Kotlin: ScopeRegistry.rootScopeQualifier   (not reachable: internal API of Koin, so we write the name)
(def ^:private root-scope (q/named "_root_"))

(defn single-of
  "Kotlin: `single(qualifier) { scope, params -> ... }`, but keyed by the class `kclass`.
  `definition` is a Clojure function of the Koin scope and the definition parameters.
  Returns the Koin definition (for `on-close`)."
  ([mdl kclass definition] (single-of mdl kclass nil definition))
  ([^org.koin.core.module.Module mdl kclass qualifier definition]
   ;; Kotlin: BeanDefinition(rootScope, kclass, qualifier, definition, Kind.Singleton)
   (let [bean (df/BeanDefinition root-scope kclass qualifier definition df/Kind.Singleton)
         ;; Kotlin: SingleInstanceFactory(bean)
         factory (inst/SingleInstanceFactory bean)]
     ;; Kotlin: module.indexPrimaryType(factory)
     (m/.indexPrimaryType mdl factory)
     ;; Kotlin: KoinDefinition(module, factory)
     (df/KoinDefinition mdl factory))))

(defn factory-of
  "Kotlin: `factory(qualifier) { scope, params -> ... }`, but keyed by the class `kclass`."
  ([mdl kclass definition] (factory-of mdl kclass nil definition))
  ([^org.koin.core.module.Module mdl kclass qualifier definition]
   ;; Kotlin: BeanDefinition(rootScope, kclass, qualifier, definition, Kind.Factory)
   (let [bean (df/BeanDefinition root-scope kclass qualifier definition df/Kind.Factory)
         ;; Kotlin: FactoryInstanceFactory(bean)
         factory (inst/FactoryInstanceFactory bean)]
     (m/.indexPrimaryType mdl factory)
     (df/KoinDefinition mdl factory))))

(defn on-close
  "Kotlin: `definition onClose { instance -> ... }`. Koin calls `f` for every definition when the container closes, also with nil for an instance that was never made."
  [definition f]
  ;; Kotlin: definition.onClose { ... }
  (dsl/.onClose definition f))
