;; # 14. The Kotlin standard library
;;
;; The Kotlin standard library is just another Kotlin library. `kt/require` reads its packages the same
;; way as the packages of `shop`: `kotlin`, `kotlin.collections`, `kotlin.text`. You need no extra jar
;; (the library brings `kotlin-stdlib`) and no extra alias.
(ns examples.14-kotlin-stdlib
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[kotlin :as k]
            '[kotlin.collections :as c]
            '[kotlin.math :as m]
            '[kotlin.text :as t]
            '[shop :as s])

;; ## kotlin.collections
;;
;; A Kotlin `List` is a `java.util.List`: it prints like a Clojure vector and `map`, `count` and `first` work on it.

;; Kotlin: listOf("a", "b", "c")
(c/listOf "a" "b" "c")
;; => ["a" "b" "c"]

;; Kotlin: mapOf("a" to 1, "b" to 2)    -- `to` is an extension function of the package `kotlin`
(c/mapOf (k/.to "a" 1) (k/.to "b" 2))
;; => {"a" 1, "b" 2}

;; The lambdas are Clojure functions.

;; Kotlin: listOf(1, 2, 3).map { it * 10 }
(c/.map (c/listOf 1 2 3) (fn [x] (* x 10)))
;; => [10 20 30]

;; Kotlin: listOf(1, 2, 3, 4).filter { it % 2 == 0 }
(c/.filter (c/listOf 1 2 3 4) even?)
;; => [2 4]

;; Kotlin: listOf("apple", "avocado", "banana").groupBy { it.first() }
(c/.groupBy (c/listOf "apple" "avocado" "banana") (fn [s] (first s)))
;; => {\a ["apple" "avocado"], \b ["banana"]}

;; Kotlin: listOf(1, 2, 3).joinToString()
(c/.joinToString (c/listOf 1 2 3))
;; => "1, 2, 3"

;; Kotlin: listOf(1, 2, 3).joinToString(separator = "-")
(c/.joinToString (c/listOf 1 2 3) :separator "-")
;; => "1-2-3"

;; Kotlin: listOf(7, 8).first()
(c/.first (c/listOf 7 8))
;; => 7

;; Kotlin: listOf(3, 1, 2).sorted()
(c/.sorted (c/listOf 3 1 2))
;; => [1 2 3]

;; ## kotlin.text
;;
;; The Kotlin extensions of `String` work on a Clojure string.

;; Kotlin: "abc".uppercase()
(t/.uppercase "abc")
;; => "ABC"

;; Kotlin: "a,b,c".split(",")
(t/.split "a,b,c" ",")
;; => ["a" "b" "c"]

;; Kotlin: "7".padStart(3, '0')    -- only a Clojure character is a Kotlin `Char`
(t/.padStart "7" 3 \0)
;; => "007"

;; Kotlin: "42".toInt()
(t/.toInt "42")
;; => 42

;; Kotlin: "x".toIntOrNull()    -- a `null` result is `nil`
(t/.toIntOrNull "x")
;; => nil

(t/.toIntOrNull "42")
;; => 42

;; A Kotlin exception is thrown as it is.
(err (t/.toInt "x"))
;; => "For input string: \"x\""

;; ## kotlin
;;
;; `require` is an `inline` function. `kt` calls it through a small generated bridge.

;; Kotlin: require(1 > 0)
(k/require true)
;; => nil

;; Kotlin: require(false) { "must be true" }
(err (k/require false (fn [] "must be true")))
;; => "must be true"

;; `runCatching` gives a `Result`, which is a value class. `getOrNull` and `isFailure` read it.

;; Kotlin: runCatching { 5 }.getOrNull()
(k/.getOrNull (k/runCatching (fn [] 5)))
;; => 5

;; Kotlin: runCatching { 1 / 0 }.isFailure
(k/isFailure (k/runCatching (fn [] (/ 1 0))))
;; => true

;; Kotlin: Pair(1, "one")
(str (k/Pair 1 "one"))
;; => "(1, one)"

;; Kotlin: Pair(1, "one").first
(k/first (k/Pair 1 "one"))
;; => 1

;; ## A Clojure integer is a `Long`
;;
;; `kt` changes a number only when the declared Kotlin type of the parameter says so (`Int`, `Long`, `Double`, ...).
;; At `Any`, `Number` or a type parameter, a Clojure value goes on unchanged, and a Clojure integer is a `Long`.
;; So the list below holds `Long`s, and a lookup with a Clojure integer finds them.

(map class (c/listOf 1 2))
;; => (java.lang.Long java.lang.Long)

(map class (c/listOf 5000000000))
;; => (java.lang.Long)

;; Kotlin: listOf(1L, 2L, 3L).contains(2L)
(c/.contains (c/listOf 1 2 3) 2)
;; => true

;; Kotlin: listOf(1L, 2L, 3L) - 2L
(c/.minus (c/listOf 1 2 3) 2)
;; => [1 3]

;; A var used as a value passes the same `Long`s.
(let [f c/listOf]
  (map class (f 1 2)))
;; => (java.lang.Long java.lang.Long)

;; Data that Kotlin code made with `Int` holds `Integer`s. `toInt` is such code. A `Long` is not equal to
;; an `Integer`, so a lookup with a Clojure integer finds nothing. Say the width with `(int 2)`, or give the
;; type arguments with `:<>`.

(def kotlin-ints (c/.map (t/.split "1,2,3" ",") (fn [s] (t/.toInt s))))

(map class kotlin-ints)
;; => (java.lang.Integer java.lang.Integer java.lang.Integer)

;; Kotlin: kotlinInts.contains(2)
(c/.contains kotlin-ints 2)
;; => false

(c/.contains kotlin-ints (int 2))
;; => true

;; The type arguments need a receiver of a known type, so the call is selected at compile time.
(let [xs (c/.map (t/.split "1,2,3" ",") (fn [s] (t/.toInt s)))]
  (c/.contains xs 2 :<> Int))
;; => true

;; ## A Java functional interface
;;
;; A Clojure function also goes where a Java interface with one abstract method is expected,
;; as Kotlin converts a lambda there. `Comparator` is such an interface.

;; Kotlin: listOf("bb", "a", "ccc").sortedWith { a, b -> a.length - b.length }
(c/.sortedWith (c/listOf "bb" "a" "ccc") (fn [a b] (compare (count a) (count b))))
;; => ["a" "bb" "ccc"]

;; ## Erased overloads
;;
;; `sum` is several Kotlin functions (one for `Iterable<Int>`, one for `Iterable<Long>`, ...). The JVM erases
;; the type argument, so the parameter types are the same. `kt` does not guess: it stops with an error.

(err (c/.sum (c/listOf 1 2 3)))
;; => "kt: (.sum (c/listOf 1 2 3)) is ambiguous. Candidates:"

;; The error lists each candidate with its Java interop call. Use the one that you mean.
(kotlin.collections.CollectionsKt/sumOfInt (c/listOf 1 2 3))
;; => 6

;; A function that is `inline` has no public JVM method, so there is no way out of this kind. Write it in Clojure.
(apply + (c/listOf 1 2 3))
;; => 6

;; ## An integer where Kotlin wants a floating-point number
;;
;; `sqrt` is declared for `Double` and for `Float`. Kotlin refuses `sqrt(4)`, and so does `kt`: both candidates would
;; need a number that the argument is not. The message says what to write.

;; Kotlin: sqrt(4)    -- does not compile
(err (m/sqrt 4))
;; => "kt: (sqrt 4) is ambiguous. Candidates:"

;; Kotlin: sqrt(4.0)
(m/sqrt 4.0)
;; => 2.0

(m/sqrt (double 4))
;; => 2.0

;; ## Type hints
;;
;; A type hint that you write is the static type of an argument, as the declared type of a variable in Kotlin. There are
;; three cases.
;;
;; 1. The hint fits some candidates for sure: it decides between them (`kind` has overloads for Int, Long and String).

(let [^String x (identity "a")] (s/kind x))
;; => "String"

;; 2. The hint fits no candidate for sure, but the value could (the hint is an interface, or a class that a parameter
;;    type extends): it is only an upper bound. The call is checked at run time. `IPersistentVector` is not a Kotlin
;;    `List`, but a vector is one.

(let [^clojure.lang.IPersistentVector v [1 2 3]] (c/.first v))
;; => 1

;; 3. The hint can never fit (`Long` is final and unrelated to every candidate): a compile error.

(err (let [^Long n (identity 1)] (c/.first n)))
;; => "kt: no Kotlin declaration of `.first` fits (.first n)"
