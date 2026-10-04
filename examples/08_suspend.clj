;; # 8. Suspend functions
;;
;; A `suspend` function is a usual var. The call gives its result when the result is ready.
;; A Clojure function that you give where Kotlin wants a `suspend` lambda runs on a virtual thread.
;; This needs JDK 21 or newer. Alias needed: `:examples` (the alias has kotlinx.coroutines).
(ns examples.08-suspend
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s]
            '[kotlin.time :as t]
            '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.flow :as flow])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def cart (s/cart :build (fn [c] (s/.add c tea 2))))

;; ## Call a suspend function
;;
;; Kotlin: `suspend fun slowSum(a: Int, b: Int): Int` calls `delay(5)`. In Clojure you call it as any function.
;; The thread waits for the result. You need no coroutine scope and no special form.

;; Kotlin: slowSum(1, 2)    -- inside a coroutine
(s/slowSum 1 2)
;; => 3

;; Kotlin: fetchProduct(1)?.name
(s/name (s/fetchProduct 1))
;; => "Tea"

;; Defaults and `nil` work as in any other call.
;; Kotlin: fetchProduct(99)
(s/fetchProduct 99)
;; => nil

;; Kotlin: fetchProduct(2, wait = 1)?.name
(s/name (s/fetchProduct 2 :wait 1))
;; => "Coffee"

;; A suspend extension function:
;; Kotlin: cart.slowTotal()
(str (s/.slowTotal cart))
;; => "7.00"

;; A suspend function with a `Duration`:
;; Kotlin: waitFor(10.milliseconds)
(s/waitFor (t/milliseconds t/Duration 10))
;; => "waited 10 ms"

;; The call waits, but it is not slow: the delay is the only cost.
(let [t0 (System/nanoTime)]
  (s/slowSum 1 1)
  (< (/ (- (System/nanoTime) t0) 1e6) 500))
;; => true

;; The exception of a suspend function is the original exception.
;; Kotlin: retrying(times = 2) { error("fail $it") }
(err (s/retrying :times 2 :block (fn [attempt] (throw (IllegalStateException. (str "fail " attempt))))))
;; => "fail 2"

;; ## A Clojure function as a suspend lambda
;;
;; Kotlin: `suspend fun retrying(times: Int = 3, block: suspend (Int) -> String): String`.
;; The function can call suspend functions. It runs on a virtual thread, so waiting is cheap.

;; Kotlin: retrying { attempt -> if (attempt < 3) error("no") else "ok $attempt" }
(s/retrying :block (fn [attempt]
                     (if (< attempt 3)
                       (throw (IllegalStateException. "no"))
                       (str "ok " attempt))))
;; => "ok 3"

;; Kotlin: retrying(2) { "x" }    -- `times` given, so the lambda can be positional
(s/retrying 2 (fn [attempt] "x"))
;; => "x"

;; Skip the default `pause` and name the suspend lambda (`charge`, which has a default too):
;; Kotlin: checkout(cart) { money -> money.cents < 100 }
(str (s/checkout cart :charge (fn [money] (< (s/cents money) 100))))
;; => "Receipt(owner=guest, total=7.00, status=NEW)"

;; Kotlin: cart.status
(str (s/status cart))
;; => "NEW"

;; ## Suspend at any depth
;;
;; Clojure functions are not colored. A suspend call can be in a nested `fn`, in `mapv`, in `loop`, in `try`.
;; Use eager functions: a lazy sequence runs after the body has ended.

(s/retrying :block (fn [attempt]
                     (str (mapv (fn [i] (s/slowSum i attempt)) [1 2 3]))))
;; => "[2 3 4]"

(s/retrying :block (fn [attempt]
                     (loop [i 0 acc 0]
                       (if (< i 5)
                         (recur (inc i) (s/slowSum acc 1))
                         (str "loop " acc)))))
;; => "loop 5"

;; The body runs on a virtual thread. The REPL thread is a platform thread.
(s/retrying :block (fn [attempt] (str (.isVirtual (Thread/currentThread)))))
;; => "true"

(.isVirtual (Thread/currentThread))
;; => false

;; ## Kotlin's own builders
;;
;; `runBlocking`, `launch`, `async`, `await`, `join` and `withContext` are normal Kotlin functions.
;; The block is a suspend lambda with a `CoroutineScope` receiver, so it takes the scope as its first parameter.
;; `runBlocking` has a default `context` before the block, so the block is named.

;; Kotlin: runBlocking { slowSum(1, 2) }
(co/runBlocking :block (fn [scope] (s/slowSum 1 2)))
;; => 3

;; Kotlin: runBlocking { val a = async { ... }; val b = async { ... }; listOf(a.await(), b.await()) }
(co/runBlocking :block
                (fn [scope]
                  (let [a (co/.async scope :block (fn [_] (co/delay 20) :a))
                        b (co/.async scope :block (fn [_] (co/delay 20) :b))]
                    [(co/.await a) (co/.await b)])))
;; => [:a :b]

;; Two delays of 100 ms run at the same time, so they take about 100 ms, not 200 ms.
(let [t0 (System/nanoTime)]
  (co/runBlocking :block
                  (fn [scope]
                    (let [jobs [(co/.async scope :block (fn [_] (co/delay 100)))
                                (co/.async scope :block (fn [_] (co/delay 100)))]]
                      (mapv co/.await jobs))))
  (< (/ (- (System/nanoTime) t0) 1e6) 190))
;; => true

;; Kotlin: runBlocking { val job = launch { ... }; job.join() }
(let [log (atom [])]
  (co/runBlocking :block
                  (fn [scope]
                    (let [job (co/.launch scope :block (fn [_] (co/delay 10) (swap! log conj :child)))]
                      (co/.join job)
                      (swap! log conj :joined))))
  @log)
;; => [:child :joined]

;; Kotlin: withContext(Dispatchers.Default) { slowSum(2, 3) }
(co/runBlocking :block
                (fn [scope]
                  (co/withContext (co/Default co/Dispatchers) (fn [_] (s/slowSum 2 3)))))
;; => 5

;; ## Cancellation
;;
;; `cancel` stops a coroutine. The `finally` block of the Clojure function runs.

;; Kotlin: val job = launch { try { delay(10_000) } finally { log += "cleanup" } }; delay(20); job.cancel(); job.join()
(let [log (atom [])]
  (co/runBlocking :block
                  (fn [scope]
                    (let [job (co/.launch scope :block
                                          (fn [_]
                                            (try (co/delay 10000)
                                                 (finally (swap! log conj :cleanup)))))]
                      (co/delay 20)
                      (co/.cancel job)
                      (co/.join job)
                      (swap! log conj (co/isCancelled job)))))
  @log)
;; => [:cleanup true]

;; ## Flow
;;
;; Kotlin: `fun productFlow(): Flow<Product>`. A `Flow` is cold: nothing runs until you collect it.
;; `FlowCollector` is a `fun interface` with a suspend method, so a Clojure function goes there.

;; Kotlin: productFlow().collect { names += it.name }
(let [names (atom [])]
  (flow/.collect (s/productFlow) (fn [p] (swap! names conj (s/name p))))
  @names)
;; => ["Tea" "Coffee" "Cake" "Water"]

;; Kotlin: productFlow().toList().size
(count (flow/.toList (s/productFlow)))
;; => 4

;; The collector can suspend. The producer waits for it (back-pressure).
(let [t0 (System/nanoTime)]
  (flow/.collect (s/productFlow) (fn [p] (co/delay 30)))
  (>= (/ (- (System/nanoTime) t0) 1e6) 120))
;; => true

;; ## Many concurrent bodies
;;
;; Each body is a virtual thread, so 1000 bodies that wait 100 ms take about 100 ms, not 100 seconds.

(let [t0 (System/nanoTime)
      results (co/runBlocking :block
                              (fn [scope]
                                (mapv co/.await
                                      (mapv (fn [i] (co/.async scope :block (fn [_] (co/delay 100) (* i i))))
                                            (range 1000)))))]
  [(count results) (reduce + results) (< (/ (- (System/nanoTime) t0) 1e6) 3000)])
;; => [1000 332833500 true]
