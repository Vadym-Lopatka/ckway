(ns spike.coflow.invariants-test
  "One test per invariant of clojure.core.async.flow that the port must keep (README, section Invariants).
  The test names carry the number. Every test runs the user code of scenarios.clj / scenarios_inv.clj in all arms
  (:orig = the original, :alias = spike.coflow.flow, :takeover = the port behind clojure.core.async.flow).
  What the original guarantees is asserted for each arm; where the original leaves something open (the order of two
  producers, timing) only the guaranteed part is asserted."
  (:require [clojure.test :refer :all]
            [spike.coflow.arms :as arms]))

(defn- each-arm [f]
  (into {} (map (fn [arm] [arm (try (f arm) (catch Throwable t {:threw (str (class t) ": " (ex-message t))}))])
                arms/arms)))

(defn- inv [arm fsym & args] (apply arms/call arm fsym args))

(defn- same-in-all-arms [results what]
  (is (apply = (vals results)) (str what " is not the same in all arms: " (pr-str results))))

(def S :clojure.core.async.flow/stop)
(def R :clojure.core.async.flow/resume)
(def P :clojure.core.async.flow/pause)

;; --- 1 (a) -----------------------------------------------------------------------------------------------------

(deftest invariant-1-step-fn-is-never-concurrent-and-state-is-threaded
  (doseq [w [:mixed :io :compute]]
    (testing (str "workload " w)
      (doseq [[arm r] (each-arm #(inv % 'inv-a-no-concurrent-step w 4 250))]
        (is (= {:total 1000 :overlaps 0 :calls 1000 :final-state 1000 :error nil} r) (str arm)))))
  (testing "stress: 8 producers, 600 messages each"
    (doseq [[arm r] (each-arm #(inv % 'inv-a-no-concurrent-step :mixed 8 600))]
      (is (= {:total 4800 :overlaps 0 :calls 4800 :final-state 4800 :error nil} r) (str arm)))))

;; --- 2 (b) -----------------------------------------------------------------------------------------------------

(deftest invariant-2-order-none-lost-none-duplicated
  (doseq [toggle? [false true]]
    (testing (str "toggle pause/resume: " toggle?)
      (doseq [[arm r] (each-arm #(inv % 'inv-b-order 4 300 toggle?))]
        (is (= {:total 1200 :count 1200 :distinct 1200 :per-producer-in-order true :error nil} r) (str arm)))))
  (testing "stress: 6 producers x 1000, random pause/resume"
    (doseq [[arm r] (each-arm #(inv % 'inv-b-order 6 1000 true))]
      (is (= {:total 6000 :count 6000 :distinct 6000 :per-producer-in-order true :error nil} r) (str arm)))))

;; --- 3 (c) -----------------------------------------------------------------------------------------------------

(deftest invariant-3-control-has-priority-and-a-paused-proc-takes-no-input
  (doseq [[arm r] (each-arm #(inv % 'inv-c-control-priority))]
    ;; the first ping may be answered with the old status: the original's pong closure holds the status of the start of
    ;; the loop turn, and the pause can be taken inside the write of an output. The later ones are exact.
    (is (= [:paused :paused :paused :running] (rest (:status r))) (str arm))
    (is (:count-stable r) (str arm " a paused proc takes no input, also when input arrives"))
    (is (:control-first r) (str arm " the pause overtook the queued data"))
    (is (= [310 310] (:all-processed r)) (str arm " after resume everything is processed once"))))

;; --- 4 (d) -----------------------------------------------------------------------------------------------------

(deftest invariant-4-transitions-in-legal-order-stop-once
  (doseq [[arm r] (each-arm #(inv % 'inv-d-transitions))]
    (is (= [R P R P R S] (:log r)) (str arm ": a transition only on a change of status"))
    (is (= 1 (:stop-count r)) (str arm))
    (is (= [true nil] (:stops r)) (str arm ": the second stop does nothing"))
    (is (= [S] (:never-resumed r)) (str arm ": stop of a proc that never ran"))))

;; --- 5 (e) -----------------------------------------------------------------------------------------------------

(deftest invariant-5-init-once-per-start-describe-is-pure
  (doseq [[arm r] (each-arm #(arms/scenario % :init-and-describe-calls))]
    (is (= 1 (:describe-after-process r)) (str arm ": process calls describe once"))
    (is (= 1 (:describe-after-create r)) (str arm ": create-flow calls no step fn describe again"))
    (is (= {:describe 1 :init 1 :init-args [{:x 42 :clojure.core.async.flow/pid :p}]} (:after-start r)) (str arm))
    (is (= [R S] (:transitions r)) (str arm))
    (is (= 2 (:init-after-restart r)) (str arm ": a second start calls init again"))))

;; --- 6 (f) -----------------------------------------------------------------------------------------------------

(deftest invariant-6-outputs-nil-and-malformed-returns
  (let [rs (each-arm #(arms/scenario % :bad-outputs))
        o (:orig rs)
        by-m (fn [r] (into {} (map (juxt :m identity) r)))]
    (same-in-all-arms rs "bad-outputs")
    (doseq [arm [:orig :alias :takeover]
            :let [r (by-m (rs arm))
                  ex-class #(get-in r [% :err :clojure.core.async.flow/ex :class])
                  ex-msg #(get-in r [% :err :clojure.core.async.flow/ex :msg])]]
      (testing (str arm)
        (is (= "clojure.lang.ExceptionInfo" (ex-class :unknown-out)))
        (is (= "can't resolve channel with io-id" (ex-msg :unknown-out)))
        (is (= "can't resolve channel with io-id" (ex-msg :unconnected)) "a declared out that is not connected")
        (is (= "java.lang.AssertionError" (ex-class :nil-msg)) "alts!! asserts before it puts")
        (is (= "Assert failed: can't put nil on channel\n(some? (port 1))" (ex-msg :nil-msg)))
        (is (= :timeout (:err (r :empty))) "empty output is fine")
        (is (= :timeout (:err (r :nil-out))))
        (is (= :timeout (:err (r :nil-msgs))))
        (is (= [1 2] (take 2 (:reports (r :partial)))) "messages before the nil one are sent")
        (is (= [7 8] (take 2 (:reports (r :list-of-msgs)))))))))

;; --- 7 (g) -----------------------------------------------------------------------------------------------------

(deftest invariant-7-exception-in-step-fn-goes-to-error-chan-and-flow-continues
  (doseq [k [:step-throws :transition-throws :compute-workload :chan-opts-xform :create-flow-errors]]
    (let [rs (each-arm #(arms/scenario % k))]
      (same-in-all-arms rs k)))
  (let [e (:error (arms/scenario :alias :step-throws))]
    (is (= #{:clojure.core.async.flow/pid :clojure.core.async.flow/status :clojure.core.async.flow/state
             :clojure.core.async.flow/count :clojure.core.async.flow/cid :clojure.core.async.flow/msg
             :clojure.core.async.flow/op :clojure.core.async.flow/ex}
           (set (keys e))))
    (is (= :step (:clojure.core.async.flow/op e)))
    (is (= :p (:clojure.core.async.flow/pid e)))
    (is (= :boom (:clojure.core.async.flow/msg e)))
    (is (= 2 (:clojure.core.async.flow/count e)) "count of the failed message: (inc count)"))
  (let [e (:error (arms/scenario :alias :transition-throws))]
    (is (= #{:clojure.core.async.flow/pid :clojure.core.async.flow/status :clojure.core.async.flow/state
             :clojure.core.async.flow/count :clojure.core.async.flow/ex}
           (set (keys e))) "an error of a transition has no :cid, :msg and :op")))

;; --- 8 (h) -----------------------------------------------------------------------------------------------------

(deftest invariant-8-back-pressure-fixed-sliding-dropping
  (doseq [k [:backpressure-fixed :backpressure-sliding :backpressure-dropping]]
    (same-in-all-arms (each-arm #(arms/scenario % k)) k))
  (testing "values (the original's own)"
    (doseq [arm arms/arms]
      (is (= 2 (:accepted-before-block (arms/scenario arm :backpressure-fixed))) (str arm))
      (is (= [0 8 9] (:seen (arms/scenario arm :backpressure-sliding))) (str arm " sliding: the newest are kept"))
      (is (= [0 1 2] (:seen (arms/scenario arm :backpressure-dropping))) (str arm " dropping: the oldest are kept")))))

(deftest invariant-8-stress-slow-consumer-blocks-the-producer
  (doseq [cap [1 5 20]]
    (doseq [[arm r] (each-arm #(inv % 'inv-h-backpressure-stress cap 400))]
      (is (= 400 (:consumed r)) (str arm " cap " cap))
      (is (:max-gap-ok r) (str arm " cap " cap " gap " (:max-gap r))))))

;; --- 9 (i) -----------------------------------------------------------------------------------------------------

(deftest invariant-9-inject-and-command-proc
  (let [rs (each-arm #(arms/scenario % :inject-cases))]
    (same-in-all-arms rs "inject-cases")
    (doseq [[arm r] rs]
      (is (= {:class "clojure.lang.ExceptionInfo" :msg "can't resolve channel with io-id"}
             (select-keys (:unknown-pid r) [:class :msg])) (str arm))
      (is (= {:io-id [:zzz :in]} (:data (:unknown-pid r))) (str arm))
      (is (= {:io-id [:a :zzz]} (:data (:unknown-id r))) (str arm))
      (is (= "java.util.concurrent.ExecutionException" (:class (:lonely-out r))) (str arm " not connected out: the future fails"))
      (is (:future? r) (str arm))))
  (testing "command-proc: named by the Graph protocol, implemented by nobody in this version"
    (let [rs (each-arm #(inv % 'inv-i-command-proc))]
      (same-in-all-arms rs "command-proc")
      (is (= {:result "java.lang.AbstractMethodError" :public-fn false} (:orig rs))))))

;; --- 10 (j) ----------------------------------------------------------------------------------------------------

(deftest invariant-10-after-stop-nothing-runs-and-out-of-order-calls
  (let [rs (each-arm #(inv % 'inv-j-after-stop))]
    (doseq [[arm r] rs]
      (is (= {:stable-after-stop true :less-than-all true :closed [nil nil]} r) (str arm))))
  (testing "stop twice, start again, pause before start, close of the channels"
    (same-in-all-arms (each-arm #(arms/scenario % :stop-start-cycle)) :stop-start-cycle)
    (same-in-all-arms (each-arm #(arms/scenario % :inject-before-start)) :inject-before-start)
    (let [r (arms/scenario :orig :stop-start-cycle)]
      (is (= [true true] (:same-chans r)))
      (is (true? (:already r)))
      (is (= [true nil true] (:stop r)))
      (is (= [nil nil] (:closed r)))
      (is (false? (:put-closed r))))
    (let [r (arms/scenario :orig :inject-before-start)]
      (is (= "flow not running" (:msg (:pause r))))
      (is (nil? (:stop r))))))

;; --- 11 (k) ----------------------------------------------------------------------------------------------------

(deftest invariant-11-fan-out-fan-in-routing
  (let [rs (each-arm #(inv % 'inv-k-routing))]
    (same-in-all-arms rs "inv-k-routing")
    (doseq [[arm r] rs]
      (is (= [1 2 3] (:a r)) (str arm " every connection gets every message, in order"))
      (is (= [1 2 3] (:b r)) (str arm))
      (is (= [:x :x :y :y] (:c r)) (str arm " fan-in: both outs arrive on the one in"))))
  (doseq [k [:fan-out-fan-in :many-to-one-in :mult-semantics :self-feedback]]
    (same-in-all-arms (each-arm #(arms/scenario % k)) k)))
