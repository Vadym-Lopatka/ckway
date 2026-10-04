;; # 6. Functions
;;
;; A Clojure function goes where Kotlin wants a function type or a `fun interface`. A Kotlin function
;; value that you get is a Clojure function. Alias needed: `:examples`.
(ns examples.06-functions
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def cake (s/Product 3 "Cake" (s/Money 500) ["food"]))

;; ## A Clojure function as a Kotlin lambda
;;
;; Kotlin: `fun applyDiscount(total: Money, f: (Money) -> Money): Money`.
;; The parameter has a function type, so you give a Clojure function. The argument is a `Money` object.

;; Kotlin: applyDiscount(Money(1000)) { it - Money(100) }
(str (s/applyDiscount (s/Money 1000) (fn [m] (s/.minus m (s/Money 100)))))
;; => "9.00"

;; Any Clojure function works, also `#()` and a var.
;; Kotlin: applyDiscount(Money(1000)) { it.percent(50) }
(str (s/applyDiscount (s/Money 1000) #(s/.percent % 50)))
;; => "5.00"

;; ## A lambda with a receiver
;;
;; Kotlin: `fun buildText(block: StringBuilder.() -> Unit): String`. The receiver is the first parameter.

;; Kotlin: buildText { append("Hello"); append(" world") }
(s/buildText (fn [sb] (.append sb "Hello") (.append sb " world")))
;; => "Hello world"

;; ## The builder DSL
;;
;; Kotlin: `fun cart(owner: String = "guest", build: Cart.() -> Unit): Cart`.
;; The block receives the cart as its first argument.

;; Kotlin: cart("ann") { add(tea); add(cake, 2) }
(def ann-cart
  (s/cart "ann" (fn [c]
                  (s/.add c tea)
                  (s/.add c cake 2))))

(str ann-cart)
;; => "Cart(ann, 3 items)"

(str (s/total ann-cart))
;; => "13.50"

;; ## Skip a parameter before the lambda
;;
;; In Kotlin you write the lambda after the parentheses, so the defaults before it need no name.
;; `kt` has no such syntax. If you skip `owner`, you name the lambda: all arguments after a keyword are named.

;; Kotlin: cart { add(tea) }
(s/owner (s/cart :build (fn [c] (s/.add c tea))))
;; => "guest"

;; Kotlin: report(cart) { "..." }   -- `title` is skipped
(s/report ann-cart :format (fn [c] (str (s/total c))))
;; => "Report: 13.50"

;; Kotlin: report(cart, "Total") { "..." }
(s/report ann-cart "Total" (fn [c] (str (s/total c))))
;; => "Total: 13.50"

;; Without the name, the lambda would be the argument for `title`. The error says what is missing.
(err (s/report ann-cart (fn [c] (str (s/total c)))))
;; => "kt: (report ann-cart (fn [c] (str (s/total c)))): missing required parameter `format`. Pass it positionally or as `:format`."

;; ## fun interface
;;
;; Kotlin: `fun interface Rule { fun applies(product: Product): Boolean }`.
;; A Clojure function goes where Kotlin wants a `fun interface`.

;; Kotlin: ann-cart.countWhere { it.hasTag("drink") }
(s/.countWhere ann-cart (fn [p] (s/.hasTag p "drink")))
;; => 1

(s/.countWhere ann-cart #(s/.hasTag % "food"))
;; => 2

;; The function must return what Kotlin expects. A number is not a Boolean.
(err (s/.countWhere ann-cart (fn [p] 1)))
;; => "kt: expected a Boolean where Kotlin expects a Boolean, got java.lang.Long"

;; The same with a `fun interface` whose method takes a value class:
;; `fun interface Discount { fun adjust(total: Money): Money }` and `Cart.total(discount: Discount)`.
;; This is the result that the function type gives:
;; Kotlin: applyDiscount(cart.total) { it - Money(100) }
(str (s/applyDiscount (s/total ann-cart) (fn [m] (s/.minus m (s/Money 100)))))
;; => "12.50"

;; Kotlin: cart.total { it - Money(100) }
(str (s/.total ann-cart (fn [m] (s/.minus m (s/Money 100)))))
;; => "12.50"

;; ## A Kotlin function value is a Clojure function
;;
;; A Kotlin function that returns a lambda gives a value that you can call. It is a real Clojure function.

;; Kotlin: val fmt = priceFormatter("EUR ")
(def fmt (s/priceFormatter "EUR "))

;; Kotlin: fmt(Money(350))
(fmt (s/Money 350))
;; => "EUR 3.50"

;; Kotlin: prices.map(fmt)
(map fmt [(s/Money 350) (s/Money 5)])
;; => ("EUR 3.50" "EUR 0.05")

(fn? fmt)
;; => true

;; You can compose it with other functions. `taxer` returns a `(Money) -> Money`.
;; Kotlin: (fmt compose taxer(20))(Money(1000))
((comp fmt (s/taxer 20)) (s/Money 1000))
;; => "EUR 12.00"

;; ## Give a Kotlin function back to Kotlin
;;
;; A Kotlin function value goes back to Kotlin as the function it is. `twice` takes and returns a function.

;; Kotlin: twice(taxer(10))(Money(1000))
(str ((s/twice (s/taxer 10)) (s/Money 1000)))
;; => "12.10"

;; Kotlin: applyDiscount(Money(1000), taxer(10))
(str (s/applyDiscount (s/Money 1000) (s/taxer 10)))
;; => "11.00"

;; ## Functions that take functions of more parameters
;;
;; Kotlin: `fun Cart.eachLine(action: (Product, Int) -> Unit)`. A `Unit` result is `nil`.

;; Kotlin: cart.eachLine { p, q -> println("${p.name} x$q") }
(s/.eachLine ann-cart (fn [p q] (println (s/name p) "x" q)))
;; prints: Cake x 2

;; The Clojure value that the function returns is ignored. The call gives `nil`, the Clojure value of `Unit`.
(s/.eachLine ann-cart (fn [p q] q))
;; => nil

;; A generic function: `fun <T> Cart.mapLines(f: (Cart.Line) -> T): List<T>`.
;; Kotlin: cart.mapLines { it.quantity }
(s/.mapLines ann-cart s/quantity)
;; => [1 2]

;; ## Errors
;;
;; A Clojure function with the wrong number of parameters: the error says what Kotlin called.
(err (s/applyDiscount (s/Money 1000) (fn [a b] a)))
;; => "kt: Kotlin called this function as (shop.Money) -> shop.Money with 1 argument, but the Clojure function does not accept 1 argument"

;; A value that is not a function:
(err (s/applyDiscount (s/Money 1000) 5))
;; => "kt: no Kotlin declaration of `applyDiscount` fits (applyDiscount (s/Money 1000) 5)"

;; `nil` where the function type is not nullable:
(err (s/applyDiscount (s/Money 1000) nil))
;; => "kt: no Kotlin declaration of `applyDiscount` fits (applyDiscount (s/Money 1000) nil)"

;; A Kotlin function value called with the wrong number of arguments:
(err (fmt (s/Money 1) (s/Money 2)))
;; => "Wrong number of args (2) passed to: Kotlin function with 1 parameter"
