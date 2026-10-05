(ns spike.ktor.ktor-tools-test
  "The Catalog API called with Ktor's own tools: `testApplication` and the Ktor client (CIO engine)."
  (:require [ckway.core :as kt]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [spike.ktor.core :as core]
            [spike.ktor.domain :as d]
            [spike.ktor.http-helper :refer [with-system]])
  (:import (io.ktor.client HttpClient)
           (io.ktor.server.testing ApplicationTestBuilder)))

(set! *warn-on-reflection* true)

;; Kotlin: import io.ktor.server.testing.*; import io.ktor.client.request.*; ...
(kt/require '[io.ktor.server.testing :as tst]
            '[io.ktor.client :as cl]
            '[io.ktor.client.engine.cio :as ccio]
            '[io.ktor.client.request :as creq]
            '[io.ktor.client.statement :as cst]
            '[io.ktor.http :as http])

(defn- result
  "[status-code body] of a Ktor client response."
  [response]
  ;; Kotlin: response.status.value, response.bodyAsText()
  [(http/value (cst/status response)) (cst/.bodyAsText response)])

(defn- url [system path]
  (str "http://127.0.0.1:" (:port system) path))

(deftest test-application
  (let [seen (atom [])]
    ;; Kotlin: testApplication { application { module() }; client.get("/health") }
    (tst/testApplication
     (fn [^ApplicationTestBuilder b]
       (tst/.application b (core/module core/default-config (d/memory-store)))
       (let [^HttpClient client (tst/client b)]
         ;; Kotlin: client.get("/health")
         (swap! seen conj (result (creq/.get client "/health")))
         (swap! seen conj (result (creq/.get client "/products/7")))
         (swap! seen conj (result (creq/.get client "/nope"))))))
    (is (= [[200 "{\"status\":\"ok\"}"]
            [404 "{\"error\":\"not found\"}"]
            [404 "{\"error\":\"not found\"}"]]
           @seen))))

(deftest ktor-client
  (with-system [s {}]
    ;; Kotlin: val client = HttpClient(CIO)
    (let [^HttpClient client (cl/HttpClient ccio/CIO)]
      (try
        ;; Kotlin: client.get(url).bodyAsText()
        (is (= [200 "{\"status\":\"ok\"}"] (result (creq/.get client ^String (url s "/health")))))
        ;; Kotlin: client.post(url) { setBody(json) }
        (let [[code body] (result (creq/.post client ^String (url s "/products")
                                              (fn [b]
                                                ;; Kotlin: header("Content-Type", "application/json"); setBody(text)
                                                (creq/.header b "Content-Type" "application/json")
                                                (creq/.setBody b "{\"name\":\"Tea\",\"price\":350}" :<> String))))]
          (is (= 201 code))
          (is (= {"id" 1 "name" "Tea" "price" 350 "tags" []} (json/read-str body))))
        ;; Kotlin: client.get(url) { parameter("tag", "x") }
        (is (= [200 "[]"] (result (creq/.get client ^String (url s "/products")
                                             (fn [b] (creq/.parameter b "tag" "x"))))))
        (is (= 204 (first (result (creq/.delete client ^String (url s "/products/1"))))))
        (finally
          ;; Kotlin: client.close()
          (cl/.close client))))))
