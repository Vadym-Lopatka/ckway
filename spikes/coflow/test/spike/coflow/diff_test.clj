(ns spike.coflow.diff-test
  "Differential tests. The user code is in scenarios.clj and is the same text in every arm; only the namespace
  alias (and for :takeover the installed port) differs. The results of all arms must be equal."
  (:require [clojure.test :refer :all]
            [spike.coflow.arms :as arms]))

(defn- run [arm k]
  (try (arms/scenario arm k)
       (catch Throwable t {:threw (str (class t) ": " (ex-message t))})))

(deftest differential-scenarios
  (doseq [k (arms/scenario-names)]
    (testing (str "scenario " k)
      (let [oracle (run :orig k)]
        (is (not (:threw oracle)) (str "the oracle itself threw in " k ": " (:threw oracle)))
        (doseq [arm [:alias :takeover]]
          (is (= oracle (run arm k)) (str k " differs in arm " arm)))))))

(deftest oracle-sanity
  ;; the scenarios are not empty: spot checks on what the original itself does (so the equality means something)
  (let [lin (arms/scenario :orig :linear)]
    (is (= [4 6 8 10 12] (:report lin)))
    (is (nil? (:after-stop-error lin)) "the error chan is closed after stop")
    (is (nil? (:after-stop-report lin))))
  (let [bp (arms/scenario :orig :backpressure-fixed)]
    (is (= [0 1 2 3 4 5] (:seen bp)))
    (is (= 2 (:accepted-before-block bp)) "buffer 1 + one message in the step fn"))
  (is (= [0 8 9] (:seen (arms/scenario :orig :backpressure-sliding))))
  (is (= [0 1 2] (:seen (arms/scenario :orig :backpressure-dropping))))
  (let [tr (arms/scenario :orig :transitions)]
    (is (= [:clojure.core.async.flow/resume :clojure.core.async.flow/pause
            :clojure.core.async.flow/resume :clojure.core.async.flow/stop]
           (:log tr)))
    (is (= [2 4] (:r2 tr)) "the state of the transitions is threaded into transform"))
  (let [e (arms/scenario :orig :step-throws)]
    (is (= {:class "clojure.lang.ExceptionInfo" :msg "boom"}
           (select-keys (:clojure.core.async.flow/ex (:error e)) [:class :msg])))
    (is (= [[] [1]] [(:r1 e) (:r2 e)]) "the loop goes on after an error, with the old state")))
