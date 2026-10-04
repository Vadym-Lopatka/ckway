(ns ckway.co
  "Kotlin `suspend` for Clojure: a suspend call waits, a Clojure function that Kotlin runs as a
  coroutine body runs on its own virtual thread.

  Contract (needs only kotlin-stdlib; JDK 21+ for bodies):
    1. `continuation` makes the Continuation that a suspend call passes to Kotlin. Its context is the
       context of the body that runs this thread (`*context*`), else EmptyCoroutineContext. With
       kotlinx.coroutines on the class path, a call whose context has no Job (every call from outside a body)
       gets a Job of its own in its context (`coroutineContext.job` works in Kotlin).
    2. `wait-for k r`: `r` is what the Kotlin call returned. COROUTINE_SUSPENDED: park this thread (a
       virtual thread: cheap; a platform thread: a real block) until Kotlin resumes `k`; else r
       itself. A failure is thrown as the original exception, never wrapped. The wait ends ONLY when `k` is
       resumed, except for these interrupts:
         - no kotlinx.coroutines, or a body whose Job is not being cancelled: an interrupt ends the wait with
           InterruptedException (the Kotlin call goes on, nobody can tell it to stop);
         - a body whose Job is being cancelled: the interrupt (it is the cancellation of that Job) is ignored. The
           callee got the cancellation through the Job in its context (it is the same Job) and resumes `k`,
           usually with a CancellationException, when it is done (a `finally` with NonCancellable work finishes
           first). So the body unwinds only after its callee: structured-concurrency order. If the callee
           returns a value although the Job is cancelling (it ignored the cancellation), the wait returns the
           value, as in Kotlin, and sets the interrupt flag again: the next blocking operation of the body fails
           at once, as the next suspension point does in Kotlin;
         - a call with its own Job (see 1), as `runBlocking` does: the interrupt cancels that Job and the thread keeps
           waiting for the callee to finish its cancellation, then throws InterruptedException (the interrupt flag
           is clear, the exception replaces it; the callee's result, if any, is dropped). Unlike `runBlocking`
           the wait is bounded: after the grace time (system property `ckway.interrupt.grace.ms`, see `grace-ms`)
           or at a second interrupt the exception is thrown although the callee has not resumed `k` (a callee
           that does not support cancellation), and the late result is dropped.
       A thread that only inherited the context of a body (`future`, `bound-fn`; `*body-thread*` is not this
       thread) has no body thread of its own that the cancellation of the Job interrupts: its call is a call from
       outside a body, in the context of the body, with a Job of its own that is a child of the Job of the body. An
       interrupt of that thread cancels that Job (the last case above), cancelling the body cancels the call, and
       the interrupt flag of such a thread is never set again.
    3. `run-body k frame f`: the adapter of a suspend function type / fun interface. Runs `(f)` on a new
       virtual thread and returns COROUTINE_SUSPENDED. The thread sees `frame` (a Clojure binding frame
       from `capture-frame`, taken when the adapter was created) and `*context*` = (.getContext k).
       When `f` returns or throws, `k` is resumed through the interceptor of its context, exactly once on every
       path (a value, an exception, an Error, a hook that throws, an interceptor that throws), and the
       intercepted continuation is released (`releaseInterceptedContinuation`, which the contract of
       ContinuationInterceptor asks for when the continuation is not used any more). Never twice: the
       interceptor gets a guard around `k` that lets only the first resume through.
    4. Everything that needs kotlinx.coroutines is in `ckway.co.kx`, loaded only if
       `kotlinx.coroutines.Job` is on the class path: ThreadContextElements of the context are applied
       on the body's thread (and restored; an element that throws makes the coroutine fail), and cancelling the
       Job of the context interrupts the thread (to stop a body that is blocked in non-coroutine code); the
       resulting InterruptedException becomes a CancellationException. The completion handler on the Job is
       disposed when the body ends.
    5. A body is not on the dispatcher thread: a ThreadLocal that no ThreadContextElement carries is
       not visible in it. `synchronized` pins the carrier on JDK 21-23 (not on 24+)."
  (:require [clojure.string :as str])
  (:import [clojure.lang Var]
           [java.util.concurrent.locks LockSupport]
           [kotlin.coroutines Continuation ContinuationInterceptor CoroutineContext EmptyCoroutineContext]))

(set! *warn-on-reflection* true)

(def ^:dynamic *context*
  "The CoroutineContext of the coroutine body that runs on this thread, or nil (outside any body)."
  nil)

(def ^:dynamic *body-thread*
  "The thread of the coroutine body that runs here (the one that the cancellation of its Job interrupts), or nil
  outside any body. A thread that only inherited the bindings of the body (`future`, `bound-fn`) sees the
  body's `*context*` but is not this thread."
  nil)

(def SUSPENDED
  "Kotlin's COROUTINE_SUSPENDED marker."
  kotlin.coroutines.intrinsics.CoroutineSingletons/COROUTINE_SUSPENDED)

(def ^:private UNSET (Object.))

;; ---------------------------------------------------------------- calling a suspend function

(definterface IWait (awaitResult []) (done []) (release []))

(declare kx)

(def ^:private max-grace-ms
  "The longest grace time (24 hours): a larger `ckway.interrupt.grace.ms` is this."
  (* 24 60 60 1000))

(defn grace-ms
  "The grace time of a top-level wait in ms, from the text `s` of `ckway.interrupt.grace.ms`: not a number (or
  nil) is the default 5000; a negative number is 0 (no grace: the exception comes as soon as the callee has been told
  to cancel); a number above 24 hours is 24 hours."
  ^long [s]
  (let [n (try (Long/parseLong (str/trim (str s))) (catch NumberFormatException _ 5000))]
    (long (max 0 (min n max-grace-ms)))))

(defn- grace-nanos
  "How long a top-level wait keeps waiting for a callee that was cancelled by an interrupt: the system property
  `ckway.interrupt.grace.ms` (read at each wait), see `grace-ms`."
  ^long []
  (* 1000000 (grace-ms (System/getProperty "ckway.interrupt.grace.ms" "5000"))))

(deftype Waiter [^CoroutineContext ctx own-job ^:volatile-mutable result ^:volatile-mutable ^Thread waiter]
  Continuation
  (getContext [_] ctx)
  (resumeWith [_ r]
    (set! result r)
    (when-let [t waiter] (LockSupport/unpark t)))
  IWait
  (done [_] (when own-job ((:complete! @kx) own-job)))
  (release [_] (set! waiter nil))
  (awaitResult [this]
    (set! waiter (Thread/currentThread))
    (let [hooks @kx]
      (try
        ;; `deadline`: nil, or (top-level call) the System/nanoTime until which this thread waits for a callee
        ;; that was told to cancel because the thread was interrupted
        (loop [deadline nil]
          (let [r result]
            (if (identical? r UNSET)
              (do (if deadline
                    (let [left (- (long deadline) (System/nanoTime))]
                      (if (pos? left)
                        (LockSupport/parkNanos this left)
                        (throw (InterruptedException.))))
                    (LockSupport/park this))
                  (if (Thread/interrupted)
                    (cond
                      ;; a second interrupt of a top-level wait: stop waiting for the callee
                      deadline (throw (InterruptedException.))
                      ;; a top-level call: its own Job is cancelled, the callee gets the cancellation and finishes
                      ;; (this thread waits for it, at most the grace time), then InterruptedException
                      own-job (do ((:cancel! hooks) own-job) (recur (+ (System/nanoTime) (grace-nanos))))
                      ;; in a body whose Job is being cancelled: the interrupt is the cancellation of this
                      ;; very Job, which the callee got through its context. Wait until it resumes `this`.
                      (and hooks ((:cancelling? hooks) ctx)) (recur nil)
                      :else (throw (InterruptedException.)))
                    (recur deadline)))
              (cond
                deadline (throw (InterruptedException.))
                (instance? kotlin.Result$Failure r) (throw (.-exception ^kotlin.Result$Failure r))
                :else (do
                        ;; A body whose Job is cancelling and whose callee ignored the cancellation: the interrupt that
                        ;; the cancellation sent may have been swallowed above. The body goes on with the value (as in
                        ;; Kotlin, where the next suspension point throws), so its next blocking operation must fail.
                        (when (and hooks (nil? own-job) ((:cancelling? hooks) ctx))
                          (.interrupt (Thread/currentThread)))
                        r)))))
        (finally (when own-job ((:complete! hooks) own-job)))))))

(defn continuation
  "The Continuation for one suspend call (see the contract)."
  ^Continuation []
  (let [ctx (or *context* EmptyCoroutineContext/INSTANCE)
        ;; a thread that only inherited the context of a body (`future`, `bound-fn`) is not the body's thread
        child? (let [bt *body-thread*] (and bt (not (identical? bt (Thread/currentThread)))))]
    (if-let [h @kx]
      (if (and ((:has-job? h) ctx) (not child?))
        (->Waiter ctx nil UNSET nil)
        ;; a call from outside a body, or from a child thread of one: it has a Job of its own, the interrupt of this
        ;; thread cancels it. In a child thread that Job is a child of the Job of the body: when the body is
        ;; cancelled, so is the call.
        (let [job ((:new-job h) ctx)]
          (->Waiter (.plus ^CoroutineContext ctx job) job UNSET nil)))
      (->Waiter ctx nil UNSET nil))))

(defn wait-for*
  "The value of a suspend call: `r` is the return value of the JVM method that got continuation `k`. The call's own
  Job (if any) is completed on every path: no suspension, a failure, a resume, an interrupt, the grace time."
  [^Continuation k r]
  (if (identical? r SUSPENDED)
    (try (.awaitResult ^ckway.co.IWait k)
         (finally (.release ^ckway.co.IWait k)))
    (do (.done ^ckway.co.IWait k) r)))

(defmacro wait-for
  "The value of a suspend call: `call` is the form that calls the JVM method with continuation `k`. If `call` throws
  before it suspends, the call's own Job is completed (and the exception goes on); otherwise `wait-for*` does it.
  Every suspend call goes through this macro or `call-suspend`, so no call site can forget the Job."
  [k call]
  `(let [k# ~k]
     (wait-for* k# (try ~call
                        (catch Throwable t# (.done ^ckway.co.IWait k#) (throw t#))))))

(defn call-suspend
  "Own the whole suspend call: make the continuation, run `(f k)` (the JVM call with `k`, and whatever prepares it),
  wait for the result. A throw anywhere in `(f k)` completes the call's own Job."
  [f]
  (let [k (continuation)]
    (wait-for k (f k))))

;; ---------------------------------------------------------------- running a body

(defn capture-frame
  "The Clojure binding frame of this thread. A body runs with the frame that was captured when its
  adapter was created, not with the frame of the thread that happens to start it."
  []
  (Var/cloneThreadBindingFrame))

(def ^:private kx
  "The kotlinx.coroutines hooks (a map of functions) or nil when kotlinx.coroutines is not on the class path."
  (delay (when (try (Class/forName "kotlinx.coroutines.Job" false (clojure.lang.RT/baseLoader)) true
                    (catch ClassNotFoundException _ false))
           @(requiring-resolve 'ckway.co.kx/hooks))))

(def ^:private start-vthread
  (delay
    (let [v (Runtime/version)]
      (when (< (.feature v) 21)
        (throw (ex-info (str "kt: Clojure functions as Kotlin suspend functions need JDK 21 or newer (virtual threads); this JVM is "
                             (System/getProperty "java.version"))
                        {:kt/error true}))))
    @(requiring-resolve 'ckway.co.vt/start!)))

(defn- failure [^Throwable e] (kotlin.ResultKt/createFailure e))

(defn- failure? [r] (instance? kotlin.Result$Failure r))

(defn- uncaught!
  "Give `t` to the uncaught exception handler of this thread (nobody else can handle it)."
  [^Throwable t]
  (let [^Thread th (Thread/currentThread)
        ^Thread$UncaughtExceptionHandler h (or (.getUncaughtExceptionHandler th) (Thread/getDefaultUncaughtExceptionHandler) th)]
    (.uncaughtException h th t)))

(defn- resume!
  "Resume `k` with the Result `res` through the interceptor (the dispatcher) of its context, as Kotlin's own
  suspend helpers do, and release the intercepted continuation afterwards (the contract of
  ContinuationInterceptor: it was got from interceptContinuation, and is not used any more after the resume).
  `k` is resumed exactly once. The interceptor gets a guard around `k` that lets only the first resume through.
  If `interceptContinuation` throws, `k` is resumed directly with that failure: the coroutine never hangs. If
  `resumeWith` of the intercepted continuation throws, the exception comes after the continuation ran (an
  unconfined interceptor that runs it inline and fails in its own completion) or before it was resumed
  (a dispatcher that refuses the task): in the first case it goes to the uncaught exception handler, in the
  second `k` is resumed with it."
  [^Continuation k res]
  (let [resumed (java.util.concurrent.atomic.AtomicBoolean. false)
        guard (reify Continuation
                (getContext [_] (.getContext k))
                (resumeWith [_ r] (when (.compareAndSet resumed false true) (.resumeWith k r))))
        fallback! (fn [^Throwable t]
                    (try (.resumeWith guard (failure t))
                         (catch Throwable t2 (uncaught! t2))))
        ip (try (.get (.getContext k) ContinuationInterceptor/Key) (catch Throwable _ nil))
        ic (try (if ip (.interceptContinuation ^ContinuationInterceptor ip guard) guard)
                (catch Throwable t (fallback! t) nil))]
    (when ic
      (try (.resumeWith ^Continuation ic res)
           (catch Throwable t (if (.get resumed) (uncaught! t) (fallback! t)))
           (finally
             (when ip (try (.releaseInterceptedContinuation ^ContinuationInterceptor ip ic) (catch Throwable _ nil))))))))

(defn- run-hooked
  "The body with the kotlinx hooks: => the Result (a value, or a Failure). Failures of a hook are failures of
  the coroutine, and a failure of `exit` too (the body's own failure first, the other one suppressed)."
  [ctx f hooks]
  (let [cancel (volatile! nil)
        entered (volatile! nil)
        res (try
              (vreset! cancel ((:on-cancel hooks) ctx (Thread/currentThread)))
              (vreset! entered ((:enter hooks) ctx))
              (try (with-bindings* {#'*context* ctx #'*body-thread* (Thread/currentThread)} f)
                   (catch Throwable e
                     (failure (try ((:map-exception hooks) ctx e) (catch Throwable _ e)))))
              (catch Throwable e (failure e)))
        exit-err (try ((:exit hooks) ctx @entered @cancel) nil (catch Throwable t t))]
    (cond
      (nil? exit-err) res
      (failure? res) (do (.addSuppressed ^Throwable (.-exception ^kotlin.Result$Failure res) ^Throwable exit-err) res)
      :else (failure exit-err))))

(defn- run-thread
  "The virtual thread of a body. `k` is resumed exactly once on every path: the body returns, the body throws,
  a hook throws, an Error."
  [^Continuation k frame f hooks]
  (let [res (try
              (Var/resetThreadBindingFrame frame)
              (let [ctx (.getContext k)]
                (if hooks
                  (run-hooked ctx f hooks)
                  (try (with-bindings* {#'*context* ctx #'*body-thread* (Thread/currentThread)} f)
                       (catch Throwable e (failure e)))))
              (catch Throwable e (failure e)))]
    (resume! k res)))

(defn run-body
  "Run `(f)` as the body of the coroutine whose continuation is `k` (see the contract)."
  [^Continuation k frame f]
  (let [start! @start-vthread
        hooks (when-not (identical? EmptyCoroutineContext/INSTANCE (.getContext k)) @kx)]
    (start! (fn [] (run-thread k frame f hooks)))
    SUSPENDED))
