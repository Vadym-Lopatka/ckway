(ns ckway.stdlib-test
  "Calls into the real Kotlin stdlib (`kotlin`, `kotlin.collections`, `kotlin.text`,
  `kotlin.sequences`, `kotlin.ranges`). Each call is made on the static path (as written) and on the dynamic path
  (`ckway.rt/call-dyn` with the same values), and the result is compared with a literal.

  Limits that show here (nothing else fails):
  * `Iterable<Int>.sum`, `sumOf { }`, `maxOrNull`, `flatMap { }` are several declarations with ONE JVM signature
    (the type argument is erased: the JVM name is `sumOfInt`, `sumOfLong`...; or the lambda's return type decides).
    kt does not guess: the call is an error that lists them (`erased-type-arguments-stay-ambiguous`).
  * The members of Kotlin's built-in types (`Int.rangeTo`, `Map.getOrDefault`, `Map.keys`) are not vars: write
    `until`/`downTo`/`step` (extensions) or Clojure's own functions."
  (:require [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.rt :as rt]))

(kt/require '[kotlin :as k] '[kotlin.collections :as c] '[kotlin.text :as tx]
            '[kotlin.sequences :as sq] '[kotlin.ranges :as rg])

(defmacro both*
  "Assert `expected` for `(via (f args...))` on the static path and on the dynamic path. An integer literal that fits
  Int is marked as a literal on the dynamic path (the static path types it as Kotlin does)."
  [via expected f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))
        lits (into {} (keep-indexed (fn [i a] (when (and (integer? a) (<= Integer/MIN_VALUE a Integer/MAX_VALUE)) [i :int]))
                                    pos))]
    `(do (is (= ~expected (~via (~f ~@args))) (str "static " '(~f ~@args)))
         (is (= ~expected (~via (rt/call-dyn (var ~f) [~@pos] ~nm ~lits))) (str "dynamic " '(~f ~@args))))))

(defmacro both [expected f & args] `(both* identity ~expected ~f ~@args))

(defmacro both-throws
  "The call throws an exception of class `cls` whose message matches `re`, on both paths."
  [cls re f & args]
  (let [[pos named] (split-with (complement keyword?) args)
        nm (into {} (map (fn [[k v]] [(name k) v]) (partition 2 named)))]
    `(do (is (~'thrown-with-msg? ~cls ~re (~f ~@args)) (str "static " '(~f ~@args)))
         (is (~'thrown-with-msg? ~cls ~re (rt/call-dyn (var ~f) [~@pos] ~nm)) (str "dynamic " '(~f ~@args))))))

(defn pairs
  "The components of a list of kotlin.Pair, as vectors."
  [ps]
  (mapv (fn [p] [(k/first p) (k/second p)]) ps))

;; ---------------------------------------------------------------- kotlin

(deftest kotlin-pair-triple
  (both "a" k/.component1 (k/.to "a" 1))
  (both 1 k/.component2 (k/.to "a" 1))
  (both "a" k/first (k/Pair "a" 1))
  (both 1 k/second (k/Pair "a" 1))
  (both 3 k/.component3 (k/Triple 1 2 3))
  (both 1 k/first (k/Triple 1 2 3)))

(deftest kotlin-preconditions
  (both nil k/require true)
  (both nil k/check true)
  (both-throws IllegalArgumentException #"Failed requirement" k/require false)
  (both-throws IllegalStateException #"Check failed" k/check false)
  (both-throws IllegalStateException #"^boom$" k/error "boom")
  (both-throws IllegalArgumentException #"^nope$" k/require false (fn [] "nope")))

(deftest kotlin-scope-functions
  (both 6 k/.let 5 inc)
  (both 6 k/.run 5 inc)
  (both 6 k/with 5 inc)
  (both "s" k/.also "s" (fn [x] nil))
  (both* str "ax" k/.apply (StringBuilder. "a") (fn [sb] (.append ^StringBuilder sb "x")))
  (both 5 k/.takeIf 5 odd?)
  (both nil k/.takeUnless 5 odd?)
  (both -1 k/.compareTo 1 2))

(deftest kotlin-lazy-and-result
  (both 5 k/value (k/lazy (fn [] 5)))
  (both true k/isSuccess (k/runCatching (fn [] 42)))
  (both 42 k/.getOrNull (k/runCatching (fn [] 42)))
  (both 7 k/.getOrElse (k/runCatching (fn [] (throw (Exception. "x")))) (fn [e] 7))
  (testing "the lambda runs once"
    (let [n (atom 0) lz (k/lazy (fn [] (swap! n inc)))]
      (is (= 1 (k/value lz)))
      (is (= 1 (k/value lz)))
      (is (= 1 @n)))))

(deftest kotlin-repeat
  (let [seen (atom [])]
    (k/repeat 3 (fn [i] (swap! seen conj i) nil))
    (is (= [0 1 2] @seen))
    (reset! seen [])
    (rt/call-dyn #'k/repeat [3 (fn [i] (swap! seen conj i) nil)] {})
    (is (= [0 1 2] @seen))))

;; ---------------------------------------------------------------- kotlin.collections

(deftest collection-factories
  (both [1 2 3] c/listOf 1 2 3)
  (both [1] c/listOf 1)
  (both [] c/listOf)
  (both [1 2] c/mutableListOf 1 2)
  (both #{1 2} c/setOf 1 2 2)
  (both {"a" 1 "b" 2} c/mapOf (k/.to "a" 1) (k/.to "b" 2))
  (both {"a" 1} c/mapOf (k/.to "a" 1))
  (both [] c/emptyList)
  (both {} c/emptyMap)
  (both [1 2] c/listOfNotNull 1 nil 2))

(deftest collection-transformations
  (both [2 3 4] c/.map [1 2 3] inc)
  (both [2 4] c/.filter [1 2 3 4] even?)
  (both 6 c/.fold [1 2 3] 0 (fn [a x] (+ a x)))
  (both {false [1 3] true [2 4]} c/.groupBy [1 2 3 4] even?)
  (both* (fn [m] (update-keys (into {} m) long)) {1 "a" 2 "bb"} c/.associateBy ["a" "bb"] count) ; `count` gives Integer keys
  (both [1 2 3] c/.sortedBy [3 1 2] (fn [x] x))
  (both [1 2 3] c/.sorted [3 1 2])
  (both [3 2 1] c/.sortedDescending [1 3 2])
  (both [3 2 1] c/.reversed [1 2 3])
  (both [1 2] c/.filterNotNull [1 nil 2])
  (both [1 2 3] c/.plus [1] [2 3])
  (both [1 2] c/.plus [1] 2)
  ;; T is shared by the list and the element: the literal keeps the Long that a Clojure vector holds (an integer is not converted at a generic position)
  (both [1 3] c/.minus [1 2 3] 2)
  (both {"a" 2} c/.mapValues {"a" 1} (fn [e] 2)))

(deftest collection-slicing
  (both "1, 2, 3" c/.joinToString [1 2 3])
  (both "1-2-3" c/.joinToString [1 2 3] "-")
  (both [1 2] c/.take [1 2 3] 2)
  (both [3] c/.drop [1 2 3] 2)
  (both [[1 2] [3 4] [5]] c/.chunked [1 2 3 4 5] 2)
  (both [[1 2] [2 3]] c/.windowed [1 2 3] 2)
  (both [1 2] c/.distinct [1 1 2])
  (both #{1 2} c/.toSet [1 1 2])
  (both [1 2] c/.toMutableList [1 2])
  (both [1] c/.toList #{1})
  (both* pairs [[1 "a"] [2 "b"]] c/.zip [1 2] ["a" "b"])
  (both* (fn [p] [(vec (k/first p)) (vec (k/second p))]) [[2 4] [1 3]] c/.partition [1 2 3 4] even?))

(deftest collection-queries
  (both 1 c/.first [1 2 3])
  (both 3 c/.last [1 2 3])
  (both 1 c/.firstOrNull [1 2 3])
  (both nil c/.firstOrNull [])
  (both 3 c/.count [1 2 3])
  (both 2 c/.count [1 2 3] odd?)
  (both true c/.any [1 2 3] even?)
  (both false c/.all [1 2 3] even?)
  (both 1 c/.indexOf [1 2 3] 2)
  (both true c/.contains [1 2 3] 2)
  (both nil c/.getOrNull [1 2 3] 5)
  (both 3 c/.getOrNull [1 2 3] 2)
  (both false c/.isNotEmpty [])
  (both true c/.isNotEmpty [1])
  (both 1 c/.getValue {"a" 1} "a")
  (both {"a" 1} c/.toMap [(k/.to "a" 1)]))

;; ---------------------------------------------------------------- kotlin.text

(deftest string-case-and-trim
  (both "ABC" tx/.uppercase "abC")
  (both "abc" tx/.lowercase "AbC")
  (both "a b" tx/.trim "  a b ")
  (both "a  " tx/.trimStart "  a  ")
  (both "  a" tx/.trimEnd "  a  ")
  (both "Abc" tx/.capitalize "abc"))

(deftest string-split-pad-replace
  (both ["a" "b" "c"] tx/.split "a,b,c" ",")
  (both ["a" "b" "c"] tx/.split "a1b2c" "1" "2")
  (both ["a" "b,c"] tx/.split "a,b,c" "," :limit 2)
  (both "  abc" tx/.padStart "abc" 5)
  (both "**abc" tx/.padStart "abc" 5 \*)
  (both "abc--" tx/.padEnd "abc" 5 \-)
  (both "a+b+c" tx/.replace "a-b-c" "-" "+")
  (both "ba" tx/.replaceFirst "aa" "a" "b")
  (both "a" tx/.substringBefore "a-b" "-")
  (both "b" tx/.substringAfter "a-b" "-")
  (both "bc" tx/.substring "abcdef" 1 3)
  (both "abab" tx/.repeat "ab" 2)
  (both "cba" tx/.reversed "abc")
  (both "bc" tx/.removePrefix "abc" "a")
  (both ["ab" "cd" "e"] tx/.chunked "abcde" 2)
  (both ["a" "b"] tx/.lines "a\nb"))

(deftest string-conversion
  (both 12 tx/.toInt "12")
  (both 12 tx/.toLong "12")
  (both 1.5 tx/.toDouble "1.5")
  (both nil tx/.toIntOrNull "x")
  (both 12 tx/.toIntOrNull "12")
  (both true tx/.toBoolean "true")
  (both "1-x" tx/.format "%d-%s" 1 "x")
  (both "1.50" tx/.format "%.2f" 1.5)
  (both-throws NumberFormatException #"For input string" tx/.toInt "x"))

(deftest string-regex
  (both true tx/.matches (tx/.toRegex "a+") "aaa")
  (both false tx/.matches (tx/.toRegex "a+") "ab")
  (both "aXc" tx/.replace "abc" (tx/.toRegex "b") "X")
  (is (= "a+" (str (tx/.toRegex "a+")))))

(deftest string-queries
  (both true tx/.isBlank "  ")
  (both false tx/.isBlank " a ")
  (both true tx/.isNotBlank " a ")
  (both true tx/.isEmpty "")
  (both true tx/.isNullOrEmpty nil)
  (both true tx/.startsWith "abc" "ab")
  (both true tx/.endsWith "abc" "bc")
  (both true tx/.contains "abc" "b")
  (both 2 tx/.indexOf "abc" "c")
  (both \a tx/.first "abc")
  (both \c tx/.last "abc")
  (both 3 tx/.count "abc")
  (both [\a \b] tx/.toList "ab"))

;; ---------------------------------------------------------------- kotlin.sequences

(deftest sequences
  (both [1 2 3] sq/.toList (sq/.take (sq/generateSequence 1 (fn [x] (inc x))) 3))
  (both [2 3 4] sq/.toList (sq/.map (sq/sequenceOf 1 2 3) inc))
  (both [2 4] sq/.toList (sq/.filter (c/.asSequence [1 2 3 4]) even?))
  (both [1 2] sq/.toList (c/.asSequence [1 2]))
  (both [2 3] sq/.toList (sq/.drop (c/.asSequence [1 2 3]) 1))
  (both [1 2] sq/.toList (sq/.distinct (c/.asSequence [1 1 2])))
  (both [1 2] sq/.toList (sq/.takeWhile (c/.asSequence [1 2 3]) (fn [x] (< x 3))))
  (both 2 sq/.count (c/.asSequence [1 2]))
  (both 1 sq/.first (c/.asSequence [1 2]))
  (both "1+2" sq/.joinToString (c/.asSequence [1 2]) "+")
  (testing "a sequence is lazy: the generator runs only as far as the take"
    (let [n (atom 0)
          s (sq/generateSequence 1 (fn [x] (swap! n inc) (inc x)))]
      (is (= [1 2 3] (vec (sq/.toList (sq/.take s 3)))))
      (is (= 2 @n)))))

;; ---------------------------------------------------------------- kotlin.ranges

(deftest ranges
  (both* vec [1 2 3 4] rg/.until 1 5)
  (both* vec [3 2 1] rg/.downTo 3 1)
  (both* vec [1 4 7] rg/.step (rg/.until 1 10) 3)
  (both* vec [3 2 1] rg/.reversed (rg/.until 1 4))
  (both 10 rg/.coerceIn 15 0 10)
  (both 0 rg/.coerceIn -5 0 10)
  (both 5 rg/.coerceIn 5 0 10)
  (both 3.0 rg/.coerceIn 5.5 1.0 3.0)
  (both 5 rg/.coerceAtLeast 1 5)
  (both 5 rg/.coerceAtMost 8 5)
  (both 2 rg/first (rg/.until 2 5))
  (both 4 rg/last (rg/.until 2 5))
  (both true rg/.contains (rg/.until 2 5) 3)
  (both false rg/.contains (rg/.until 2 5) 5))

;; ---------------------------------------------------------------- limits

(defn- ambiguity-message [v args]
  (try (rt/call-dyn v args {}) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest erased-type-arguments-stay-ambiguous
  (doseq [[v args] [[#'c/.sum [[1 2 3]]]
                    [#'c/.sumOf [[1 2 3] (fn [x] x)]]
                    [#'c/.maxOrNull [[1 2 3]]]
                    [#'c/.flatMap [[1] (fn [x] [x])]]]]
    (is (re-find #"is ambiguous" (str (ambiguity-message v args))) (str v " is ambiguous at run time")))
  (testing "the message says why, and gives the Java interop call of each candidate"
    (let [m (ambiguity-message #'c/.sum [[1 2 3]])]
      (is (re-find #"same JVM parameter types" m) m)
      (is (re-find #"the JVM erases" m) m)
      (is (re-find #"\(kotlin\.collections\.CollectionsKt/sumOfInt x\)" m) m)
      (is (re-find #"\(kotlin\.collections\.CollectionsKt/sumOfLong x\)" m) m)
      (is (re-find #"\(kotlin\.collections\.UCollectionsKt/sumOfUInt x\)" m) m))
    (let [m (ambiguity-message #'c/.sumOf [[1 2 3] (fn [x] x)])]
      (is (re-find #"the JVM erases" m) m)
      (is (re-find #"result type of a lambda" m) m)
      (is (re-find #"no public JVM method \(it is `inline`\)" m) m))
    (let [m (ambiguity-message #'c/.maxOrNull [[1 2 3]])]
      (is (re-find #"\(kotlin\.collections\.CollectionsKt/maxOrNull x\)" m) m)
      (is (re-find #"JVM descriptor \(Ljava/lang/Iterable;\)Ljava/lang/Double;" m) m))
    (let [m (ambiguity-message #'c/.flatMap [[1] (fn [x] [x])])]
      (is (re-find #"\(kotlin\.collections\.CollectionsKt/flatMapSequence x y\)" m) m)))
  (testing "the Java interop call the message names works"
    (is (= 6 (kotlin.collections.CollectionsKt/sumOfInt [(int 1) (int 2) (int 3)])))))
