(ns kt.co.kx
  "The part of kt.co that needs kotlinx.coroutines (loaded only if it is on the class path):
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

(defn- enter
  "Apply every ThreadContextElement of `ctx` to this thread. => state for `exit`."
  [ctx]
  (let [els (elements ctx)]
    (mapv (fn [^ThreadContextElement el] [el (.updateThreadContext el ctx)]) els)))

(defn- exit
  "Restore what `enter` changed and dispose the cancellation handle."
  [ctx entered ^DisposableHandle cancel]
  (doseq [[^ThreadContextElement el state] (rseq entered)]
    (.restoreThreadContext el ctx state))
  (when cancel (.dispose cancel)))

(defn- map-exception
  "An interrupt that the Job's cancellation caused is a CancellationException, as in Kotlin."
  [ctx ^Throwable e]
  (let [job (job-of ctx)]
    (if (and (instance? InterruptedException e) job (not (.isActive job)))
      (doto (java.util.concurrent.CancellationException. "kt: coroutine body cancelled") (.initCause e))
      e)))

(def hooks
  {:on-cancel on-cancel :enter enter :exit exit :map-exception map-exception})
