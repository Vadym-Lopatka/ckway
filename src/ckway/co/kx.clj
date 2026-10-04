(ns ckway.co.kx
  "The part of ckway.co that needs kotlinx.coroutines (loaded only if it is on the class path):
  cancellation by the Job of the context, and ThreadContextElements."
  (:import [kotlin.coroutines CoroutineContext]
           [kotlin.jvm.functions Function1 Function2]
           [kotlinx.coroutines DisposableHandle Job ThreadContextElement]))

(set! *warn-on-reflection* true)

(defn- job-of ^Job [^CoroutineContext ctx] (.get ctx Job/Key))

(defn- on-cancel
  "Interrupt `t` when the Job of `ctx` starts to cancel. => the handle to dispose, or nil."
  [ctx ^Thread t]
  (when-let [job (job-of ctx)]
    (.invokeOnCompletion job true true
                         (reify Function1
                           (invoke [_ cause]
                             (when (some? cause) (.interrupt t))
                             kotlin.Unit/INSTANCE)))))

(defn- elements [^CoroutineContext ctx]
  (.fold ctx [] (reify Function2
                  (invoke [_ acc el] (if (instance? ThreadContextElement el) (conj acc el) acc)))))

(defn- restore-all!
  "Restore the `entered` states ([element state] pairs) in reverse order. Every restore runs, even if one throws.
  => the first exception (the others are suppressed in it), or nil."
  [ctx entered]
  (reduce (fn [^Throwable err [^ThreadContextElement el state]]
            (try (.restoreThreadContext el ctx state) err
                 (catch Throwable t (if err (do (.addSuppressed err t) err) t))))
          nil (reverse entered)))

(defn- enter
  "Apply every ThreadContextElement of `ctx` to this thread. => state for `exit`. If an element throws,
  the ones that were applied are restored and the exception is thrown."
  [ctx]
  (loop [els (seq (elements ctx)) done []]
    (if els
      (let [^ThreadContextElement el (first els)
            st (try (.updateThreadContext el ctx)
                    (catch Throwable t
                      (when-let [r (restore-all! ctx done)] (.addSuppressed t r))
                      (throw t)))]
        (recur (next els) (conj done [el st])))
      done)))

(defn- exit
  "Restore what `enter` changed and dispose the cancellation handle (always). Throws the first exception of a restore."
  [ctx entered ^DisposableHandle cancel]
  (try
    (when-let [err (restore-all! ctx entered)] (throw err))
    (finally (when cancel (.dispose cancel)))))

(defn- map-exception
  "An interrupt that the Job's cancellation caused is a CancellationException, as in Kotlin."
  [ctx ^Throwable e]
  (let [job (job-of ctx)]
    (if (and (instance? InterruptedException e) job (not (.isActive job)))
      (doto (java.util.concurrent.CancellationException. "kt: coroutine body cancelled") (.initCause e))
      e)))

(defn- has-job? [^CoroutineContext ctx] (some? (job-of ctx)))

(defn- new-job
  "A Job for a suspend call made outside any coroutine body: without a parent, or (a call from a thread that inherited
  the context of a body) a child of the Job of `ctx`."
  ^Job [ctx] (let [^Job parent (job-of ctx)] (kotlinx.coroutines.JobKt/Job parent)))

(defn- cancelling?
  "Has the Job of `ctx` been cancelled (the body is being cancelled)?"
  [ctx]
  (let [job (job-of ctx)] (and job (not (.isActive job)))))

(defn- cancel-job! [^Job job] (.cancel job nil))

(defn- complete-job! [^kotlinx.coroutines.CompletableJob job] (.complete job))

(def hooks
  {:on-cancel on-cancel :enter enter :exit exit :map-exception map-exception
   :has-job? has-job? :new-job new-job :cancelling? cancelling? :cancel! cancel-job! :complete! complete-job!})
