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

(declare ensure-pump! ensure-put-pump! xf-close! try-put-now)

(deftype KPort [^Channel ch                        ;; the Kotlin channel
                spec                               ;; buffer-spec
                ^CoroutineScope scope              ;; for the pump coroutines
                ^AtomicLong cnt                    ;; items in the buffer, best effort, for datafy only
                xf-rf                              ;; the transducer step fn, or nil
                ^ReentrantLock xf-lock             ;; guards xf-rf (a transducer may be stateful)
                ;; takers (core.async side): one pump coroutine receives and hands out
                ^ReentrantLock plock               ;; guards takers, stash, putters
                ^ConcurrentLinkedQueue takers
                ^AtomicReference stash             ;; [v] a received value that waits for a taker, or nil
                ^AtomicBoolean pumping
                ^AtomicBoolean drained
                ^Channel wake                      ;; conflated: a taker came / the stash was taken
                ^AtomicReference pump-job
                ;; putters (core.async side)
                ^ConcurrentLinkedQueue putters
                ^AtomicBoolean putting
                ^AtomicReference put-job]
  impl/ReadPort
  (take! [p handler]
    (let [^Lock h handler]
      (.lock plock)
      (try
        (let [st (.get stash)]
          (cond
            st
            (when (commit-now h)
              (.set stash nil)
              (.decrementAndGet cnt)
              ;; Kotlin: wake.trySend(Unit)
              (kch/.trySend wake true)
              (box (nth st 0)))

            (.get drained)
            (do (commit-now h) (box nil))

            ;; a pump runs: a blocking take waits in the queue of the pump. A poll! (not blockable) looks at the
            ;; channel itself, below.
            (and (.get pumping) (impl/blockable? h))
            (do (.add takers h)
                (kch/.trySend wake true)
                nil)

            :else
            (do
              (.lock h)
              (let [ret (try
                          (when (impl/active? h)
                            ;; Kotlin: ch.tryReceive()
                            (let [r (kch/.tryReceive ch)]
                              (cond
                                (kch/isSuccess r) (do (impl/commit h) (.decrementAndGet cnt) (box (kch/.getOrNull r)))
                                (kch/isClosed r) (do (impl/commit h) (box nil))
                                :else ::empty)))
                          (finally (.unlock h)))]
                (cond
                  (identical? ret ::empty)
                  (do (when (impl/blockable? h)
                        (.add takers h)
                        (ensure-pump! p))
                      nil)
                  :else ret)))))
        (finally (.unlock plock)))))

  impl/WritePort
  (put! [p val handler]
    (when (nil? val)
      (throw (IllegalArgumentException. "Can't put nil on channel")))
    (let [^Lock h handler]
      (.lock plock)
      (try
        (if (and (nil? xf-rf) (.isEmpty putters) (not (.get putting)))
          (let [ret (try-put-now p h val)]
            (if (identical? ret ::full)
              (do (when (impl/blockable? h)
                    (.add putters [h val])
                    (ensure-put-pump! p))
                  nil)
              (first ret)))
          (do (when (impl/blockable? h)
                (.add putters [h val])
                (ensure-put-pump! p))
              nil))
        (finally (.unlock plock)))))

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

(defn kotlin-channel
  "The raw kotlinx.coroutines Channel behind a port."
  ^Channel [^KPort p] (.-ch p))

(def ^{:doc "Alias of `kotlin-channel`."} ->kotlin kotlin-channel)

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
             (ReentrantLock.) (ConcurrentLinkedQueue.) (AtomicReference. nil) (AtomicBoolean. false)
             (AtomicBoolean. false)
             ;; Kotlin: Channel<Unit>(Channel.CONFLATED)
             (kch/Channel (kch/CONFLATED kch/Channel))
             (AtomicReference. nil)
             (ConcurrentLinkedQueue.) (AtomicBoolean. false) (AtomicReference. nil)))))

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

(defn send1!
  "Sends one value to the Kotlin channel, waiting for room. Returns true, or false when the channel is closed."
  [^KPort p v]
  (try
    ;; Kotlin: ch.send(v)
    (kch/.send (.-ch p) v)
    (.incrementAndGet ^AtomicLong (.-cnt p))
    true
    (catch ClosedSendChannelException _ false)))

(defn sent!
  "Tells the port that one item entered its buffer through a select (for datafy)."
  [^KPort p]
  (.incrementAndGet ^AtomicLong (.-cnt p)))

(defn received!
  "Tells the port that one item left its buffer (for datafy)."
  [^KPort p]
  (.decrementAndGet ^AtomicLong (.-cnt p)))

(defn try-send1!
  "Sends one value to the Kotlin channel if there is room now. Returns :ok, :full or :closed."
  [^KPort p v]
  ;; Kotlin: ch.trySend(v)
  (let [r (kch/.trySend (.-ch p) v)]
    (cond
      (kch/isSuccess r) (do (.incrementAndGet ^AtomicLong (.-cnt p)) :ok)
      (kch/isClosed r) :closed
      :else :full)))

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
  (kch/.trySend (.-wake p) true)
  nil)

;; --- the pump: serves the takers of a port ------------------------------------------------------------------

(defn- recv-or-closed
  "Waits for the next value of the Kotlin channel. Returns the value, or ::closed."
  [^Channel ch]
  ;; Kotlin: select { ch.onReceiveCatching { it } }
  ;; (`ch.receiveCatching()` itself is not used: a suspend function that returns a value class comes back unwrapped, see README)
  (let [r (sel/select (fn [sb] (sel/.invoke sb (kch/onReceiveCatching ch) (fn [r] r))))]
    (if-some [v (kch/.getOrNull r)] v ::closed)))

(defn recv!
  "Waits for the next value of a port (not through the core.async protocols: the Kotlin channel is read directly).
  Returns the value, or `:spike.coflow.chan/closed` when the port is closed and empty."
  [^KPort p]
  (let [v (recv-or-closed (.-ch p))]
    (when-not (identical? v ::closed) (.decrementAndGet ^AtomicLong (.-cnt p)))
    v))

(defn- next-taker-cb
  [^ConcurrentLinkedQueue takers]
  (loop []
    (when-let [^Lock h (.poll takers)]
      (or (commit-now h) (recur)))))

(defn- call-cb [cb v]
  (try (cb v) (catch Throwable t (.printStackTrace t))))

(defmacro ^:private with-plock [p & body]
  `(let [^ReentrantLock l# (.-plock ~(with-meta p {:tag 'spike.coflow.chan.KPort}))]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn- deliver! [^KPort p v]
  (loop []
    (let [cb (with-plock p
               (if-let [cb (next-taker-cb (.-takers p))]
                 (do (.set ^AtomicReference (.-stash p) nil) cb)
                 (do (.set ^AtomicReference (.-stash p) [v]) nil)))]
      (if cb
        (call-cb cb v)
        (do
          ;; Kotlin: wake.receive()
          (kch/.receive (.-wake p))
          (when (with-plock p (some? (.get ^AtomicReference (.-stash p))))
            (recur)))))))

(defn- pump-recv
  "The receive of the pump: the next value, ::closed, or ::wake (a taker gave up or came: look again)."
  [^KPort p]
  ;; Kotlin: select { ch.onReceiveCatching { it }; wake.onReceive { WAKE } }
  (let [r (sel/select (fn [sb]
                        (sel/.invoke sb (kch/onReceiveCatching (.-ch p)) (fn [r] r))
                        (sel/.invoke sb (kch/onReceive (.-wake p)) (fn [_] ::wake))))]
    (if (identical? r ::wake)
      ::wake
      (if-some [v (kch/.getOrNull r)] v ::closed))))

(defn- wake!
  "Tells the pump of a port that a taker gave up (so that it does not keep a value in its hand for nobody)."
  [port]
  (when (instance? KPort port)
    (kch/.trySend (.-wake ^KPort port) true)))

(defn- has-active-taker?
  "Under plock: is a taker waiting? Takers that gave up (a timeout, an alts that took another branch) are removed."
  [^ConcurrentLinkedQueue takers]
  (loop []
    (when-let [^Lock h (.peek takers)]
      (if (impl/active? h) true (do (.poll takers) (recur))))))

(defn- pump-loop
  "Receives from the Kotlin channel only while a taker waits (so that the port keeps its buffer capacity), hands the
  value to the first active taker. Ends when the port is closed and nobody waits (a later take! starts it again),
  or when the port is closed and empty (the waiting takers get nil)."
  [^KPort p]
  (loop []
    (let [what (with-plock p
                 (cond
                   (has-active-taker? (.-takers p)) :go
                   (kch/isClosedForSend (.-ch p)) (do (.set ^AtomicBoolean (.-pumping p) false) :exit)
                   :else :wait))]
      (case what
        :exit nil
        ;; Kotlin: wake.receive()
        :wait (do (kch/.receive (.-wake p)) (recur))
        :go (let [v (pump-recv p)]
              (cond
                (identical? v ::wake) (recur)
                (identical? v ::closed)
                (let [cbs (with-plock p
                            (.set ^AtomicBoolean (.-drained p) true)
                            (loop [acc []]
                              (if-let [cb (next-taker-cb (.-takers p))] (recur (conj acc cb)) acc)))]
                  (doseq [cb cbs] (call-cb cb nil)))
                :else
                (do (.decrementAndGet ^AtomicLong (.-cnt p))
                    (deliver! p v)
                    (recur))))))))

(defn- ensure-pump! [^KPort p]
  (when (.compareAndSet ^AtomicBoolean (.-pumping p) false true)
    ;; Kotlin: scope.launch { pumpLoop() }
    (.set ^AtomicReference (.-pump-job p)
          (co/.launch ^CoroutineScope (.-scope p) (fn [_] (pump-loop p))))))

(defn try-put-now
  "Tries to put without waiting, for a core.async handler: under its lock, if it is still active and the Kotlin
  channel has room (or is closed), commits it. Returns [box callback] (box holds true/false), ::full, or nil
  (handler not active)."
  [^KPort p ^Lock h v]
  (.lock h)
  (try
    (when (impl/active? h)
      ;; Kotlin: ch.trySend(v)
      (let [r (kch/.trySend (.-ch p) v)]
        (cond
          (kch/isSuccess r) (let [cb (impl/commit h)] (.incrementAndGet ^AtomicLong (.-cnt p)) [(box true) cb])
          (kch/isClosed r) (let [cb (impl/commit h)] [(box false) cb])
          :else ::full)))
    (finally (.unlock h))))

(defn- put-pump-loop
  "Serves the puts that did not fit. A put counts as done only when its value is in the channel, so a handler that
  gave up (a timeout, an alts that took another branch) loses nothing: the pump polls with a growing delay."
  [^KPort p]
  (loop []
    (let [[h v :as x] (with-plock p
                        (let [x (.peek ^ConcurrentLinkedQueue (.-putters p))]
                          (when-not x (.set ^AtomicBoolean (.-putting p) false))
                          x))]
      (when x
        (let [done (loop [wait 1]
                     (let [r (try-put-now p h v)]
                       (cond
                         (nil? r) true
                         (identical? r ::full) (do (co/delay (long wait)) (recur (min 20 (* 2 wait))))
                         :else r)))]
          (with-plock p (.poll ^ConcurrentLinkedQueue (.-putters p)))
          ;; the callback of a put that was queued
          (when (vector? done)
            (call-cb (nth done 1) @(nth done 0))))
        (recur)))))

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
