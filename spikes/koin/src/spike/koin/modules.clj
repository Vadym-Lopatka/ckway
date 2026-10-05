(ns spike.koin.modules
  "The Koin modules of the Catalog application.

  Two ways to read a definition are used on purpose:
  * reified, with `:<>`: `scope.get<IPersistentMap>()`. Needs a class that the Kotlin compiler sees.
  * with a `KClass` value: `scope.get(ProductStore::class, named(\"memory\"))`. Works for a protocol."
  (:require [ckway.core :as kt]
            [spike.koin.catalog :as catalog]
            [spike.koin.di :as di]
            [spike.koin.domain :as d]))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core.module :as m]
            '[org.koin.core.scope :as sc]
            '[org.koin.core.parameter :as p]
            '[org.koin.core.qualifier :as q])

;; The classes that are the keys of the definitions.
;; Kotlin: ProductStore::class, CatalogService::class
(def ^kotlin.reflect.KClass store-class (di/protocol-class d/ProductStore))
(def ^kotlin.reflect.KClass service-class (di/protocol-class catalog/CatalogService))

(def store-names
  "The names (Koin qualifiers) of the stores. The key `:store` of the config picks one."
  #{:memory :seeded})

(defn- store-qualifier [store-name]
  ;; Kotlin: named("memory")
  (q/named ^String (name store-name)))

(defn- config-of
  "The config map, read with a reified call."
  [^org.koin.core.scope.Scope scope]
  ;; Kotlin: scope.get<IPersistentMap>()
  (sc/.get scope :<> clojure.lang.IPersistentMap))

(defn- seeded-store [seed]
  (let [store (d/memory-store)]
    (run! #(d/add-product! store %) seed)
    store))

(defn app-module
  "The main module. `config` is the config map (with its defaults already merged in)."
  [config]
  ;; Kotlin: val appModule = module { ... }
  (dsl/module
   :moduleDeclaration
   (fn [^org.koin.core.module.Module mdl]
     ;; Kotlin: single<IPersistentMap> { config }
     (m/.single mdl :definition (fn [_ _] config) :<> clojure.lang.IPersistentMap)

     ;; The clock. A factory: each `get` makes a new Instant.
     ;; Kotlin: factory<Instant> { (get<IPersistentMap>()[:clock])() }
     (m/.factory mdl
                 :definition (fn [^org.koin.core.scope.Scope scope _] ((:clock (config-of scope))))
                 :<> java.time.Instant)

     ;; The stores. One class, two names.
     ;; Kotlin: single<ProductStore>(named("memory")) { MemoryStore() }
     (di/on-close
      (di/single-of mdl store-class (store-qualifier :memory) (fn [_ _] (d/memory-store)))
      (fn [store] (when store ((:on-close config) :memory-store))))
     ;; Kotlin: single<ProductStore>(named("seeded")) { seededStore(get<IPersistentMap>()[:seed]) }
     (di/single-of mdl store-class (store-qualifier :seeded)
                   (fn [^org.koin.core.scope.Scope scope _] (seeded-store (:seed (config-of scope)))))
     ;; A factory with a definition parameter: each `get` makes a new store from the seed it is given.
     ;; Kotlin: factory<ProductStore>(named("scratch")) { params -> seededStore(params.get(0)) }
     (di/factory-of mdl store-class (q/named "scratch")
                    (fn [_ ^org.koin.core.parameter.ParametersHolder params] (seeded-store (p/.get params 0))))

     ;; The service. A single that needs the store, the clock and a property.
     ;; Kotlin: single<CatalogService> { CatalogServiceImpl(get(named(config.store)), { get<Instant>() }, getProperty("catalog.page-size")) }
     (di/on-close
      (di/single-of mdl service-class
                    (fn [^org.koin.core.scope.Scope scope _]
                      (let [;; Kotlin: get(ProductStore::class, named("memory"))   -- not reified, takes the class
                            store (sc/.get scope store-class (store-qualifier (:store (config-of scope))))
                            ;; Kotlin: getProperty<Long>("catalog.page-size")
                            page-size (sc/.getProperty scope "catalog.page-size")]
                        (catalog/make-service store
                                              ;; Kotlin: get<Instant>()
                                              #(sc/.get scope :<> java.time.Instant)
                                              page-size))))
      (fn [svc] (when svc ((:on-close config) :service)))))))

(def request-scope
  "The name of the scope of one request. Kotlin: named(\"request\")"
  (q/named "request"))

(defn request-module
  "A module with a scope. Inside a scope called `request` there is one `ArrayList` (the request log)."
  []
  (dsl/module
   :moduleDeclaration
   (fn [^org.koin.core.module.Module mdl]
     ;; Kotlin: scope(named("request")) { scoped<ArrayList<String>> { ArrayList() } }
     (m/.scope mdl request-scope
               (fn [^org.koin.dsl.ScopeDSL s]
                 (dsl/.scoped s :definition (fn [_ _] (java.util.ArrayList.)) :<> (java.util.ArrayList String)))))))
