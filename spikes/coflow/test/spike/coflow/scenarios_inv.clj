;; More user code for the differential tests: one function per invariant of clojure.core.async.flow
;; (see README "Invariants of core.async.flow that the port keeps"). Compiled into the same arm namespaces as
;; scenarios.clj, after it.

(defn inv-a-no-concurrent-step
  "1. the step fn never runs concurrently with itself; the state goes from call to call with no loss.
  n-prod producers inject `per` messages each."
  [workload n-prod per]
  (let [total (* n-prod per)
        in-step (atom false) overlaps (atom 0) calls (atom 0)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :workload workload})
               :init (fn [_] {:n 0})
               :transform (fn [s _ m]
                            (when-not (compare-and-set! in-step false true) (swap! overlaps inc))
                            (swap! calls inc)
                            (when (zero? (rem m 7)) (Thread/yield))
                            (let [s' (update s :n inc)]
                              (reset! in-step false)
                              [s' (when (= (:n s') total) {::fk/report [(:n s')]})]))})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    (let [producers (mapv (fn [p] (future @(flow/inject g [:p :in] (range per)))) (range n-prod))]
      (run! deref producers)
      (let [final (rd report 20000)
            e (rd error 20)]
        (flow/stop g)
        {:total total :overlaps @overlaps :calls @calls :final-state final :error (when (not= e :timeout) e)}))))

(defn inv-b-order
  "2. messages on a connection keep their order, none lost, none duplicated: n-prod producers, small buffers,
  optionally pause/resume at random while they run."
  [n-prod per toggle?]
  (let [total (* n-prod per)
        sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 identity) :chan-opts {:in {:buf-or-n 2}}}
                                      :b {:proc (lift1 identity) :chan-opts {:in {:buf-or-n 1}}}
                                      :c {:proc (flow/process collector) :args {:sink sink} :chan-opts {:in {:buf-or-n 2}}}}
                              :conns [[[:a :out] [:b :in]] [[:b :out] [:c :in]]]})
        stop? (atom false)]
    (flow/resume g)
    (let [toggler (when toggle?
                    (future (loop [paused? false]
                              (when-not @stop?
                                (if paused? (flow/resume g) (flow/pause g))
                                (deref (promise) (rand-int 4) nil)
                                (recur (not paused?))))))
          producers (mapv (fn [p] (future @(flow/inject g [:a :in] (mapv (fn [i] [p i]) (range per))))) (range n-prod))
          _ (run! deref producers)
          _ (reset! stop? true)
          _ (when toggler @toggler)
          _ (flow/resume g)
          _ (await-count sink total 30000)
          got @sink
          e (rd error 20)]
      (flow/stop g)
      {:total total :count (count got) :distinct (count (set got))
       :per-producer-in-order (every? (fn [p] (= (range per) (map second (filter #(= p (first %)) got)))) (range n-prod))
       :error (when (not= e :timeout) e)})))

(defn inv-c-control-priority []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {:n 0})
               :transform (fn [s _ m] (deref (promise) 2 nil) [(update s :n inc) {::fk/report [m]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n 1000}}}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] (range 300))
    (flow/pause g)
    (let [p1 (flow/ping-proc g :p)
          p2 (flow/ping-proc g :p)
          ;; paused: more input is not taken
          _ @(flow/inject g [:p :in] (range 300 310))
          p3 (flow/ping-proc g :p)
          p4 (flow/ping-proc g :p)]
      (flow/resume g)
      (let [n-reports (loop [n 0] (if (= :timeout (rd report 700)) n (recur (inc n))))
            p5 (flow/ping-proc g :p)]
        (flow/stop g)
        {:status [(::fk/status p1) (::fk/status p2) (::fk/status p3) (::fk/status p4) (::fk/status p5)]
         :count-stable (= (::fk/count p2) (::fk/count p3) (::fk/count p4))
         :control-first (< (::fk/count p2) 300)
         :all-processed [n-reports (::fk/count p5)]}))))

(defn inv-d-transitions []
  (let [log (atom [])
        mkp (fn [lg] (flow/process
                      (flow/map->step
                       {:describe (fn [] {:ins {:in ""}})
                        :init (fn [_] {})
                        :transition (fn [s t] (swap! lg conj t) s)
                        :transform (fn [s _ _] [s nil])})))
        [g report error] (mk {:procs {:p {:proc (mkp log)}} :conns []})]
    (doseq [c [#(flow/resume g) #(flow/resume g) #(flow/pause g) #(flow/pause g) #(flow/resume g)
               #(flow/pause-proc g :p) #(flow/pause-proc g :p) #(flow/resume-proc g :p) #(flow/resume-proc g :p)]]
      (c))
    (flow/ping g)
    (let [r1 (flow/stop g) r2 (flow/stop g)]
      (await-pred log #(some #{::fk/stop} %) T)
      (let [log2 (atom [])
            [g2 _ _] (mk {:procs {:p {:proc (mkp log2)}} :conns []})]
        (flow/stop g2)
        (await-pred log2 #(some #{::fk/stop} %) T)
        {:log @log :stop-count (count (filter #{::fk/stop} @log)) :stops [r1 r2]
         :never-resumed @log2}))))

(defn inv-h-backpressure-stress
  "8. a slow consumer blocks the producer: the gap between messages put and messages taken is bounded by the buffer"
  [cap total]
  (let [consumed (atom 0) produced (atom 0) max-gap (atom 0)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] (deref (promise) 1 nil) (swap! consumed inc) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n cap}}}} :conns []})]
    (flow/resume g)
    (dotimes [i total]
      (swap! produced inc)
      @(flow/inject g [:p :in] [i])
      (swap! max-gap max (- @produced @consumed)))
    (await-pred consumed #(>= % total) 30000)
    (let [r {:consumed @consumed :max-gap-ok (<= @max-gap (+ cap 3)) :max-gap @max-gap}]
      (flow/stop g)
      r)))

(defn inv-j-after-stop []
  (let [calls (atom 0)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] (swap! calls inc) (deref (promise) 20 nil) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n 100}}}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] (range 50))
    (flow/stop g)
    (let [_ (rd report 400)
          later @calls
          _ (rd report 400)
          later2 @calls]
      {:stable-after-stop (= later later2) :less-than-all (< later2 50)
       :closed [(rd report 50) (rd error 50)]})))

(defn inv-k-routing []
  (let [sa (atom []) sb (atom []) sc (atom [])
        [g report error] (mk {:procs {:src {:proc (lift1 identity)}
                                      :a {:proc (flow/process collector) :args {:sink sa}}
                                      :b {:proc (flow/process collector) :args {:sink sb}}
                                      :c {:proc (flow/process collector) :args {:sink sc}}
                                      :two {:proc (flow/process (flow/map->step
                                                                 {:describe (fn [] {:ins {:in ""} :outs {:o1 "" :o2 ""}})
                                                                  :transform (fn [s _ m] [s {:o1 [m] :o2 [m]}])}))}}
                              ;; src.out -> a.in and b.in (fan-out); two.o1 -> c.in and two.o2 -> c.in (fan-in)
                              :conns [[[:src :out] [:a :in]] [[:src :out] [:b :in]]
                                      [[:two :o1] [:c :in]] [[:two :o2] [:c :in]]]})]
    (flow/resume g)
    @(flow/inject g [:src :in] [1 2 3])
    @(flow/inject g [:two :in] [:x :y])
    (await-count sa 3 T) (await-count sb 3 T) (await-count sc 4 T)
    (let [r {:a @sa :b @sb :c (sort-by str @sc)}]
      (flow/stop g)
      r)))

(defn inv-i-command-proc []
  (let [[g report error] (mk {:procs {:p {:proc (lift1 inc)}} :conns []})
        r (try (clojure.core.async.flow.impl.graph/command-proc g :p ::some-command {:k 1})
               (catch Throwable t (.getName (class t))))
        public (some? (ns-resolve 'clojure.core.async.flow 'command-proc))]
    (flow/stop g)
    {:result r :public-fn public}))

(defn throughput
  "n messages through a 3-proc pipeline (a -> b -> sink). Returns {:n :ms :msgs-per-s}"
  [n]
  (let [done (promise)
        sink (flow/map->step {:describe (fn [] {:ins {:in ""}})
                              :init (fn [_] {:c 0})
                              :transform (fn [s _ m]
                                           (let [c (inc (:c s))]
                                             (when (= c n) (deliver done true))
                                             [{:c c} nil]))})
        [g report error] (mk {:procs {:a {:proc (lift1 inc)} :b {:proc (lift1 inc)} :s {:proc (flow/process sink)}}
                              :conns [[[:a :out] [:b :in]] [[:b :out] [:s :in]]]})]
    (flow/resume g)
    (flow/ping g)
    (let [t0 (System/nanoTime)
          f (flow/inject g [:a :in] (range n))
          ok (deref done 120000 false)
          ms (/ (- (System/nanoTime) t0) 1e6)]
      (flow/stop g)
      {:n n :ok ok :ms (long ms) :msgs-per-s (long (/ n (/ ms 1000.0)))})))
