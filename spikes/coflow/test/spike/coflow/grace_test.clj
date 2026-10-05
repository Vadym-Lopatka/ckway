(ns spike.coflow.grace-test
  "The default of `stop` is the original's: user code is never interrupted. The forced cancel after a grace time is an opt-in."
  (:require [clojure.test :refer :all]
            [spike.coflow.flow :as flow]
            [spike.coflow.ext :as ext]))

(defn- mk [ms interrupted finished entered]
  (flow/create-flow
   {:procs {:p {:proc (flow/process
                       (flow/map->step
                        {:describe (fn [] {:ins {:in ""}})
                         :init (fn [_] {})
                         :transform (fn [s _ _]
                                      (deliver entered true)
                                      (try (deref (promise) ms nil)
                                           (catch InterruptedException e (reset! interrupted true)))
                                      (deliver finished true)
                                      [s nil])}))}}
    :conns []}))

(deftest default-stop-never-interrupts-user-code
  (let [interrupted (atom false) finished (promise) entered (promise)
        g (mk 1500 interrupted finished entered)]
    (flow/start g) (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (is (true? (deref entered 5000 false)))
    (is (true? (flow/stop g)))
    (is (true? (ext/await-stopped g 30000)) "the reaper ends when the step fn has ended")
    (is (true? (deref finished 100 false)) "the step fn ran to its end")
    (is (false? @interrupted) "and was never interrupted")))

(deftest grace-is-opt-in
  (let [interrupted (atom false) finished (promise) entered (promise)
        g (mk 60000 interrupted finished entered)]
    (flow/start g) (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (is (true? (deref entered 5000 false)))
    (is (true? (ext/stop-with-grace g 200)))
    (is (true? (ext/await-stopped g 30000)))
    (is (true? @interrupted) "the step fn was interrupted by the forced cancel")))
