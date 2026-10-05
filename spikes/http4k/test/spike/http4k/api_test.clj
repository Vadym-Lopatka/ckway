(ns spike.http4k.api-test
  "The http4k way: call the handler as a function, in memory. No socket, no port."
  (:require [ckway.core :as kt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spike.http4k.api :as api]
            [spike.http4k.domain :as domain]
            [spike.http4k.support :refer [handle json-body log-collector]]))

(set! *warn-on-reflection* true)

(kt/require '[org.http4k.core :as h])

(def GET h/Method.GET)
(def POST h/Method.POST)
(def DELETE h/Method.DELETE)
(def PUT h/Method.PUT)

(defn- fresh []
  (let [[log-fn lines] (log-collector)]
    {:handler (api/make-handler (domain/memory-store) log-fn)
     :lines lines}))

(defn- post-product [handler data]
  (handle handler POST "/products" (json-body data)))

(deftest health
  (let [{:keys [handler]} (fresh)
        resp (handle handler GET "/health")]
    (is (= 200 (:status resp)))
    (is (= {:status "ok"} (:body resp)))
    (is (str/starts-with? (:content-type resp) "application/json"))))

(deftest list-products
  (let [{:keys [handler]} (fresh)]
    (testing "empty at the start"
      (is (= {:status 200 :body []} (select-keys (handle handler GET "/products") [:status :body]))))
    (post-product handler {:name "Tea" :price 350 :tags ["drink" "hot"]})
    (post-product handler {:name "Cake" :price 500 :tags ["food"]})
    (post-product handler {:name "Water" :price 100})
    (testing "all"
      (is (= ["Tea" "Cake" "Water"] (map :name (:body (handle handler GET "/products"))))))
    (testing "tag filter"
      (is (= ["Tea"] (map :name (:body (handle handler GET "/products?tag=hot")))))
      (is (= ["Cake"] (map :name (:body (handle handler GET "/products?tag=food"))))))
    (testing "a tag that nobody has"
      (is (= [] (:body (handle handler GET "/products?tag=nothing")))))))

(deftest get-product
  (let [{:keys [handler]} (fresh)]
    (post-product handler {:name "Tea" :price 350 :tags ["hot"]})
    (testing "found"
      (let [resp (handle handler GET "/products/1")]
        (is (= 200 (:status resp)))
        (is (= {:id 1 :name "Tea" :price 350 :tags ["hot"]} (:body resp)))))
    (testing "not found"
      (let [resp (handle handler GET "/products/99")]
        (is (= 404 (:status resp)))
        (is (= {:error "not found"} (:body resp)))))
    (testing "an id that is not a number is a 400 (the Path.int() lens fails)"
      (let [resp (handle handler GET "/products/abc")]
        (is (= 400 (:status resp)))
        (is (string? (get-in resp [:body :error])))))
    (testing "an id that is too big for an Int is a 400 too"
      (is (= 400 (:status (handle handler GET "/products/99999999999")))))))

(deftest create-product
  (let [{:keys [handler]} (fresh)]
    (testing "valid"
      (let [resp (post-product handler {:name "Tea" :price 350 :tags ["hot"]})]
        (is (= 201 (:status resp)))
        (is (= "/products/1" (:location resp)))
        (is (= {:id 1 :name "Tea" :price 350 :tags ["hot"]} (:body resp)))))
    (testing "tags are optional and default to a list"
      (let [resp (post-product handler {:name "Cake" :price 0})]
        (is (= 201 (:status resp)))
        (is (= [] (get-in resp [:body :tags])))
        (is (= "/products/2" (:location resp)))))
    (testing "the product can be read again from the Location"
      (is (= 200 (:status (handle handler GET "/products/2")))))
    (testing "invalid data is a 400 with an error text, and nothing is stored"
      (doseq [[data message] [[{:price 1} "name must be a non-empty string"]
                              [{:name "" :price 1} "name must be a non-empty string"]
                              [{:name "x"} "price must be a non-negative integer (cents)"]
                              [{:name "x" :price -1} "price must be a non-negative integer (cents)"]
                              [{:name "x" :price 1.5} "price must be a non-negative integer (cents)"]
                              [{:name "x" :price "1"} "price must be a non-negative integer (cents)"]
                              [{:name "x" :price 1 :tags "hot"} "tags must be a list of strings"]
                              [{:name "x" :price 1 :tags [1 2]} "tags must be a list of strings"]]]
        (let [resp (post-product handler data)]
          (is (= 400 (:status resp)) (pr-str data))
          (is (= {:error message} (:body resp)) (pr-str data))))
      (is (= 2 (count (:body (handle handler GET "/products"))))))
    (testing "a body that is not JSON, empty, or not an object is a 400"
      (doseq [body ["not json" "" "[1,2]" "42" "null"]]
        (let [resp (handle handler POST "/products" body)]
          (is (= 400 (:status resp)) (pr-str body))
          (is (string? (get-in resp [:body :error])) (pr-str body)))))))

(deftest delete-product
  (let [{:keys [handler]} (fresh)]
    (post-product handler {:name "Tea" :price 350})
    (testing "deleted"
      (let [resp (handle handler DELETE "/products/1")]
        (is (= 204 (:status resp)))
        (is (= "" (:body-text resp)))))
    (testing "gone"
      (is (= 404 (:status (handle handler GET "/products/1")))))
    (testing "deleted twice is a 404"
      (let [resp (handle handler DELETE "/products/1")]
        (is (= 404 (:status resp)))
        (is (= {:error "not found"} (:body resp)))))
    (testing "a bad id is a 400"
      (is (= 400 (:status (handle handler DELETE "/products/abc")))))))

(deftest unknown-routes
  (let [{:keys [handler]} (fresh)]
    (testing "unknown path"
      (let [resp (handle handler GET "/nothing/here")]
        (is (= 404 (:status resp)))
        (is (= {:error "not found"} (:body resp)))))
    (testing "a known path with a method that it does not have is a 405 (http4k's own choice)"
      (let [resp (handle handler PUT "/products")]
        (is (= 405 (:status resp)))
        (is (= {:error "method not allowed"} (:body resp)))))))

;; ## Filters

(deftest request-logging-filter
  (let [{:keys [handler lines]} (fresh)]
    (handle handler GET "/health")
    (handle handler GET "/products/99")
    (handle handler GET "/nothing")
    (is (= 3 (count @lines)))
    (is (re-find #"^GET /health -> 200 \(\d+ ms\)$" (first @lines)))
    (is (re-find #"^GET /products/99 -> 404 " (second @lines)))
    (is (re-find #"^GET /nothing -> 404 " (nth @lines 2)))))

(defn- exploding-store
  "A store that fails in every call, as a broken database would."
  []
  (reify domain/ProductStore
    (all-products [_] (throw (IllegalStateException. "boom: secret database detail")))
    (product [_ _] (throw (IllegalStateException. "boom: secret database detail")))
    (add-product! [_ _] (throw (IllegalStateException. "boom: secret database detail")))
    (delete-product! [_ _] (throw (IllegalStateException. "boom: secret database detail")))))

(deftest error-filter
  (let [[log-fn lines] (log-collector)
        handler (api/make-handler (exploding-store) log-fn)]
    (doseq [[method uri body] [[GET "/products" nil]
                               [GET "/products/1" nil]
                               [POST "/products" (json-body {:name "x" :price 1})]
                               [DELETE "/products/1" nil]]]
      (reset! lines [])
      (let [resp (handle handler method uri body)]
        (testing (str method " " uri)
          (is (= 500 (:status resp)))
          (is (= {:error "internal error"} (:body resp)))
          (testing "the client sees no detail and no stack trace"
            (is (not (str/includes? (:body-text resp) "boom")))
            (is (not (str/includes? (:body-text resp) "Exception")))
            (is (not (str/includes? (:body-text resp) "\tat "))))
          (testing "the log has the error with the stack trace, and the request line"
            (is (some #(and (str/includes? % "IllegalStateException") (str/includes? % "boom")
                            (str/includes? % "\tat "))
                      @lines))
            (is (some #(str/includes? % "-> 500") @lines))))))
    (testing "the health route does not use the store, so it still works"
      (is (= 200 (:status (handle handler GET "/health")))))))
