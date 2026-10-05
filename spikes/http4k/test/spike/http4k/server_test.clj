(ns spike.http4k.server-test
  "A real server on a free port, called with java.net.http.HttpClient."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [spike.http4k.core :as core]
            [spike.http4k.domain :as domain]
            [spike.http4k.support :refer [log-collector]])
  (:import [java.net ConnectException URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse
            HttpResponse$BodyHandlers]))

(set! *warn-on-reflection* true)

(defn- client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.build)))

(defn- send-request
  "Returns {:status :body :location}. `method` is a string."
  [^HttpClient client port method path body]
  (let [publisher (if body
                    (HttpRequest$BodyPublishers/ofString ^String body)
                    (HttpRequest$BodyPublishers/noBody))
        request (-> (HttpRequest/newBuilder (URI/create (str "http://localhost:" port path)))
                    (.method ^String method publisher)
                    (.build))
        ^HttpResponse response (.send client request (HttpResponse$BodyHandlers/ofString))
        text ^String (.body response)]
    {:status (.statusCode response)
     :body (when (seq text) (json/read-str text :key-fn keyword))
     :location (.orElse (.firstValue (.headers response) "Location") nil)}))

(deftest real-server-roundtrip
  (let [[log-fn lines] (log-collector)
        system (core/start! {:log-fn log-fn})
        port (:port system)
        client (client)]
    (try
      (testing "port 0 gave a real port"
        (is (pos? port)))
      (testing "health"
        (is (= {:status 200 :body {:status "ok"}}
               (select-keys (send-request client port "GET" "/health" nil) [:status :body]))))
      (testing "create, read, list, delete over a socket"
        (let [created (send-request client port "POST" "/products"
                                    (json/write-str {:name "Tea" :price 350 :tags ["hot"]}))]
          (is (= 201 (:status created)))
          (is (= "/products/1" (:location created)))
          (is (= {:id 1 :name "Tea" :price 350 :tags ["hot"]} (:body created))))
        (is (= 200 (:status (send-request client port "GET" "/products/1" nil))))
        (is (= ["Tea"] (map :name (:body (send-request client port "GET" "/products?tag=hot" nil)))))
        (is (= 204 (:status (send-request client port "DELETE" "/products/1" nil))))
        (is (= 404 (:status (send-request client port "GET" "/products/1" nil)))))
      (testing "errors over a socket"
        (is (= 400 (:status (send-request client port "GET" "/products/abc" nil))))
        (is (= 400 (:status (send-request client port "POST" "/products" "{}"))))
        (is (= 404 (:status (send-request client port "GET" "/nothing" nil)))))
      (testing "the request filter logged the calls"
        (is (some #(re-find #"^GET /health -> 200" %) @lines)))
      (finally
        (core/stop! system)))
    (testing "after stop the port is closed"
      (is (thrown? ConnectException
                   (try
                     (send-request client port "GET" "/health" nil)
                     (catch java.io.IOException e
                       (throw (if (instance? ConnectException e) e (or (ex-cause e) e))))))))))

(deftest start-and-stop-twice
  (let [client (client)
        ports (doall
               (for [_ (range 2)]
                 (let [system (core/start! {:log-fn (constantly nil)})
                       port (:port system)]
                   (try
                     (is (= 200 (:status (send-request client port "GET" "/health" nil))))
                     (finally
                       (core/stop! system)))
                   port)))]
    (testing "each run is a fresh server with a fresh store"
      (is (= 2 (count ports))))
    (testing "stop! is safe to call again"
      (let [system (core/start! {:log-fn (constantly nil)})]
        (core/stop! system)
        (is (nil? (core/stop! system)))))))

(deftest two-servers-at-once-are-independent
  (let [client (client)
        a (core/start! {:log-fn (constantly nil)})
        b (core/start! {:store (domain/memory-store) :log-fn (constantly nil)})]
    (try
      (is (not= (:port a) (:port b)))
      (send-request client (:port a) "POST" "/products" (json/write-str {:name "OnlyInA" :price 1}))
      (is (= ["OnlyInA"] (map :name (:body (send-request client (:port a) "GET" "/products" nil)))))
      (is (= [] (:body (send-request client (:port b) "GET" "/products" nil))))
      (finally
        (core/stop! a)
        (core/stop! b)))))

(deftest a-store-error-over-a-socket-is-a-500
  (let [[log-fn lines] (log-collector)
        store (reify domain/ProductStore
                (all-products [_] (throw (RuntimeException. "db down")))
                (product [_ _] nil)
                (add-product! [_ _] nil)
                (delete-product! [_ _] false))
        system (core/start! {:store store :log-fn log-fn})]
    (try
      (let [resp (send-request (client) (:port system) "GET" "/products" nil)]
        (is (= 500 (:status resp)))
        (is (= {:error "internal error"} (:body resp)))
        (is (some #(re-find #"db down" %) @lines)))
      (finally
        (core/stop! system)))))
