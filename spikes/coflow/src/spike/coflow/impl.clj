;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

;; Derived work: this file is a port of clojure.core.async.flow.impl (core.async 1.10.874-alpha3) to
;; kotlinx.coroutines. The structure, the function names and the logic of the flow are the original's.
;; The channels, the process loops, `alts!!`, the mults and the executors are rewritten on coroutines.

(ns spike.coflow.impl
  (:require [ckway.core :as kt]
            [clojure.core.async.flow :as-alias flow]
            [clojure.core.async.flow.spi :as spi]
            [clojure.core.async.flow.impl.graph :as graph]
            [spike.coflow.chan :as chan]
            [clojure.walk :as walk]
            [clojure.datafy :as datafy])
  (:import [java.util.concurrent Future FutureTask Callable Executor TimeUnit ExecutionException TimeoutException]
           [java.util.concurrent.atomic AtomicReference]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function BiConsumer]
           [kotlinx.coroutines CoroutineScope CoroutineDispatcher Job]
           [java.util.concurrent CancellationException]
           [kotlinx.coroutines.channels Channel ClosedSendChannelException]))

(set! *warn-on-reflection* true)

(kt/require '[kotlin.coroutines :as kc]
            '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.channels :as kch]
            '[kotlinx.coroutines.selects :as sel])

(defn datafy [x]
  (condp instance? x
    clojure.lang.Fn (-> x str symbol)
    Executor (str x)
    clojure.lang.Var (symbol x)
    (datafy/datafy x)))

;; ---------------------------------------------------------------------------------------------------------------
;; workloads -> dispatchers

(defn dispatcher-for
  "A workload (:mixed, :io, :compute) or a java.util.concurrent.Executor -> a CoroutineDispatcher.
  :mixed and :io -> Dispatchers.IO, :compute -> Dispatchers.Default."
  ^CoroutineDispatcher [exec]
  (cond
    (instance? CoroutineDispatcher exec) exec
    ;; Kotlin: executor.asCoroutineDispatcher()
    (instance? Executor exec) (co/.asCoroutineDispatcher ^Executor exec)
    :else (case exec
            ;; Kotlin: Dispatchers.IO
            (:mixed :io) (co/IO co/Dispatchers)
            ;; Kotlin: Dispatchers.Default
            :compute (co/Default co/Dispatchers)
            (throw (IllegalArgumentException. (str "no dispatcher for " (pr-str exec)))))))

(defn- new-scope
  "The scope of a running flow: a SupervisorJob (a failed proc does not cancel the others)."
  ^CoroutineScope []
  ;; Kotlin: CoroutineScope(SupervisorJob() + Dispatchers.Default)
  (co/CoroutineScope (kc/.plus (co/SupervisorJob) (co/Default co/Dispatchers))))

(defn- scope-job ^Job [^CoroutineScope scope] (co/job (.getCoroutineContext scope)))

(defn- cancel-job! [^Job j] (co/.cancel j))

(defn futurize-in
  "Runs (apply f args) as a coroutine in `scope` on `dispatcher`; returns a java.util.concurrent.FutureTask (the class
  of the original's futures) that completes with the return, or the exception, of f. The task is run by the coroutine,
  so `future-cancel` (Future.cancel(true)) interrupts the thread of the body, as with the original's executor thread,
  and the cancel also cancels the coroutine. `on-done` (optional, a fn of no args) runs when the task is done."
  ([scope dispatcher f args] (futurize-in scope dispatcher f args nil))
  ([^CoroutineScope scope ^CoroutineDispatcher dispatcher f args on-done]
   (let [job-ref (volatile! nil)
         ^FutureTask task (proxy [FutureTask] [(reify Callable (call [_] (apply f args)))]
                            (done []
                              (when (.isCancelled ^FutureTask this)
                                (when-let [^Job j @job-ref] (cancel-job! j)))
                              (when on-done (on-done))))
         ;; Kotlin: scope.launch(dispatcher) { task.run() }
         ^Job job (co/.launch scope :context dispatcher :block (fn [_] (.run task)))]
     (vreset! job-ref job)
     task)))

(defn futurize [f {:keys [exec]}]
  (fn [& args]
    ;; Kotlin: CoroutineScope(SupervisorJob() + dispatcher).launch { f(*args) }
    (let [d (dispatcher-for exec)
          scope (co/CoroutineScope (kc/.plus (co/SupervisorJob) d))]
      ;; the scope has one coroutine: when the task is done, the scope is done too
      (futurize-in scope d f args #(cancel-job! (scope-job scope))))))

(defn prep-proc [ret pid {:keys [proc, args, chan-opts] :or {chan-opts {}}}]
  (let [{:keys [ins outs signal-select]} (spi/describe proc)
        copts (fn [cs]
                (zipmap (keys cs) (map #(chan-opts %) (keys cs))))
        inopts (copts ins)
        outopts (copts outs)]
    (when (or (some (partial contains? inopts) (keys outopts))
              (some (partial contains? outopts) (keys inopts)))
      (throw (ex-info ":ins and :outs cannot share ids within a process"
                      {:pid pid :ins (keys inopts) :outs (keys outopts)})))
    (assoc ret pid {:pid pid :proc proc :ins inopts :outs outopts :args args :signal-select signal-select})))

;; ---------------------------------------------------------------------------------------------------------------
;; the scope and the dispatchers reach a proc through the resolver

(defprotocol Coroutines
  (flow-scope [r] "the CoroutineScope that owns the coroutines of the flow")
  (reaper-jobs [g] "the reaper Jobs of the runs that were stopped (a vector; see spike.coflow.ext/await-stopped)")
  (set-grace! [g ms] "the grace time (ms) after which the next stop cancels the run by force; nil: never (the default)")
  (get-dispatcher [r context] "the CoroutineDispatcher for :mixed, :io or :compute"))

;; ---------------------------------------------------------------------------------------------------------------
;; mult: every message of a source goes to every tap; the next one is taken when all taps have it

(defn- mult
  "Starts a coroutine that copies the messages of the port `src` to all taps. Returns the atom of the taps."
  [^CoroutineScope scope src]
  (let [taps (atom [])]
    ;; Kotlin: scope.launch { for (m in src) taps.forEach { launch { it.send(m) } } }
    (co/.launch scope
                (fn [s]
                  (loop []
                    (let [v (chan/recv! src)]
                      (when-not (identical? v ::chan/closed)
                        (let [ts @taps
                              ;; a tap with room gets the message at once; the others get a child coroutine
                              plan (mapv (fn [t]
                                           (let [[items done?] (chan/xf-apply t v)]
                                             (loop [items items]
                                               (if (empty? items)
                                                 [t nil done?]
                                                 (case (chan/try-send1! t (first items))
                                                   :ok (recur (rest items))
                                                   :full [t (vec items) done?]
                                                   :closed [t :closed done?])))))
                                         ts)
                              jobs (mapv (fn [[t rest-items done?]]
                                           (when (and (some? rest-items) (not= rest-items :closed))
                                             ;; Kotlin: async { rest.forEach { t.send(it) } }
                                             (co/.async s (fn [_]
                                                            (every? (fn [x] (chan/send1! t x)) rest-items)))))
                                         plan)
                              oks (mapv (fn [j] (if j (co/.await j) true)) jobs)]
                          (doseq [[[t ri done?] ok] (map vector plan oks)]
                            (when done? (chan/close! t)))
                          (let [dead (set (keep (fn [[[t ri _] ok]] (when (or (= ri :closed) (false? ok)) t))
                                                (map vector plan oks)))]
                            (when (seq dead)
                              (swap! taps (fn [ts] (vec (remove dead ts)))))))
                        (recur))))))
    taps))

(defn- tap! [taps tap-port] (swap! taps conj tap-port))

;; ---------------------------------------------------------------------------------------------------------------

(defn- children-of [^Job job]
  ;; Kotlin: job.children
  (iterator-seq (.iterator (co/children job))))

(defn- grace-ms
  "The grace time after which a stopped run is cancelled by force: the option of `ext/stop-with-grace`, or the system
  property coflow.stop.grace.ms. Default: none. The original never interrupts user code after stop, so the default is no
  forced cancel."
  [opt]
  (or opt (Long/getLong "coflow.stop.grace.ms")))

(defn- start-reaper
  "The cleanup of a stopped run. It runs in a coroutine that is NOT a child of the flow's scope (it is launched in
  GlobalScope, and it ends by itself). It waits until every proc has taken the stop command and ended (their transition
  fn runs), then closes the internal channels (so the pumps, mults and blocked injects of the run end), then waits until
  every other coroutine of the scope (a running transform, an inject) is done. No time limit, as in the original
  (user code is never interrupted). Only if a grace time is set (option or property) is the scope cancelled after it.
  So `stop` does not wait. Returns the Job of the reaper."
  ^Job [^CoroutineScope scope proc-jobs internals opt-grace]
  (let [job (scope-job scope)
        grace (grace-ms opt-grace)
        run-all (fn []
                  (doseq [^Job j proc-jobs] (co/.join j))
                  (doseq [p internals] (chan/close-quiet! p))
                  (doseq [^Job j (children-of job)] (co/.join j))
                  true)]
    ;; Kotlin: GlobalScope.launch(Dispatchers.Default) { withTimeoutOrNull(grace) { ... join ... }; close; job.cancelAndJoin() }
    (co/.launch co/GlobalScope :context (co/Default co/Dispatchers)
                :block (fn [_]
                         (if grace
                           (do (co/withTimeoutOrNull (long grace) (fn [_] (run-all)))
                               (doseq [p internals] (chan/close-quiet! p))
                               (co/.cancelAndJoin job))
                           (run-all))))))

(defn create-flow
  "see lib ns for docs"
  [{:keys [procs conns mixed-exec io-exec compute-exec]}]
  (let [lock (ReentrantLock.)
        chans (atom nil)
        reapers (atom [])
        grace (atom nil)
        execs {:mixed mixed-exec :io io-exec :compute compute-exec}
        _ (assert (every? #(or (nil? %) (instance? Executor %)) (vals execs))
                  "mixed-exe, io-exec and compute-exec must be Executors")
        pdescs (reduce-kv prep-proc {} procs)
        allopts (fn [iok] (into {} (mapcat #(map (fn [[k opts]] [[(:pid %) k] opts]) (iok %)) (vals pdescs))))
        inopts (allopts :ins)
        outopts (allopts :outs)
        set-conj (fnil conj #{})
        ;;out-coord->#{in-coords}
        conn-map (reduce (fn [ret [out in :as conn]]
                           (if (and (contains? outopts out)
                                    (contains? inopts in))
                             (update ret out set-conj in)
                             (throw (ex-info "invalid connection" {:conn conn}))))
                         {} conns)
        running-chans #(or (deref chans) (throw (Exception. "flow not running")))
        send-command (fn sc
                       ([cmap]
                        (let [{:keys [control]} (running-chans)]
                          (chan/send! control cmap)))
                       ([command to] (sc #::flow{:command command :to to})))
        handle-ping (fn [to timeout-ms]
                      (let [{:keys [scope]} (running-chans)
                            n (if (= to ::flow/all) (count procs) 1)
                            reply-chan (chan/port scope (count procs))
                            deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))
                            _ (send-command #::flow{:command ::flow/ping, :to to, :reply-chan reply-chan})
                            ret (loop [ret nil, got 0]
                                  (if (>= got n)
                                    ret
                                    (let [remaining (max 0 (quot (- deadline (System/nanoTime)) 1000000))
                                          ;; Kotlin: select { reply.onReceiveCatching { it }; onTimeout(remaining) { null } }
                                          m (sel/select
                                             (fn [sb]
                                               (sel/.invoke sb (kch/onReceiveCatching (chan/->kotlin reply-chan))
                                                            (fn [r] (kch/.getOrNull r)))
                                               (sel/.onTimeout sb remaining (fn [] nil))))]
                                      (if (some? m)
                                        (do (chan/received! reply-chan)
                                            (recur (assoc ret (::flow/pid m) m) (inc got)))
                                        ret))))]
                        (if (= to ::flow/all) ret (-> ret vals first))))]
    (reify
      clojure.core.protocols/Datafiable
      (datafy [_]
        (walk/postwalk datafy {:procs procs, :conns conns, :execs execs
                               :chans (select-keys @chans [:ins :outs :error :report])}))

      clojure.core.async.flow.impl.graph.Graph
      (start [_]
        (.lock lock)
        (try
          (if-let [{:keys [report error]} @chans]
            {:report-chan report :error-chan error :already-running true}
            (let [scope (new-scope)
                  jobs (atom [])
                  taps (atom [])
                  control-chan (chan/port scope 10)
                  control-taps (mult scope control-chan)
                  report-chan (chan/port scope (chan/sliding 100))
                  error-chan (chan/port scope (chan/sliding 100))
                  make-chan (fn [[[pid cid] {:keys [buf-or-n xform]}]]
                              (if xform
                                (chan/port
                                 scope
                                 buf-or-n xform
                                 (fn [ex]
                                   (chan/send! error-chan
                                               #::flow{:ex ex, :pid pid, :cid cid, :xform xform})
                                   nil))
                                (chan/port scope (or buf-or-n 10))))
                  in-chans (zipmap (keys inopts) (map make-chan inopts))
                  needs-mult? (fn [out ins]
                                (or (< 1 (count ins))
                                    (= (first out) (ffirst ins))))
                  out-chans (zipmap (keys outopts)
                                    (map (fn [[coord opts :as co]]
                                           (let [conns (conn-map coord)]
                                             (cond
                                               (empty? conns) nil
                                               (needs-mult? coord conns) (make-chan co)
                                               ;;direct connect 1:1
                                               :else (in-chans (first conns)))))
                                         outopts))
                  ;;pid->{:select :chan}
                  castees (reduce (fn [ret {:keys [pid signal-select]}]
                                    (assoc ret pid
                                           {:select signal-select
                                            :chan (chan/port scope (chan/sliding 100))}))
                                  {} (vals pdescs))
                  cast (fn [sigid msgs]
                         (doseq [{:keys [select chan]} (vals castees)]
                           (when (and select (select sigid))
                             (doseq [m msgs]
                               (chan/send! chan [sigid m])))))
                  ;;mults
                  _ (doseq [[out ins] conn-map]
                      (when (needs-mult? out ins)
                        (let [m (mult scope (out-chans out))]
                          (doseq [in ins]
                            (tap! m (in-chans in))))))
                  write-chan #(if-let [[_ c] (or (find in-chans %) (find out-chans %))]
                                c
                                (throw (ex-info "can't resolve channel with io-id" {:io-id %})))
                  resolver (reify
                             spi/Resolver
                             (get-write-chan [_ coord]
                               (write-chan coord))
                             (get-exec [_ context] (or (execs context) (co/.asExecutor (dispatcher-for context))))
                             Coroutines
                             (flow-scope [_] scope)
                             (get-dispatcher [_ context]
                               (dispatcher-for (or (execs context) context))))
                  start-proc
                  (fn [{:keys [pid proc args ins outs]}]
                    (try
                      (let [chan-map (fn [ks coll] (zipmap (keys ks) (map #(coll [pid %]) (keys ks))))
                            control-tap (chan/port scope 10)]
                        (tap! control-taps control-tap)
                        (swap! taps conj control-tap)
                        (let [job (spi/start proc {:pid pid :args (assoc args ::flow/pid pid)
                                                   :resolver resolver :cast cast
                                                   :ins (assoc (chan-map ins in-chans)
                                                               ::flow/control control-tap
                                                               ::flow/casts (-> pid castees :chan))
                                                   :outs (assoc (chan-map outs out-chans)
                                                                ::flow/error error-chan
                                                                ::flow/report report-chan)})]
                          (when (instance? Job job) (swap! jobs conj job))))
                      (catch Throwable ex
                        (try (chan/send! control-chan #::flow{:command ::flow/stop :to ::flow/all})
                             (catch Throwable _))
                        ;; the proc that did start must not outlive the failed start
                        (cancel-job! (scope-job scope))
                        (throw ex))))]
              (doseq [p (vals pdescs)]
                (start-proc p))
              ;;the only connection to a running flow is via channels
              (reset! chans {:control control-chan :resolver resolver :cast cast
                             :report report-chan, :error error-chan
                             :ins in-chans, :outs out-chans
                             :scope scope :jobs jobs
                             :internals #(vec (distinct (concat @taps (map :chan (vals castees)) (vals in-chans)
                                                                (filter some? (vals out-chans)))))})
              {:report-chan report-chan :error-chan error-chan}))
          (finally (.unlock lock))))
      (stop [_]
        (.lock lock)
        (try
          (when-let [{:keys [report error scope control jobs internals]} @chans]
            (send-command ::flow/stop ::flow/all)
            ;; the control channel is internal: closing it ends its mult coroutine (the buffered stop is still delivered)
            (chan/close! control)
            (chan/close! error)
            (chan/close! report)
            (reset! chans nil)
            ;; Same as the original: stop returns at once. Structured cleanup runs in a reaper coroutine that is not
            ;; part of the flow's scope.
            (swap! reapers #(conj (filterv (fn [^Job j] (.isActive j)) %) (start-reaper scope @jobs (internals) @grace)))
            true)
          (finally (.unlock lock))))
      (pause [_] (send-command ::flow/pause ::flow/all))
      (resume [_] (send-command ::flow/resume ::flow/all))
      (ping [_ timeout-ms] (handle-ping ::flow/all timeout-ms))
      (pause-proc [_ pid] (send-command ::flow/pause pid))
      (resume-proc [_ pid] (send-command ::flow/resume pid))
      (ping-proc [_ pid timeout-ms] (handle-ping pid timeout-ms))
      (inject [_ [target id :as coord] msgs]
        (let [{:keys [resolver cast scope]} (running-chans)
              do-io (if (= target ::flow/cast)
                      #(cast id msgs)
                      (let [chan (spi/get-write-chan resolver coord)]
                        ;; a port of ours is written directly; anything else (also nil: an out that is not
                        ;; connected) goes through the core.async protocol, so it fails as the original does
                        #(doseq [m msgs]
                           (if (chan/port? chan) (chan/send! chan m) (chan/put!! chan m)))))]
          (futurize-in scope (get-dispatcher resolver :io) do-io [])))

      ;; port addition: the scope of the running flow (nil when it is not running), for tests and tools
      Coroutines
      (flow-scope [_] (:scope @chans))
      (reaper-jobs [_] @reapers)
      (set-grace! [_ ms] (reset! grace ms))
      (get-dispatcher [_ context] (dispatcher-for (or (execs context) context))))))

(defn handle-command
  [pid pong status cmd]
  (let [transition #::flow{:stop :exit, :resume :running, :pause :paused}
        {::flow/keys [to command reply-chan]} cmd]
    (if (#{::flow/all pid} to)
      (do
        (when (= command ::flow/ping) (pong reply-chan))
        (or (transition command) status))
      status)))

(defn handle-transition
  "when transition, returns maybe new state"
  [transition status nstatus state]
  (if (not= status nstatus)
    (transition state (case nstatus
                        :exit ::flow/stop
                        :running ::flow/resume
                        :paused ::flow/pause))
    state))

(defn- try-receive
  "Kotlin: ch.tryReceive(). Returns [value] (a message), [nil] (closed), or nil (nothing now)."
  [port]
  (let [r (kch/.tryReceive (chan/->kotlin port))]
    (cond
      (kch/isSuccess r) (do (chan/received! port) [(kch/.getOrNull r)])
      (kch/isClosed r) [nil]
      :else nil)))

(defn- write-or-control
  "Writes msg to the port `outc`, but a control message has priority and is returned instead.
  Returns [:control cmd] or [:sent ok].
  alts? (a proc with ports of the user): ONE arbitration for control and the put, with core.async's commit protocol
  (`chan/alts-ops`), so the message goes into the user's channel only if control did not win. Otherwise (all ports are
  ours): a Kotlin select, control first."
  [control outc msg alts?]
  (if alts?
    (let [[r id] (chan/alts-ops [[:take control ::control] [:put outc msg ::put]])]
      (if (= id ::control) [:control r false] [:sent r]))
    ;; fast path, the same choice as the biased select below, without suspending: control first, then the send
    (if-let [[cmd] (try-receive control)]
      [:control cmd false]
      (case (chan/try-send1! outc msg)
        :ok [:sent true]
        :closed [:sent false]
        (try
          ;; Kotlin: select { control.onReceiveCatching { ... }; outc.onSend(msg) { ... } }   -- biased: control first
          (sel/select
           (fn [sb]
             (sel/.invoke sb (kch/onReceiveCatching (chan/->kotlin control))
                          (fn [r] [:control (kch/.getOrNull r) false]))
             (sel/.invoke sb (kch/onSend (chan/->kotlin outc)) msg
                          (fn [_] (chan/sent! outc) [:sent true]))))
          (catch ClosedSendChannelException _ [:sent false]))))))

(defn send-outputs [status state outputs outs resolver control handle-command transition cast alts?]
  (loop [nstatus status, nstate state, outputs (seq outputs)]
    (if (or (nil? outputs) (= nstatus :exit))
      [nstatus nstate]
      (let [[output msgs] (first outputs)]
        (if (and (vector? output) (= (first output) ::flow/cast))
          (do (cast (second output) msgs)
              (recur nstatus nstate (next outputs)))
          (if-let [outc (or (outs output) (spi/get-write-chan resolver output))]
            (let [[nstatus nstate]
                  (loop [nstatus nstatus, nstate nstate, msgs (seq msgs)]
                    (if (or (nil? msgs) (= nstatus :exit))
                      [nstatus nstate]
                      (let [;; core.async's alts!! asserts this before it puts (an AssertionError, not the
                            ;; IllegalArgumentException of `put!`): same text here
                            _ (when (nil? (first msgs))
                                (throw (AssertionError. "Assert failed: can't put nil on channel\n(some? (port 1))")))
                            ;; the transducer of the port (if any) runs before the write, as in core.async
                            [items done?] (if (chan/port? outc) (chan/xf-apply outc (first msgs)) [[(first msgs)] false])
                            [nnstatus nnstate]
                            (loop [nstatus nstatus, nstate nstate, items (seq items)]
                              (if (or (nil? items) (= nstatus :exit))
                                [nstatus nstate]
                                (let [[kind v] (write-or-control control outc (first items) alts?)]
                                  (if (= kind :control)
                                    (let [nnstatus (handle-command nstatus v)
                                          nnstate (handle-transition transition nstatus nnstatus nstate)]
                                      (recur nnstatus nnstate items))
                                    (recur nstatus nstate (next items))))))]
                        (when done? (chan/close! outc))
                        (recur nnstatus nnstate (next msgs)))))]
              (recur nstatus nstate (next outputs)))
            (recur nstatus nstate (next outputs))))))))

(defn- cancelled?
  "True when the exception comes from the cancellation of the proc coroutine (so it must go on, not be reported)."
  [^CoroutineScope scope ex]
  (or (instance? CancellationException ex)
      (not (co/isActive scope))))

(defn- select-input
  "Waits for the next message on the control port, the casts port or one of the read-ins (in this order: control wins),
  as `alts!! ... :priority true` does. Returns [msg id] where id is ::control, ::casts or the cid of the input.
  A closed input gives [nil cid].
  alts? (a proc with ports of the user): ONE arbitration over all of them with core.async's commit protocol
  (`chan/alts-ops`): a channel gives a message only if nothing else has won, so a user's channel loses no message. Otherwise
  (all ports are ours): tryReceive in priority order, then one Kotlin `select`."
  [control casts read-ins ipred alts?]
  (let [ids (cond-> [[::control control]]
              casts (conj [::casts casts])
              true (into (comp (filter (fn [[cid _]] (ipred cid))) (map (fn [[cid c]] [cid c]))) read-ins))]
    (if alts?
      (chan/alts-ops (mapv (fn [[id c]] [:take c id]) ids))
      (or (some (fn [[id c]] (when-let [[v] (try-receive c)] [v id])) ids)
          (sel/select
           (fn [sb]
             ;; Kotlin: select { control.onReceiveCatching { ... }; casts.onReceiveCatching { ... }; ins.forEach { ... } }
             (doseq [[id c] ids]
               (sel/.invoke sb (kch/onReceiveCatching (chan/->kotlin c))
                            (fn [r] (let [v (kch/.getOrNull r)] (when (some? v) (chan/received! c)) [v id]))))))))))

(defn- wait-control
  "paused: only the control port is read. Returns the message, or ::chan/closed."
  [control alts?]
  (if alts?
    (let [[v _] (chan/alts-ops [[:take control ::control]])]
      (if (nil? v) ::chan/closed v))
    (chan/recv! control)))

(defn- compute-transform
  "workload :compute. Each call of the step fn runs as a coroutine on Dispatchers.Default; the loop waits at most
  timeout-ms with `Future.get`, as the original does. A failure comes as an ExecutionException and a timeout as a
  TimeoutException. A transform that timed out is left running (it is not interrupted), as in the original."
  [^CoroutineScope fscope ^CoroutineDispatcher dispatcher step timeout-ms state a b]
  (let [^Future fut (futurize-in fscope dispatcher step [state a b])]
    (.get fut (long timeout-ms) TimeUnit/MILLISECONDS)))

(defn proc
  "see lib ns for docs"
  [step {:keys [workload compute-timeout-ms] :or {compute-timeout-ms 5000}}]
  (let [{:keys [params ins ping-map-fn] :or {ping-map-fn identity} :as desc} (step)
        workload (or workload (:workload desc) :mixed)]
    ;;(assert (or (not params) init) "must have :init if :params")
    (reify
      clojure.core.protocols/Datafiable
      (datafy [_]
        (let [{:keys [params ins outs]} desc]
          (walk/postwalk datafy {:step step :desc desc})))
      spi/ProcLauncher
      (describe [_] desc)
      (start [_ {:keys [pid args ins outs resolver cast]}]
        (assert (or (not params) args) "must provide :args if :params")
        (let [scope (flow-scope resolver)
              transform (if (= workload :compute)
                          (let [^CoroutineDispatcher cd (get-dispatcher resolver :compute)]
                            (fn [state a b]
                              (compute-transform scope cd step compute-timeout-ms state a b)))
                          (fn [state a b] (step state a b)))
              ^CoroutineDispatcher exs (get-dispatcher resolver (if (= workload :mixed) :mixed :io))
              state (step args)
              ins (into (or ins {}) (::flow/in-ports state))
              outs (into (or outs {}) (::flow/out-ports state))
              control (::flow/control ins)
              casts (::flow/casts ins)
              ;; the user's own channels (core.async) stay as they are: the loop reads and writes them itself
              read-ins (dissoc ins ::flow/control ::flow/casts)
              wouts outs
              ;; a proc that has a port of the user waits on ONE arbitration (core.async's commit protocol); a proc that
              ;; has only ports of ours uses the Kotlin select
              alts? (boolean (or (some #(and (some? %) (not (chan/port? %))) (vals read-ins))
                                 (some #(and (some? %) (not (chan/port? %))) (vals wouts))))
              run
              (fn [^CoroutineScope loop-scope]
                (loop [status :paused, state state, count 0, read-ins read-ins]
                  (let [pong (fn [c]
                               (let [pins (dissoc ins ::flow/control ::flow/casts)
                                     pouts (dissoc outs ::flow/error ::flow/report)]
                                 (chan/put!! c (assoc (walk/postwalk datafy
                                                                     #::flow{:pid pid, :status status
                                                                             :count count
                                                                             :ins pins :outs pouts})
                                                      ::flow/state (ping-map-fn state)))))
                        handle-command (partial handle-command pid pong)
                        [nstatus nstate count read-ins]
                        (try
                          (if (= status :paused)
                            (let [msg (wait-control control alts?)
                                  nstatus (if (identical? msg ::chan/closed) :exit (handle-command status msg))
                                  nstate (handle-transition step status nstatus state)]
                              [nstatus nstate count read-ins])
                            ;;:running
                            (let [ipred (or (::flow/input-filter state) identity)
                                  [msg cid] (select-input control casts read-ins ipred alts?)]
                              (if (= cid ::control)
                                (let [nstatus (handle-command status msg)
                                      nstate (handle-transition step status nstatus state)]
                                  [nstatus nstate count read-ins])
                                (try
                                  (let [[nstate outputs]
                                        (if (= cid ::casts) ;;[sigid msg]
                                          (transform state (first msg) (second msg))
                                          (transform state cid msg))
                                        [nstatus nstate]
                                        (send-outputs status nstate outputs wouts resolver control handle-command step cast alts?)]
                                    [nstatus nstate (inc count) (if (some? msg)
                                                                  read-ins
                                                                  (dissoc read-ins cid))])
                                  (catch Throwable ex
                                    (when (cancelled? loop-scope ex) (throw ex))
                                    (chan/send! (wouts ::flow/error)
                                                #::flow{:pid pid, :status status, :state state,
                                                        :count (inc count), :cid cid, :msg msg :op :step, :ex ex})
                                    [status state count read-ins])))))
                          (catch Throwable ex
                            (when (cancelled? loop-scope ex) (throw ex))
                            (chan/send! (wouts ::flow/error)
                                        #::flow{:pid pid, :status status, :state state, :count (inc count), :ex ex})
                            [status state count read-ins]))]
                    (when-not (= nstatus :exit) ;;fall out
                      (recur nstatus nstate (long count) read-ins)))))]
          ;; Kotlin: scope.launch(dispatcher) { run() }
          (co/.launch scope :context exs :block run))))))
