(ns spike.coflow.exact-test
  "A proc with ports of the user waits on ONE arbitration, core.async's commit protocol (spike.coflow.chan/alts-ops):
  nothing is taken from a user's channel unless the proc consumes it, and nothing is put into it unless the proc writes it.
  The user's channel here is a test double that commits a take or a put exactly when the test says (so the window that
  the first version lost a message in is hit on purpose, not by luck)."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as impl]
            [ckway.core :as kt]
            [spike.coflow.arms :as arms]
            [spike.coflow.chan :as cc]
            [spike.coflow.flow :as flow]
            [spike.coflow.ext :as ext])
  (:import [java.util.concurrent.locks Lock]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co]
            '[kotlin.coroutines :as kc])

;; --- doubles --------------------------------------------------------------------------------------------------------

(defn latch-read-port
  "A user's channel (ReadPort) that holds a message and gives it to a registered take only when `(offer! p v)` is
  called. offer! returns true if a take committed (the message left the user's channel), false if no registered take was
  still free (the message stays with the user)."
  []
  (let [handlers (atom [])]
    {:port (reify impl/ReadPort
             (take! [_ h] (swap! handlers conj h) nil))
     :registered #(count @handlers)
     :offer! (fn [v]
               (boolean
                (some (fn [^Lock h]
                        (.lock h)
                        (try (when (impl/active? h)
                               (let [cb (impl/commit h)] (cb v) true))
                             (finally (.unlock h))))
                      @handlers)))}))

(defn latch-write-port
  "A user's channel (WritePort) that takes a registered put only when `(accept!)` is called. accept! returns the message
  that was put, or nil if no registered put was still free."
  []
  (let [puts (atom [])]
    {:port (reify impl/WritePort
             (put! [_ v h] (swap! puts conj [h v]) nil))
     :registered #(count @puts)
     :accept! (fn []
                (some (fn [[^Lock h v]]
                        (.lock h)
                        (try (when (impl/active? h)
                               (let [cb (impl/commit h)] (cb true) v))
                             (finally (.unlock h))))
                      @puts))}))

(defn- await-n [f n]
  (loop [i 0] (when (and (< (f) n) (< i 4000)) (deref (promise) 1 nil) (recur (inc i))))
  (>= (f) n))

(defn- offer-until
  "offer until a registered take is free (the proc registers a new one after each control message), at most 3 s"
  [offer! v]
  (loop [i 0] (cond (offer! v) true (> i 3000) false :else (do (deref (promise) 1 nil) (recur (inc i))))))

(defn- with-scope [f]
  (let [scope (co/CoroutineScope (kc/.plus (co/SupervisorJob) (co/Default co/Dispatchers)))]
    (try (f scope) (finally (co/.cancel (co/job (.getCoroutineContext ^kotlinx.coroutines.CoroutineScope scope)))))))

;; --- the arbitration itself (deterministic) -------------------------------------------------------------------------

(deftest control-wins-then-the-user-channel-gives-nothing
  (with-scope
    (fn [scope]
      (let [control (cc/port scope 10)
            {:keys [port registered offer!]} (latch-read-port)
            res (future (cc/alts-ops [[:take control :control] [:take port :user]]))]
        (is (true? (await-n registered 1)) "the take on the user's channel is registered")
        (cc/send! control :cmd)
        (is (= [:cmd :control] (deref res 3000 :hang)))
        (is (false? (offer! "m")) "control committed first: the user's channel is not asked to give its message")))))

(deftest user-channel-wins-then-control-stays-in-its-port
  (with-scope
    (fn [scope]
      (let [control (cc/port scope 10)
            {:keys [port registered offer!]} (latch-read-port)
            res (future (cc/alts-ops [[:take control :control] [:take port :user]]))]
        (is (true? (await-n registered 1)))
        (is (true? (offer! "m")))
        (is (= ["m" :user] (deref res 3000 :hang)))
        (cc/send! control :cmd)
        (is (= [:cmd :control] (cc/alts-ops [[:take control :control]])) "the control message was not lost")))))

(deftest control-in-a-port-that-the-pump-already-read-stays-in-order
  ;; the pump of our port receives a value for a handler that then loses: the value stays in the port (stash), in order
  (with-scope
    (fn [scope]
      (let [control (cc/port scope 10)
            {:keys [port registered offer!]} (latch-read-port)
            res (future (cc/alts-ops [[:take control :control] [:take port :user]]))]
        (is (true? (await-n registered 1)))
        (is (true? (offer! "m")))
        (deref res 3000 :hang)
        ;; the dead handler of `control` is still in its taker queue: three messages arrive now
        (doseq [i [1 2 3]] (cc/send! control i))
        (is (= [1 2 3] (mapv (fn [_] (first (cc/alts-ops [[:take control :control]]))) (range 3)))
            "no message lost or reordered")))))

(deftest a-put-to-the-user-channel-happens-only-if-control-did-not-win
  (with-scope
    (fn [scope]
      (let [control (cc/port scope 10)
            {:keys [port registered accept!]} (latch-write-port)
            res (future (cc/alts-ops [[:take control :control] [:put port "out" :put]]))]
        (is (true? (await-n registered 1)))
        (cc/send! control :cmd)
        (is (= [:cmd :control] (deref res 3000 :hang)))
        (is (nil? (accept!)) "control won: the message is not put into the user's channel")))))

(deftest a-put-wins-when-the-user-channel-accepts-first
  (with-scope
    (fn [scope]
      (let [control (cc/port scope 10)
            {:keys [port registered accept!]} (latch-write-port)
            res (future (cc/alts-ops [[:take control :control] [:put port "out" :put]]))]
        (is (true? (await-n registered 1)))
        (is (= "out" (accept!)))
        (is (= [true :put] (deref res 3000 :hang)))
        (cc/send! control :cmd)
        (is (= [:cmd :control] (cc/alts-ops [[:take control :control]])))))))

(deftest the-race-1000-times-no-message-is-taken-and-not-delivered
  ;; control and the user's channel offer in the same moment: exactly one wins; the loser keeps its message
  (with-scope
    (fn [scope]
      (let [taken-by-user (atom 0) delivered-to-proc (atom 0) control-seen (atom 0) control-sent (atom 0)]
        (dotimes [_ 1000]
          (let [control (cc/port scope 10)
                {:keys [port registered offer!]} (latch-read-port)
                res (future (cc/alts-ops [[:take control :control] [:take port :user]]))]
            (await-n registered 1)
            (let [go (promise)
                  f1 (future @go (dotimes [_ (rand-int 3000)] (Thread/onSpinWait)) (offer! "m"))
                  f2 (future @go (cc/send! control :cmd))]
              (deliver go true)
              (let [offered @f1 _ @f2 r (deref res 3000 :hang)]
                (swap! control-sent inc)
                (when offered (swap! taken-by-user inc))
                (if (= :user (second r)) (swap! delivered-to-proc inc) (swap! control-seen inc))
                ;; the control message is always still available: either the proc took it, or it is in the port
                (when (= :user (second r))
                  (is (= [:cmd :control] (cc/alts-ops [[:take control :control]]))))
                (is (= offered (= :user (second r))) "the user's channel gave a message iff the proc got it")))))
        (println (format "[arbitration] 1000 races: user's channel won %d, control won %d; every message delivered or untouched"
                         @delivered-to-proc @control-seen))
        (is (= @taken-by-user @delivered-to-proc))))))

;; --- a flow with a user's channel in ::flow/in-ports ----------------------------------------------------------------------

(defn- in-port-flow [src]
  (let [seen (atom [])
        step (flow/map->step
              {:describe (fn [] {:params {:src ""}})
               :init (fn [{:keys [src]}] {:clojure.core.async.flow/in-ports {:src src}})
               :transform (fn [s _ m] (swap! seen conj m) [s nil])})
        g (flow/create-flow {:procs {:p {:proc (flow/process step) :args {:src src}}} :conns []})]
    [g seen]))

(deftest proc-waiting-on-the-user-channel-pause-stop-take-nothing
  (let [{:keys [port registered offer!]} (latch-read-port)
        [g seen] (in-port-flow port)]
    (flow/start g)
    (flow/resume g)
    (flow/ping-proc g :p)
    (is (true? (await-n registered 1)) "the running proc waits on the user's channel")
    (flow/pause g)
    (flow/ping-proc g :p)
    (is (false? (offer! "m1")) "paused: nothing is taken (every registered take is dead)")
    (flow/resume g)
    (flow/ping-proc g :p)
    (is (true? (offer-until offer! "m2")) "running again: the message is taken and consumed")
    (flow/ping-proc g :p)
    (is (= ["m2"] @seen))
    (flow/stop g)
    (is (true? (ext/await-stopped g 5000)))
    (is (false? (offer! "m3")) "after stop nothing is taken")
    (is (= ["m2"] @seen))))

(deftest proc-with-a-user-out-port-puts-only-if-it-writes
  (let [{:keys [port registered accept!]} (latch-write-port)
        step (flow/map->step
              {:describe (fn [] {:params {:snk ""} :ins {:in ""}})
               :init (fn [{:keys [snk]}] {:clojure.core.async.flow/out-ports {:snk snk}})
               :transform (fn [s _ m] [s {:snk [m]}])})
        g (flow/create-flow {:procs {:p {:proc (flow/process step) :args {:snk port}}} :conns []})]
    (flow/start g) (flow/resume g)
    @(flow/inject g [:p :in] [1])
    (is (true? (await-n registered 1)) "the proc is blocked in its write to the user's channel")
    (flow/stop g)
    (is (true? (ext/await-stopped g 5000)) "the proc left its blocked write on stop")
    (is (nil? (accept!)) "the message of the abandoned write is not put into the user's channel")))

;; --- stress: stops in the flood, loss must be 0 in every arm ----------------------------------------------------------------

(deftest in-port-stop-race-loses-nothing
  (doseq [arm arms/arms]
    (let [rs (arms/call arm 'inports-stop-race 300)]
      (println (format "[in-port stop race] %-8s 300 rounds (a put into the user's channel and a stop in the same moment): lost %d, consumed %d, left in the channel %d"
                       (name arm) (:lost rs) (:consumed rs) (:left rs)))
      (is (zero? (:lost rs)) (str arm " no message lost")))))
