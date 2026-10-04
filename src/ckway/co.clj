(ns ckway.co
  "Kotlin `suspend` for Clojure: a suspend call waits, a Clojure function that Kotlin runs as a
  coroutine body runs on its own virtual thread.

  Contract (needs only kotlin-stdlib; JDK 21+ for bodies):
    1. `continuation` makes the Continuation that a suspend call passes to Kotlin. Its context is the
       context of the body that runs this thread (`*context*`), else EmptyCoroutineContext.
    2. `wait-for k r`: `r` is what the Kotlin call returned. COROUTINE_SUSPENDED: park this thread (a
       virtual thread: cheap; a platform thread: a real block) until Kotlin resumes `k`; else r
       itself. A failure is thrown as the original exception, never wrapped. An interrupt ends the wait
       with InterruptedException.
    3. `run-body k frame f`: the adapter of a suspend function type / fun interface. Runs `(f)` on a new
       virtual thread and returns COROUTINE_SUSPENDED. The thread sees `frame` (a Clojure binding frame
       from `capture-frame`, taken when the adapter was created) and `*context*` = (.getContext k).
       When `f` returns or throws, `k` is resumed through the interceptor of its context.
    4. Everything that needs kotlinx.coroutines is in `ckway.co.kx`, loaded only if
       `kotlinx.coroutines.Job` is on the class path: ThreadContextElements of the context are applied
       on the body's thread (and restored), and cancelling the Job of the context interrupts the
       thread; the resulting InterruptedException becomes a CancellationException.
    5. A body is not on the dispatcher thread: a ThreadLocal that no ThreadContextElement carries is
       not visible in it. `synchronized` pins the carrier on JDK 21-23 (not on 24+)."
  (:import [clojure.lang Var]
           [java.util.concurrent.locks LockSupport]
           [kotlin.coroutines Continuation ContinuationInterceptor CoroutineContext EmptyCoroutineContext]))

(set! *warn-on-reflection* true)

(def ^:dynamic *context*
  "The CoroutineContext of the coroutine body that runs on this thread, or nil (outside any body)."
  nil)

(def SUSPENDED
  "Kotlin's COROUTINE_SUSPENDED marker."
  kotlin.coroutines.intrinsics.CoroutineSingletons/COROUTINE_SUSPENDED)

(def ^:private UNSET (Object.))

;; ---------------------------------------------------------------- calling a suspend function

(definterface IWait (awaitResult []))

(deftype Waiter [^CoroutineContext ctx ^:volatile-mutable result ^:volatile-mutable ^Thread waiter]
  Continuation
  (getContext [_] ctx)
  (resumeWith [_ r]
    (set! result r)
    (when-let [t waiter] (LockSupport/unpark t)))
  IWait
  (awaitResult [this]
    (set! waiter (Thread/currentThread))
    (loop []
      (let [r result]
        (if (identical? r UNSET)
          (do (LockSupport/park this)
              (if (Thread/interrupted) (throw (InterruptedException.)) (recur)))
          (if (instance? kotlin.Result$Failure r)
            (throw (.-exception ^kotlin.Result$Failure r))
            r))))))

(defn continuation
  "The Continuation for one suspend call (see the contract)."
  ^Continuation []
  (->Waiter (or *context* EmptyCoroutineContext/INSTANCE) UNSET nil))

(defn wait-for
  "The value of a suspend call: `r` is the return value of the JVM method that got continuation `k`."
  [^Continuation k r]
  (if (identical? r SUSPENDED) (.awaitResult ^ckway.co.IWait k) r))

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

(defn- intercepted
  "`k` wrapped by the ContinuationInterceptor (the dispatcher) of its context, as Kotlin's own suspend helpers do."
  ^Continuation [^Continuation k]
  (if-let [ip (.get (.getContext k) ContinuationInterceptor/Key)]
    (.interceptContinuation ^ContinuationInterceptor ip k)
    k))

(defn- run-thread [^Continuation k frame f hooks]
  (Var/resetThreadBindingFrame frame)
  (let [ctx (.getContext k)
        cancel (when hooks ((:on-cancel hooks) ctx (Thread/currentThread)))
        entered (when hooks ((:enter hooks) ctx))
        res (try (let [v (with-bindings* {#'*context* ctx} f)] v)
                 (catch Throwable e
                   (kotlin.ResultKt/createFailure (if hooks ((:map-exception hooks) ctx e) e))))]
    (when hooks ((:exit hooks) ctx entered cancel))
    (.resumeWith (intercepted k) res)))

(defn run-body
  "Run `(f)` as the body of the coroutine whose continuation is `k` (see the contract)."
  [^Continuation k frame f]
  (let [start! @start-vthread
        hooks (when-not (identical? EmptyCoroutineContext/INSTANCE (.getContext k)) @kx)]
    (start! (fn [] (run-thread k frame f hooks)))
    SUSPENDED))
