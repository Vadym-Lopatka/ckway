(ns spike.koin.global-test
  "`startKoin` / `stopKoin`: the one test that uses the global container.
  It is the only global state of the spike, and it is stopped in a `finally`."
  (:require [clojure.test :refer [deftest is]]
            [ckway.core :as kt]
            [spike.koin.modules :as modules]
            [spike.koin.catalog :as catalog]
            [spike.koin.core :as core])
  (:import (org.koin.core.error KoinApplicationAlreadyStartedException)))

(set! *warn-on-reflection* true)

(kt/require '[org.koin.core.context :as ctx]
            '[org.koin.core :as k])

(deftest start-and-stop-the-global-container
  (try
    ;; Kotlin: startKoin { modules(appModule) }
    (let [app (ctx/startKoin (fn [a]
                               (k/.modules a ^org.koin.core.module.Module (modules/app-module core/defaults))
                               (k/.properties a {"catalog.page-size" 10})))
          ;; Kotlin: GlobalContext.get()
          koin (ctx/.get ctx/GlobalContext)]
      (is (identical? (k/koin app) koin))
      (let [svc (k/.get ^org.koin.core.Koin koin modules/service-class)]
        (is (= "Tea" (:name (catalog/add-product! svc {:name "Tea" :price 1}))))
        (is (= 1 (count (catalog/list-products svc nil)))))
      (is (thrown? KoinApplicationAlreadyStartedException
                   (ctx/startKoin (fn [_a] nil)))))
    (finally
      ;; Kotlin: stopKoin()
      (ctx/stopKoin)))
  (is (nil? (ctx/.getOrNull ctx/GlobalContext)) "nothing is left"))
