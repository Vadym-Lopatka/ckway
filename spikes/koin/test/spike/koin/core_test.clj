(ns spike.koin.core-test
  "The Catalog application through `spike.koin.core`: operations, config, lifecycle, override."
  (:require [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [spike.koin.core :as core]
            [spike.koin.di :as di]
            [spike.koin.domain :as d]
            [spike.koin.modules :as modules])
  (:import (java.time Instant)
           (org.koin.core.error ClosedScopeException)))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.dsl :as dsl]
            '[org.koin.core :as k])

(def tea {:name "Tea" :price 350 :tags ["drink" "hot"]})
(def cake {:name "Cake" :price 500 :tags ["food"]})

(defmacro with-system [[sym config & [extra-modules]] & body]
  `(let [~sym (core/start! ~config ~(or extra-modules []))]
     (try ~@body (finally (core/stop! ~sym)))))

(deftest operations
  (with-system [sys {}]
    (testing "an empty catalog"
      (is (= [] (core/list-products sys)))
      (is (nil? (core/get-product sys 1))))
    (testing "add gives the product with an id"
      (is (= {:id 1 :name "Tea" :price 350 :tags ["drink" "hot"]} (core/add-product! sys tea)))
      (is (= {:id 2 :name "Cake" :price 500 :tags ["food"]} (core/add-product! sys cake))))
    (testing "get"
      (is (= "Cake" (:name (core/get-product sys 2))))
      (is (nil? (core/get-product sys 99))))
    (testing "list, with and without a tag"
      (is (= ["Tea" "Cake"] (map :name (core/list-products sys))))
      (is (= ["Tea"] (map :name (core/list-products sys "drink"))))
      (is (= [] (core/list-products sys "nothing"))))
    (testing "delete"
      (is (true? (core/delete-product! sys 1)))
      (is (false? (core/delete-product! sys 1)))
      (is (= ["Cake"] (map :name (core/list-products sys)))))
    (testing "the audit log uses the clock factory"
      (let [log (core/audit sys)]
        (is (= [[:add 1] [:add 2] [:delete 1]] (map (juxt :op :id) log)))
        (is (every? #(instance? Instant (:at %)) log))))))

(deftest validation
  (with-system [sys {}]
    (doseq [[bad problem] [[{:name "" :price 1} "name must be a non-empty string"]
                           [{:name "x" :price -1} "price must be a non-negative integer (cents)"]
                           [{:name "x" :price 1 :tags "a"} "tags must be a list of strings"]]]
      (let [e (try (core/add-product! sys bad) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= (str "invalid product: " problem) (ex-message e)))
        (is (= :invalid (:type (ex-data e))))))
    (is (= [] (core/list-products sys)) "nothing was stored")
    (is (= [] (core/audit sys)) "nothing was logged")))

(deftest config
  (testing "defaults"
    (with-system [sys {}]
      (is (= "catalog" (core/property sys "app.name")))
      (is (= 50 (core/property sys "catalog.page-size")))
      (is (= :memory (:store (core/config-of sys))))))
  (testing "the config map is in the container"
    (with-system [sys {:app-name "shop" :page-size 2}]
      (is (= "shop" (:app-name (core/config-of sys))))
      (is (= "shop" (core/property sys "app.name")))))
  (testing "the page size limits a list"
    (with-system [sys {:page-size 2}]
      (run! #(core/add-product! sys (assoc tea :name (str "p" %))) (range 5))
      (is (= 2 (count (core/list-products sys))))))
  (testing "the :seeded store"
    (with-system [sys {:store :seeded :seed [tea cake]}]
      (is (= ["Tea" "Cake"] (map :name (core/list-products sys))))
      (is (= 3 (:id (core/add-product! sys {:name "Water" :price 1}))))))
  (testing "bad config is a clear error and starts nothing"
    (doseq [[bad msg] [[{:store :disk} "bad config: :store must be one of (:memory :seeded), got :disk"]
                       [{:page-size 0} "bad config: :page-size must be a positive integer"]
                       [{:seed 1} "bad config: :seed must be a list of products"]
                       [{:clock 1} "bad config: :clock must be a function"]]]
      (is (= msg (try (core/start! bad) (catch clojure.lang.ExceptionInfo e (ex-message e))))))))

(deftest property-default
  (with-system [sys {}]
    (is (nil? (core/property sys "no.such")))
    (is (= "dflt" (core/property sys "no.such" "dflt")))))

(deftest factory-is-new-each-time
  (let [ticks (atom 0)]
    (with-system [sys {:clock #(Instant/ofEpochSecond (swap! ticks inc))}]
      (is (= (Instant/ofEpochSecond 1) (core/now sys)))
      (is (= (Instant/ofEpochSecond 2) (core/now sys)))
      (core/add-product! sys tea)
      (is (= [(Instant/ofEpochSecond 3)] (map :at (core/audit sys)))))))

(deftest single-is-one-instance
  (with-system [sys {}]
    (let [a (k/.get ^org.koin.core.Koin (:koin sys) modules/service-class)
          b (k/.get ^org.koin.core.Koin (:koin sys) modules/service-class)]
      (is (identical? a b)))))

(deftest systems-are-isolated
  (with-system [a {}]
    (with-system [b {}]
      (core/add-product! a tea)
      (is (= 1 (count (core/list-products a))))
      (is (= 0 (count (core/list-products b)))))))

;; Lifecycle ---------------------------------------------------------------------------------------

(deftest start-stop-twice
  (dotimes [_ 2]
    (let [sys (core/start! {})]
      (is (core/running? sys))
      (core/add-product! sys tea)
      (core/stop! sys)
      (is (not (core/running? sys)))
      (core/stop! sys) ; a second stop is safe
      (is (not (core/running? sys))))))

(deftest restart-starts-clean
  (let [a (core/start! {})
        _ (core/add-product! a tea)
        _ (core/stop! a)
        b (core/start! {})]
    (try (is (= [] (core/list-products b)) "a new system does not see the old data")
         (finally (core/stop! b)))))

(deftest closed-container-refuses
  (let [sys (core/start! {})
        koin ^org.koin.core.Koin (:koin sys)]
    (core/stop! sys)
    (testing "our functions say the system is stopped"
      (let [e (try (core/list-products sys) (catch clojure.lang.ExceptionInfo e e))]
        (is (= "the catalog system is stopped" (ex-message e)))
        (is (= :stopped (:type (ex-data e))))))
    (testing "Koin itself refuses a get on a closed container"
      (let [e (try (k/.get koin modules/service-class) (catch ClosedScopeException e e))]
        (is (instance? ClosedScopeException e))
        (is (= "Scope '_root_' is closed" (ex-message e)))))
    (testing "also a reified get"
      (is (thrown? ClosedScopeException (k/.get koin :<> clojure.lang.IPersistentMap))))))

(deftest on-close-runs-when-the-container-stops
  (let [closed (atom [])
        sys (core/start! {:on-close #(swap! closed conj %)})]
    (core/add-product! sys tea)          ; makes the service and the store
    (is (= [] @closed) "nothing is closed while the system runs")
    (core/stop! sys)
    (is (= #{:service :memory-store} (set @closed)))
    (core/stop! sys)
    (is (= 2 (count @closed)) "a second stop closes nothing again")))

(deftest on-close-skips-definitions-that-were-never-made
  (let [closed (atom [])
        sys (core/start! {:on-close #(swap! closed conj %)})]
    (core/stop! sys)
    (is (= [] @closed))))

;; Override with a test module ---------------------------------------------------------------------

(kt/require '[org.koin.core.qualifier :as q]
            '[org.koin.core.module :as m])

(defn- fake-store [calls]
  (reify d/ProductStore
    (all-products [_] (swap! calls conj :all) [{:id 7 :name "Fake" :price 1 :tags ["f"]}])
    (product [_ id] (swap! calls conj [:product id]) nil)
    (add-product! [_ data] (swap! calls conj [:add data]) (assoc data :id 8))
    (delete-product! [_ id] (swap! calls conj [:delete id]) true)))

(defn- test-module [calls]
  ;; Kotlin: val testModule = module { single<ProductStore>(named("memory")) { FakeStore() } }
  (dsl/module               (fn [mdl]
                (di/single-of mdl modules/store-class (q/named "memory") (fn [_ _] (fake-store calls))))))

(deftest override-one-definition-with-a-test-module
  (let [calls (atom [])]
    (with-system [sys {} [(test-module calls)]]
      (is (= ["Fake"] (map :name (core/list-products sys))))
      (is (= 8 (:id (core/add-product! sys {:name "n" :price 2}))))
      (is (= [:all [:add {:name "n" :price 2}]] @calls) "the service called the fake store")
      (is (= "catalog" (core/property sys "app.name")) "the other definitions are the same"))))

(deftest override-can-be-forbidden
  ;; Kotlin: koinApplication { allowOverride(false); modules(appModule, testModule) }
  (let [calls (atom [])
        e (try (dsl/koinApplication
                                (fn [app]
                  (k/.allowOverride app false)
                  (k/.modules app ^java.util.List [(modules/app-module (merge core/defaults {}))
                                                   (test-module calls)])))
               nil
               (catch Exception e e))]
    (is (some? e))
    (is (re-find #"Already existing definition" (str (class e) (ex-message e))))))

(deftest request-scope
  (with-system [sys {}]
    (testing "one log per scope; the same log inside one scope"
      (is (= ["a" "b"] (core/with-request sys (fn [^java.util.ArrayList log] (.add log "a") (.add log "b") (vec log)))))
      (is (= [] (core/with-request sys (fn [^java.util.ArrayList log] (vec log))))))
    (testing "the scope is closed also when the body throws"
      (let [seen (atom nil)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (core/with-request sys (fn [log] (reset! seen log) (throw (ex-info "boom" {}))))))
        (is (some? @seen))))))

(deftest definition-parameters
  (with-system [sys {}]
    (let [a (core/new-store sys [tea cake])
          b (core/new-store sys [tea])]
      (is (= ["Tea" "Cake"] (map :name (d/all-products a))))
      (is (= ["Tea"] (map :name (d/all-products b))))
      (is (not (identical? a b)) "a factory makes a new store each time")
      (is (= [] (core/list-products sys)) "the scratch stores are not the app store"))))
