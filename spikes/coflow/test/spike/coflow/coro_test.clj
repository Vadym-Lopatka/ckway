(ns spike.coflow.coro-test
  "What is specific to coroutines: the scope of the flow, structured concurrency, threads, cancellation, throughput.
  Numbers are printed with `println` (they are results to read, not assertions on speed)."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [ckway.core :as kt]
            [spike.coflow.arms :as arms]
            [spike.coflow.chan :as cc]
            [spike.coflow.flow :as flow]
            [spike.coflow.impl :as impl])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent.atomic AtomicInteger]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.flow :as kflow])

(def fk-stop :clojure.core.async.flow/stop)
(def fk-report :clojure.core.async.flow/report)

(defn- rd [c ms] (let [t (a/timeout ms) [v p] (a/alts!! [c t])] (if (= p t) :timeout v)))

(defn- platform-threads [] (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn- scope-job ^kotlinx.coroutines.Job [g]
  (co/job (.getCoroutineContext ^kotlinx.coroutines.CoroutineScope (impl/flow-scope g))))

(defn- pipeline []
  (flow/create-flow {:procs {:a {:proc (flow/process (flow/lift1->step inc))}
                             :b {:proc (flow/process (flow/lift1->step inc))}}
                     :conns [[[:a :out] [:b :in]]]}))

(deftest stop-completes-the-scope-no-coroutine-is-left
  (let [g (pipeline)
        {:keys [report-chan]} (flow/start g)
        job (scope-job g)]
    (is (co/isActive job) "running")
    (is (true? (flow/resume g)) "resume returns what the original returns: the result of the put on the control channel")
    @(flow/inject g [:a :in] [1 2 3])
    ;; the pump of the report chan is a coroutine of the scope too: take something from it
    (flow/ping g)
    (is (true? (flow/stop g)))
    (is (co/isCompleted job) "the Job of the scope is completed: no coroutine is active")
    (is (not (co/isActive job)))
    (is (nil? (impl/flow-scope g)) "no scope when not running")
    (testing "also with a user taker on the report channel"
      (let [g (pipeline)
            {:keys [report-chan]} (flow/start g)
            job (scope-job g)
            taker (future (a/<!! report-chan))]
        (flow/stop g)
        (is (nil? (deref taker 3000 :hang)) "the take of a closed channel returns nil")
        (is (co/isCompleted job))))))

(deftest failed-start-leaves-no-coroutine
  (let [g (flow/create-flow {:procs {:ok {:proc (flow/process (flow/lift1->step inc))}
                                     :bad {:proc (flow/process (flow/map->step
                                                                {:describe (fn [] {})
                                                                 :init (fn [_] (throw (ex-info "init-boom" {})))
                                                                 :transform (fn [s _ _] [s nil])}))}}
                             :conns []})
        ]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"init-boom" (flow/start g)))
    (is (thrown-with-msg? Exception #"flow not running" (flow/pause g)))))

(deftest start-stop-50-flows-no-thread-growth
  (let [cycle (fn [] (let [g (pipeline)]
                       (flow/start g) (flow/resume g)
                       @(flow/inject g [:a :in] [1 2 3])
                       (flow/ping g :timeout-ms 2000)
                       (flow/stop g)))
        _ (dotimes [_ 5] (cycle))                         ;; warm up: pools, class loading
        before (platform-threads)
        active-before (Thread/activeCount)
        _ (dotimes [_ 50] (cycle))
        after (platform-threads)
        active-after (Thread/activeCount)]
    (println "[threads] 50 start/stop cycles: platform threads" before "->" after
             ", Thread/activeCount (this thread group)" active-before "->" active-after)
    (is (<= (- after before) 8) "no growth with the number of flows (a small slack for pools that start late)")))

(deftest thousand-idle-procs-start-and-stop
  (let [n 1000
        procs (into {} (map (fn [i] [(keyword (str "p" i)) {:proc (flow/process (flow/lift1->step inc))}]) (range n)))
        g (flow/create-flow {:procs procs :conns []})
        before (platform-threads)
        t0 (System/nanoTime)
        {:keys [report-chan error-chan]} (flow/start g)
        t1 (System/nanoTime)
        during (platform-threads)
        job (scope-job g)
        _ (flow/resume g)
        pinged (flow/ping g :timeout-ms 20000)
        t2 (System/nanoTime)]
    (println (format "[1000 idle procs] start %d ms, ping all %d ms (%d answered), platform threads %d -> %d (virtual threads: one per proc body)"
                     (long (/ (- t1 t0) 1e6)) (long (/ (- t2 t1) 1e6)) (count pinged) before during))
    (is (= n (count pinged)))
    (is (every? #(= :running (:clojure.core.async.flow/status %)) (vals pinged)))
    (is (< (- during before) 40) "1000 idle procs do not hold 1000 platform threads")
    (let [t3 (System/nanoTime)]
      (is (true? (flow/stop g)))
      (println (format "[1000 idle procs] stop %d ms" (long (/ (- (System/nanoTime) t3) 1e6)))))
    (is (co/isCompleted job))
    (is (nil? (rd error-chan 50)))))

(deftest cancel-of-the-scope-stops-loops-that-wait-in-select
  (let [g (pipeline)
        _ (flow/start g)
        _ (flow/resume g)
        job (scope-job g)]
    (flow/ping g)
    (let [t0 (System/nanoTime)]
      ;; Kotlin: scope.cancel()   -- the loops wait in `select { control.onReceive ...; in.onReceive ... }`
      (co/.cancel job)
      (is (true? (deref (future (co/.join job) true) 3000 false)) "Job.cancel() ends loops that wait in select")
      (println (format "[cancel] loops waiting in select ended in %d ms" (long (/ (- (System/nanoTime) t0) 1e6)))))
    (is (co/isCompleted job))
    ;; the flow object still thinks it runs; stop must not hang and must clean up
    (is (true? (deref (future (flow/stop g)) 10000 :hang)))))

(deftest cancel-of-the-scope-and-a-step-fn-that-catches-exception
  ;; limits 4/16 of ckway: `(catch Exception ...)` catches the interrupt of a cancel. The loop is written so that this
  ;; is harmless: after the step fn returns, the next suspend call (the select) sees the cancelled Job.
  (let [in-step (promise) swallowed (promise)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m]
                            (deliver in-step true)
                            (try (deref (promise) 30000 nil)
                                 (catch Exception e (deliver swallowed (class e))))
                            [s {fk-report [:after]}])})
        g (flow/create-flow {:procs {:p {:proc (flow/process step)}} :conns []})
        _ (flow/start g)
        _ (flow/resume g)
        job (scope-job g)]
    @(flow/inject g [:p :in] [1])
    (is (true? (deref in-step 3000 false)))
    (let [t0 (System/nanoTime)]
      (co/.cancel job)
      (is (true? (deref (future (co/.join job) true) 5000 false)) "the loop ends although the step fn swallowed the interrupt")
      (println (format "[cancel] step fn with (catch Exception) ended %d ms after cancel; the catch saw %s"
                       (long (/ (- (System/nanoTime) t0) 1e6)) (deref swallowed 100 nil))))
    (flow/stop g)))

(deftest stop-lets-the-stop-transition-run
  ;; stop is not a cancel: the proc takes the stop command and runs its transition fn (resource cleanup)
  (let [stopped (promise)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transition (fn [s t] (when (= t fk-stop) (deliver stopped (.isVirtual (Thread/currentThread)))) s)
               :transform (fn [s _ _] [s nil])})
        g (flow/create-flow {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/start g) (flow/resume g)
    (flow/stop g)
    (is (realized? stopped) "the stop transition ran before stop returned")
    (is (true? @stopped) "and it ran on a virtual thread (the body of a coroutine)")))

(deftest stop-with-a-step-fn-that-never-returns-cancels-after-the-grace-time
  (System/setProperty "coflow.stop.grace.ms" "300")
  (try
    (let [in-step (promise)
          step (flow/map->step
                {:describe (fn [] {:ins {:in ""}})
                 :init (fn [_] {})
                 :transform (fn [s _ _] (deliver in-step true) (deref (promise) 60000 nil) [s nil])})
          g (flow/create-flow {:procs {:p {:proc (flow/process step)}} :conns []})]
      (flow/start g) (flow/resume g)
      (let [job (scope-job g)]
        @(flow/inject g [:p :in] [1])
        (is (true? (deref in-step 3000 false)))
        (let [t0 (System/nanoTime)]
          (is (true? (flow/stop g)))
          (let [ms (long (/ (- (System/nanoTime) t0) 1e6))]
            (println (format "[stop] a stuck step fn: stop returned after %d ms (grace 300 ms, then cancel)" ms))
            (is (< ms 4000)))
          (is (co/isCompleted job)))))
    (finally (System/clearProperty "coflow.stop.grace.ms"))))

(deftest workloads-and-dispatchers
  ;; the body of a proc is a Clojure fn on a virtual thread, whatever the workload; the dispatcher only decides where
  ;; the coroutine is resumed. :compute runs each transform as its own coroutine.
  (let [seen (atom {})
        mkp (fn [w] (flow/process (flow/map->step
                                   {:describe (fn [] {:ins {:in ""}})
                                    :init (fn [_] {})
                                    :transform (fn [s _ _]
                                                 (swap! seen assoc w (let [t (Thread/currentThread)] [(.isVirtual t) (.getName t)]))
                                                 [s nil])})
                                  {:workload w}))
        g (flow/create-flow {:procs {:m {:proc (mkp :mixed)} :i {:proc (mkp :io)} :c {:proc (mkp :compute)}} :conns []})]
    (flow/start g) (flow/resume g)
    (doseq [p [:m :i :c]] @(flow/inject g [p :in] [1]))
    (flow/ping g)
    (let [r (loop [n 0] (if (or (= 3 (count @seen)) (> n 100)) @seen (do (flow/ping g) (recur (inc n)))))]
      (println "[workloads] thread of the step fn, per workload:" r)
      (is (= #{:mixed :io :compute} (set (keys r))))
      (is (every? (comp true? first) (vals r)) "a body runs on a virtual thread in every workload"))
    (flow/stop g)))

(deftest compute-runs-transforms-in-parallel-coroutines
  (let [n 8 cur (AtomicInteger.) peak (AtomicInteger.)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ _]
                            (let [c (.incrementAndGet cur)]
                              (.updateAndGet peak (reify java.util.function.IntUnaryOperator (applyAsInt [_ x] (max x c))))
                              (deref (promise) 100 nil)
                              (.decrementAndGet cur)
                              [s {fk-report [:done]}]))})
        g (flow/create-flow {:procs (into {} (map (fn [i] [(keyword (str "c" i)) {:proc (flow/process step {:workload :compute})}]) (range n)))
                             :conns []})
        {:keys [report-chan]} (flow/start g)]
    (flow/resume g)
    (doseq [i (range n)] @(flow/inject g [(keyword (str "c" i)) :in] [1]))
    (dotimes [_ n] (is (= [:done] [(rd report-chan 5000)])))
    (println "[compute] peak of concurrent transforms of 8 procs (100 ms each):" (.get peak)
             "- Dispatchers.Default does not bound a Clojure body: it runs on a virtual thread")
    (is (> (.get peak) 1))
    (flow/stop g)))

(deftest throughput-smoke
  (doseq [n [20000 200000]]
    (doseq [arm [:orig :alias]]
      (let [r (arms/call arm 'throughput n)]
        (println (format "[throughput] %-5s %7d messages through a 3-proc pipeline: %6d ms  = %8d msg/s %s"
                         (name arm) n (:ms r) (:msgs-per-s r) (if (:ok r) "" "(INCOMPLETE)")))
        (is (:ok r))))))

(deftest reports-as-kotlin-flow
  (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/map->step
                                                              {:describe (fn [] {:ins {:in ""}})
                                                               :transform (fn [s _ m] [s {fk-report [m]}])}))}}
                             :conns []})
        {:keys [report-chan]} (flow/start g)]
    (flow/resume g)
    @(flow/inject g [:a :in] [1 2 3])
    ;; the Flow reads the raw channel: stop closes it, the Flow ends
    ;; wait until the proc has handled the 3 messages (its count), so that all reports are in the channel
    (loop [n 0]
      (when (and (< n 500) (< (:clojure.core.async.flow/count (flow/ping-proc g :a)) 3))
        (recur (inc n))))
    (let [f (future (vec (kflow/.toList ^kotlinx.coroutines.flow.Flow (cc/->flow report-chan))))]
      (flow/stop g)
      (is (= [1 2 3] (deref f 5000 :hang))))))
