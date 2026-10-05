(ns spike.coflow.takeover
  "Drop-in mode: user code that requires `clojure.core.async.flow` stays as it is, and runs on coroutines.

  `install!` replaces the root of three vars of clojure.core.async.flow: `create-flow`, `process` and `futurize`.
  Everything else (`start`, `stop`, `ping`, `inject`, ... `lift1->step`, ...) works on them through the Graph protocol or
  is plain data code, so it needs no change. Keywords stay `:clojure.core.async.flow/...` for free, because the
  namespace is the same one.

  `install!` returns a function that puts the old roots back. This changes the vars of a library for the whole JVM:
  use it in an application, not in a library. The primary way to use the port is `spike.coflow.flow` (see README)."
  (:require [spike.coflow.flow :as port]))

(set! *warn-on-reflection* true)

(defn install!
  "Replaces create-flow, process and futurize of clojure.core.async.flow by the coroutine versions.
  Returns a function (no args) that restores the original roots."
  []
  (require 'clojure.core.async.flow)
  (let [targets {'create-flow #'port/create-flow
                 'process #'port/process
                 'futurize #'port/futurize}
        ns* (the-ns 'clojure.core.async.flow)
        olds (into {} (map (fn [[k _]] [k (var-get (ns-resolve ns* k))]) targets))]
    (doseq [[k v] targets]
      (alter-var-root (ns-resolve ns* k) (constantly (var-get v))))
    (fn restore! []
      (doseq [[k f] olds]
        (alter-var-root (ns-resolve ns* k) (constantly f))))))

(defmacro with-port
  "Runs the body with the port installed behind clojure.core.async.flow."
  [& body]
  `(let [restore# (install!)]
     (try ~@body (finally (restore#)))))
