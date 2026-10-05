(ns spike.coflow.ext
  "Extras that are NOT part of the clojure.core.async.flow API: what the coroutine port adds."
  (:require [ckway.core :as kt]
            [spike.coflow.impl :as impl]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co])

(defn await-stopped
  "Waits until the cleanup of the stopped runs of the flow `g` is complete: every coroutine of the old scopes is done
  (the grace time of `stop` is over, or they ended before). `stop` itself returns at once, as in clojure.core.async.flow.
  Returns true, or false when `timeout-ms` passed first. Returns true at once when the flow was never stopped."
  [g timeout-ms]
  (boolean
   (every? (fn [^kotlinx.coroutines.Job j]
             ;; Kotlin: withTimeoutOrNull(ms) { job.join(); true } ?: false
             (co/withTimeoutOrNull (long timeout-ms) (fn [_] (co/.join j) true)))
           (impl/reaper-jobs g))))

(defn running-scope
  "The CoroutineScope of the running flow `g` (its Job is the scope's SupervisorJob), or nil when it is not running."
  ^kotlinx.coroutines.CoroutineScope [g]
  (impl/flow-scope g))
