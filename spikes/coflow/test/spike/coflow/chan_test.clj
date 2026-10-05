(ns spike.coflow.chan-test
  "The port type: a Kotlin Channel that works with core.async code (<!!, <!, alts!!, poll!, take!, put!, close!),
  and the helpers of spike.coflow.chan."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [ckway.core :as kt]
            [spike.coflow.chan :as cc]))

(set! *warn-on-reflection* true)

(kt/require '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.channels :as kch]
            '[kotlinx.coroutines.flow :as kflow]
            '[kotlin.coroutines :as kc])

(defn- with-scope [f]
  ;; Kotlin: val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  (let [scope (co/CoroutineScope (kc/.plus (co/SupervisorJob) (co/Default co/Dispatchers)))]
    (try (f scope)
         (finally (co/.cancel (co/job (.getCoroutineContext ^kotlinx.coroutines.CoroutineScope scope)))))))

(deftest core-async-reads-and-writes
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 3)]
        (is (true? (a/>!! p "a")))
        (is (= "a" (a/<!! p)))
        (is (nil? (a/poll! p)))
        (a/>!! p "b")
        (is (= "b" (a/poll! p)))
        (testing "a pending take, served by a later put"
          (let [f (future (a/<!! p))]
            (is (true? (cc/put!! p "late")))
            (is (= "late" (deref f 2000 :timeout)))))
        (testing "take! with a callback"
          (let [pr (promise)]
            (a/take! p (fn [v] (deliver pr v)))
            (a/>!! p "cb")
            (is (= "cb" (deref pr 2000 :timeout)))))
        (testing "go block"
          (let [g (a/go (a/<! p))]
            (a/>!! p "in-go")
            (is (= "in-go" (a/<!! g)))))
        (testing "put! with a callback"
          (let [pr (promise)]
            (a/put! p "x" (fn [ok] (deliver pr ok)))
            (is (true? (deref pr 2000 :timeout)))
            (is (= "x" (a/<!! p)))))
        (testing "nil can not be put"
          (is (thrown? IllegalArgumentException (a/>!! p nil))))
        (testing "close: buffered values stay, then nil"
          (a/>!! p "last")
          (a/close! p)
          (is (true? (cc/closed? p)))
          (is (= "last" (a/<!! p)))
          (is (nil? (a/<!! p)))
          (is (false? (a/>!! p "no"))))))))

(deftest alts-with-core-async-channels
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 2) c (a/chan 2) t (a/timeout 100)]
        (testing "alts!! across a port and a core.async channel"
          (a/>!! c "from-c")
          (is (= ["from-c" c] (a/alts!! [p c t])))
          (a/>!! p "from-p")
          (let [[v port] (a/alts!! [c p t])]
            (is (= "from-p" v)) (is (identical? p port))))
        (testing "timeout wins when nothing comes"
          (let [[v port] (a/alts!! [p c (a/timeout 50)])]
            (is (nil? v)) (is (not (identical? port p)))))
        (testing "alts!! with a put branch on a port"
          (let [[v port] (a/alts!! [[p "put-by-alts"]])]
            (is (true? v)) (is (identical? p port))
            (is (= "put-by-alts" (a/<!! p)))))))))

(deftest no-value-is-lost-or-doubled-when-alts-takes-another-branch
  ;; the hard case of the port: a take that waits on two channels. Whatever branch wins, a value that arrives
  ;; at the other one must stay in it, and a value goes to one taker only. 4 consumers use alts!! on [port other];
  ;; 1 producer fills both; everything arrives exactly once. Repeated: a double delivery (found once in three
  ;; suite runs, fixed in deliver!) showed up in about one round of two.
  (with-scope
    (fn [scope]
      (dotimes [round 12]
        (let [p (cc/port scope 4) o (a/chan 4) n 4000 got (atom [])
              done (promise)
              consumers (mapv (fn [_] (future (loop []
                                                (let [[v port] (a/alts!! [p o (a/timeout 5000)])]
                                                  (when (some? v)
                                                    (swap! got conj v)
                                                    (when (= n (count @got)) (deliver done true))
                                                    (recur))))))
                              (range 4))
              producer (future (dotimes [i n] (if (even? i) (a/>!! p i) (a/>!! o i))))]
          @producer
          (is (true? (deref done 20000 false)))
          (run! deref consumers)
          (is (= (range n) (sort @got)) (str "every value once, round " round)))))))

(deftest sliding-and-dropping-buffers
  (with-scope
    (fn [scope]
      (let [s (cc/port scope (a/sliding-buffer 2))
            d (cc/port scope (a/dropping-buffer 2))]
        (doseq [i (range 5)] (a/>!! s i) (a/>!! d i))
        (a/close! s) (a/close! d)
        (is (= [3 4] (cc/drain s)) "sliding: DROP_OLDEST")
        (is (= [0 1] (cc/drain d)) "dropping: DROP_LATEST")
        (is (= {:type 'SlidingBuffer :capacity 2 :overflow :drop-oldest} (cc/buffer-spec (a/sliding-buffer 2))))
        (is (= {:type 'DroppingBuffer :capacity 2 :overflow :drop-latest} (cc/buffer-spec (a/dropping-buffer 2))))
        (is (= {:type 'FixedBuffer :capacity 7 :overflow :suspend} (cc/buffer-spec 7)))))))

(deftest transducer-port
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 5 (comp (map inc) (partition-all 2)) nil)]
        (doseq [i (range 5)] (cc/send! p i))
        (cc/close! p)
        (is (= [[1 2] [3 4] [5]] (cc/drain p)) "the flush at close, as in core.async")))))

(deftest helpers
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 2)]
        (is (= ::cc/timeout (cc/take!! p 30)) "take!! with a timeout")
        (is (= :nothing (cc/take!! p 30 :nothing)))
        (is (true? (cc/put!! p "1")))
        (is (true? (cc/put!! p "2")))
        (is (= ::cc/timeout (cc/put!! p "3" 30)) "a put that does not fit in time")
        (is (= "1" (cc/poll! p)))
        (is (= "2" (cc/take!! p)))
        (is (nil? (cc/poll! p)))
        (testing "a value is not lost when a take times out just before a put"
          (dotimes [_ 200]
            (let [r (cc/take!! p 1)]
              (cc/put!! p "x")
              (let [v (if (= r ::cc/timeout) (cc/take!! p 1000) r)]
                (is (= "x" v))))))
        (cc/put!! p "a") (cc/put!! p "b")
        (cc/close! p)
        (is (= ["a" "b"] (vec (cc/->seq p))))
        (is (= [] (cc/drain p)))))))

(deftest port-as-kotlin-flow
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 10)]
        (doseq [i (range 5)] (cc/put!! p (str "m" i)))
        (cc/close! p)
        ;; Kotlin: flow.toList()
        (is (= ["m0" "m1" "m2" "m3" "m4"] (vec (kflow/.toList ^kotlinx.coroutines.flow.Flow (cc/->flow p)))))))))

(deftest datafy-shape-like-core-async
  (with-scope
    (fn [scope]
      (let [p (cc/port scope 4) c (a/chan 4)]
        (is (= (set (keys (clojure.datafy/datafy c))) (set (keys (clojure.datafy/datafy p)))))
        (is (= (set (keys (:buffer (clojure.datafy/datafy c)))) (set (keys (:buffer (clojure.datafy/datafy p))))))
        (is (= (:buffer (clojure.datafy/datafy c)) (:buffer (clojure.datafy/datafy p))))))))

(deftest ckway-suspend-function-that-returns-a-value-class
  ;; ckway batch D boxes the result of a suspend function whose Kotlin return type is a value class
  ;; (ChannelResult). Before, this threw: kt: `this` expects kotlinx.coroutines.channels.ChannelResult, got java.lang.String "a".
  ;; If this test fails after a ckway change, the value class is unboxed again.
  (let [c (kch/Channel 5)]
    (kch/.send c "a")
    (is (= "a" (kch/.getOrNull (kch/.receiveCatching c))))
    (kch/.close c)
    (is (nil? (kch/.getOrNull (kch/.receiveCatching c))) "closed")))
