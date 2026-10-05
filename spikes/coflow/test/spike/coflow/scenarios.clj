;; The user code of the differential tests. It is plain Clojure with NO ns form: `spike.coflow.arms` reads this
;; file and compiles it into one namespace per arm. Only the aliases differ between the arms:
;;   flow = the namespace of the functions (clojure.core.async.flow, or spike.coflow.flow)
;;   fk   = the namespace of the keywords (always clojure.core.async.flow: the contract of the original)
;; The `takeover` arm uses `clojure.core.async.flow` for `flow` too, but with the port installed behind it.
;; A scenario returns plain data. The test says: the data of all arms must be equal.

(require '[clojure.core.async :as a]
         '[clojure.datafy :as d])

(def T 4000)

(defn rd
  "read one value of a channel with a timeout, any channel (core.async `alts!!`)"
  ([c] (rd c T))
  ([c ms] (let [t (a/timeout ms) [v p] (a/alts!! [c t])] (if (= p t) :timeout v))))

(defn rd-n [c n] (vec (repeatedly n #(rd c))))

(defn await-pred
  "wait (no polling, no sleep) until (pred @at) holds, at most ms. Returns true or false."
  [at pred ms]
  (let [p (promise) k (gensym "w")]
    (add-watch at k (fn [_ _ _ v] (when (pred v) (deliver p true))))
    (when (pred @at) (deliver p true))
    (let [r (deref p ms false)] (remove-watch at k) r)))

(defn await-count [at n ms] (await-pred at #(>= (count %) n) ms))

(defn nx
  "an exception as data"
  [ex]
  (when ex
    {:class (.getName (class ex)) :msg (ex-message ex) :data (ex-data ex)
     :cause (when-let [c (ex-cause ex)] {:class (.getName (class c)) :msg (ex-message c)})}))

(defn norm-err
  [m]
  (if (map? m) (cond-> (update m ::fk/ex nx) (contains? m ::fk/xform) (update ::fk/xform (fn [x] (when x :xform)))) m))

(defn chan-shape
  "a datafied channel without the numbers that cannot be equal (the takers that wait)"
  [c]
  (when c (select-keys (d/datafy c) [:closed? :buffer])))

(defn norm-ping
  [m]
  (when (map? m)
    (-> m
        (update ::fk/ins #(update-vals % (fn [c] (some-> c chan-shape (update :buffer dissoc :count)))))
        (update ::fk/outs #(update-vals % (fn [c] (some-> c chan-shape (update :buffer dissoc :count))))))))

(defn collector
  "a sink step fn: collects into the atom of :sink, and reports every message"
  ([] {:params {:sink "atom"} :ins {:in "x"}})
  ([{:keys [sink]}] {:sink sink})
  ([s _] s)
  ([{:keys [sink] :as s} _ m] (swap! sink conj m) [s {::fk/report [m]}]))

(defn mk
  "create + start; returns [g report error]"
  [def]
  (let [g (flow/create-flow def)
        {:keys [report-chan error-chan]} (flow/start g)]
    [g report-chan error-chan]))

(defn lift1 [f] (flow/process (flow/lift1->step f)))

;; ---------------------------------------------------------------------------------------------------------------

(defn linear []
  (let [[g report error] (mk {:procs {:a {:proc (lift1 inc)}
                                      :b {:proc (lift1 #(* 2 %))}
                                      :c {:proc (flow/process collector) :args {:sink (atom [])}}}
                              :conns [[[:a :out] [:b :in]] [[:b :out] [:c :in]]]})]
    (flow/resume g)
    @(flow/inject g [:a :in] [1 2 3 4 5])
    (let [r (rd-n report 5)]
      (flow/stop g)
      {:report r :after-stop-error (rd error) :after-stop-report (rd report)})))

(defn fan-out-fan-in []
  (let [sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 inc)}
                                      :b {:proc (lift1 #(* 10 %))}
                                      :c {:proc (lift1 #(* 100 %))}
                                      :d {:proc (flow/process collector) :args {:sink sink}}}
                              :conns [[[:a :out] [:b :in]] [[:a :out] [:c :in]]
                                      [[:b :out] [:d :in]] [[:c :out] [:d :in]]]})]
    (flow/resume g)
    @(flow/inject g [:a :in] [1 2 3])
    (let [r (rd-n report 6)]
      (flow/stop g)
      {:sorted (sort r) :per-source-order [(filter #(< % 1000) (sort r))]
       ;; order within one path is kept: 20 30 40 come in this order, 200 300 400 too
       :order-b (filterv #{20 30 40} r) :order-c (filterv #{200 300 400} r)})))

(defn many-to-one-in []
  ;; two outs of two procs to the same in of a third: every message arrives once
  (let [sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 identity)}
                                      :b {:proc (lift1 identity)}
                                      :c {:proc (flow/process collector) :args {:sink sink}}}
                              :conns [[[:a :out] [:c :in]] [[:b :out] [:c :in]]]})]
    (flow/resume g)
    @(flow/inject g [:a :in] [:a1 :a2])
    @(flow/inject g [:b :in] [:b1 :b2])
    (let [r (rd-n report 4)]
      (flow/stop g)
      {:sorted (sort r) :a (filterv #{:a1 :a2} r) :b (filterv #{:b1 :b2} r)})))

(defn transitions []
  (let [log (atom []) stopped (promise)
        step (flow/map->step
              {:describe (fn [] {:params {:log "" :stopped ""} :ins {:in ""}})
               :init (fn [{:keys [log stopped]}] {:log log :stopped stopped :n 0})
               :transition (fn [s t]
                             (swap! (:log s) conj t)
                             (when (= t ::fk/stop) (deliver (:stopped s) true))
                             (update s :n inc))
               :transform (fn [s _ m] [(update s :n inc) {::fk/report [[m (:n s)]]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:log log :stopped stopped}}}
                              :conns []})]
    (let [paused-ping (-> (flow/ping-proc g :p :timeout-ms 5000) norm-ping (update ::fk/state select-keys [:n]))]
      (flow/resume g)
      ;; a ping is answered after the commands before it: it tells that the transition is done
      (flow/ping-proc g :p :timeout-ms 5000)
      @(flow/inject g [:p :in] [1])
      (let [r1 (rd report)]
        (flow/pause g)
        (flow/resume g)
        (flow/ping-proc g :p :timeout-ms 5000)
        @(flow/inject g [:p :in] [2])
        (let [r2 (rd report)
              before-stop @log]
          (flow/stop g)
          {:paused-ping paused-ping :r1 r1 :r2 r2 :log-before-stop before-stop
           :stopped (deref stopped T :no-stop) :log @log})))))

(defn pause-resume []
  (let [sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 inc)}
                                      :b {:proc (flow/process collector) :args {:sink sink}}}
                              :conns [[[:a :out] [:b :in]]]})]
    (let [s0 (mapv #(::fk/status (flow/ping-proc g % :timeout-ms 5000)) [:a :b])
          _ (flow/resume g)
          s1 (mapv #(::fk/status (flow/ping-proc g % :timeout-ms 5000)) [:a :b])
          _ (flow/pause-proc g :b)
          s2 (mapv #(::fk/status (flow/ping-proc g % :timeout-ms 5000)) [:a :b])
          _ @(flow/inject g [:a :in] [1 2 3])
          ;; b is paused: nothing in the report; a did its work
          none (rd report 200)
          pb (flow/ping-proc g :b :timeout-ms 5000)
          _ (flow/resume-proc g :b)
          got (rd-n report 3)
          _ (flow/pause g)
          s3 (mapv #(::fk/status (flow/ping-proc g % :timeout-ms 5000)) [:a :b])
          _ @(flow/inject g [:a :in] [10])
          none2 (rd report 200)
          _ (flow/resume g)
          got2 (rd report)]
      (flow/stop g)
      {:s0 s0 :s1 s1 :s2 s2 :none none :b-paused-count (::fk/count pb) :b-paused-status (::fk/status pb)
       :got got :s3 s3 :none2 none2 :got2 got2})))

(defn ping-shapes []
  (let [step (flow/map->step {:describe (fn [] {:ins {:in "" :in2 ""} :outs {:out ""} :ping-map-fn #(select-keys % [:n])})
                              :init (fn [_] {:n 7 :secret 1})
                              :transform (fn [s _ m] [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)} :q {:proc (lift1 inc)}}
                              :conns [[[:p :out] [:q :in]]]})]
    (flow/resume g)
    (let [all (flow/ping g :timeout-ms 5000)
          one (flow/ping-proc g :p :timeout-ms 5000)
          unknown (flow/ping-proc g :nope :timeout-ms 100)
          short (flow/ping g :timeout-ms 1)] ;; not compared: which procs answer within 1 ms depends on timing
      (flow/stop g)
      {:all-keys (set (keys all))
       :all (update-vals all norm-ping)
       :one (norm-ping one)
       :unknown unknown
       :short-answered :not-compared})))

(defn ping-reply-on-report-chan []
  ;; ping replies do not go to the report channel
  (let [[g report error] (mk {:procs {:q {:proc (lift1 inc)}} :conns []})]
    (flow/resume g)
    (flow/ping g :timeout-ms 5000)
    (let [r (rd report 150)]
      (flow/stop g)
      {:report r})))

(defn inject-cases []
  (let [sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 inc)}
                                      :b {:proc (flow/process collector) :args {:sink sink}}
                                      :lonely {:proc (lift1 inc)}}
                              :conns [[[:a :out] [:b :in]]]})]
    (flow/resume g)
    (let [fut (flow/inject g [:a :in] [1 2])
          ;; inject to an OUT coord: writes to the channel behind the out (here the in of b)
          fut2 (flow/inject g [:a :out] [100])
          r (rd-n report 3)
          unknown-pid (try (flow/inject g [:zzz :in] [1]) :no-throw (catch Throwable t (nx t)))
          unknown-id (try (flow/inject g [:a :zzz] [1]) :no-throw (catch Throwable t (nx t)))
          ;; out with no connection: the coord resolves to nil -> the future fails
          lonely-out (try (deref (flow/inject g [:lonely :out] [1]) T :timeout) (catch Throwable t (nx t)))
          nil-msg (try (deref (flow/inject g [:a :in] [nil]) T :timeout) (catch Throwable t (nx t)))
          cast-ok (deref (flow/inject g [::fk/cast :some-signal] [1 2]) T :timeout)]
      (flow/stop g)
      {:done [(deref fut T :t) (deref fut2 T :t)] :r (sort r) :future? (future? fut)
       :unknown-pid unknown-pid :unknown-id unknown-id :lonely-out lonely-out :nil-msg nil-msg :cast-ok cast-ok})))

(defn inject-before-start []
  (let [g (flow/create-flow {:procs {:a {:proc (lift1 inc)}} :conns []})]
    {:inject (try (flow/inject g [:a :in] [1]) :no-throw (catch Throwable t (nx t)))
     :pause (try (flow/pause g) :no-throw (catch Throwable t (nx t)))
     :resume (try (flow/resume g) :no-throw (catch Throwable t (nx t)))
     :ping (try (flow/ping g :timeout-ms 5000) :no-throw (catch Throwable t (nx t)))
     :ping-proc (try (flow/ping-proc g :a :timeout-ms 5000) :no-throw (catch Throwable t (nx t)))
     :pause-proc (try (flow/pause-proc g :a) :no-throw (catch Throwable t (nx t)))
     :stop (flow/stop g)}))

(defn step-throws []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {:seen []})
               :transition (fn [s t] (if (and (= t ::fk/pause) (:boom-pause s)) (throw (ex-info "pause-boom" {})) s))
               :transform (fn [s _ m]
                            (if (= m :boom)
                              (throw (ex-info "boom" {:m m}))
                              [(update s :seen conj m) {::fk/report [(:seen s)]}]))})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1 :boom 2])
    (let [r1 (rd report)
          e (norm-err (rd error))
          r2 (rd report)
          ping (norm-ping (flow/ping-proc g :p :timeout-ms 5000))]
      (flow/stop g)
      {:r1 r1 :error e :r2 r2 :ping-status (::fk/status ping) :ping-count (::fk/count ping)})))

(defn transition-throws []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {:n 0})
               :transition (fn [s t] (if (= t ::fk/pause) (throw (ex-info "pause-boom" {:t t})) (update s :n inc)))
               :transform (fn [s _ m] [s {::fk/report [[m (:n s)]]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    (flow/pause g)
    (let [e (norm-err (rd error))
          ;; the proc is still alive, status unchanged (running), state unchanged
          ping (norm-ping (flow/ping-proc g :p :timeout-ms 5000))]
      @(flow/inject g [:p :in] [1])
      (let [r (rd report)]
        (flow/stop g)
        {:error e :status (::fk/status ping) :r r}))))

(defn bad-outputs []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :outs {:out "" :unconnected ""}})
               :init (fn [_] {:n 0})
               :transform (fn [s _ m]
                            (let [s' (update s :n inc)]
                              (case m
                                :unknown-out [s' {:nope [1]}]
                                :unconnected [s' {:unconnected [1]}]
                                :nil-msg [s' {::fk/report [nil]}]
                                :nil-state nil
                                :not-vector 42
                                :msgs-not-seq [s' {::fk/report 5}]
                                :outs-not-map [s' [1 2]]
                                :empty [s' {}]
                                :nil-out [s' nil]
                                :nil-msgs [s' {::fk/report nil}]
                                :ok [s' {::fk/report [[:ok (:n s')]]}]
                                :partial [s' {::fk/report [1 2 nil 3]}]
                                :coord-out [s' {[:p :in] [:self]}]
                                :list-of-msgs [s' {::fk/report '(7 8)}]
                                :report-set [s' {::fk/report #{9}}]
                                :str-msgs [s' {::fk/report "ab"}]
                                [s' nil])))})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    (let [probe (fn [m]
                  ;; the :ok message after the probe marks the end of what the probe did
                  @(flow/inject g [:p :in] [m :ok])
                  (let [reps (loop [acc []]
                               (let [x (rd report)]
                                 (if (or (= x :timeout) (and (vector? x) (= :ok (first x)))) (conj acc x) (recur (conj acc x)))))]
                    {:m m :reports reps :err (norm-err (rd error 50))}))
          results (mapv probe [:unknown-out :unconnected :nil-msg :not-vector :msgs-not-seq
                               :outs-not-map :empty :nil-out :nil-msgs :partial :coord-out :list-of-msgs
                               :report-set :str-msgs :nil-state])]
      (flow/stop g)
      results)))

(defn init-and-describe-calls []
  (let [calls (atom {:describe 0 :init 0 :transition [] :init-args []})
        step (fn
               ([] (swap! calls update :describe inc) {:params {:x "x"} :ins {:in ""} :outs {:out ""}})
               ([args] (swap! calls #(-> % (update :init inc) (update :init-args conj args))) {:s 1})
               ([s t] (swap! calls update :transition conj t) s)
               ([s _ m] [s nil]))
        proc (flow/process step)
        after-process (:describe @calls)
        g (flow/create-flow {:procs {:p {:proc proc :args {:x 42}}} :conns []})
        after-create (:describe @calls)
        _ (flow/start g)
        after-start @calls
        _ (flow/resume g)
        _ (flow/ping g :timeout-ms 5000)
        _ (flow/stop g)
        _ (await-pred calls #(some #{::fk/stop} (:transition %)) T)
        stopped @calls
        ;; start again: init is called again, the launcher is reused
        _ (flow/start g)
        _ (await-pred calls #(= 2 (:init %)) T)
        _ (flow/stop g)
        again @calls]
    {:describe-after-process after-process :describe-after-create after-create
     :after-start (select-keys after-start [:describe :init :init-args])
     :transitions (:transition stopped)
     :init-after-restart (:init again) :init-args-after-restart (:init-args again)}))

(defn stop-start-cycle []
  (let [[g report error] (mk {:procs {:a {:proc (lift1 inc)}} :conns []})
        again-start (flow/start g)
        same-chans? [(= report (:report-chan again-start)) (= error (:error-chan again-start))]
        already (:already-running again-start)
        stop1 (flow/stop g)
        stop2 (flow/stop g)
        closed [(rd report) (rd error)]
        put-closed (a/>!! report 1)
        {r2 :report-chan e2 :error-chan} (flow/start g)
        _ (flow/resume g)
        _ @(flow/inject g [:a :in] [1])
        ping2 (some? (flow/ping-proc g :a :timeout-ms 5000))
        stop3 (flow/stop g)]
    {:same-chans same-chans? :already already :stop [stop1 stop2 stop3] :closed closed
     :put-closed put-closed :new-chans-differ [(not= report r2) (not= error e2)] :ping-after-restart ping2
     :pause-after-stop (try (flow/pause g) (catch Throwable t (nx t)))}))

(defn in-ports-source []
  ;; a source that reads a user channel (the non-input form), and a sink out-port
  (let [src (a/chan 5) snk (a/chan 5) closed? (promise)
        step (flow/map->step
              {:describe (fn [] {:params {:src "" :snk ""} :outs {:out ""}})
               :init (fn [{:keys [src snk]}] {::fk/in-ports {:src src} ::fk/out-ports {:snk snk}})
               :transition (fn [s t] (when (= t ::fk/stop) (a/close! (-> s ::fk/in-ports :src)) (deliver closed? true)) s)
               :transform (fn [s cid m] [s {::fk/report [[cid m]] :snk [m]}])})
        [g report error] (mk {:procs {:s {:proc (flow/process step) :args {:src src :snk snk}}} :conns []})]
    (flow/resume g)
    (a/>!! src 1) (a/>!! src 2)
    (let [r (rd-n report 2)
          o (rd-n snk 2)]
      (a/close! src)
      ;; the closed input: transform is called once with a nil msg
      (let [r3 (rd report)]
        (flow/stop g)
        {:report r :snk o :on-close r3 :stop-transition (deref closed? T :no)}))))

(defn input-filter []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:x "" :y ""}})
               :init (fn [_] {:got []})
               :transform (fn [s cid m]
                            (let [s (update s :got conj [cid m])]
                              ;; after an x, only y is read
                              [(assoc s ::fk/input-filter (if (= cid :x) #{:y} (constantly true)))
                               {::fk/report [(:got s)]}]))})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :x] [1 2])
    (let [r1 (rd report)
          none (rd report 200)]
      @(flow/inject g [:p :y] [:y1])
      (let [r2 (rd report)
            r3 (rd report)]
        (flow/stop g)
        {:r1 r1 :none none :r2 r2 :r3 r3}))))

(defn signals []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :signal-select #{:tick}})
               :init (fn [_] {})
               :transform (fn [s cid m] [s {::fk/report [[cid m]]}])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)} :q {:proc (flow/process step)}} :conns []})]
    (flow/resume g)
    @(flow/inject g [::fk/cast :tick] [1 2])
    @(flow/inject g [::fk/cast :other] [3])
    (let [r (sort-by pr-str (rd-n report 4))
          none (rd report 150)]
      (flow/stop g)
      {:r r :none none})))

(defn lifts []
  (let [sink (atom [])
        [g report error] (mk {:procs {:star {:proc (flow/process (flow/lift*->step (fn [x] [x x (* 10 x)])))}
                                      :one {:proc (lift1 (fn [x] (when (even? x) x)))}
                                      :s1 {:proc (flow/process collector) :args {:sink sink}}
                                      :s2 {:proc (flow/process collector) :args {:sink sink}}}
                              :conns [[[:star :out] [:s1 :in]] [[:one :out] [:s2 :in]]]})]
    (flow/resume g)
    @(flow/inject g [:star :in] [1])
    @(flow/inject g [:one :in] [1 2 3 4])
    (let [r (sort (rd-n report 5))
          none (rd report 150)
          desc (flow/lift1->step identity)]
      (flow/stop g)
      {:r r :none none
       :describe-keys [(set (keys (desc))) (set (keys ((flow/lift*->step identity))))]
       :init-nil (desc nil) :trans-nil (desc nil :x)})))

(defn map->step-checks []
  (let [s (flow/map->step {:describe (fn [] {:ins {:in ""}}) :transform (fn [s i m] [s {:o [m]}])})]
    {:desc (s) :init (s {:a 1}) :trans (s :state ::fk/pause) :xform (s :st :in 5)
     :missing (try (flow/map->step {:describe (fn [] {})}) (catch Throwable t (nx t)))}))

(defn futurize-cases []
  (let [f (flow/futurize (fn [x y] (+ x y)) :exec :io)
        fut (f 1 2)
        thread-info (atom nil)
        ft (flow/futurize (fn [] (reset! thread-info :ran) 5))
        ex (flow/futurize (fn [] (throw (ex-info "bad" {:x 1}))) :exec :compute)
        exec (java.util.concurrent.Executors/newSingleThreadExecutor)
        fe (flow/futurize (fn [] (.getName (Thread/currentThread))) :exec exec)]
    (try
      {:value (deref fut T :t) :future? (instance? java.util.concurrent.Future fut)
       :default-exec (deref (ft) T :t)
       :ex (try (deref (ex) T :t) (catch Throwable t (nx t)))
       :executor-given (string? (deref (fe) T :t))
       :bad-exec (try ((flow/futurize identity :exec :nonsense)) (catch Throwable t (class t)))}
      (finally (.shutdownNow exec)))))

(defn create-flow-errors []
  (let [p (lift1 inc)]
    {:invalid-conn-in (try (flow/create-flow {:procs {:a {:proc p}} :conns [[[:a :out] [:a :nope]]]}) (catch Throwable t (nx t)))
     :invalid-conn-pid (try (flow/create-flow {:procs {:a {:proc p}} :conns [[[:zz :out] [:a :in]]]}) (catch Throwable t (nx t)))
     :shared-ids (try (flow/create-flow {:procs {:a {:proc (flow/process (flow/map->step {:describe (fn [] {:ins {:x ""} :outs {:x ""}})
                                                                                          :transform (fn [s _ _] [s nil])}))}}
                                         :conns []})
                      (catch Throwable t (nx t)))
     :bad-exec (try (flow/create-flow {:procs {:a {:proc p}} :conns [] :io-exec :not-an-executor})
                    (catch Throwable t (class t)))
     :params-without-args (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/map->step {:describe (fn [] {:params {:x ""}})
                                                                                                      :init (fn [_] {})
                                                                                                      :transform (fn [s _ _] [s nil])}))}}
                                                     :conns []})]
                            (try (flow/start g) :started (catch Throwable t (nx t))))
     :init-throws (let [g (flow/create-flow {:procs {:a {:proc (flow/process (flow/map->step {:describe (fn [] {})
                                                                                              :init (fn [_] (throw (ex-info "init-boom" {})))
                                                                                              :transform (fn [s _ _] [s nil])}))}}
                                             :conns []})]
                    [(try (flow/start g) :started (catch Throwable t (nx t)))
                     ;; the failed start leaves the flow stopped
                     (try (flow/pause g) (catch Throwable t (nx t)))])
     :describe-throws (try (flow/process (fn ([] (throw (ex-info "describe-boom" {})))))
                           (catch Throwable t (nx t)))}))

(defn backpressure-fixed []
  (let [gate (promise) seen (atom [])
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] (deref gate T nil) (swap! seen conj m) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n 1}}}} :conns []})]
    (flow/resume g)
    (let [accepted (loop [i 0]
                     (if (< i 10)
                       (let [r (deref (flow/inject g [:p :in] [i]) 1500 ::blocked)]
                         (if (= r ::blocked) i (recur (inc i))))
                       i))
          ;; the blocked inject is still pending; release the consumer
          _ (deliver gate true)
          ;; (the blocked message `accepted` was written by the pending inject; send the rest)
          _ (doseq [i (range (inc accepted) 6)] @(flow/inject g [:p :in] [i]))
          _ (await-count seen 6 T)
          out @seen]
      (flow/stop g)
      {:accepted-before-block accepted :seen out})))

(defn backpressure-sliding []
  (let [gate (promise) seen (atom [])
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] (deref gate T nil) (swap! seen conj m) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n (a/sliding-buffer 2)}}}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [0])
    ;; give the proc the time to take 0 (it blocks in the step on the gate)
    (let [ping (flow/ping-proc g :p :timeout-ms 5000)]
      (doseq [i (range 1 10)] (deref (flow/inject g [:p :in] [i]) 1000 ::blocked))
      (deliver gate true)
      (await-count seen 3 T)
      (let [out @seen]
        (flow/stop g)
        {:seen out}))))

(defn backpressure-dropping []
  (let [gate (promise) seen (atom [])
        step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] (deref gate T nil) (swap! seen conj m) [s nil])})
        [g report error] (mk {:procs {:p {:proc (flow/process step) :chan-opts {:in {:buf-or-n (a/dropping-buffer 2)}}}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [0])
    (let [ping (flow/ping-proc g :p :timeout-ms 5000)]
      (doseq [i (range 1 10)] (deref (flow/inject g [:p :in] [i]) 1000 ::blocked))
      (deliver gate true)
      (await-count seen 3 T)
      (let [out @seen]
        (flow/stop g)
        {:seen out}))))

(defn chan-opts-xform []
  (let [sink (atom [])
        [g report error] (mk {:procs {:a {:proc (lift1 identity)
                                          :chan-opts {:out {:buf-or-n 5 :xform (comp (map #(if (= % 3) (throw (ex-info "xf-boom" {})) %))
                                                                                     (map inc))}}}
                                      :b {:proc (flow/process collector) :args {:sink sink}}
                                      :c {:proc (flow/process collector) :args {:sink sink}}}
                              ;; two taps: the out chan is its own channel (a mult), so the xform applies
                              :conns [[[:a :out] [:b :in]] [[:a :out] [:c :in]]]})]
    (flow/resume g)
    @(flow/inject g [:a :in] [1 2 3 4])
    (let [r (sort (rd-n report 6))
          e (norm-err (rd error))
          more (rd report 150)]
      (flow/stop g)
      {:r r :error e :more more})))

(defn compute-workload []
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :workload :compute})
               :init (fn [_] {})
               :transform (fn [s _ m]
                            (case m
                              :slow (do (deref (promise) 1000 nil) [s {::fk/report [:slow-done]}])
                              :boom (throw (ex-info "compute-boom" {}))
                              [s {::fk/report [m]}]))})
        [g report error] (mk {:procs {:p {:proc (flow/process step {:compute-timeout-ms 200})}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1 :boom :slow 2])
    (let [r1 (rd report)
          e1 (norm-err (rd error))
          e2 (norm-err (rd error))
          r2 (rd report)]
      (flow/stop g)
      {:r1 r1 :e1 e1 :e2 (update e2 ::fk/ex #(select-keys % [:class :msg])) :r2 r2})))

(defn workload-option []
  ;; the option overrides the describe; all of them just work
  (let [mkp (fn [w] (flow/process (flow/lift1->step inc) {:workload w}))
        [g report error] (mk {:procs {:a {:proc (mkp :io)} :b {:proc (mkp :compute)} :c {:proc (mkp :mixed)}
                                      :s {:proc (flow/process collector) :args {:sink (atom [])}}}
                              :conns [[[:a :out] [:b :in]] [[:b :out] [:c :in]] [[:c :out] [:s :in]]]})]
    (flow/resume g)
    @(flow/inject g [:a :in] [1 2 3])
    (let [r (rd-n report 3)]
      (flow/stop g)
      r)))

(defn datafy-shapes []
  (let [step (flow/map->step {:describe (fn [] {:ins {:in "i"} :outs {:out "o"}}) :transform (fn [s _ _] [s nil])})
        p (flow/process step)
        g (flow/create-flow {:procs {:a {:proc p} :b {:proc (lift1 inc)}} :conns [[[:a :out] [:b :in]]]})
        before (d/datafy g)
        _ (flow/start g)
        during (d/datafy g)
        _ (flow/stop g)
        after (d/datafy g)
        strip (fn [m] (-> m (update :procs update-vals #(update % :proc (fn [x] (when x :proc))))))]
    {:proc-keys (set (keys (d/datafy p)))
     :proc-desc (:desc (d/datafy p))
     :before-keys (set (keys before)) :before-chans (:chans before) :conns (:conns before)
     :execs (:execs before)
     :during-chans-keys (set (keys (:chans during)))
     :during-ins (set (keys (:ins (:chans during))))
     :during-outs (set (keys (:outs (:chans during))))
     :during-ins-shape (update-vals (:ins (:chans during)) #(dissoc % :buffer :take-count :put-count))
     :after-chans (:chans after)
     :proc-names (-> before :procs keys set)}))

(defn mult-semantics []
  ;; a slow tap slows the fast one: the mult waits for all taps (buffer 1 on both)
  (let [gate (promise) fast (atom []) slow (atom [])
        mkstep (fn [sink g]
                 (flow/map->step {:describe (fn [] {:ins {:in ""}})
                                  :init (fn [_] {})
                                  :transform (fn [s _ m] (when g (deref g T nil)) (swap! sink conj m) [s nil])}))
        [g report error] (mk {:procs {:a {:proc (lift1 identity) :chan-opts {:out {:buf-or-n 1}}}
                                      :f {:proc (flow/process (mkstep fast nil)) :chan-opts {:in {:buf-or-n 1}}}
                                      :s {:proc (flow/process (mkstep slow gate)) :chan-opts {:in {:buf-or-n 1}}}}
                              :conns [[[:a :out] [:f :in]] [[:a :out] [:s :in]]]})]
    (flow/resume g)
    (doseq [i (range 3)] @(flow/inject g [:a :in] [i]))
    (let [;; the fast tap can get at most a few before the slow one blocks the mult
          _ (rd report 300)
          fast-before (count @fast)]
      (deliver gate true)
      (await-count slow 3 T)
      (let [r {:fast-before-release (<= fast-before 3) :fast @fast :slow @slow}]
        (flow/stop g)
        r))))

(defn self-feedback []
  ;; an out connected to an in of the same proc: needs a mult (own channel)
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""} :outs {:out ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] [s (if (< m 3) {:out [(inc m)]} {::fk/report [m]})])})
        [g report error] (mk {:procs {:p {:proc (flow/process step)}} :conns [[[:p :out] [:p :in]]]})]
    (flow/resume g)
    @(flow/inject g [:p :in] [0])
    (let [r (rd report)]
      (flow/stop g)
      r)))

(defn custom-launcher []
  ;; a ProcLauncher written with core.async channels: reads ::flow/control with alts!!, as the SPI doc asks
  (let [started (promise)
        launcher (reify clojure.core.async.flow.spi.ProcLauncher
                   (describe [_] {:ins {:in ""} :outs {:out ""}})
                   (start [_ {:keys [pid ins outs resolver]}]
                     (let [control (::fk/control ins) in (:in ins) report (::fk/report outs)]
                       (deliver started [pid (set (keys ins)) (set (keys outs)) (some? resolver)])
                       (a/thread
                         (loop [status :paused]
                           (let [[v c] (a/alts!! (if (= status :running) [control in] [control]) :priority true)]
                             (if (= c control)
                               (let [mine? (#{::fk/all pid} (::fk/to v)) cmd (::fk/command v)]
                                 (when (and mine? (= cmd ::fk/ping))
                                   (a/>!! (::fk/reply-chan v) {::fk/pid pid ::fk/status status}))
                                 (cond (not mine?) (recur status)
                                       (= cmd ::fk/stop) nil
                                       (= cmd ::fk/pause) (recur :paused)
                                       (= cmd ::fk/resume) (recur :running)
                                       :else (recur status)))
                               (if (nil? v) nil (do (a/>!! report [:got v]) (recur status))))))))))
        [g report error] (mk {:procs {:p {:proc launcher}} :conns []})]
    (flow/resume g)
    @(flow/inject g [:p :in] [1 2])
    (let [r (rd-n report 2)
          st (deref started T :no)
          ping (flow/ping-proc g :p :timeout-ms 5000)]
      (flow/stop g)
      {:r r :start-args (update st 1 sort) :ping ping})))

(def scenarios
  {:linear linear :fan-out-fan-in fan-out-fan-in :many-to-one-in many-to-one-in :transitions transitions
   :pause-resume pause-resume :ping-shapes ping-shapes :ping-reply-on-report-chan ping-reply-on-report-chan
   :inject-cases inject-cases :inject-before-start inject-before-start :step-throws step-throws
   :transition-throws transition-throws :bad-outputs bad-outputs :init-and-describe-calls init-and-describe-calls
   :stop-start-cycle stop-start-cycle :in-ports-source in-ports-source :input-filter input-filter :signals signals
   :lifts lifts :map->step-checks map->step-checks :futurize-cases futurize-cases
   :create-flow-errors create-flow-errors :backpressure-fixed backpressure-fixed
   :backpressure-sliding backpressure-sliding :backpressure-dropping backpressure-dropping
   :chan-opts-xform chan-opts-xform :compute-workload compute-workload :workload-option workload-option
   :datafy-shapes datafy-shapes :mult-semantics mult-semantics :self-feedback self-feedback
   :custom-launcher custom-launcher})
