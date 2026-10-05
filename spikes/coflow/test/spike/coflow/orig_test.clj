(ns spike.coflow.orig-test
  "Tests that come from the original.

  1. flow_test.clj of clojure/core.async (branch dev-flow-test, the only flow test of the repository; kept in
     test/orig/flow_test_original.txt): ported to spike.coflow.flow. The original file has one real test
     (`test-proc-description-from-lift`, written twice) and an empty one (`test-step1`).
  2. examples/ex-flow.clj of the checkout (test/orig/ex-flow.clj, UNCHANGED): loaded and run against the original and
     against the port installed behind clojure.core.async.flow (takeover). Its flow has a source that reads a user
     channel (::flow/in-ports), a dedupe proc with state, a filter and a sink.
  3. examples/my-flow.clj (test/orig/my-flow.clj, unchanged): it does not compile with the original either
     (`roller-thread` and `ddupe` are not defined in it). The test says that the port fails the same way."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.core.async.flow :as orig-flow]
            [clojure.core.async.flow.spi :as fspi]
            [spike.coflow.flow :as flow]
            [spike.coflow.takeover :as takeover]))

;; ---- 1. the ported test (the original text, with the port as `flow`) ------------------------------------------

(deftest test-step1)

;; Test that a proc's describe returns the proc fn's description
(deftest test-proc-description-from-lift
  (let [step-fn (flow/lift1->step identity)
        proc (flow/process step-fn)]
    (is (some? (step-fn)))
    (is (= (step-fn) (fspi/describe proc)))))

;; ---- 2. ex-flow.clj --------------------------------------------------------------------------------------------

(defn- run-ex-flow
  "Loads the example (the file as it is) and runs its flow with the `flow` functions that clojure.core.async.flow has
  at this moment (the original, or the port installed behind it)."
  [source]
  (remove-ns 'ex-flow)
  (binding [*out* (java.io.StringWriter.)] ;; the example prints; the output of the proc threads is not captured
    (load-string source))
  (let [gdef @(ns-resolve 'ex-flow 'gdef)
        g (orig-flow/create-flow gdef)
        {:keys [report-chan error-chan]} (orig-flow/start g)
        _ (orig-flow/resume g)
        ;; as in the comment block of the example
        _ @(orig-flow/inject g [:craps-finder :in] [[1 2] [2 1] [2 1] [6 6] [6 6] [4 3] [1 1]])
        ;; 6 of these 7 are craps (sum 2, 3 or 12)
        deadline (+ (System/currentTimeMillis) 10000)
        sink-count (loop []
                     (let [c (:clojure.core.async.flow/count (orig-flow/ping-proc g :prn-sink))]
                       (if (or (>= c 6) (> (System/currentTimeMillis) deadline))
                         c
                         (do (deref (promise) 20 nil) (recur)))))
        dedupe-state (:clojure.core.async.flow/state (orig-flow/ping-proc g :dedupe))
        _ (orig-flow/pause g)
        paused (set (map :clojure.core.async.flow/status (vals (orig-flow/ping g))))
        _ (orig-flow/pause-proc g :prn-sink)
        _ (orig-flow/resume-proc g :prn-sink)
        _ (orig-flow/resume g)
        err (let [t (a/timeout 100) [v p] (a/alts!! [error-chan t])] (if (= p t) :none v))
        datafied (keys (clojure.datafy/datafy g))
        stop (orig-flow/stop g)]
    {:sink-count-at-least-6 (>= sink-count 6)
     :dedupe-state-keys (set (keys dedupe-state))
     :paused paused
     :errors err
     :datafy-keys (set datafied)
     :stop stop}))

(def ex-flow-source (slurp "test/orig/ex-flow.clj"))

;; ex-flow.clj of the checkout was written for an older `process`, which took a map of fns. The released
;; flow (1.10.874-alpha3) takes a step fn only, so line 65 of the file fails in the ORIGINAL too. For the run we change that one
;; form: the map goes through `flow/map->step`. Nothing else is changed.
(def ex-flow-patched
  (-> ex-flow-source
      (str/replace "{:proc (flow/process\n               {:describe" "{:proc (flow/process (flow/map->step\n               {:describe")
      (str/replace "(prn v))})}}" "(prn v))}))}}")))

(defn- root-message [^Throwable t] (loop [t t] (if-let [c (ex-cause t)] (recur c) (ex-message t))))

(deftest ex-flow-example-as-it-is-fails-the-same-way
  (let [load (fn [] (remove-ns 'ex-flow) (try (load-string ex-flow-source) :loaded (catch Throwable t (root-message t))))
        on-orig (load)
        on-port (takeover/with-port (load))]
    (is (= "Wrong number of args (0) passed to: clojure.lang.PersistentArrayMap" on-orig))
    (is (= on-orig on-port))))

(deftest ex-flow-example-runs-on-the-original-and-on-the-port
  (is (not= ex-flow-source ex-flow-patched))
  (let [on-orig (run-ex-flow ex-flow-patched)
        on-port (takeover/with-port (run-ex-flow ex-flow-patched))]
    (is (= {:sink-count-at-least-6 true :dedupe-state-keys #{:last} :paused #{:paused} :errors :none
            :datafy-keys #{:procs :conns :execs :chans} :stop true}
           on-orig))
    (is (= on-orig on-port))))

;; ---- 3. my-flow.clj --------------------------------------------------------------------------------------------

(defn- load-my-flow []
  (remove-ns 'my-flow)
  (try (load-file "test/orig/my-flow.clj") :loaded
       (catch Throwable t (loop [t t] (if-let [c (ex-cause t)] (recur c) (ex-message t))))))

(deftest my-flow-example-fails-the-same-way
  (let [on-orig (load-my-flow)
        on-port (takeover/with-port (load-my-flow))]
    (is (string? on-orig))
    (is (re-find #"Unable to resolve symbol: roller-thread" on-orig))
    (is (= on-orig on-port))))
