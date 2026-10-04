(ns ckway.coro-next-test
  "kotlinx-coroutines 1.11.0. Its `runBlocking` is public in two parts of the multi-file facade `BuildersKt`
  (`BuildersKt__BuildersKt`, and `runBlockingK` of `BuildersKt__Builders_concurrentKt`), so the index saw the function
  twice and every call was \"ambiguous\" with two identical candidates. Run by `bin/test` in a JVM with the alias
  `:coro-next` (not in the main run: it needs that version; `coro-1-11-0-is-on-the-class-path` checks it)."
  (:require [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.rt :as rt]))

(kt/require '[kotlinx.coroutines :as co] '[fx :as f])

(deftest coro-1-11-0-is-on-the-class-path
  (is (re-find #"kotlinx-coroutines-core-jvm-1\.11\.0"
               (str (.getLocation (.getCodeSource (.getProtectionDomain kotlinx.coroutines.Job)))))))

(deftest run-blocking-is-one-declaration
  (is (= 1 (count (:kt/decls (meta #'co/runBlocking)))))
  (is (= 1 (count (filter #(= "runBlocking" (:name %)) (:kt/decls (meta #'co/runBlocking)))))))

(deftest run-blocking-forms-of-the-coroutine-tests
  (testing "static path"
    (is (= 42 (co/runBlocking :block (fn [scope] 42))))
    (is (= 2 (co/runBlocking :block (fn [scope] (f/step 1)))))
    (is (= 11 (co/runBlocking :block (fn [scope] (co/.await (co/.async scope :block (fn [s] (f/step 10)))))))))
  (testing "dynamic path"
    (is (= 42 (rt/call-dyn #'co/runBlocking [] {"block" (fn [scope] 42)})))
    (is (= 2 (rt/call-dyn #'co/runBlocking [] {"block" (fn [scope] (f/step 1))})))))
