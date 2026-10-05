(ns spike.coflow.chan
  "Channels of the coflow port: a Kotlin `Channel` behind a small port type.

  A `port` is a kotlinx.coroutines `Channel` plus what is needed so that core.async code can use it:
  `clojure.core.async/<!!`, `<!` (in a go block), `alts!!`, `poll!`, `take!`, `put!`, `>!!` and `close!` work on it,
  because the port implements the core.async protocols (`ReadPort`, `WritePort`, `Channel`) and nothing else of core.async.

  The proc loops of the flow do not use these protocols. They read and write the Kotlin channel directly with a Kotlin
  `select` (see `spike.coflow.impl`). The protocols are the boundary for the user, and for a user `ProcLauncher`.

  Helpers for a caller who is not in a coroutine and does not use core.async:
  `take!!`, `put!!`, `poll!`, `close!`, `->seq`, `drain`, `->flow`.

  Rule for one port: read it either with the helpers/protocols (one pump coroutine serves the takers), or read the raw
  Kotlin channel (`->kotlin`, `->flow`) - not both at the same time."
  (:require [ckway.core :as kt]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.core.protocols :as cp]
            [clojure.datafy :as datafy])
  (:import [kotlinx.coroutines.channels Channel ClosedSendChannelException]
           [kotlinx.coroutines CoroutineScope]
           [java.util.concurrent ConcurrentLinkedQueue]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong AtomicReference]
           [java.util.concurrent.locks Lock ReentrantLock Condition]
           [clojure.lang IDeref]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.channels :as kch]
            '[kotlinx.coroutines.selects :as sel]
            '[kotlinx.coroutines.flow :as kflow])

;; ---------------------------------------------------------------------------------------------------------------
;; handlers: a core.async handler is a Lock plus the Handler protocol

(defn- box [v] (reify IDeref (deref [_] v)))

(defn handler
  "A core.async handler for a take or a put. `cb` is called with the result when the operation completes later.
  Returns [handler cancel!]; `cancel!` returns true if the handler was still active (so it will never fire)."
  [cb blockable?]
  (let [active (AtomicBoolean. true)
        l (ReentrantLock.)
        h (reify
            Lock
            (lock [_] (.lock l))
            (unlock [_] (.unlock l))
            impl/Handler
            (active? [_] (.get active))
            (blockable? [_] blockable?)
            (lock-id [_] 0)
            (commit [_] (.set active false) cb))]
    [h (fn cancel! []
         (.lock l)
         (try (.compareAndSet active true false)
              (finally (.unlock l))))]))

(defn- commit-now
  "With the handler locked: if it is still active commit it and return its callback, else nil."
  [^Lock h]
  (.lock h)
  (try (when (impl/active? h) (impl/commit h))
       (finally (.unlock h))))

;; ---------------------------------------------------------------------------------------------------------------
;; buffer description

(defn buffer-spec
  "buf-or-n as core.async reads it -> {:type SimpleName-symbol :capacity n :overflow :suspend|:drop-oldest|:drop-latest}.
  A number n is a fixed buffer of n (0: no buffer). A core.async buffer object gives its kind and capacity.
  Sliding buffer -> BufferOverflow.DROP_OLDEST, dropping buffer -> BufferOverflow.DROP_LATEST."
  [buf-or-n]
  (cond
    (map? buf-or-n) buf-or-n
    (nil? buf-or-n) {:type 'FixedBuffer :capacity 0 :overflow :suspend}
    (number? buf-or-n) {:type 'FixedBuffer :capacity (long buf-or-n) :overflow :suspend}
    :else
    (let [n (impl/capacity buf-or-n)
          nm (.getSimpleName (class buf-or-n))]
      (case nm
        "SlidingBuffer" {:type 'SlidingBuffer :capacity n :overflow :drop-oldest}
        "DroppingBuffer" {:type 'DroppingBuffer :capacity n :overflow :drop-latest}
        "PromiseBuffer" (throw (IllegalArgumentException. "a promise buffer is not supported by the coflow port"))
        {:type (symbol nm) :capacity n :overflow :suspend}))))

(defn sliding
  "A buffer spec for `port`: n items, the oldest is dropped when full (BufferOverflow.DROP_OLDEST).
  Like core.async/sliding-buffer, without core.async."
  [n] {:type 'SlidingBuffer :capacity n :overflow :drop-oldest})

(defn dropping
  "A buffer spec for `port`: n items, the new item is dropped when full (BufferOverflow.DROP_LATEST)."
  [n] {:type 'DroppingBuffer :capacity n :overflow :drop-latest})

(defn- new-kotlin-channel ^Channel [{:keys [capacity overflow]}]
  (let [cap (int capacity)]
    (case overflow
      ;; Kotlin: Channel<Any>(capacity)
      :suspend (kch/Channel cap)
      ;; Kotlin: Channel<Any>(capacity, BufferOverflow.DROP_OLDEST)
      :drop-oldest (kch/Channel cap :onBufferOverflow kch/BufferOverflow.DROP_OLDEST)
      ;; Kotlin: Channel<Any>(capacity, BufferOverflow.DROP_LATEST)
      :drop-latest (kch/Channel cap :onBufferOverflow kch/BufferOverflow.DROP_LATEST))))

;; ---------------------------------------------------------------------------------------------------------------
;; the port

(defmacro ^:private with-plock* [l & body]
  `(let [^ReentrantLock l# ~(with-meta l {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(declare ensure-pump! ensure-put-pump! xf-close! try-put-now received! has-active-taker? wake-takers! call-cb)

(deftype KPort [^Channel ch                        ;; the Kotlin channel
                spec                               ;; buffer-spec
                ^CoroutineScope scope              ;; for the pump coroutines
                ^AtomicLong cnt                    ;; items in the buffer, best effort, for datafy only (no decision reads it)
                xf-rf                              ;; the transducer step fn, or nil
                ^ReentrantLock xf-lock             ;; guards xf-rf (a transducer may be stateful)
                ;; takers (core.async side). Every change of (channel content, takers) is made under plock, with
                ;; non-suspending channel calls: a value leaves the channel only for a handler that commits.
                ^ReentrantLock plock               ;; guards takers, putters, and every receive of the pump
                ^ConcurrentLinkedQueue takers
                ^AtomicBoolean pumping
                ^Channel wake                      ;; conflated: a taker came, a value was put, or the port closed
                ^AtomicReference pump-job
                ;; putters (core.async side)
                ^ConcurrentLinkedQueue putters
                ^AtomicBoolean putting
                ^AtomicReference put-job
                ^Channel put-wake]                 ;; conflated: room may exist now (a receive happened), or the port closed
  impl/ReadPort
  (take! [p handler]
    (let [^Lock h handler]
      (.lock plock)
      (try
        (.lock h)
        (try
          (when (impl/active? h)
            ;; an older taker waits: queue behind it (order). Else take at once, under plock.
            (let [r (when-not (has-active-taker? takers)
                      ;; Kotlin: ch.tryReceive()
                      (kch/.tryReceive ch))]
              (cond
                (and r (kch/isSuccess r)) (do (impl/commit h) (received! p) (box (kch/.getOrNull r)))
                (and r (kch/isClosed r)) (do (impl/commit h) (box nil))
                :else (do (when (impl/blockable? h)
                            (.add takers h)
                            (ensure-pump! p)
                            (wake-takers! p)
                            ;; a rendezvous put that waits can hand its value to this taker now
                            (when (.get putting) (kch/.trySend put-wake true)))
                          nil))))
          (finally (.unlock h)))
        (finally (.unlock plock)))))

  impl/WritePort
  (put! [p val handler]
    (when (nil? val)
      (throw (IllegalArgumentException. "Can't put nil on channel")))
    (let [^Lock h handler
          ret (with-plock* plock
                (if (and (nil? xf-rf) (.isEmpty putters) (not (.get putting)))
                  (let [ret (try-put-now p h val)]
                    (if (identical? ret ::full)
                      (do (when (impl/blockable? h)
                            (.add putters [h val])
                            (ensure-put-pump! p))
                          nil)
                      ret))
                  (do (when (impl/blockable? h)
                        (.add putters [h val])
                        (ensure-put-pump! p))
                      nil)))]
      ;; a rendezvous hand-off: the taker's callback runs outside plock
      (when-let [tcb (nth ret 2 nil)] (call-cb (nth tcb 0) (nth tcb 1)))
      (first ret)))

  impl/Channel
  (closed? [_] (kch/isClosedForSend ch))
  (close! [this] (xf-close! this) nil)

  cp/Datafiable
  (datafy [this]
    (with-meta
      {:put-count (.size putters)
       :take-count (.size takers)
       :closed? (kch/isClosedForSend ch)
       :buffer {:type (:type spec)
                :count (long (max 0 (min (.get cnt) (:capacity spec))))
                :capacity (:capacity spec)}}
      {::datafy/obj this})))

(defmacro ^:private with-plock [p & body]
  `(let [^ReentrantLock l# (.-plock ~(with-meta p {:tag 'spike.coflow.chan.KPort}))]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn kotlin-channel
  "The raw kotlinx.coroutines Channel behind a port."
  ^Channel [^KPort p] (.-ch p))

(def ^{:doc "Alias of `kotlin-channel`."} ->kotlin kotlin-channel)

(defn pump-jobs
  "The Jobs of the pump coroutines of a port (the take pump and the put pump) that were started."
  [^KPort p]
  (keep identity [(.get ^AtomicReference (.-pump-job p)) (.get ^AtomicReference (.-put-job p))]))

(defn port?
  [x] (instance? KPort x))

(defn port
  "Makes a port. `scope` is the CoroutineScope that owns the pump coroutines of this port.
  buf-or-n: as in core.async (number, or a buffer object; sliding/dropping buffers become
  BufferOverflow.DROP_OLDEST / DROP_LATEST). xform (optional) and exh (optional, called with the exception, its
  non-nil result is added as an item) as in core.async/chan."
  ([scope buf-or-n] (port scope buf-or-n nil nil))
  ([scope buf-or-n xform exh]
   (let [buf-or-n (if (= buf-or-n 0) nil buf-or-n)
         _ (assert (if xform buf-or-n true) "buffer must be supplied when transducer is")
         spec (buffer-spec buf-or-n)
         ;; Kotlin: Channel<Any>(capacity, overflow)
         ch (new-kotlin-channel spec)
         rf (when xform
              (let [step (xform (fn ([acc] acc) ([acc x] (conj! acc x))))]
                (fn ([acc] (try (step acc) (catch Throwable t (if-some [r (when exh (exh t))] (conj! acc r) acc))))
                  ([acc x] (try (step acc x) (catch Throwable t (if-some [r (when exh (exh t))] (conj! acc r) acc)))))))]
     (KPort. ch spec scope (AtomicLong. 0) rf (ReentrantLock.)
             (ReentrantLock.) (ConcurrentLinkedQueue.) (AtomicBoolean. false)
             ;; Kotlin: Channel<Unit>(Channel.CONFLATED)
             (kch/Channel (kch/CONFLATED kch/Channel))
             (AtomicReference. nil)
             (ConcurrentLinkedQueue.) (AtomicBoolean. false) (AtomicReference. nil)
             ;; Kotlin: Channel<Unit>(Channel.CONFLATED)
             (kch/Channel (kch/CONFLATED kch/Channel))))))

;; --- writing from Clojure (internal use of the flow, and helpers) -------------------------------------------

(defn xf-apply
  "Runs the transducer of the port (if any) on a value. Returns [items done?]. Without a transducer: [[v] false]."
  [^KPort p v]
  (when (nil? v)
    (throw (IllegalArgumentException. "Can't put nil on channel")))
  (if-let [rf (.-xf-rf p)]
    (let [^ReentrantLock l (.-xf-lock p)]
      (.lock l)
      (try
        (let [r (rf (transient []) v)
              done? (reduced? r)
              items (persistent! (if done? @r r))]
          [items done?])
        (finally (.unlock l))))
    [[v] false]))

(defn- drop-mode? [^KPort p] (contains? #{:drop-oldest :drop-latest} (:overflow (.-spec p))))

(defn wake-takers!
  "Tells the pump of a port that something changed for the takers: a value was put, a taker came, or the port closed.
  A trySend on a conflated channel: cheap, never blocks."
  [^KPort p]
  ;; Kotlin: wake.trySend(Unit)
  (kch/.trySend (.-wake p) true))

(defn- count-in! [^KPort p]
  (let [^AtomicLong cnt (.-cnt p)]
    (if (drop-mode? p)
      (let [cap (:capacity (.-spec p))]
        (loop [] (let [n (.get cnt)] (when-not (.compareAndSet cnt n (min cap (inc n))) (recur)))))
      (.incrementAndGet cnt))))

(defn raw-try-send!
  "Sends one value to the Kotlin channel if there is room now. Returns :ok, :full or :closed.
  The Kotlin channel decides: a sliding port drops its oldest value (DROP_OLDEST), a dropping port the new one
  (DROP_LATEST), a fixed one answers :full. Wakes the pump of the takers after a put."
  [^KPort p v]
  (with-plock p
    ;; Kotlin: ch.trySend(v)
    (let [r (kch/.trySend (.-ch p) v)]
      (cond
        (kch/isSuccess r) (do (count-in! p) (wake-takers! p) :ok)
        (kch/isClosed r) :closed
        :else :full))))

(defn send1!
  "Sends one value to the Kotlin channel, waiting for room. Returns true, or false when the channel is closed."
  [^KPort p v]
  (if (drop-mode? p)
    (not= :closed (raw-try-send! p v))
    (try
      ;; Kotlin: ch.send(v)
      (kch/.send (.-ch p) v)
      (count-in! p)
      (wake-takers! p)
      true
      (catch ClosedSendChannelException _ false))))

(defn sent!
  "Tells the port that one item entered its buffer through a select (the proc side): counts it and wakes the pump of
  the takers. Call it right after the select returned with the send clause."
  [^KPort p]
  (count-in! p)
  (wake-takers! p))

(defn received!
  "Tells the port that one item left its buffer. Also wakes the pump of the pending puts: there may be room now."
  [^KPort p]
  (.decrementAndGet ^AtomicLong (.-cnt p))
  (when (.get ^AtomicBoolean (.-putting p))
    (kch/.trySend (.-put-wake p) true)))

(defn try-send1!
  "Sends one value to the Kotlin channel if there is room now. Returns :ok, :full or :closed."
  [^KPort p v]
  (raw-try-send! p v))

(defn send!
  "Blocking put of a value (all items of its transducer). Returns true, or false if the port is closed."
  [^KPort p v]
  (let [[items done?] (xf-apply p v)
        ok (reduce (fn [ok x] (if (send1! p x) ok false)) true items)]
    (when done? (xf-close! p))
    ok))

(defn xf-close!
  "close! of a port: flushes a transducer, then closes the Kotlin channel."
  [^KPort p]
  (when-let [rf (.-xf-rf p)]
    (let [^ReentrantLock l (.-xf-lock p)]
      (.lock l)
      (try
        (when-not (kch/isClosedForSend (.-ch p))
          (doseq [x (persistent! (rf (transient [])))] (send1! p x)))
        (finally (.unlock l)))))
  ;; Kotlin: ch.close()
  (kch/.close (.-ch p))
  ;; a pump that waits for a taker looks at the closed channel again
  (wake-takers! p)
  (kch/.trySend (.-put-wake p) true)
  nil)

;; --- the pump: serves the takers of a port ------------------------------------------------------------------

(defn- recv-or-closed
  "Waits for the next value of the Kotlin channel. Returns the value, or ::closed."
  [^Channel ch]
  ;; Kotlin: ch.receiveCatching().getOrNull()   (a suspend function that returns a value class: boxed by ckway since batch D)
  (let [r (kch/.receiveCatching ch)]
    (if-some [v (kch/.getOrNull r)] v ::closed)))

(defn recv!
  "Waits for the next value of a port (not through the core.async protocols: the Kotlin channel is read directly).
  Returns the value, or `:spike.coflow.chan/closed` when the port is closed and empty."
  [^KPort p]
  (let [v (recv-or-closed (.-ch p))]
    (when-not (identical? v ::closed) (received! p))
    v))

(defn- call-cb [cb v]
  (try (cb v) (catch Throwable t (.printStackTrace t))))

(defn- wake!
  "Tells the pump of a port that a taker gave up (it drops the handler)."
  [port]
  (when (instance? KPort port)
    (kch/.trySend (.-wake ^KPort port) true)))

(defn- has-active-taker?
  "Under plock: is a taker waiting? Takers that gave up (a timeout, an alts that took another branch) are removed."
  [^ConcurrentLinkedQueue takers]
  (loop []
    (when-let [^Lock h (.peek takers)]
      (if (impl/active? h) true (do (.poll takers) (recur))))))

(defn- rendezvous? [^KPort p]
  (and (= 0 (:capacity (.-spec p))) (= :suspend (:overflow (.-spec p)))))

(defn- serve!
  "Under plock. Gives values to the waiting takers, in order, while the channel has an item (or is closed and empty:
  nil). Per taker: lock its handler, and only if it is still active do a tryReceive and commit. A taker that is not
  active any more is dropped and takes nothing. Never suspends, never calls a callback.
  Returns [[[cb value] ...] mode]: mode :wait (wait for a wake), :poll (a rendezvous port: look again soon) or :exit."
  [^KPort p]
  (let [^ConcurrentLinkedQueue takers (.-takers p)
        ^Channel ch (.-ch p)]
    (loop [acc []]
      (let [^Lock h (.peek takers)]
        (if (nil? h)
          (if (kch/isClosedForSend ch)
            (do (.set ^AtomicBoolean (.-pumping p) false) [acc :exit])
            [acc :wait])
          (let [r (do (.lock h)
                      (try
                        (if (impl/active? h)
                          ;; Kotlin: ch.tryReceive()
                          (let [r (kch/.tryReceive ch)]
                            (cond
                              (kch/isSuccess r) (let [cb (impl/commit h)] (received! p) [cb (kch/.getOrNull r)])
                              (kch/isClosed r) [(impl/commit h) nil]
                              :else ::empty))
                          ::gone)
                        (finally (.unlock h))))]
            (cond
              (identical? r ::gone) (do (.poll takers) (recur acc))
              (identical? r ::empty) [acc (if (rendezvous? p) :poll :wait)]
              :else (do (.poll takers) (recur (conj acc r))))))))))

(defn- pump-loop
  "Waits only on the `wake` channel (never receives from the data channel while it holds nothing). On a wake it serves
  the takers under plock (see `serve!`), then calls the callbacks outside plock. Ends when the port is closed and
  nobody waits (a later take! starts it again)."
  [^KPort p]
  (loop []
    (let [[cbs mode] (with-plock p (serve! p))]
      (doseq [[cb v] cbs] (call-cb cb v))
      (case mode
        :exit nil
        ;; Kotlin: wake.receive()
        :wait (do (kch/.receive (.-wake p)) (recur))
        ;; Kotlin: withTimeoutOrNull(1) { wake.receive() }   (a suspended sender of a rendezvous channel gives no signal)
        :poll (do (co/withTimeoutOrNull 1 (fn [_] (kch/.receive (.-wake p)) true)) (recur))))))

(defn- ensure-pump! [^KPort p]
  (when (.compareAndSet ^AtomicBoolean (.-pumping p) false true)
    ;; Kotlin: scope.launch { pumpLoop() }
    (.set ^AtomicReference (.-pump-job p)
          (co/.launch ^CoroutineScope (.-scope p) (fn [_] (pump-loop p))))))

(defn- handoff-taker!
  "Under plock, rendezvous port only: if an active taker waits and the channel is open, commits it and returns
  [callback value] (the caller calls it outside plock), else nil."
  [^KPort p v]
  (when-not (kch/isClosedForSend (.-ch p))
    (let [^ConcurrentLinkedQueue takers (.-takers p)]
      (loop []
        (when-let [^Lock h (.peek takers)]
          (let [cb (commit-now h)]
            (.poll takers)
            (if cb [cb v] (recur))))))))

(defn try-put-now
  "Tries to put without waiting, for a core.async handler: under its lock, if it is still active and the Kotlin
  channel has room (or is closed), commits it. Returns [box callback] (box holds true/false), ::full, or nil
  (handler not active). Lock order: plock, then the handler (as in take! and put!)."
  [^KPort p ^Lock h v]
  (with-plock p
    (.lock h)
    (try
      (when (impl/active? h)
        (let [tk (when (rendezvous? p) (handoff-taker! p v))
              r (if tk :ok (raw-try-send! p v))]
          (case r
            :ok (let [cb (impl/commit h)] [(box true) cb tk])
            :closed (let [cb (impl/commit h)] [(box false) cb])
            ::full)))
      (finally (.unlock h)))))

(defn- put-pump-loop
  "Serves the puts of a core.async user that did not fit. The put counts as done only when its value is in the
  channel, so a handler that gave up (a timeout, an alts that took another branch) loses nothing.
  It waits on `put-wake` (a suspend receive): a receive of the port, or a close, wakes it. No polling: an idle port uses no CPU."
  [^KPort p]
  (loop []
    (let [x (with-plock p
              (loop []
                (let [x (.peek ^ConcurrentLinkedQueue (.-putters p))]
                  (cond
                    (nil? x) (do (.set ^AtomicBoolean (.-putting p) false) nil)
                    (not (impl/active? (nth x 0))) (do (.poll ^ConcurrentLinkedQueue (.-putters p)) (recur))
                    :else x))))]
      (when x
        (let [r (try-put-now p (nth x 0) (nth x 1))]
          (if (identical? r ::full)
            ;; Kotlin: putWake.receive()
            (do (kch/.receive (.-put-wake p)) (recur))
            (do (with-plock p (.poll ^ConcurrentLinkedQueue (.-putters p)))
                (when (vector? r)
                  (call-cb (nth r 1) @(nth r 0))
                  (when-let [tk (nth r 2 nil)] (call-cb (nth tk 0) (nth tk 1))))
                (recur))))))))

(defn- ensure-put-pump! [^KPort p]
  (when (.compareAndSet ^AtomicBoolean (.-putting p) false true)
    ;; Kotlin: scope.launch { putPumpLoop() }
    (.set ^AtomicReference (.-put-job p)
          (co/.launch ^CoroutineScope (.-scope p) (fn [_] (put-pump-loop p))))))

(defn join-pumps
  "Waits (at most timeout-ms) for the pump coroutines of a port to end. Call it after the port is closed."
  [^KPort p timeout-ms]
  (doseq [^AtomicReference r [(.-pump-job p) (.-put-job p)]]
    (when-let [j (.get r)]
      ;; Kotlin: withTimeoutOrNull(ms) { job.join() }
      (co/withTimeoutOrNull (long timeout-ms) (fn [_] (co/.join ^kotlinx.coroutines.Job j) true)))))

;; ---------------------------------------------------------------------------------------------------------------
;; helpers for a caller who is not in a coroutine. They work on any core.async port (also a core.async channel).

(def timeout ::timeout)

(defn take!!
  "Takes a value from the port, blocking. Returns nil if the port is closed.
  With timeout-ms: returns `spike.coflow.chan/timeout` (the keyword :spike.coflow.chan/timeout) if nothing came,
  or timeout-val when you give one. A value is never lost on a timeout."
  ([port] (take!! port nil nil))
  ([port timeout-ms] (take!! port timeout-ms timeout))
  ([port timeout-ms timeout-val]
   (let [p (promise)
         [h cancel!] (handler #(deliver p %) true)]
     (if-let [b (impl/take! port h)]
       @b
       (try
         (if timeout-ms
           (let [r (deref p timeout-ms ::none)]
             (if (identical? r ::none)
               (if (cancel!) (do (wake! port) timeout-val) @p)
               r))
           @p)
         (catch InterruptedException e (cancel!) (wake! port) (throw e)))))))

(defn put!!
  "Puts a value, blocking. Returns true, or false if the port is closed. With timeout-ms: returns
  `spike.coflow.chan/timeout` when it could not put in time (the value is then not put)."
  ([port v] (put!! port v nil))
  ([port v timeout-ms]
   (let [p (promise)
         [h cancel!] (handler #(deliver p %) true)]
     (if-let [b (impl/put! port v h)]
       @b
       (if timeout-ms
         (let [r (deref p timeout-ms ::none)]
           (if (identical? r ::none)
             (if (cancel!) timeout @p)
             r))
         @p)))))

(defn poll!
  "Takes a value if one is ready now. Never blocks. Returns the value, or nil."
  [port]
  (let [[h _] (handler (fn [_] nil) false)]
    (when-let [b (impl/take! port h)] @b)))

(defn close!
  "Closes the port. The values in its buffer can still be taken."
  [port]
  (impl/close! port))

(defn closed?
  "True when close! was called on the port."
  [port]
  (impl/closed? port))

(defn ->seq
  "A lazy seq of the values of the port, ending when the port is closed. Taking an element blocks.
  With timeout-ms the seq also ends when no value comes in time."
  ([port] (->seq port nil))
  ([port timeout-ms]
   (lazy-seq
    (let [v (take!! port timeout-ms ::end)]
      (when-not (or (nil? v) (identical? v ::end))
        (cons v (->seq port timeout-ms)))))))

(defn drain
  "All values until the port is closed, as a vector. With timeout-ms: until closed, or until nothing came for
  that long."
  ([port] (vec (->seq port)))
  ([port timeout-ms] (vec (->seq port timeout-ms))))

(defn ->flow
  "The values of a port as a kotlinx.coroutines.flow.Flow (cold: collecting it takes the values). The Flow reads the
  raw Kotlin channel: do not take from the same port with take!!/<!! at the same time."
  [^KPort p]
  ;; Kotlin: ch.receiveAsFlow()
  (kflow/.receiveAsFlow (.-ch p)))

;; --- demand-driven access to a core.async port that is not ours (the user's ::flow/in-ports and ::flow/out-ports) ---

(defn poll-state
  "A take that never waits, on any core.async ReadPort. Returns nil when nothing is ready, [v] when a message was
  taken, or [nil] when the port is closed."
  [port]
  (let [[h _] (handler (fn [_] nil) false)]
    (when-let [b (impl/take! port h)] [@b])))

(defn try-put-state
  "A put that never waits, on any core.async WritePort. Returns nil when it did not fit, [true] when it was put,
  [false] when the port is closed."
  [port v]
  (let [[h _] (handler (fn [_] nil) false)]
    (when-let [b (impl/put! port v h)] [@b])))

(defn take-with!
  "Registers `h` (see `handler`) as a take of the port. Returns a box when a value was taken at once (h is then
  committed), or nil when the take is pending."
  [port h]
  (impl/take! port h))

(defn put-with!
  "Registers `h` (see `handler`) as a put of v. Returns a box when it was done at once, or nil when it is pending."
  [port v h]
  (impl/put! port v h))

;; --- one arbitration for a proc with user ports: core.async's own commit protocol, as `alts` does it ---------------

(def ^:private lock-ids (AtomicLong. 0))

(defn alts-ops
  "Waits for exactly one of `ops`, in priority order (the first op has the highest priority), with core.async's commit
  protocol (`impl/Handler`: active?, commit, lock-id, blockable?) and ONE shared flag and lock for all ops, as `alts!`
  does. An op is [:take port id] or [:put port msg id]; a port is anything that implements ReadPort / WritePort of
  clojure.core.async.impl.protocols: a port of ours (Kotlin Channel) or a core.async channel of the user. Returns [value id]:
  for a take the message (nil: closed), for a put true (false: closed).
  Why: a channel commits a take or a put of a handler only if the shared flag is still free (under the lock), and
  the commit makes the flag busy. So exactly one op wins, and nothing is taken from a channel that is not delivered
  to the caller. A pump of one of OUR ports takes a value out of the channel only for a handler that commits."
  [ops]
  (let [flag (atom true)
        lock (ReentrantLock.)
        lid (.incrementAndGet ^AtomicLong lock-ids)
        res (promise)
        mk (fn [id]
             (reify
               Lock
               (lock [_] (.lock lock))
               (unlock [_] (.unlock lock))
               impl/Handler
               (active? [_] @flag)
               (blockable? [_] true)
               (lock-id [_] lid)
               (commit [_] (reset! flag false) (fn [v] (deliver res [v id])))))]
    (loop [ops (seq ops)]
      (if ops
        (let [[kind port x y] (first ops)
              id (if (= kind :take) x y)
              b (if (= kind :take)
                  (impl/take! port (mk id))
                  (impl/put! port x (mk id)))]
          (if b
            [@b id]
            (recur (next ops))))
        (try
          (deref res)
          (catch InterruptedException e
            ;; the wait is cancelled (a forced cancel of the proc): no op may commit after this
            (.lock lock)
            (try (reset! flag false) (finally (.unlock lock)))
            (throw e)))))))

(defn close-quiet!
  "Closes a port of ours without flushing a transducer (used when a flow is cleaned up: nobody reads any more)."
  [^KPort p]
  (kch/.close (.-ch p))
  (wake-takers! p)
  (kch/.trySend (.-put-wake p) true)
  nil)
