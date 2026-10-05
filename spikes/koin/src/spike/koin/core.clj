(ns spike.koin.core
  "The Catalog application, wired with Koin.

  `(start! config)` makes an isolated Koin container and returns a system map.
  The Catalog operations are plain functions of that system. `(stop! system)` closes it."
  (:require [ckway.core :as kt]
            [spike.koin.catalog :as catalog]
            [spike.koin.modules :as modules])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core :as k]
            '[org.koin.core.scope :as sc]
            '[org.koin.core.parameter :as p]
            '[org.koin.core.qualifier :as q])

(def defaults
  {:app-name "catalog"
   :store :memory          ; one of modules/store-names
   :seed []                ; products of the :seeded store
   :page-size 50           ; the most items that a list gives
   :clock #(Instant/now)   ; a function of no arguments
   :on-close (fn [_what])}) ; called when a definition is closed

(defn- check-config [{:keys [store seed page-size clock on-close] :as config}]
  (let [fail (fn [msg] (throw (ex-info (str "bad config: " msg) {:type :bad-config :config config})))]
    (when-not (contains? modules/store-names store)
      (fail (str ":store must be one of " (sort modules/store-names) ", got " (pr-str store))))
    (when-not (and (integer? page-size) (pos? page-size)) (fail ":page-size must be a positive integer"))
    (when-not (sequential? seed) (fail ":seed must be a list of products"))
    (when-not (ifn? clock) (fail ":clock must be a function"))
    (when-not (ifn? on-close) (fail ":on-close must be a function"))
    config))

(defn start!
  "Starts an isolated Koin container (no global state) and returns the system.
  `config` is merged over `defaults`. `extra-modules` are loaded after the app modules, so they can override."
  ([config] (start! config []))
  ([config extra-modules]
   (let [config (check-config (merge defaults config))
         app-module (modules/app-module config)
         request-module (modules/request-module)
         ;; Kotlin: koinApplication { modules(appModule, requestModule, *extra); properties(mapOf(...)) }
         app (dsl/koinApplication
                            (fn [app]
                (k/.modules app ^java.util.List (into [app-module request-module] extra-modules))
                (k/.properties app {"app.name" (:app-name config)
                                    "catalog.page-size" (long (:page-size config))})))]
     {:app app
      :koin (k/koin app)
      :config config
      :open? (atom true)})))

(defn stop!
  "Closes the container. Safe to call again."
  [{:keys [app open?]}]
  (when (compare-and-set! open? true false)
    ;; Kotlin: koinApplication.close()
    (k/.close ^org.koin.core.KoinApplication app))
  nil)

(defn running? [system] @(:open? system))

(defn- koin ^org.koin.core.Koin [system]
  (when-not (running? system)
    (throw (ex-info "the catalog system is stopped" {:type :stopped})))
  (:koin system))

(defn- service [system]
  ;; Kotlin: koin.get(CatalogService::class)
  (k/.get (koin system) modules/service-class))

;; The Catalog operations ------------------------------------------------------------------------

(defn list-products
  "All products, or the ones with `tag`."
  ([system] (list-products system nil))
  ([system tag] (catalog/list-products (service system) tag)))

(defn get-product [system id] (catalog/get-product (service system) id))

(defn add-product!
  "Adds a product. Throws ex-info {:type :invalid} when the data is bad."
  [system data]
  (catalog/add-product! (service system) data))

(defn delete-product! [system id] (catalog/delete-product! (service system) id))

(defn audit [system] (catalog/audit (service system)))

;; Other things that Koin gives --------------------------------------------------------------------

(defn now
  "A new Instant from the clock factory."
  [system]
  ;; Kotlin: koin.get<Instant>()
  (k/.get (koin system) :<> java.time.Instant))

(defn config-of
  "The config map, taken from the container (reified call)."
  [system]
  ;; Kotlin: koin.get<IPersistentMap>()
  (k/.get (koin system) :<> clojure.lang.IPersistentMap))

(defn property
  "A Koin property, or `default`, or nil."
  ([system key]
   ;; Kotlin: koin.getProperty(key)
   (k/.getProperty (koin system) ^String key))
  ([system key default]
   ;; Kotlin: koin.getProperty(key, default)
   (k/.getProperty (koin system) ^String key default)))

(defn new-store
  "A new store made from `seed`, by the factory with a definition parameter."
  [system seed]
  ;; Kotlin: koin.get(ProductStore::class, named("scratch")) { parametersOf(seed) }
  (k/.get (koin system) modules/store-class (q/named "scratch") (fn [] (p/parametersOf seed))))

(defn with-request
  "Runs `(f request-log)` in a new Koin scope. `request-log` is an `ArrayList` that belongs to the scope.
  The scope is closed at the end, also when `f` throws."
  [system f]
  (let [;; Kotlin: koin.createScope("request-1", named("request"))
        scope (k/.createScope (koin system) (str "request-" (random-uuid)) modules/request-scope)]
    (try
      ;; Kotlin: scope.get<ArrayList<String>>()
      (f (sc/.get scope :<> (java.util.ArrayList String)))
      (finally
        ;; Kotlin: scope.close()
        (sc/.close scope)))))

;; A small demo -----------------------------------------------------------------------------------

(defn -main [& _]
  (let [system (start! {:store :seeded
                        :seed [{:name "Tea" :price 350 :tags ["drink" "hot"]}
                               {:name "Cake" :price 500 :tags ["food"]}]
                        :page-size 10})]
    (try
      (println "app.name:" (property system "app.name"))
      (println "products:" (list-products system))
      (println "added:" (add-product! system {:name "Water" :price 100 :tags ["drink"]}))
      (println "drinks:" (map :name (list-products system "drink")))
      (println "bad add:" (try (add-product! system {:name "" :price 1})
                               (catch clojure.lang.ExceptionInfo e (ex-message e))))
      (println "deleted 1:" (delete-product! system 1))
      (println "audit:" (map #(update % :at str) (audit system)))
      (println "request:" (with-request system (fn [^java.util.ArrayList log] (.add log "hello") (vec log))))
      (finally (stop! system)))))
