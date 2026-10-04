(ns ckway.cache-test
  "The call cache of the dynamic path (`ckway.rt/call-dyn`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.rt :as rt])
  (:import [java.util.concurrent ConcurrentHashMap Executors Callable]))

(kt/require '[fx :as f])

(defn- cache-of ^ConcurrentHashMap [v] (:kt/cache (meta v)))
(defn- size [v] (.size (cache-of v)))
(defn- msg [thunk] (try (thunk) nil (catch Throwable t (ct/root-message t))))

(deftest a-different-argument-class-selects-again
  (.clear (cache-of #'f/amb))
  (testing "amb(Int) / amb(Long): the class of the argument decides, in any order, any number of times"
    (dotimes [_ 3]
      (is (= "int" (rt/call-dyn #'f/amb [(int 1)] {})))
      (is (= "long" (rt/call-dyn #'f/amb [1] {})))
      (is (str/includes? (msg #(rt/call-dyn #'f/amb [(short 1)] {})) "ambiguous") "Short fits both: an error every time, never cached")
      (is (= "long" (rt/call-dyn #'f/amb [(long 7)] {}))))
    (is (= 2 (size #'f/amb)) "Integer and Long: two shapes, two entries (the ambiguous call has none)"))
  (testing "an integer literal is a different shape from a Long value (the literal is an Int for Kotlin)"
    (is (= "int" (rt/call-dyn #'f/amb [1] {} {0 :int})))
    (is (= "long" (rt/call-dyn #'f/amb [1] {})))
    (is (= "long" (rt/call-dyn #'f/amb [3000000000] {} {0 :long})))
    (is (= "int" (rt/call-dyn #'f/amb [1] {} {0 :int}))))
  (testing "nil, then not nil, then nil: the conversion of the argument differs"
    (let [inc1 (fn [x] (inc x))]
      (dotimes [_ 3]
        (is (= -3 (rt/call-dyn #'f/maybe [nil 3] {})))
        (is (= 4 (rt/call-dyn #'f/maybe [inc1 3] {})))
        (is (= -5 (rt/call-dyn #'f/maybe [nil 5] {})))
        (is (nil? (rt/call-dyn #'f/nick [nil] {})))
        (is (= "x" (rt/call-dyn #'f/nick [" x "] {})))
        (is (nil? (rt/call-dyn #'f/nick [nil] {}))))))
  (testing "the same arguments, other named-argument keys"
    (dotimes [_ 2]
      (is (= "Hello, Bob?" (rt/call-dyn #'f/greet ["Bob"] {"punct" "?"})))
      (is (= "Yo, Bob!" (rt/call-dyn #'f/greet ["Bob"] {"greeting" "Yo"})))
      (is (= "Hello, Bob!" (rt/call-dyn #'f/greet ["Bob"] {})))
      (is (= "Hello, Bob!" (rt/call-dyn #'f/greet [] {"name" "Bob"})))))
  (testing "a class var is a different shape from another class var in the same position"
    (is (= "made" (rt/call-dyn #'f/.make [f/WithCompanion] {})))
    (is (= "made" (rt/call-dyn #'f/.make [f/WithCompanion] {})))
    (is (some? (msg #(rt/call-dyn #'f/.make [f/Pt] {}))) "another class var: selection runs again and rejects it")))

(deftest errors-are-not-cached
  (.clear (cache-of #'f/nick))
  (dotimes [_ 2]
    (is (str/includes? (msg #(rt/call-dyn #'f/greet [nil] {})) "nil")))
  (is (= "Hello, a!" (rt/call-dyn #'f/greet ["a"] {})))
  (is (zero? (size #'f/nick))))

(deftest the-cache-has-a-bound
  (let [v #'f/eq
        cache (cache-of v)]
    (.clear cache)
    (testing "a call site that sees many classes: at most 64 entries, always the right answer"
      (dotimes [i 300]
        (let [x (eval '(reify Object (toString [_] "x")))]
          (is (= "string:x" (rt/call-dyn v ["a" x] {})) (str "call " i))
          (is (<= (.size cache) 64) (str "size after call " i)))))
    (is (pos? (.size cache)))))

(deftest thread-safe
  (let [pool (Executors/newFixedThreadPool 8)
        work (fn [seed]
               (reify Callable
                 (call [_]
                   (loop [i 0 bad 0]
                     (if (= i 3000)
                       bad
                       (let [k (mod (+ i seed) 4)
                             [expected actual]
                             (case k
                               0 ["int" (rt/call-dyn #'f/amb [(int 1)] {})]
                               1 ["long" (rt/call-dyn #'f/amb [1] {})]
                               2 [-3 (rt/call-dyn #'f/maybe [nil 3] {})]
                               3 ["Hello, Bob?" (rt/call-dyn #'f/greet ["Bob"] {"punct" "?"})])]
                         (recur (inc i) (if (= expected actual) bad (inc bad)))))))))]
    (try
      (.clear (cache-of #'f/amb))
      (let [bads (mapv #(.get ^java.util.concurrent.Future %) (mapv #(.submit pool ^Callable (work %)) (range 8)))]
        (is (= (repeat 8 0) bads) "no wrong answer under 8 threads"))
      (finally (.shutdown pool)))))

(deftest no-stale-entry-after-require
  (let [v #'f/amb
        old (cache-of v)]
    (rt/call-dyn v [(int 1)] {})
    (is (pos? (.size old)))
    (testing "kt/require makes new vars metadata, so a new, empty cache"
      (meta/clear-cache!)
      (kt/require '[fx :as f])
      (is (not (identical? old (cache-of #'f/amb))))
      (is (zero? (size #'f/amb)))
      (is (= "int" (rt/call-dyn #'f/amb [(int 1)] {})))
      (is (= 1 (size #'f/amb))))
    (testing "the package changed (here: only amb(Long) is left): the old plan for Int is gone"
      (let [orig meta/package-index]
        (try
          (meta/clear-cache!)
          (with-redefs [meta/package-index (fn [pkg]
                                             (let [idx (orig pkg)]
                                               (if (= "fx" pkg)
                                                 (update idx "amb" (fn [ds] (filterv #(= "long" (-> % :params first :jvm-type)) ds)))
                                                 idx)))]
            (kt/require '[fx :as f])
            (is (= "long" (rt/call-dyn #'f/amb [(int 1)] {})) "an Int now goes to amb(Long)"))
          (finally
            (meta/clear-cache!)
            (kt/require '[fx :as f]))))
      (is (= "int" (rt/call-dyn #'f/amb [(int 1)] {})) "and back"))))

;; ---------------------------------------------------------------- numbers for the report

(defn- ns-per-call [n f]
  (dotimes [_ 200000] (f))
  (let [t0 (System/nanoTime)]
    (dotimes [_ n] (f))
    (/ (double (- (System/nanoTime) t0)) n)))

(defn- uncached
  "A var with the metadata of `v` but no cache: every call selects and plans again (the cost without a cache)."
  [v]
  (let [u (intern (create-ns 'ckway.cache-test.scratch) (symbol (str (.sym ^clojure.lang.Var v))))]
    (alter-meta! u (constantly (dissoc (meta v) :kt/cache)))
    u))

(deftest ns-per-call-before-and-after
  (let [user (f/User 1 "ann") uid (f/Uid 5)
        cases [["(a) top-level call, f/greet \"Bob\"" #'f/greet ["Bob"]]
               ["(b) member call, skipped default, f/.greet user" #'f/.greet [user]]
               ["(c) bridged value-class call, f/nextUid uid" #'f/nextUid [uid]]]]
    (doseq [[label v pos] cases]
      (let [u (uncached v)
            before (ns-per-call 100000 #(rt/call-dyn u pos {}))
            after (ns-per-call 1000000 #(rt/call-dyn v pos {}))]
        (println (format "CALL-DYN %-52s uncached %6.0f ns   cached %5.0f ns" label before after))
        (is (< after before) label)))))
