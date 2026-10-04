(ns kt.nocoro-test
  "S-E: suspend calls and adapters need only kotlin-stdlib. Run in a JVM whose class path has NO
  kotlinx-coroutines (bin/test does: `clojure -M:test -n kt.nocoro-test`). The fixtures are
  test-fixtures/stdlib/Plain.kt (suspendCoroutine, startCoroutine)."
  (:require [clojure.test :refer [deftest is testing]]
            [kt.core :as kt]
            [kt.rt :as rt]))

(kt/require '[fxs :as p])

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

(deftest the-class-path-has-no-kotlinx-coroutines
  (is (thrown #(Class/forName "kotlinx.coroutines.Job")))
  (is (nil? (find-ns 'kt.co.kx)) "the kotlinx part of kt.co is not loaded"))

(deftest suspend-calls-without-kotlinx
  (testing "a call that really suspends (another thread resumes it), a call that does not, a failure"
    (is (= 2 (p/plainAsync 1)))
    (is (= 2 (rt/call-dyn #'p/plainAsync [1] {})))
    (is (= 2 (p/plainSync 1)))
    (is (nil? (p/plainUnit)))
    (let [e (thrown #(p/plainFail))]
      (is (instance? IllegalStateException e))
      (is (= "plainboom" (ex-message e)))))
  (testing "the same on the dynamic path"
    (is (= "plainboom" (ex-message (thrown #(rt/call-dyn #'p/plainFail [] {})))))))

(deftest adapters-without-kotlinx
  (testing "a Clojure function as a suspend function type; the body runs on a virtual thread"
    (is (= 3 (p/runPlain (fn [x] (p/plainAsync (p/plainAsync x))))))
    (is (= 3 (rt/call-dyn #'p/runPlain [(fn [x] (p/plainAsync (p/plainAsync x)))] {})))
    (is (= 1 (p/runPlain (fn [x] (if (.isVirtual (Thread/currentThread)) 1 0)))) "1 means: the body ran on a virtual thread")
    (is (= "plainboom" (ex-message (thrown #(p/runPlain (fn [x] (p/plainFail))))))))
  (testing "a fun interface with a suspend method"
    (is (= 7 (p/runPlainFi (fn [x] (+ 5 (p/plainSync x))))))
    (is (= 5 (p/usePBefore (fn [x] (p/plainAsync x)) 4)))
    (is (= 5 (rt/call-dyn #'p/usePBefore [(fn [x] (p/plainAsync x)) 4] {}))))
  (testing "a Kotlin suspend function value is a Clojure function"
    (let [h (p/plainFn)]
      (is (= 8 (h 7)))
      (is (= 3 (p/runPlain (fn [x] (h (inc x))))))))
  (testing "an exception in the body reaches Kotlin"
    (is (instance? java.io.IOException (thrown #(p/runPlain (fn [x] (throw (java.io.IOException. "io"))))))))
  (testing "still no kotlinx part loaded: no Job in a stdlib-only context"
    (is (nil? (find-ns 'kt.co.kx)))))
