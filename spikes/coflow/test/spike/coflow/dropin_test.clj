(ns spike.coflow.dropin-test
  "Runs only in the JVM that has `dropin` first on the class path (alias :dropin, see bin/test): there
  `clojure.core.async.flow` is the shim, so the user's `require` is the original one and the flow is the port."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [spike.coflow.flow]
            [spike.coflow.ext :as ext]))

(deftest ^:dropin the-shim-is-loaded-and-is-the-port
  (is (str/includes? (str (io/resource "clojure/core/async/flow.clj")) "/dropin/") "the shim is first on the class path")
  (is (identical? @#'flow/create-flow @#'spike.coflow.flow/create-flow))
  (is (= (set (keys (ns-publics 'spike.coflow.flow))) (set (keys (ns-publics 'clojure.core.async.flow)))))
  (is (= '([config]) (:arglists (meta #'flow/create-flow))))
  (is (string? (:doc (meta #'flow/create-flow))))
  (testing "the keywords are the original's, as the namespace is"
    (is (= :clojure.core.async.flow/pause ::flow/pause))))

(deftest ^:dropin user-code-with-the-original-require-runs-on-coroutines
  (let [step (flow/map->step
              {:describe (fn [] {:ins {:in ""}})
               :init (fn [_] {})
               :transform (fn [s _ m] [s {::flow/report [(* 2 m)]}])})
        g (flow/create-flow {:procs {:p {:proc (flow/process step)}} :conns []})
        {:keys [report-chan]} (flow/start g)]
    (flow/resume g)
    @(flow/inject g [:p :in] [21])
    (is (= 42 (a/<!! report-chan)))
    (flow/stop g)
    (is (nil? (a/<!! report-chan)))
    (is (true? (ext/await-stopped g 5000)) "the port's extra function works on this flow: it is the coroutine port")))
