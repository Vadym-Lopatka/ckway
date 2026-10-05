;; Second round of user code for the differential tests: the user-visible differences that the review found.
;; Each scenario was first run against the port as it was (and failed there, passed on the oracle), then the port was
;; fixed. Compiled into the arm namespaces after scenarios.clj and scenarios_inv.clj.

(defn ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))

(defn drain-poll
  "everything that is in a channel now, with `poll!`"
  [c]
  (loop [acc []] (let [v (a/poll! c)] (if (some? v) (recur (conj acc v)) acc))))

(defn gate-step
  "a proc whose transform waits on `gate` for the message 1 (the others pass at once) and reports the message"
  [gate]
  (flow/map->step {:describe (fn [] {:ins {:in ""}})
                   :init (fn [_] {})
                   :transform (fn [s _ m] (when (= m 1) (deref gate T nil)) [s {::fk/report [m]}])}))

;; --- 1. stop returns at once --------------------------------------------------------------------------------------

(defn stop-returns-at-once []
  (let [gate (promise)
        entered (promise)
        step (flow/map->step {:describe (fn [] {:ins {:in ""}})
                              :init (fn [_] {})
                              :transform (fn [s _ m] (deliver entered true) (deref gate T nil) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (deref entered T nil)
    (let [t0 (System/nanoTime)
          r (flow/stop g)
          fast (< (ms-since t0) 2000)]
      (deliver gate true)
      {:stop-ret r :stop-fast fast :report-closed (rd report 500) :error-closed (rd error 500)})))

(defn stop-from-a-step-fn []
  (let [gref (promise) result (promise) log (atom [])
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transition (fn [s t] (swap! log conj t) s)
               :transform (fn [s _ m]
                            (let [t0 (System/nanoTime)
                                  r (flow/stop @gref)]
                              (deliver result [r (< (ms-since t0) 3000)]))
                            [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (deliver gref g)
    (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (let [r (deref result T :hang)]
      (await-pred log #(some #{::fk/stop} %) T)
      {:result r :log @log
       :pause-after (try (flow/pause g) (catch Throwable t (nx t)))
       :channels-closed [(rd report 300) (rd error 300)]})))

(defn start-after-stop-while-the-old-run-is-stuck []
  (let [gate (promise)
        [g report error] (mk {:procs {:p {:proc (flow/process (gate-step gate))}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (rd report 100)
    (let [t0 (System/nanoTime)
          stop-ret (flow/stop g)
          fast (< (ms-since t0) 2000)
          {r2 :report-chan e2 :error-chan} (flow/start g)]
      (flow/resume g)
      @(flow/inject g [:p :in] [2])
      (let [new-run (rd r2)
            status (::fk/status (flow/ping-proc g :p :timeout-ms 5000))]
        (deliver gate true)
        (flow/stop g)
        {:stop-ret stop-ret :stop-fast fast :new-run-report new-run :new-run-status status
         :new-chans-open? (not= r2 report)}))))

;; --- 2. the user's channels in ::flow/in-ports and ::flow/out-ports ---------------------------------------------------

(defn inports-left
  "How many messages are left in the user's channel, and which: the proc takes from it only when it really reads."
  [mode]
  (let [n 5 src (a/chan 10) gate (promise) entered (promise)
        _ (dotimes [i n] (a/>!! src i))
        step (flow/map->step
              {:describe (fn [] {:params {:src ""}})
               :init (fn [{:keys [src]}]
                       (cond-> {::fk/in-ports {:src src}}
                         (= mode :filtered) (assoc ::fk/input-filter (constantly false))))
               :transform (fn [s _ m]
                            (when (= mode :stopped) (deliver entered true) (deref gate T nil))
                            [s {::fk/report [m]}])})
        def {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []}]
    (case mode
      :never-started (do (flow/create-flow def) {:left (drain-poll src)})
      :paused (let [[g report error] (mk def)]
                (flow/ping-proc g :p :timeout-ms 5000)
                (flow/stop g)
                (deref (promise) 150 nil)
                {:left (drain-poll src)})
      :filtered (let [[g report error] (mk def)]
                  (flow/resume g)
                  (flow/ping-proc g :p :timeout-ms 5000)
                  (flow/stop g)
                  (deref (promise) 150 nil)
                  {:left (drain-poll src)})
      :stopped (let [[g report error] (mk def)]
                 (flow/resume g)
                 (deref entered T nil)
                 (flow/stop g)
                 ;; the stop reaches the proc while its transform waits; then the transform goes on
                 (deref (promise) 1500 nil)
                 (deliver gate true)
                 (deref (promise) 1500 nil)
                 {:left (drain-poll src)})
      :all-read (let [[g report error] (mk def)]
                  (flow/resume g)
                  (let [got (rd-n report n)]
                    (flow/stop g)
                    {:got got :left (drain-poll src)})))))

(defn inports-closed-input []
  (let [src (a/chan 3)
        step (flow/map->step
              {:describe (fn [] {:params {:src ""}})
               :init (fn [{:keys [src]}] {::fk/in-ports {:src src}})
               :transform (fn [s cid m] [s {::fk/report [[cid m]]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []})]
    (a/>!! src 1)
    (flow/resume g)
    (let [r1 (rd report)]
      (a/close! src)
      (let [r2 (rd report) r3 (rd report 200)]
        (flow/stop g)
        {:r1 r1 :on-close r2 :nothing-more r3}))))

(defn outports-backpressure
  "How many messages can the proc take in before it blocks on a full user's channel (so: no extra buffer between)."
  [cap]
  (let [sink (a/chan cap)
        step (flow/map->step
              {:describe (fn [] {:params {:sink ""} :ins {:in ""}})
               :init (fn [{:keys [sink]}] {::fk/out-ports {:snk sink}})
               :transform (fn [s _ m] [s {:snk [m]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:sink sink} :chan-opts {:in {:buf-or-n 1}}}}
                              :conns []})]
    (flow/resume g)
    (let [accepted (loop [i 0]
                     (if (< i 30)
                       (if (= ::blocked (deref (flow/inject g [:p :in] [i]) 1500 ::blocked)) i (recur (inc i)))
                       i))
          in-sink (a/poll! sink)]
      ;; read what is in the user's channel now: the proc goes on
      (let [rest-of (drain-poll sink)]
        (flow/stop g)
        {:accepted-before-block accepted :first (if (= in-sink 0) :zero in-sink)
         :buffered-after-first (count rest-of) :in-order (= rest-of (range 1 (inc (count rest-of))))}))))

(defn outports-control-priority []
  ;; a proc that is blocked in a write to a full user's channel still answers a ping and a pause, and the write
  ;; happens once (no duplicate) when the user reads later
  (let [sink (a/chan 1)
        step (flow/map->step
              {:describe (fn [] {:params {:sink ""} :ins {:in ""}})
               :init (fn [{:keys [sink]}] {::fk/out-ports {:snk sink}})
               :transform (fn [s _ m] [s {:snk [m m]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:sink sink}}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [7])
    (let [ping (::fk/status (flow/ping-proc g :p :timeout-ms 5000))
          _ (flow/pause g)
          _ (flow/resume g)
          ping2 (::fk/status (flow/ping-proc g :p :timeout-ms 5000))
          first-msg (rd sink)
          second-msg (rd sink)
          none (rd sink 200)]
      (flow/stop g)
      {:ping ping :ping2 ping2 :msgs [first-msg second-msg] :nothing-more none})))

;; --- 3. the report and error channels ------------------------------------------------------------------------------

(defn report-and-error-backpressure
  "Nobody reads the report and error channels: both keep the newest 100 messages (sliding buffer 100)."
  []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m]
                            (if (and (vector? m) (= :throw (first m)))
                              (throw (ex-info "x" {:i (second m)}))
                              [s {::fk/report (range m)}]))})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [250])
    @(flow/inject g [:p :in] (map (fn [i] [:throw i]) (range 150)))
    ;; the ping is answered after the 151 messages were handled (the control channel has priority, so wait for the count)
    (loop [n 0] (when (and (< n 2000) (< (::fk/count (flow/ping-proc g :p :timeout-ms 5000)) 151)) (recur (inc n))))
    (let [reps (drain-poll report)
          errs (drain-poll error)]
      (flow/stop g)
      {:report {:n (count reps) :first (first reps) :last (last reps) :contiguous (= reps (range 150 250))}
       :error {:n (count errs) :first (some-> errs first ::fk/ex ex-data :i) :last (some-> errs last ::fk/ex ex-data :i)}})))

;; --- 5. :compute, a transform that times out ---------------------------------------------------------------------

(defn compute-timeout-no-interrupt []
  (let [interrupted (atom false) finished (promise)
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :workload :compute})
               :init (fn [_] {})
               :transform (fn [s _ m]
                            (try (deref (promise) 700 nil)
                                 (catch InterruptedException e (reset! interrupted true)))
                            (deliver finished true)
                            [s {::fk/report [m]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step {:compute-timeout-ms 150})}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (let [e (norm-err (rd error))
          fin (deref finished 5000 :no)
          status (::fk/status (flow/ping-proc g :p :timeout-ms 5000))
          late-report (rd report 200)]
      (flow/stop g)
      {:error-ex (select-keys (::fk/ex e) [:class]) :interrupted @interrupted :ran-to-end fin
       :loop-goes-on status :result-is-dropped late-report})))

;; --- 6. futures ----------------------------------------------------------------------------------------------------

(defn futures []
  (let [seen (promise) started (promise)
        r1 ((flow/futurize (fn [a b] (+ a b)) :exec :io) 1 2)
        slow ((flow/futurize (fn [] (deref (promise) 1500 :timed-out)) :exec :mixed))
        cf ((flow/futurize (fn [] (deliver started true)
                             (try (deref (promise) 5000 :done)
                                  (catch InterruptedException e (deliver seen :interrupted) :int))) :exec :io))
        _ (deref started T nil)
        cancel-ret (future-cancel cf)
        ex-f ((flow/futurize (fn [] (throw (ex-info "boom" {:a 1}))) :exec :compute))
        g (flow/create-flow {:procs {:p {:proc (lift1 inc)}} :conns []})
        _ (flow/start g)
        inj (flow/inject g [:p :in] [1])
        fts [r1 slow cf ex-f inj]]
    (deref inj T nil)
    (let [r {:instance-of-future-task (mapv #(instance? java.util.concurrent.FutureTask %) fts)
             :future? (mapv future? fts)
             :deref (deref r1)
             :deref-timeout (deref slow 50 :timeout-val)
             :get-timeout (try (.get ^java.util.concurrent.Future slow 50 java.util.concurrent.TimeUnit/MILLISECONDS)
                               (catch Throwable t (class t)))
             :cancel [cancel-ret (future-cancelled? cf) (future-done? cf)]
             :cancel-deref (try (deref cf) (catch Throwable t (class t)))
             :body-saw (deref seen T :no-interrupt)
             :ex (try @ex-f (catch Throwable t [(class t) (class (ex-cause t)) (ex-message (ex-cause t)) (ex-data (ex-cause t))]))
             :ex-get (try (.get ^java.util.concurrent.Future ex-f) (catch Throwable t (class t)))
             :inject-done (future-done? inj)
             :cancel-after-done (future-cancel r1)
             :done-flags (mapv future-done? [r1 cf ex-f inj])}]
      (flow/stop g)
      r)))

(defn make-flow [d] (flow/create-flow d))
(defn start-flow [g] (flow/start g))
(defn resume-flow [g] (flow/resume g))
(defn ping-flow [g] (flow/ping g :timeout-ms 5000))
(defn stop-flow [g] (flow/stop g))

(def scenarios
  (merge scenarios
         {:stop-returns-at-once stop-returns-at-once
          :stop-from-a-step-fn stop-from-a-step-fn
          :start-after-stop-while-the-old-run-is-stuck start-after-stop-while-the-old-run-is-stuck
          :inports-never-started #(inports-left :never-started)
          :inports-paused #(inports-left :paused)
          :inports-filtered #(inports-left :filtered)
          :inports-stopped #(inports-left :stopped)
          :inports-all-read #(inports-left :all-read)
          :inports-closed-input inports-closed-input
          :outports-backpressure-1 #(outports-backpressure 1)
          :outports-backpressure-3 #(outports-backpressure 3)
          :outports-control-priority outports-control-priority
          :report-and-error-backpressure report-and-error-backpressure
          :compute-timeout-no-interrupt compute-timeout-no-interrupt
          :futures futures}))

(defn inports-race-stress
  "Many pause/resume while a user's channel is read, and a stop in the middle: how many messages are lost?
  (The original loses none; the port may lose at most one, see README.)"
  [n rounds]
  (vec (for [_ (range rounds)]
         (let [src (a/chan n) consumed (atom [])
               _ (dotimes [i n] (a/>!! src i))
               step (flow/map->step
                     {:describe (fn [] {:params {:src ""}})
                      :init (fn [{:keys [src]}] {::fk/in-ports {:src src}})
                      :transform (fn [s _ m] (swap! consumed conj m) [s nil])})
               [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []})
               stop? (atom false)
               toggler (future (loop [paused? false]
                                 (when-not @stop?
                                   (if paused? (flow/resume g) (flow/pause g))
                                   (deref (promise) 1 nil)
                                   (recur (not paused?)))))]
           (flow/resume g)
           (await-count consumed (inc (rand-int (quot n 2))) 10000)
           (reset! stop? true)
           @toggler
           (flow/stop g)
           (deref (promise) 200 nil)
           (let [got @consumed left (drain-poll src)
                 all (concat got left)]
             {:lost (- n (count got) (count left))
              :increasing (apply < -1 all)})))))

(defn inports-race-no-loss-while-running
  "Pause/resume at random while a user's channel is read; the flow is NOT stopped: nothing is lost, order is kept."
  [n]
  (let [src (a/chan n) consumed (atom [])
        _ (dotimes [i n] (a/>!! src i))
        step (flow/map->step
              {:describe (fn [] {:params {:src ""}})
               :init (fn [{:keys [src]}] {::fk/in-ports {:src src}})
               :transform (fn [s _ m] (swap! consumed conj m) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []})
        stop? (atom false)
        toggler (future (loop [paused? false]
                          (when-not @stop?
                            (if paused? (flow/resume g) (flow/pause g))
                            (deref (promise) 1 nil)
                            (recur (not paused?)))))]
    (flow/resume g)
    (await-count consumed (quot n 2) 20000)
    (reset! stop? true)
    @toggler
    (flow/resume g)
    (await-count consumed n 20000)
    (flow/stop g)
    {:consumed (count @consumed) :in-order (= @consumed (range n)) :left (count (drain-poll src))}))
