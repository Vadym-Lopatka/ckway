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
  (:import [java.util.concurrent Future Executor TimeUnit CompletableFuture ExecutionException TimeoutException]
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
  "Runs (apply f args) as a coroutine in `scope` on `dispatcher`; returns a Future (a CompletableFuture) that
  completes with its return, or with its exception. Cancelling the future cancels the coroutine."
  [^CoroutineScope scope ^CoroutineDispatcher dispatcher f args]
  (let [cf (CompletableFuture.)
        ;; Kotlin: scope.launch(dispatcher) { cf.complete(f(*args)) }
        ^Job job (co/.launch scope :context dispatcher
                             :block (fn [_]
                                      (try (.complete cf (apply f args))
                                           (catch Throwable t
                                             (if (instance? CancellationException t)
                                               (do (.cancel cf true) (throw t))
                                               (.completeExceptionally cf t))))))]
    (.whenComplete cf (reify BiConsumer
                        (accept [_ _ _] (when (.isCancelled cf) (cancel-job! job)))))
    cf))

(defn futurize [f {:keys [exec]}]
  (fn [& args]
    ;; Kotlin: CoroutineScope(SupervisorJob() + dispatcher).launch { f(*args) }
    (let [scope (co/CoroutineScope (kc/.plus (co/SupervisorJob) (dispatcher-for exec)))
          fut (futurize-in scope (dispatcher-for exec) f args)]
      ;; the scope has one coroutine: when it is done, the job of the scope is done too
      (.whenComplete ^CompletableFuture fut (reify BiConsumer
                                              (accept [_ _ _] (cancel-job! (scope-job scope)))))
      fut)))

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
;; bridges for ports that are not ours (the ::flow/in-ports and ::flow/out-ports that the user made with core.async)

(defn- bridge-in
  "A port that a coroutine fills from a foreign core.async ReadPort (one message ahead, rendezvous)."
  [scope foreign]
  (let [bp (chan/port scope 0)]
    ;; Kotlin: scope.launch { for (m in foreign) bridge.send(m); bridge.close() }
    (co/.launch scope (fn [_]
                        (loop []
                          (let [v (chan/take!! foreign)]
                            (if (nil? v)
                              (chan/close! bp)
                              (when (chan/send! bp v) (recur)))))))
    bp))

(defn- bridge-out
  "A port that a coroutine empties into a foreign core.async WritePort."
  [scope foreign]
  (let [bp (chan/port scope 0)]
    ;; Kotlin: scope.launch { for (m in bridge) foreign.send(m) }
    (co/.launch scope (fn [_]
                        (loop []
                          (let [v (chan/recv! bp)]
                            (when-not (identical? v ::chan/closed)
                              (chan/put!! foreign v)
                              (recur))))))
    bp))

;; ---------------------------------------------------------------------------------------------------------------

(defn create-flow
  "see lib ns for docs"
  [{:keys [procs conns mixed-exec io-exec compute-exec]}]
  (let [lock (ReentrantLock.)
        chans (atom nil)
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
                             :scope scope :jobs jobs})
              {:report-chan report-chan :error-chan error-chan}))
          (finally (.unlock lock))))
      (stop [_]
        (.lock lock)
        (try
          (when-let [{:keys [report error scope jobs]} @chans]
            (send-command ::flow/stop ::flow/all)
            (chan/close! error)
            (chan/close! report)
            (reset! chans nil)
            ;; port addition: structured concurrency. The procs get the grace time to take the stop command (their
            ;; transition fn runs), then the scope is cancelled and joined: no coroutine outlives stop.
            (let [grace (Long/getLong "coflow.stop.grace.ms" 5000)]
              ;; Kotlin: withTimeoutOrNull(grace) { jobs.joinAll() }
              (co/withTimeoutOrNull grace (fn [_] (doseq [^Job j @jobs] (co/.join j)) true))
              (chan/join-pumps report 1000)
              (chan/join-pumps error 1000)
              ;; Kotlin: scope.coroutineContext.job.cancelAndJoin()
              (co/.cancelAndJoin (scope-job scope)))
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
  Returns [:control cmd] or [:sent ok]."
  [control outc msg]
  ;; fast path, the same choice as the biased select below, without suspending: control first, then the send
  (if-let [[cmd] (try-receive control)]
    [:control cmd]
    (case (chan/try-send1! outc msg)
      :ok [:sent true]
      :closed [:sent false]
      (try
        ;; Kotlin: select { control.onReceiveCatching { ... }; outc.onSend(msg) { ... } }   -- biased: control first
        (sel/select
         (fn [sb]
           (sel/.invoke sb (kch/onReceiveCatching (chan/->kotlin control))
                        (fn [r] [:control (kch/.getOrNull r)]))
           (sel/.invoke sb (kch/onSend (chan/->kotlin outc)) msg
                        (fn [_] (chan/sent! outc) [:sent true]))))
        (catch ClosedSendChannelException _ [:sent false])))))

(defn send-outputs [status state outputs outs resolver control handle-command transition cast]
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
                            [items done?] (chan/xf-apply outc (first msgs))
                            [nnstatus nnstate]
                            (loop [nstatus nstatus, nstate nstate, items (seq items)]
                              (if (or (nil? items) (= nstatus :exit))
                                [nstatus nstate]
                                (let [[kind v] (write-or-control control outc (first items))]
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
  "Waits for the next message on the control port, the casts port or one of the read-ins (in this order: the
  select is biased, so control wins). Returns [msg id] where id is ::control, ::casts or the cid of the input.
  A closed input gives [nil cid]."
  [control casts read-ins ipred]
  (let [ids (cond-> [[::control control]]
              casts (conj [::casts casts])
              true (into (comp (filter (fn [[cid _]] (ipred cid))) (map (fn [[cid c]] [cid c]))) read-ins))]
    ;; fast path: the first port (in this order) that has something now, without suspending
    (or (some (fn [[id c]] (when-let [[v] (try-receive c)] [v id])) ids)
        (sel/select
         (fn [sb]
           ;; Kotlin: select { control.onReceiveCatching { ... }; casts.onReceiveCatching { ... }; ins.forEach { ... } }
           (doseq [[id c] ids]
             (sel/.invoke sb (kch/onReceiveCatching (chan/->kotlin c))
                          (fn [r] (let [v (kch/.getOrNull r)] (when (some? v) (chan/received! c)) [v id])))))))))

(defn- wait-control
  "paused: only the control port is read. Returns the message, or ::closed."
  [control]
  (chan/recv! control))

(defn- compute-transform
  "workload :compute. Each call of the step fn runs as a coroutine on Dispatchers.Default; the loop waits at most
  timeout-ms. A failure comes as an ExecutionException and a timeout as a TimeoutException, as with the Future of
  the original."
  [^CoroutineScope fscope ^CoroutineDispatcher dispatcher step timeout-ms ^CoroutineScope loop-scope state a b]
  ;; Kotlin: val d = async(Dispatchers.Default) { step(state, a, b) }; select { d.onAwait; onTimeout(ms) }
  (let [^kotlinx.coroutines.Deferred d (co/.async fscope :context dispatcher :block (fn [_] (step state a b)))]
    (try
      (let [[kind v] (sel/select
                      (fn [sb]
                        (sel/.invoke sb (co/onAwait d) (fn [r] [:ok r]))
                        (sel/.onTimeout sb (long timeout-ms) (fn [] [:timeout nil]))))]
        (if (= kind :ok)
          v
          (do (cancel-job! d)
              (throw (TimeoutException.)))))
      (catch Throwable ex
        (cond
          (cancelled? loop-scope ex) (do (cancel-job! d) (throw ex))
          (or (instance? TimeoutException ex) (instance? ExecutionException ex)) (throw ex)
          :else (throw (ExecutionException. ex)))))))

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
                            (fn [loop-scope state a b]
                              (compute-transform scope cd step compute-timeout-ms loop-scope state a b)))
                          (fn [_ state a b] (step state a b)))
              ^CoroutineDispatcher exs (get-dispatcher resolver (if (= workload :mixed) :mixed :io))
              state (step args)
              ins (into (or ins {}) (::flow/in-ports state))
              outs (into (or outs {}) (::flow/out-ports state))
              control (::flow/control ins)
              casts (::flow/casts ins)
              ;; ports that are not ours (user chans from core.async) are read/written through a bridge coroutine
              mine (fn [bridge] (fn [c] (if (or (nil? c) (chan/port? c)) c (bridge scope c))))
              read-ins (into {} (map (fn [[k c]] [k ((mine bridge-in) c)])
                                     (dissoc ins ::flow/control ::flow/casts)))
              wouts (into {} (map (fn [[k c]] [k ((mine bridge-out) c)]) outs))
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
                            (let [msg (wait-control control)
                                  nstatus (if (identical? msg ::chan/closed) :exit (handle-command status msg))
                                  nstate (handle-transition step status nstatus state)]
                              [nstatus nstate count read-ins])
                            ;;:running
                            (let [ipred (or (::flow/input-filter state) identity)
                                  [msg cid] (select-input control casts read-ins ipred)]
                              (if (= cid ::control)
                                (let [nstatus (handle-command status msg)
                                      nstate (handle-transition step status nstatus state)]
                                  [nstatus nstate count read-ins])
                                (try
                                  (let [[nstate outputs]
                                        (if (= cid ::casts) ;;[sigid msg]
                                          (transform loop-scope state (first msg) (second msg))
                                          (transform loop-scope state cid msg))
                                        [nstatus nstate]
                                        (send-outputs status nstate outputs wouts resolver control handle-command step cast)]
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
