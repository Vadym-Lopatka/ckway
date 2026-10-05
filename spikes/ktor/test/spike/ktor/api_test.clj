(ns spike.ktor.api-test
  "The Catalog API: the real server on a free port, called with java.net.http.HttpClient."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spike.ktor.core :as core]
            [spike.ktor.domain :as d]
            [spike.ktor.http-helper :refer [request with-system]]))

(set! *warn-on-reflection* true)

(def tea (json/write-str {:name "Tea" :price 350 :tags ["drink" "hot"]}))
(def cake (json/write-str {:name "Cake" :price 500 :tags ["food"]}))

(deftest health
  (with-system [s {}]
    (let [r (request s "GET" "/health")]
      (is (= 200 (:status r)))
      (is (= {:status "ok"} (:json r)))
      (is (str/starts-with? (:content-type r) "application/json")))))

(deftest port-is-real
  (with-system [s {:port 0}]
    (is (pos? (:port s)))))

(deftest products-crud
  (with-system [s {}]
    (testing "empty list"
      (is (= {:status 200 :json []} (select-keys (request s "GET" "/products") [:status :json]))))
    (testing "create"
      (let [r (request s "POST" "/products" tea)]
        (is (= 201 (:status r)))
        (is (= "/products/1" (:location r)))
        (is (= {:id 1 :name "Tea" :price 350 :tags ["drink" "hot"]} (:json r)))))
    (testing "create without tags gives an empty list"
      (let [r (request s "POST" "/products" (json/write-str {:name "Water" :price 100}))]
        (is (= 201 (:status r)))
        (is (= [] (:tags (:json r))))))
    (request s "POST" "/products" cake)
    (testing "list"
      (is (= ["Tea" "Water" "Cake"] (map :name (:json (request s "GET" "/products"))))))
    (testing "filter by tag"
      (is (= ["Cake"] (map :name (:json (request s "GET" "/products?tag=food")))))
      (is (= ["Tea"] (map :name (:json (request s "GET" "/products?tag=hot")))))
      (is (= [] (:json (request s "GET" "/products?tag=none")))))
    (testing "get one"
      (let [r (request s "GET" "/products/3")]
        (is (= 200 (:status r)))
        (is (= "Cake" (:name (:json r))))))
    (testing "delete"
      (is (= 204 (:status (request s "DELETE" "/products/1"))))
      (is (= 404 (:status (request s "DELETE" "/products/99"))))
      (is (= 404 (:status (request s "GET" "/products/1")))))))

(deftest not-found-and-bad-ids
  (with-system [s {}]
    (testing "unknown id"
      (let [r (request s "GET" "/products/42")]
        (is (= 404 (:status r)))
        (is (= {:error "not found"} (:json r)))))
    (testing "id is not a number"
      (let [r (request s "GET" "/products/abc")]
        (is (= 400 (:status r)))
        (is (contains? (:json r) :error))))
    (testing "delete: unknown id and bad id"
      (is (= 404 (:status (request s "DELETE" "/products/42"))))
      (is (= {:error "not found"} (:json (request s "DELETE" "/products/42"))))
      (is (= 400 (:status (request s "DELETE" "/products/abc")))))
    (testing "unknown routes"
      (doseq [[m p] [["GET" "/nope"] ["GET" "/products/1/extra"] ["PUT" "/products"] ["GET" "/"]]]
        (let [r (request s m p)]
          (is (= 404 (:status r)) (str m " " p))
          (is (= {:error "not found"} (:json r))))))))

(deftest invalid-bodies
  (with-system [s {}]
    (doseq [[label body msg] [["not JSON" "{oops" "body must be a JSON object"]
                              ["empty body" "" "body must be a JSON object"]
                              ["JSON array" "[1,2]" "body must be a JSON object"]
                              ["no name" (json/write-str {:price 1}) "name must be a non-empty string"]
                              ["empty name" (json/write-str {:name "" :price 1}) "name must be a non-empty string"]
                              ["no price" (json/write-str {:name "x"}) "price must be a non-negative integer (cents)"]
                              ["float price" "{\"name\":\"x\",\"price\":1.5}" "price must be a non-negative integer (cents)"]
                              ["negative price" (json/write-str {:name "x" :price -1}) "price must be a non-negative integer (cents)"]
                              ["tags not a list" (json/write-str {:name "x" :price 1 :tags "a"}) "tags must be a list of strings"]
                              ["tags not strings" (json/write-str {:name "x" :price 1 :tags [1]}) "tags must be a list of strings"]]]
      (testing label
        (let [r (request s "POST" "/products" body)]
          (is (= 400 (:status r)))
          (is (= {:error msg} (:json r))))))
    (testing "nothing was stored"
      (is (= [] (:json (request s "GET" "/products")))))))

(defrecord BrokenStore []
  d/ProductStore
  (all-products [_] (throw (ex-info "secret database password" {})))
  (product [_ _] (throw (IllegalStateException. "secret detail")))
  (add-product! [_ _] (throw (ex-info "secret" {})))
  (delete-product! [_ _] (throw (ex-info "secret" {}))))

(deftest uncaught-exception-is-a-500-and-is-logged
  (let [logged (atom [])]
    (with-system [s {:store (->BrokenStore) :log-error #(swap! logged conj %)}]
      (doseq [[m p b] [["GET" "/products" nil]
                       ["GET" "/products/1" nil]
                       ["POST" "/products" tea]
                       ["DELETE" "/products/1" nil]]]
        (let [r (request s m p b)]
          (is (= 500 (:status r)) (str m " " p))
          (is (= {:error "internal error"} (:json r)))
          (is (not (re-find #"secret|Exception|ex-info|\bat \w+\." (:body r))) "no detail leaks")))
      (testing "the server still works"
        (is (= 200 (:status (request s "GET" "/health"))))))
    (is (= 4 (count @logged)))
    (is (every? #(instance? Throwable %) @logged))
    (is (= "secret database password" (ex-message (first @logged))))))

(deftest start-and-stop-twice
  (let [ports (doall
               (for [_ (range 2)]
                 (let [s (core/start! {})
                       p (:port s)]
                   (is (= 200 (:status (request s "GET" "/health"))))
                   (core/stop! s)
                   p)))]
    (is (= 2 (count ports)))
    (testing "after stop the port is closed"
      (is (thrown? java.io.IOException
                   (request {:port (first ports)} "GET" "/health"))))))

(deftest servers-are-independent
  (with-system [a {}]
    (with-system [b {}]
      (request a "POST" "/products" tea)
      (is (not= (:port a) (:port b)))
      (is (= 1 (count (:json (request a "GET" "/products")))))
      (is (= 0 (count (:json (request b "GET" "/products"))))))))

(deftest config-comes-from-a-map
  (let [store (d/memory-store)]
    (d/add-product! store {:name "Pre" :price 1 :tags []})
    (with-system [s {:store store :call-logging? true}]
      (is (= ["Pre"] (map :name (:json (request s "GET" "/products"))))))))

(deftest start-failures-do-not-hang
  (testing "the module throws"
    (let [e (try (with-redefs [core/module (fn [_ _] (fn [_] (throw (IllegalStateException. "bad module"))))]
                   (core/start! {:start-timeout-ms 5000}))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= "the Ktor server failed to start" (ex-message e)))
      (is (= "bad module" (ex-message (ex-cause e))))))
  (testing "the port is taken"
    (with-system [a {}]
      (let [e (try (core/start! {:port (:port a) :start-timeout-ms 3000})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= 200 (:status (request a "GET" "/health"))) "the first server is not hurt")))))

(deftest stop-twice-is-safe
  (let [s (core/start! {})]
    (core/stop! s)
    (core/stop! s)
    (is true))
  (testing "a system map with no server"
    (is (nil? (core/stop! {})))
    (is (nil? (core/stop! nil)))))

(deftest concurrent-requests
  (with-system [s {}]
    (let [rs (doall (pmap (fn [i] (request s "POST" "/products" (json/write-str {:name (str "p" i) :price i}))) (range 30)))]
      (is (every? #(= 201 (:status %)) rs))
      (is (= (range 1 31) (sort (map #(:id (:json %)) rs))))
      (is (= 30 (count (:json (request s "GET" "/products"))))))))
