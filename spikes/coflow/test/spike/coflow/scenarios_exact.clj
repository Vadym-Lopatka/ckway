;; Third round of user code: the user's channels must lose nothing (see exact_test.clj). Compiled into the arm
;; namespaces after scenarios_ext.clj.

(defn inports-stop-race
  "A put into the user's channel and a stop in the same moment, many times. A message must be consumed by the proc or
  stay in the user's channel: lost = rounds in which it is neither."
  [rounds]
  (let [lost (atom 0) consumed (atom 0) left (atom 0)]
    (dotimes [_ rounds]
      (let [src (a/chan 1) seen (atom [])
            step (flow/map->step
                  {:describe (fn [] {:params {:src ""}})
                   :init (fn [{:keys [src]}] {::fk/in-ports {:src src}})
                   :transform (fn [s _ m] (swap! seen conj m) [s nil])})
            [g report error] (mk {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []})]
        (flow/resume g)
        (flow/ping-proc g :p :timeout-ms 5000)
        (let [go (promise)
              f1 (future @go (a/>!! src :m))
              f2 (future @go (flow/stop g))]
          (deliver go true)
          @f1 @f2
          (deref (promise) 60 nil)
          (let [l (drain-poll src)
                c (count @seen)
                ;; neither yet: allow a slow machine one more look before it counts as lost
                [c l] (if (zero? (+ c (count l)))
                        (do (deref (promise) 400 nil) [(count @seen) (drain-poll src)])
                        [c l])]
            (swap! consumed + c) (swap! left + (count l))
            (when (zero? (+ c (count l))) (swap! lost inc))))))
    {:lost @lost :consumed @consumed :left @left}))
