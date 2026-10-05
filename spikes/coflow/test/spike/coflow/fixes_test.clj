(ns spike.coflow.fixes-test
  "The user-visible differences that the review found, one test group per item (see README, review items 1 to 6).
  The scenarios are in scenarios_ext.clj (user code, run in the three arms). Here: the concrete values that the original
  gives (so that equality is not vacuous) and the checks that only the port has (races, CPU, the reaper)."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [ckway.core :as kt]
            [spike.coflow.arms :as arms]
            [spike.coflow.chan :as cc]
            [spike.coflow.flow :as flow]
            [spike.coflow.ext :as ext])
  (:import [java.lang.management ManagementFactory]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co])

(defn- dropin?
  "True in the JVM where `clojure.core.async.flow` is the drop-in shim: the :orig arm is then the port itself."
  []
  (boolean (some-> (clojure.java.io/resource "clojure/core/async/flow.clj") str (clojure.string/includes? "/dropin/"))))

(defn- each-arm [f]
  (into {} (map (fn [arm] [arm (try (f arm) (catch Throwable t {:threw (str (class t) ": " (ex-message t))}))])
                arms/arms)))

(defn- all-arms-give [expected k & args]
  (doseq [[arm r] (each-arm #(apply arms/call % k args))]
    (is (= expected r) (str k " in arm " arm))))

(defn- scenario-in-all-arms [k expected]
  (doseq [[arm r] (each-arm #(arms/scenario % k))]
    (is (= expected r) (str k " in arm " arm))))

;; --- review item 1: stop ----------------------------------------------------------------------------------------

(deftest item-1-stop-returns-at-once
  (scenario-in-all-arms :stop-returns-at-once {:stop-ret true :stop-fast true :report-closed nil :error-closed nil}))

(deftest item-1-stop-from-a-step-fn
  (scenario-in-all-arms :stop-from-a-step-fn
                        {:result [true true]
                         :log [:clojure.core.async.flow/resume :clojure.core.async.flow/stop]
                         :pause-after {:class "java.lang.Exception" :msg "flow not running" :data nil :cause nil}
                         :channels-closed [nil nil]}))

(deftest item-1-start-after-stop-while-the-old-run-is-stuck
  (scenario-in-all-arms :start-after-stop-while-the-old-run-is-stuck
                        {:stop-ret true :stop-fast true :new-run-report 2 :new-run-status :running :new-chans-open? true}))

(deftest item-1-await-stopped-joins-the-reaper
  (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/lift1->step inc))}} :conns []})]
    (is (true? (ext/await-stopped g 100)) "never stopped: nothing to wait for")
    (flow/start g) (flow/resume g)
    (let [scope (ext/running-scope g)
          job (co/job (.getCoroutineContext ^kotlinx.coroutines.CoroutineScope scope))]
      (is (true? (flow/stop g)))
      (is (true? (ext/await-stopped g 5000)))
      (is (co/isCompleted job) "after the reaper: no coroutine of the run is active"))))

;; --- review item 2: user channels in in-ports / out-ports ------------------------------------------------------------

(deftest item-2-in-ports-no-read-ahead
  (scenario-in-all-arms :inports-never-started {:left [0 1 2 3 4]})
  (scenario-in-all-arms :inports-paused {:left [0 1 2 3 4]})
  (scenario-in-all-arms :inports-filtered {:left [0 1 2 3 4]})
  (scenario-in-all-arms :inports-stopped {:left [1 2 3 4]})
  (scenario-in-all-arms :inports-all-read {:got [0 1 2 3 4] :left []})
  (scenario-in-all-arms :inports-closed-input {:r1 [:src 1] :on-close [:src nil] :nothing-more :timeout}))

(deftest item-2-out-ports-back-pressure-adds-no-buffer
  (scenario-in-all-arms :outports-backpressure-1 {:accepted-before-block 3 :first :zero :buffered-after-first 1 :in-order true})
  (scenario-in-all-arms :outports-backpressure-3 {:accepted-before-block 5 :first :zero :buffered-after-first 3 :in-order true})
  (scenario-in-all-arms :outports-control-priority {:ping :running :ping2 :running :msgs [7 7] :nothing-more :timeout}))

(deftest item-2-in-ports-lose-nothing-with-stops-in-the-flood
  ;; One arbitration for a proc with user ports (spike.coflow.chan/alts-ops, core.async's commit protocol): nothing is
  ;; taken from a user's channel unless the proc consumes it. The oracle loses none, and so does the port: 0 in every arm.
  (doseq [[arm r] (each-arm #(arms/call % 'inports-race-stress 400 25))]
    (is (every? :increasing r) (str arm " order is kept, no duplicates"))
    (let [m (apply max (map :lost r))]
      (println (format "[in-ports flood] %-8s 25 rounds, stop in the middle of pause/resume floods: max messages lost in a round = %d"
                       (name arm) m))
      (is (zero? m) (str arm " lost no message")))))

(deftest item-2-in-ports-nothing-lost-while-the-flow-runs
  (doseq [[arm r] (each-arm #(arms/call % 'inports-race-no-loss-while-running 3000))]
    (is (= {:consumed 3000 :in-order true :left 0} r) (str arm " pause/resume at random, no stop"))))

;; --- review item 3: report and error channels read through core.async ------------------------------------------------

(deftest item-3-report-and-error-back-pressure-same-as-the-oracle
  (scenario-in-all-arms :report-and-error-backpressure
                        {:report {:n 100 :first 150 :last 249 :contiguous true}
                         :error {:n 100 :first 50 :last 149}}))

(defn- sliding-port-with-abandoned-takes
  "A reader that gives up many takes (alts!! with a timeout of 1 ms) while 5000 values come in. Returns what the reader
  got, and what is in the port when the producer is done and nobody reads: 300 more values, then a drain."
  []
  (let [scope-holder (flow/create-flow {:procs {:a {:proc (flow/process (flow/lift1->step inc))}} :conns []})
        _ (flow/start scope-holder)
        scope (ext/running-scope scope-holder)
        p (cc/port scope (cc/sliding 100))
        stop? (atom false) got (atom [])
        reader (future (while (not @stop?)
                         (let [t (a/timeout 1) [v c] (a/alts!! [p t])]
                           (when (and (some? v) (identical? c p)) (swap! got conj v)))))
        n 5000]
    (dotimes [i n] (a/>!! p i) (when (zero? (rem i 50)) (Thread/yield)))
    (reset! stop? true)
    @reader
    ;; nobody reads now (the last take was given up): the port must keep exactly the newest 100
    (dotimes [i 300] (a/>!! p (+ n i)))
    (let [rest-of (cc/drain (do (cc/close! p) p))]
      (flow/stop scope-holder)
      {:got @got :rest rest-of :n n})))

(deftest item-3-abandoned-takes-never-lose-or-reorder-and-add-no-capacity
  (dotimes [round 5]
    (let [{:keys [got rest n]} (sliding-port-with-abandoned-takes)]
      (is (apply < -1 (concat got rest)) "no reordering, no duplicate")
      (is (= (range (+ n 200) (+ n 300)) rest) "exactly the newest 100 are in the port: the hand-held value adds no slot")
      (is (every? #(< -1 % (+ n 300)) got)))))

(deftest item-3-abandoned-take-then-close-delivers-everything
  ;; fixed buffer big enough: nothing is dropped, so every value must arrive exactly once and in order, also when the
  ;; port is closed while the reader gives up takes
  (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/lift1->step inc))}} :conns []})
        _ (flow/start g)
        scope (ext/running-scope g)]
    (dotimes [_ 10]
      (let [p (cc/port scope 6000)
            got (atom []) done (promise)
            reader (future (loop []
                             (let [t (a/timeout 1) [v c] (a/alts!! [p t])]
                               (cond
                                 (and (nil? v) (identical? c p)) (deliver done true)   ;; closed and empty
                                 (identical? c p) (do (swap! got conj v) (recur))
                                 :else (recur)))))]
        (dotimes [i 5000] (a/>!! p i))
        (cc/close! p)
        (is (true? (deref done 20000 false)))
        @reader
        (is (= (range 5000) @got))))
    (flow/stop g)))

(deftest item-3-stop-while-the-reader-gives-up-takes
  ;; the flow is stopped while a reader does alts!! with short timeouts on the report channel
  (dotimes [_ 5]
    (let [step (flow/map->step {:describe (fn [] {:ins {:in ""}})
                                :transform (fn [s _ m] [s {:clojure.core.async.flow/report [m]}])})
          g (flow/create-flow {:procs {:p {:proc (flow/process step)}} :conns []})
          {:keys [report-chan]} (flow/start g)
          got (atom []) done (promise)
          reader (future (loop []
                           (let [t (a/timeout 1) [v c] (a/alts!! [report-chan t])]
                             (cond
                               (and (nil? v) (identical? c report-chan)) (deliver done true)
                               (identical? c report-chan) (do (swap! got conj v) (recur))
                               :else (recur)))))]
      (flow/resume g)
      @(flow/inject g [:p :in] (range 60))
      (loop [n 0] (when (and (< n 5000) (< (:clojure.core.async.flow/count (flow/ping-proc g :p)) 60)) (recur (inc n))))
      (flow/stop g)
      (is (true? (deref done 10000 false)))
      @reader
      (is (= (range 60) @got) "all 60 reports (fewer than the 100 of the buffer) arrive once, in order"))))

;; --- review item 4: no polling, idle CPU ---------------------------------------------------------------------------

(defn- process-cpu-ms []
  (let [^com.sun.management.OperatingSystemMXBean os (ManagementFactory/getOperatingSystemMXBean)]
    (/ (.getProcessCpuTime os) 1e6)))

(defn- idle-cpu-ms
  "Process CPU time (all threads of the JVM) over `ms` of an idle running flow of 20 procs, with a core.async reader
  waiting on the report channel and, for the port, a pending put on a full port."
  [arm ms]
  (let [n 20
        procs (into {} (map (fn [i] [(keyword (str "p" i)) {:proc (arms/call arm 'lift1 inc)}]) (range n)))
        g (arms/call arm 'make-flow {:procs procs :conns []})
        {:keys [report-chan]} (arms/call arm 'start-flow g)
        reader (future (a/<!! report-chan))]
    (arms/call arm 'resume-flow g)
    (arms/call arm 'ping-flow g)
    (deref (promise) 300 nil)
    (let [c0 (process-cpu-ms)
          _ (deref (promise) ms nil)
          c1 (process-cpu-ms)]
      (arms/call arm 'stop-flow g)
      (- c1 c0))))

(deftest item-4-idle-flow-uses-no-cpu
  (doseq [arm [:orig :alias]] (idle-cpu-ms arm 300)) ;; warm up
  (let [o (idle-cpu-ms :orig 2000)
        p (idle-cpu-ms :alias 2000)]
    (println (format "[idle CPU] 2000 ms idle, 20 procs, a pending <!! on the report channel: oracle %.0f ms CPU, port %.0f ms CPU" o p))
    (is (< p 250.0) "the port is idle: no polling")))

(deftest item-4-pending-put-uses-no-cpu
  (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/lift1->step inc))}} :conns []})
        _ (flow/start g)
        scope (ext/running-scope g)
        p (cc/port scope 1)
        c (a/chan 1)]
    (a/>!! p :full) (a/>!! c :full)
    (let [results (atom [])]
      (dotimes [_ 5] (a/put! p :pending (fn [ok] (swap! results conj [:p ok]))) (a/put! c :pending (fn [ok] (swap! results conj [:c ok]))))
      (deref (promise) 300 nil)
      (let [c0 (process-cpu-ms)
            _ (deref (promise) 2000 nil)
            idle (- (process-cpu-ms) c0)]
        (println (format "[idle CPU] 2000 ms with 5 pending puts on a full port (core.async channel next to it has the same): %.0f ms CPU" idle))
        (is (< idle 250.0))
        ;; the puts are served in order as room comes, none is lost
        (is (= :full (a/<!! p)))
        (dotimes [_ 5] (a/<!! p))
        (is (= 5 (count (filter #(= [:p true] %) @results))) "every pending put completed once")
        (is (nil? (a/poll! p)))))
    (flow/stop g)))

;; --- review item 5 and 6 -----------------------------------------------------------------------------------------------

(deftest item-5-compute-timeout-is-not-interrupted
  (scenario-in-all-arms :compute-timeout-no-interrupt
                        {:error-ex {:class "java.util.concurrent.TimeoutException"} :interrupted false :ran-to-end true
                         :loop-goes-on :running :result-is-dropped :timeout}))

(deftest item-6-futures-are-futuretasks
  (doseq [[arm r] (each-arm #(arms/scenario % :futures))]
    (is (= [true true true true true] (:instance-of-future-task r)) (str arm))
    (is (= :interrupted (:body-saw r)) (str arm " future-cancel interrupts the running body"))
    (is (= :timeout-val (:deref-timeout r)) (str arm))
    (is (= java.util.concurrent.TimeoutException (:get-timeout r)) (str arm))
    (is (= java.util.concurrent.CancellationException (:cancel-deref r)) (str arm))
    (is (= [java.util.concurrent.ExecutionException clojure.lang.ExceptionInfo "boom" {:a 1}] (:ex r)) (str arm)))
  (is (apply = (vals (each-arm #(arms/scenario % :futures))))))
