;; # 7. Value classes
;;
;; A Kotlin `@JvmInline value class` is a Long, a String or another simple value at run time, and Kotlin
;; hides that. In Clojure a value class value is always the object, never the number inside.
;; Alias needed: `:examples`.
(ns examples.07-value-classes
  (:require [examples.util :refer [err]]
            [kt.core :as kt]))

(kt/require '[shop :as s]
            '[kotlin.time :as t]
            '[kotlin :as kk])

;; ## Construct and read
;;
;; Kotlin: `@JvmInline value class Money(val cents: Long)`.

;; Kotlin: Money(350)
(def price (s/Money 350))

(class price)
;; => shop.Money

(number? price)
;; => false

;; Kotlin: price.cents
(s/cents price)
;; => 350

;; Kotlin: price.toString()
(str price)
;; => "3.50"

;; ## Operators by name
;;
;; A Kotlin operator is a function with a name: `plus`, `minus`, `times`, `compareTo`. Call it with the prefix `.`.

;; Kotlin: price + Money(150)
(str (s/.plus price (s/Money 150)))
;; => "5.00"

;; Kotlin: price - Money(50)
(str (s/.minus price (s/Money 50)))
;; => "3.00"

;; Kotlin: price * 3
(str (s/.times price 3))
;; => "10.50"

;; Kotlin: price.percent(10)
(str (s/.percent price 10))
;; => "0.35"

;; Kotlin: price.compareTo(Money(100))
(s/.compareTo price (s/Money 100))
;; => 1

;; Kotlin: price > Money(100)
(pos? (s/.compareTo price (s/Money 100)))
;; => true

;; A fold over the operator:
;; Kotlin: prices.fold(Money.ZERO) { a, b -> a + b }
(str (reduce (fn [a b] (s/.plus a b)) (s/ZERO s/Money) [price price price]))
;; => "10.50"

;; ## Equality
;;
;; Two value class objects are equal when the values inside are equal. `hash` agrees with `=`.

;; Kotlin: Money(350) == Money(350)
(= price (s/Money 350))
;; => true

(= (hash price) (hash (s/Money 350)))
;; => true

(contains? #{(s/Money 350)} price)
;; => true

;; The object is not equal to the number inside.
(= price 350)
;; => false

;; ## Companion constants and factories
;;
;; Kotlin: `Money.ZERO`, `Money.CURRENCY`, `Money.of(...)`.

;; Kotlin: Money.ZERO
(str (s/ZERO s/Money))
;; => "0.00"

;; Kotlin: Money.CURRENCY
(s/CURRENCY s/Money)
;; => "EUR"

;; Kotlin: Money.of(2, 5)
(str (s/.of s/Money 2 5))
;; => "2.05"

;; ## A raw number is not a value class
;;
;; Kotlin has no conversion from a `Long` to a `Money`, and `kt` has none. Make the object.

(err (s/.plus price 5))
;; => "kt: no Kotlin declaration of `.plus` fits (.plus price 5)"

(err (s/.plus price nil))
;; => "kt: no Kotlin declaration of `.plus` fits (.plus price nil)"

(err (s/Money "350"))
;; => "kt: no Kotlin declaration of `Money` fits (Money \"350\")"

;; The same rule holds for the result of a Clojure function. Kotlin expects a `Money` back:
(err (s/applyDiscount (s/Money 1000) (fn [m] 5)))
;; => "kt: expected shop.Money where Kotlin expects it, got java.lang.Long 5. A value class is always the object, not the underlying value."

(err (s/applyDiscount (s/Money 1000) (fn [m] nil)))
;; => "kt: nil where Kotlin expects a non-null shop.Money"

;; ## Value classes of the Kotlin library: Duration
;;
;; `kotlin.time.Duration` is a value class too. `2.seconds` is an extension property of the companion.
;; Its receivers are the companion first, then the number.

;; Kotlin: 2.seconds
(def two-seconds (t/seconds t/Duration 2))

(str two-seconds)
;; => "2s"

;; Kotlin: 2.seconds.inWholeMilliseconds
(t/inWholeMilliseconds two-seconds)
;; => 2000

;; Kotlin: 250.milliseconds
(str (t/milliseconds t/Duration 250))
;; => "250ms"

;; Kotlin: 1.5.seconds
(str (t/seconds t/Duration 1.5))
;; => "1.5s"

;; Kotlin: 2.seconds + 3.seconds
(str (t/.plus two-seconds (t/seconds t/Duration 3)))
;; => "5s"

;; A Duration as a parameter, with a default: `fun pause(d: Duration = 1.seconds): Long`.

;; Kotlin: pause()
(s/pause)
;; => 1000

;; Kotlin: pause(2.seconds)
(s/pause two-seconds)
;; => 2000

;; Kotlin: pause(d = 250.milliseconds)
(s/pause :d (t/milliseconds t/Duration 250))
;; => 250

;; A Duration as a result: `fun doubled(d: Duration): Duration = d * 2`.
;; Kotlin: doubled(2.seconds)
(str (s/doubled two-seconds))
;; => "4s"

;; A number is not a Duration.
(err (s/pause 5))
;; => "kt: no Kotlin declaration of `pause` fits (pause 5)"

;; ## Result
;;
;; `kotlin.Result<T>` is a value class. Ask it with the functions of the package `kotlin`.
;; Kotlin: `fun parseMoney(text: String): Result<Money>`.

;; Kotlin: parseMoney("3.5")
(def ok (s/parseMoney "3.5"))

(kk/isSuccess ok)
;; => true

(str (kk/.getOrNull ok))
;; => "3.50"

;; Kotlin: parseMoney("x")
(def failed (s/parseMoney "x"))

(kk/isFailure failed)
;; => true

(kk/.getOrNull failed)
;; => nil

(ex-message (kk/.exceptionOrNull failed))
;; => "For input string: \"x\""

;; `getOrThrow` throws the original exception.
(err (kk/.getOrThrow failed))
;; => "For input string: \"x\""
