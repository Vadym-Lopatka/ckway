(ns spike.coflow.orig-test
  "Tests that come from the original.

  1. flow_test.clj of clojure/core.async (branch dev-flow-test, the only flow test of the repository; kept in
     test/orig/flow_test_original.txt): ported to spike.coflow.flow. The original file has one real test
     (`test-proc-description-from-lift`, written twice) and an empty one (`test-step1`)."
  (:require [clojure.test :refer :all]
            [clojure.core.async.flow.spi :as fspi]
            [spike.coflow.flow :as flow]))

;; ---- 1. the ported test (the original text, with the port as `flow`) ------------------------------------------

(deftest test-step1)

;; Test that a proc's describe returns the proc fn's description
(deftest test-proc-description-from-lift
  (let [step-fn (flow/lift1->step identity)
        proc (flow/process step-fn)]
    (is (some? (step-fn)))
    (is (= (step-fn) (fspi/describe proc)))))
