(ns ckway.suspend-test
  "Step 4: Kotlin `suspend` (DESIGN-2 note 'suspend'; spike D checks S1-S12, X1-X3).
  A suspend call waits for its result; a Clojure function at a suspend function type or at a
  `fun interface` with a suspend method is a coroutine body on its own virtual thread (ckway.co)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct :refer [both compile-error]]
            [ckway.core :as kt]
            [ckway.resolve :as r]
            [ckway.rt :as rt])
  (:import [java.io StringWriter]
           [java.util.concurrent CancellationException]))

(kt/require '[fx :as f] '[kotlinx.coroutines :as co] '[kotlinx.coroutines.flow :as flow] '[kotlin.time :as t])

(def ^:dynamic *v* :root)

(defn- thrown [f] (try (f) nil (catch Throwable t t)))
(defn- ev [form] (binding [*ns* (the-ns 'ckway.suspend-test)] (eval form)))
(defn- expansions
  "The forms that the static and the dynamic path emit while the compiler compiles `form` here, and
  the reflection warnings: {:static [..] :dynamic [..] :warnings text}."
  [form]
  (let [static (atom []) dyn (atom []) emit r/emit dynf r/dynamic-form w (StringWriter.)]
    (with-redefs [r/emit (fn [p] (let [x (emit p)] (swap! static conj x) x))
                  r/dynamic-form (fn [v parsed] (let [x (dynf v parsed)] (swap! dyn conj x) x))]
      (binding [*warn-on-reflection* true *err* w] (ev form)))
    {:static @static :dynamic @dyn :warnings (str w)}))
(defmacro ^:private timed [& body]
  `(let [t0# (System/nanoTime) v# (do ~@body)] [(/ (- (System/nanoTime) t0#) 1e6) v#]))
(defn- await-true [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 5) (recur))))))

;; ---------------------------------------------------------------- S-A calling a suspend function

(deftest s-e-kotlinx-part-is-loaded-when-available
  (f/runB (fn [x] (f/step 1)))
  (is (some? (find-ns 'ckway.co.kx)) "kotlinx.coroutines is on the class path here, so the Job/ThreadContextElement part is used"))

(deftest s-a-top-level-and-members
  (testing "a suspend function is a usual var; the call gives the result (static and dynamic path)"
    (both 2 f/step 1)
    (both 7 f/now)
    (both "hi you x1" f/greetLater)
    (both "hi bob x1" f/greetLater "bob")
    (both "hi you x5" f/greetLater :n 5)
    (both "hi bob x2" f/greetLater "bob" 2))
  (testing "member with a skipped default ($default), extension, companion, inherited"
    (let [svc (f/Svc "p")]
      (both "p:k:1" f/.load svc "k")
      (both "p:k:5" f/.load svc "k" 5)
      (both "p:k:7" f/.load svc "k" :n 7))
    (both "ABC!!" f/.shoutLater "abc")
    (both "ABC!" f/.shoutLater "abc" 1)
    (is (= "made3" (.getPrefix ^fx.Svc (f/.build f/Svc 3))))
    (is (= "p4" (.getPrefix ^fx.Svc (f/.build f/Svc 4 "p"))))
    (is (= "p4" (.getPrefix ^fx.Svc (rt/call-dyn #'f/.build [f/Svc 4 "p"] {}))))
    (both "base:q" f/.loadBase (f/LoaderImpl) "q"))
  (testing "Unit is nil, a nullable result may be nil"
    (both nil f/unitStep)
    (both nil f/.nothing (f/Svc))
    (both nil f/nullStep 0)
    (both "n3" f/nullStep 3))
  (testing "no suspension: the value comes back at once"
    (let [[ms v] (timed (f/now))]
      (is (= 7 v))
      (is (< ms 500)))))

(deftest s-a-value-classes
  (testing "Duration parameter (mangled name, bridged), with a default"
    (both "w:5" f/waitFor (t/milliseconds t/Duration 5))
    (both "x:5" f/waitFor (t/milliseconds t/Duration 5) "x")
    (both "x:5" f/waitFor (t/milliseconds t/Duration 5) :tag "x"))
  (testing "value class result and parameter: always the object"
    (let [tk (f/mkTicket 5)]
      (is (instance? fx.Ticket tk))
      (is (= 5 (.getN ^fx.Ticket tk)))
      (is (= tk (rt/call-dyn #'f/mkTicket [5] {})))
      (is (= (f/mkTicket 6) (f/ticketNext tk)))
      (is (= (f/mkTicket 6) (rt/call-dyn #'f/ticketNext [tk] {})))
      (is (nil? (f/ticketOrNull nil)))
      (is (= (f/mkTicket 6) (f/ticketOrNull tk)))
      (is (= (f/mkTicket 6) (rt/call-dyn #'f/ticketOrNull [tk] {}))))
    (is (instance? fx.Ticket (f/mkTicket 1)))
    (is (str/includes? (str (compile-error '(f/ticketNext 5))) "kt:"))))

(deftest s-a-failures
  (testing "the original exception, not wrapped (static, dynamic)"
    (doseq [e [(thrown #(f/boom)) (thrown #(rt/call-dyn #'f/boom [] {}))]]
      (is (instance? IllegalStateException e))
      (is (= "boom" (ex-message e))))
    (doseq [e [(thrown #(f/boomIo)) (thrown #(rt/call-dyn #'f/boomIo [] {}))]]
      (is (instance? java.io.IOException e))))
  (testing "a Clojure caller can catch it"
    (is (= "boom" (try (f/boom) (catch IllegalStateException e (ex-message e)))))))

(deftest s-a-waits-on-platform-and-virtual-threads
  (testing "the test thread is a platform thread: it blocks until the result is ready"
    (is (not (.isVirtual (Thread/currentThread))))
    (let [[ms v] (timed (f/step 1))]
      (is (= 2 v))
      (is (>= ms 5) "delay(10) took at least a few ms")
      (is (< ms 3000))))
  (testing "inside a body the thread is virtual and stays virtual across suspends"
    (is (= [true true] (f/runB (fn [x] [(f/isVirtualNow) (do (f/step 1) (f/isVirtualNow))])))))
  (testing "Kotlin sees a coroutine that suspended: a delay of 10ms inside a body does not block the Kotlin thread"
    (let [[ms v] (timed (f/manyIt 50 (fn [i] (f/step i))))]
      (is (< ms 2000)))))

(deftest s-a-context
  (testing "outside any body the callee gets a context with no dispatcher and a Job of its own (review B6)"
    (is (false? (f/hasDispatcher)))
    (is (some? (f/currentJob)) "a top-level call has its own Job (it used to have none: EmptyCoroutineContext)")
    (is (true? (f/isActiveNow)))
    (is (nil? (f/ctxTag))))
  (testing "inside a body it gets the body's coroutine context: dispatcher, Job, elements"
    (is (= [true true] (f/runB (fn [x] [(f/hasDispatcher) (some? (f/currentJob))]))))
    (is (= "t1" (f/runTaggedB "t1" (fn [x] (f/ctxTag)))))
    (is (= "t1" (rt/call-dyn #'f/runTaggedB ["t1" (fn [x] (f/ctxTag))] {}))))
  (testing "the Job is the one of the coroutine that runs the body (launch returns it)"
    (let [p (promise)
          sc (f/defaultScope)
          job (f/launchIt sc (fn [x] (deliver p (f/currentJob))))]
      (try (is (identical? job (deref p 5000 :timeout)))
           (finally (f/cancelScope sc)))))
  (testing "structured concurrency: runBlocking waits for a child that the body launched with its scope"
    (let [done (atom false)]
      (co/runBlocking :block
                      (fn [scope]
                        (co/.launch scope :block (fn [s] (co/delay 100) (reset! done true)))
                        :body-returned))
      (is @done "the child finished before runBlocking returned"))))

;; ---------------------------------------------------------------- S-A static proof

(deftest s-a-static-path
  (let [svc (f/Svc "p")
        cases {"(f/step 1)" '(fn [] (f/step 1))
               "(f/.load svc \"k\")" '(fn [^fx.Svc svc] (f/.load svc "k"))
               "(f/ticketNext tk) (bridged, mangled name)" '(fn [^fx.Ticket tk] (f/ticketNext tk))
               "(co/delay 10)" '(fn [] (co/delay 10))
               "(f/runIt (fn [x] (f/step x))) (adapter + inner call)" '(fn [] (f/runIt (fn [x] (f/step x))))
               "(f/greetLater) ($default)" '(fn [] (f/greetLater))
               "(co/delay (t/milliseconds t/Duration 10)) (bridged)" '(fn [] (co/delay (t/milliseconds t/Duration 10)))}]
    (doseq [[label form] cases]
      (let [x (expansions form)
            text (str/join " | " (map pr-str (:static x)))]
        (testing label
          (is (seq (:static x)))
          (is (empty? (:dynamic x)) "no call-dyn")
          (is (= "" (:warnings x)) "no reflection warning")
          (is (str/includes? text "ckway.co/wait-for") text)
          (is (str/includes? text "ckway.co/continuation") text)
          (is (not (str/includes? text "call-dyn")) text))
        (println "EXPANSION" label "=>" text)))
    (testing "the adapter of a suspend function type runs the body on a virtual thread"
      (let [text (pr-str (:static (expansions '(fn [] (f/runIt (fn [x] (f/step x)))))))]
        (is (str/includes? text "ckway.co/run-body"))
        (is (str/includes? text "ckway.co/capture-frame"))
        (is (str/includes? text "kotlin.jvm.functions.Function2"))))
    (testing "bridged calls go through a bridge class"
      (is (str/includes? (pr-str (:static (expansions '(fn [^fx.Ticket tk] (f/ticketNext tk))))) "ckway.bridge.C_")))
    (testing "and the fn actually works"
      (is (= 2 ((ev '(fn [] (f/step 1))))))
      (is (= "p:k:1" ((ev '(fn [^fx.Svc svc] (f/.load svc "k"))) svc)))
      (is (nil? ((ev '(fn [] (co/delay 10)))))))))

;; ---------------------------------------------------------------- S-B Clojure function as suspend function

(deftest s-b-sequential-and-loops
  (testing "S1: nested suspend calls"
    (is (= 4 (f/runB (fn [x] (f/step (f/step (f/step x)))))))
    (is (= 4 (rt/call-dyn #'f/runB [(fn [x] (f/step (f/step (f/step x))))] {}))))
  (testing "S2: suspending and non-suspending calls mixed"
    (is (= [7 2 7] (f/runB (fn [x] (let [a (f/now) b (f/step x) c (f/now)] [a b c]))))))
  (testing "S3: long loops (1000 delays; 100000 calls that do not suspend)"
    (is (= 1000 (f/runB (fn [x] (loop [i 0 acc 0] (if (< i 1000) (recur (inc i) (f/fastStep acc)) acc))))))
    (is (= 700000 (f/runB (fn [x] (loop [i 0 acc 0] (if (< i 100000) (recur (inc i) (+ acc (f/now))) acc))))))))

(deftest s-b-exceptions
  (testing "S4a: try/catch/finally in the body"
    (let [log (atom [])]
      (f/runB (fn [x] (try (f/boom) (catch IllegalStateException e (swap! log conj (ex-message e))) (finally (swap! log conj :fin)))))
      (is (= ["boom" :fin] @log))))
  (testing "S4b: an uncaught exception reaches Kotlin as a failure (the finally ran)"
    (let [fin (atom false)
          r (f/failsOf (fn [x] (try (f/boom) (finally (reset! fin true)))))]
      (is @fin)
      (is (= "THROWN java.lang.IllegalStateException: boom" r))))
  (testing "the caller of runIt gets the original exception, also a checked one"
    (is (instance? IllegalStateException (thrown #(f/runIt (fn [x] (throw (IllegalStateException. "u")))))))
    (is (instance? java.io.IOException (thrown #(f/runIt (fn [x] (f/boomIo))))))
    (is (instance? java.io.IOException (thrown #(rt/call-dyn #'f/runIt [(fn [x] (throw (java.io.IOException. "c")))] {})))))
  (testing "a throw in Clojure code and a suspend failure are both catchable by Kotlin"
    (is (= "THROWN clojure.lang.ExceptionInfo: bang {}"
           (f/failsOf (fn [x] (f/step x) (throw (ex-info "bang" {}))))))))

(deftest s-b-any-call-depth
  (testing "S5: a suspend call in a nested fn and in mapv"
    (is (= 2 (f/runB (fn [x] ((fn [i] (f/step i)) x)))))
    (let [[ms r] (timed (f/runB (fn [x] (mapv (fn [i] (f/step i)) (range 5)))))]
      (is (= [1 2 3 4 5] r))
      (is (>= ms 30) "5 sequential delays of 10ms")))
  (testing "S6: doseq, a lazy seq, locking"
    (let [log (atom [])]
      (f/runB (fn [x] (doseq [i (range 3)] (swap! log conj (f/step i)))))
      (is (= [1 2 3] @log)))
    (is (= [1 2 3] (f/runB (fn [x] (doall (map (fn [i] (f/step i)) (range 3)))))))
    (let [o (Object.)]
      (is (= 2 (f/runB (fn [x] (locking o (f/step 1))))))))
  (testing "X2: common forms around suspend calls"
    (is (= [2 3 12 [1 :err 3] 9 3]
           (f/runB
            (fn [x]
              (let [a (cond (> x 5) :big (= x 1) (f/step x) :else :other)
                    b (case a 2 (f/step a) :none)
                    c (when-let [v (f/step b)] (-> v inc (+ (f/now))))
                    d (loop [i 0 acc []]
                        (if (< i 3)
                          (recur (inc i) (conj acc (try (if (odd? i) (f/boom) (f/step i)) (catch IllegalStateException _ :err))))
                          acc))
                    e (reduce + (map inc [1 2 3]))
                    g (let [h (fn [y] (inc y))] (h (f/step 1)))]
                [a b c d e g])))))))

(deftest s-b-thread-context-elements
  (testing "S7: the ThreadContextElement of the caller is applied on the body's thread for its life, and restored"
    (is (= ["t1" "t1" "t1"]
           (f/runTaggedB "t1" (fn [x] (let [a (f/currentTag) _ (f/step 1) b (f/currentTag) _ (f/step 2) c (f/currentTag)] [a b c])))))
    (testing "the tag is gone after the body (the virtual thread is not reused, the dispatcher thread was restored)"
      (is (nil? (f/currentTag)))
      (is (= [nil] (f/runB (fn [x] [(f/currentTag)])))))
    (testing "withContext from a body of Clojure: the inner body sees the element too"
      (is (= "t2" (co/runBlocking :block (fn [scope] (co/withContext (f/tagCtx "t2") (fn [s] (f/currentTag))))))))))

(deftest s-b-cancellation
  (testing "S8: cancelling the Job stops the body, runs finally, and the Job is cancelled with a CancellationException"
    (let [uncaught (atom [])
          old (Thread/getDefaultUncaughtExceptionHandler)]
      (Thread/setDefaultUncaughtExceptionHandler (reify Thread$UncaughtExceptionHandler (uncaughtException [_ t e] (swap! uncaught conj e))))
      (try
        (let [log (atom []) fin (atom false) seen (atom nil)
              sc (f/defaultScope)
              job (f/launchIt sc (fn [x]
                                   (try (loop [i 0] (swap! log conj i) (f/step i) (recur (inc i)))
                                        (catch Throwable e (reset! seen e) (throw e))
                                        (finally (reset! fin true)))))]
          (Thread/sleep 60)
          (co/.cancel job)
          (is (await-true #(.isCompleted ^kotlinx.coroutines.Job job) 3000))
          (let [n1 (count @log)]
            (Thread/sleep 100)
            (is (= n1 (count @log)) "no step after the cancel"))
          (is @fin "finally ran")
          (is (.isCancelled ^kotlinx.coroutines.Job job))
          (is (instance? CancellationException (.getCancellationException ^kotlinx.coroutines.Job job)))
          (is (or (instance? CancellationException @seen) (instance? InterruptedException @seen)))
          (f/cancelScope sc))
        (Thread/sleep 50)
        (is (empty? @uncaught) (str "no stray exception on a dispatcher thread: " (pr-str @uncaught)))
        (finally (Thread/setDefaultUncaughtExceptionHandler old)))))
  (testing "a body that is blocked in Clojure code (Thread/sleep) is interrupted and surfaces as a CancellationException"
    (let [fin (promise)
          sc (f/defaultScope)
          job (f/launchIt sc (fn [x] (try (Thread/sleep 10000) (finally (deliver fin :fin)))))]
      (Thread/sleep 50)
      (co/.cancel job)
      (is (= :fin (deref fin 3000 :timeout)))
      (is (await-true #(.isCancelled ^kotlinx.coroutines.Job job) 3000))
      (f/cancelScope sc)))
  (testing "cancelling a launch from a runBlocking body"
    (is (true? (co/runBlocking :block
                               (fn [scope]
                                 (let [j (co/.launch scope :block (fn [s] (co/delay 5000)))]
                                   (co/.cancel j)
                                   (co/.join j)
                                   (.isCancelled ^kotlinx.coroutines.Job j))))))))

(deftest s-b-bindings
  (testing "X1: binding inside the body works across suspends"
    (is (= 7 (f/runB (fn [x] (binding [*print-length* 7] (f/step 1) *print-length*))))))
  (testing "The body sees the binding frame captured when the adapter was created: a binding around the call"
    (binding [*v* :at-creation]
      (is (= :at-creation (f/runB (fn [x] (f/step 1) *v*))))))
  (testing "...and not the frame of the thread that happens to start it: stored adapter, started inside another binding"
    (let [k (f/Keeper)]
      (f/.keep k (fn [x] *v*))                       ; created with the root binding
      (binding [*v* :at-start]
        (is (= :root (f/.runSaved k))))
      (binding [*v* :other]
        (f/.keep k (fn [x] *v*))                     ; created inside this binding
        (is (= :other (f/.runSaved k)))))
    (is (= :root (f/runB (fn [x] *v*))) "root again after the binding exits"))
  (testing "X3: bindings of many bodies on many threads do not mix"
    (let [bad (atom 0)
          ms (f/manyIt 200 (fn [x]
                             (binding [*print-length* x]
                               (dotimes [i 5] (f/fastStep i) (when-not (= *print-length* x) (swap! bad inc))))
                             (when *print-length* (swap! bad inc))
                             x))]
      (is (zero? @bad)))))

(deftest s-b-adapters
  (testing "nullable / defaulted suspend function type"
    (is (= -1 (f/runOpt)))
    (is (= 6 (f/runOpt (fn [x] (f/step x)))))
    (is (= 6 (rt/call-dyn #'f/runOpt [(fn [x] (f/step x))] {}))))
  (testing "return value is coerced to the declared Kotlin type exactly as in non-suspend adapters"
    (is (= 5 (f/runInt (fn [x] (f/step x)))) "a Long result for suspend (Int) -> Int")
    (is (instance? Integer (f/runInt (fn [x] (long (f/step x))))))
    (is (= "ran" (f/runUnit (fn [x] :ignored))) "any value for Unit")
    (is (= "ran" (f/runUnit (fn [x] (f/step x)))))
    (is (str/includes? (str (ex-message (thrown #(f/runInt (fn [x] nil))))) "nil where Kotlin expects a non-null Int"))
    (is (str/includes? (str (ex-message (thrown #(f/runInt (fn [x] "no"))))) "kt:")))
  (testing "no parameter, several parameters (Function1 and Function4 with the continuation)"
    (is (= 8 (f/runZero (fn [] (f/step 6)))))
    (is (= "1b3" (f/runThree (fn [a b c] (str a b c)))))
    (is (= "1b3" (rt/call-dyn #'f/runThree [(fn [a b c] (f/step 0) (str a b c))] {}))))
  (testing "T.(A) -> R: the receiver is the first argument"
    (is (= "ab" (f/withRecv (fn [sb] (.append ^StringBuilder sb "a") (f/step 1) (.append ^StringBuilder sb "b") nil))))
    (is (= "ab" (rt/call-dyn #'f/withRecv [(fn [sb] (.append ^StringBuilder sb "a") (f/step 1) (.append ^StringBuilder sb "b"))] {}))))
  (testing "fun interface with a suspend method (static, dynamic, unknown type)"
    (is (= 5 (f/useBefore (fn [x] (f/step x)) 4)))
    (is (= 5 (rt/call-dyn #'f/useBefore [(fn [x] (f/step x)) 4] {})))
    (is (= 5 ((ev '(fn [g] (f/useBefore g 4))) (fn [x] (f/step x)))))
    (is (= "went" (f/useUnit (fn [x] (f/step x)) 4)) "Unit method")
    (is (= 7 (f/useBefore (fn [x] (long (+ x 3))) 4)) "Long to Int"))
  (testing "an unknown-type local is adapted at run time; a Kotlin suspend function value passes through"
    (is (= 2 ((ev '(fn [g] (f/runIt g))) (fn [x] (f/step x)))))
    (let [h (f/suspFn)]
      (is (= 101 (f/runIt h)) "Kotlin runs the Kotlin function")
      (is (identical? (rt/own h) (rt/own (f/same h))) "Kotlin got the original object back")))
  (testing "wrong arity: a clear kt error, raised in the body"
    (let [e (thrown #(f/runIt (fn [a b] a)))]
      (is (str/includes? (ex-message e) "kt: Kotlin called this function as suspend (Int) -> Any? with 1 argument")
          (ex-message e))
      (is (instance? clojure.lang.ArityException (ex-cause e)))))
  (testing "annotation metadata on a fn literal goes to the adapter method (static path); dynamic: none"
    (is (true? (f/markerOfSusp ^{fx.Marker true} (fn [x] x))))
    (is (false? (f/markerOfSusp (fn [x] x))))
    (is (true? (f/markerOfSBefore ^{fx.Marker true} (fn [x] x))))
    (is (false? (f/markerOfSBefore (fn [x] x))))
    (is (false? (rt/call-dyn #'f/markerOfSusp [^{fx.Marker true} (fn [x] x)] {})))))

;; ---------------------------------------------------------------- S-C Kotlin suspend function value

(deftest s-c-kotlin-suspend-function-values
  (testing "a suspend function returned by Kotlin is a Clojure function with the Kotlin parameters"
    (let [h (f/suspFn)]
      (is (ifn? h))
      (is (instance? kotlin.jvm.functions.Function2 h) "still the Kotlin type")
      (is (= 105 (h 5)))
      (is (= 105 (apply h [5])))
      (is (= [101 102] (mapv h [1 2])))
      (is (instance? clojure.lang.ArityException (thrown #(h))))
      (is (instance? clojure.lang.ArityException (thrown #(h 1 2))))))
  (testing "...it can be called from a body, where the callee gets the body's context"
    (is (= 101 (f/runB (fn [x] ((f/suspFn) x))))))
  (testing "passed into a Clojure adapter: Kotlin hands a suspend function to the Clojure function"
    (is (= 11 (f/decorate (fn [x handler] (inc (handler x))))))
    (is (= 11 (rt/call-dyn #'f/decorate [(fn [x handler] (inc (handler x)))] {})))
    (is (= 11 ((ev '(fn [g] (f/decorate g))) (fn [x handler] (inc (handler x))))))
    (is (= [:handler true]
           (let [seen (atom nil)]
             (f/decorate (fn [x handler] (reset! seen (ifn? handler)) (handler x)))
             [:handler @seen]))))
  (testing "passing it back to Kotlin hands over the original object"
    (let [raw (atom nil)]
      (f/decorate (fn [x handler] (reset! raw handler) (handler x)))
      (is (identical? (rt/own @raw) (rt/own (f/same @raw))))))
  (testing "a failure in the Kotlin function is thrown as it is"
    (is (instance? ArithmeticException
                   (thrown #(f/decorate (fn [x handler] (/ 1 0)))))))
  (testing "the same wrapper works when Kotlin itself calls it (continuation as last argument)"
    (let [h (f/suspFn)]
      (is (= 103 (f/runIt (fn [x] (h 3))))))))

;; ---------------------------------------------------------------- S-D Kotlin's builders

(deftest s-d-kotlin-builders
  (testing "runBlocking, launch, async/await, join, cancel, withContext, delay"
    (is (= 2 (co/runBlocking :block (fn [scope] (f/step 1)))))
    (is (= 11 (co/runBlocking :block (fn [scope] (co/.await (co/.async scope :block (fn [s] (f/step 10))))))))
    (let [log (atom [])]
      (co/runBlocking :block
                      (fn [scope]
                        (let [j (co/.launch scope :block (fn [s] (f/step 1) (swap! log conj :child)))]
                          (is (instance? kotlinx.coroutines.Job j))
                          (co/.join j)
                          (swap! log conj :joined))))
      (is (= [:child :joined] @log)))
    (is (= 5 (co/runBlocking :block (fn [scope] (co/withContext (f/defaultDispatcher) (fn [s] (f/step 4)))))))
    (let [[ms _] (timed (co/delay 30))] (is (>= ms 20)))
    (let [[ms _] (timed (co/delay (t/milliseconds t/Duration 30)))] (is (>= ms 20)))
    (is (nil? (co/delay 1)))
    (is (nil? (rt/call-dyn #'co/delay [1] {}))))
  (testing "parallel async: two 100ms delays run concurrently"
    (let [[ms r] (timed (co/runBlocking :block
                                        (fn [scope]
                                          (let [a (co/.async scope :block (fn [s] (co/delay 100) :a))
                                                b (co/.async scope :block (fn [s] (co/delay 100) :b))]
                                            [(co/.await a) (co/.await b)]))))]
      (is (= [:a :b] r))
      (is (< ms 190) (str ms "ms: two 100ms delays in parallel, not 200"))))
  (testing "an exception in async surfaces at await; a failing launch child fails runBlocking"
    (is (= "boom" (ex-message (thrown #(co/runBlocking :block (fn [scope] (co/.await (co/.async scope :block (fn [s] (f/boom))))))))))
    (is (= "child" (ex-message (thrown #(co/runBlocking :block (fn [scope] (co/.launch scope :block (fn [s] (throw (IllegalStateException. "child")))) (co/delay 100))))))))
  (testing "Flow: collect with back-pressure; FlowCollector is a fun interface with a suspend method"
    (let [acc (atom [])]
      (flow/.collect (f/numbers) (fn [x] (swap! acc conj x) (f/step x)))
      (is (= [1 2 3] @acc)))
    (let [acc (atom [])]
      (rt/call-dyn #'flow/.collect [(f/numbers) (fn [x] (swap! acc conj x))] {})
      (is (= [1 2 3] @acc)))
    (testing "back-pressure: the producer waits for the collector (slow collector, items in order)"
      (let [log (atom [])
            [ms _] (timed (flow/.collect (f/numbers) (fn [x] (co/delay 30) (swap! log conj x))))]
        (is (= [1 2 3] @log))
        (is (>= ms 60) "3 x 30ms collector delay, sequential")))))

(deftest s-d-join-on-an-untyped-job
  (testing "the same member of a class and of its superclass is one member: JobSupport.join overrides Job.join"
    (let [j (co/runBlocking :block (fn [scope] (co/.launch scope :block (fn [s] (co/delay 20)))))
          join (ev '(fn [job] (co/.join job)))]
      (is (nil? (join j)))
      (is (nil? (rt/call-dyn #'co/.join [j] {}))))))

;; ---------------------------------------------------------------- concurrency

(deftest s-b-concurrency
  (testing "1000 bodies, 3 steps of 10ms each: far less than the serial 30 s"
    (let [[ms n] (timed (f/manyIt 1000 (fn [i] (f/step (f/step (f/step i))))))]
      (println "CONCURRENCY 1000 bodies x 3 steps: manyIt reported" n "ms, wall" (long ms) "ms")
      (is (< n 8000) (str n "ms"))
      (is (< ms 10000)))))

;; ---------------------------------------------------------------- cost (printed, loose bounds)

(deftest cost-report
  (let [n 20000
        step (fn [] (f/hop 0))
        platform-ns (let [_ (dotimes [_ 2000] (step)) t0 (System/nanoTime)]
                      (dotimes [_ n] (step)) (quot (- (System/nanoTime) t0) n))
        virtual-ns (let [run #(f/yieldIt (fn [x] (loop [i 0 a 0] (if (< i n) (recur (inc i) (f/yieldStep a)) a))))
                         _ (run) ns (run)]
                     (quot ns n))
        kotlin-ns (let [_ (f/ktYieldLoop n) ns (f/ktYieldLoop n)] (quot ns n))
        kotlin-hop-ns (let [_ (f/ktHopLoop n) ns (f/ktHopLoop n)] (quot ns n))
        ;; adapter cost per body: 20000 bodies that return at once, one after the other
        body-us (let [run #(let [t0 (System/nanoTime)] (dotimes [_ 5000] (f/runIt (fn [x] x))) (/ (- (System/nanoTime) t0) 5000 1000.0))
                      _ (run)]
                  (run))
        fast-ns (let [_ (dotimes [_ 20000] (f/now)) t0 (System/nanoTime)]
                  (dotimes [_ n] (f/now)) (quot (- (System/nanoTime) t0) n))]
    (println (format "COST per suspend call that really suspends: virtual thread (yield(), dispatcher in context) %d ns, Kotlin itself %d ns"
                     virtual-ns kotlin-ns))
    (println (format "COST per suspend call from a platform thread (withContext(Default) hop, call + wait) %d ns, Kotlin itself (same hop, runBlocking) %d ns"
                     platform-ns kotlin-hop-ns))
    (println (format "COST call without suspension: %d ns; one coroutine body (adapter + virtual thread + resume): %.1f us"
                     fast-ns body-us))
    (is (< platform-ns 1000000))
    (is (< virtual-ns 1000000))
    (is (< body-us 5000))))
