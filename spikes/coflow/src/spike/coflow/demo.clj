(ns spike.coflow.demo
  "A small flow that prints, then stops cleanly. Run: clojure -M:run"
  (:require [spike.coflow.flow :as flow]
            [spike.coflow.chan :as chan]))

(set! *warn-on-reflection* true)

(defn -main [& _]
  (let [;; the keywords of the flow are the ones of clojure.core.async.flow
        report :clojure.core.async.flow/report
        step (flow/map->step
              {:describe (fn [] {:ins {:in "numbers"}})
               :init (fn [_] {:sum 0})
               :transform (fn [{:keys [sum]} _ n]
                            (let [s (+ sum n)]
                              [{:sum s} {report [{:got n :sum s}]}]))})
        g (flow/create-flow
           {:procs {:double {:proc (flow/process (flow/lift1->step #(* 2 %)))}
                    :sum {:proc (flow/process step)}}
            :conns [[[:double :out] [:sum :in]]]})
        {:keys [report-chan error-chan]} (flow/start g)]
    (flow/resume g)
    @(flow/inject g [:double :in] [1 2 3])
    (dotimes [_ 3]
      (println "report:" (chan/take!! report-chan 3000)))
    (println "ping:" (-> (flow/ping-proc g :sum) :clojure.core.async.flow/status))
    (println "stop:" (flow/stop g))
    (println "report chan after stop:" (chan/take!! report-chan 1000) "(nil = closed)")
    (shutdown-agents)))
