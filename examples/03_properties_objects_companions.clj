;; # 3. Properties, objects and companions
;;
;; A property is a function without the `.` prefix. An `object` is a var that holds the instance.
;; Companion members take the class var as their first argument. Alias needed: `:examples`.
(ns examples.03-properties-objects-companions
  (:require [clojure.string :as str]
            [examples.util :refer [err]]
            [kt.core :as kt]))

(kt/require '[shop :as s])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def cake (s/Product 3 "Cake" (s/Money 500) ["food"]))
(def cart (s/Cart "ann"))
(s/.add cart tea 2)
(s/.add cart cake)

;; ## Read a property
;;
;; A property is called like a function. Its receiver is the first argument. The name has no dot.

;; Kotlin: tea.name
(s/name tea)
;; => "Tea"

;; Kotlin: tea.tags
(s/tags tea)
;; => ["drink" "hot"]

;; Kotlin: tea.price
(str (s/price tea))
;; => "3.50"

;; Kotlin: cart.owner
(s/owner cart)
;; => "ann"

;; A `val` with a getter is a property too.
;; Kotlin: cart.total
(str (s/total cart))
;; => "12.00"

;; A property is a function, so it works with `map`.
;; Kotlin: Catalog.products.map { it.name }
(map s/name (s/products s/Catalog))
;; => ("Tea" "Coffee" "Cake" "Water")

;; ## A property and a method with the same name
;;
;; `Cart` has the property `count` and the method `count(tag)`. The method has the prefix `.`, the property has not.

;; Kotlin: cart.count
(s/count cart)
;; => 3

;; Kotlin: cart.count("drink")
(s/.count cart "drink")
;; => 2

;; If you write the method form for the property, the error tells you the right form.
;; `cart` is a `def` var, so `kt` checks the class of the receiver when the call runs.
;; Kotlin would not compile this: `count` is a property, and `cart.count()` is not a call.
(err (s/.count cart))
;; => "kt: (s/.count cart): the receiver `cart` is a shop.Cart, but the Kotlin declaration that was selected needs shop.Repository"

;; The last line of the message is the hint.
(str/trim (last (str/split-lines (try (s/.count cart) (catch Exception e (ex-message e))))))
;; => "Hint: `count` is a property of shop.Cart: write (s/count cart)"

;; ## Objects
;;
;; The var of a Kotlin `object` holds the instance. You do not call it.

;; Kotlin: Catalog.size
(s/size s/Catalog)
;; => 4

;; Kotlin: Catalog.byTag("food")
(map s/name (s/.byTag s/Catalog "food"))
;; => ("Cake")

;; Kotlin: Catalog.find(2)    -- the parameter is a Long; the literal 2 widens
(s/name (s/.find s/Catalog 2))
;; => "Coffee"

;; The var is the instance itself.
(class s/Catalog)
;; => shop.Catalog

;; Calling it is an error with a helpful message.
(err (s/Catalog))
;; => "kt: `Catalog` is an object, not a function: it cannot be called. The var is the value itself; call a member with the object as its first argument, e.g. (alias/.member alias/Name ...)."

;; ## Companion objects
;;
;; A companion member is called with the class var as the receiver. This is the Kotlin `Money.ZERO`.

;; Kotlin: Cart.MAX_LINES
(s/MAX_LINES s/Cart)
;; => 100

;; Kotlin: Cart.DEFAULT_NOTE
(s/DEFAULT_NOTE s/Cart)
;; => "none"

;; Kotlin: Money.CURRENCY
(s/CURRENCY s/Money)
;; => "EUR"

;; Kotlin: Money.ZERO
(str (s/ZERO s/Money))
;; => "0.00"

;; A companion function has the prefix `.`.
;; Kotlin: Money.of(3, 50)
(str (s/.of s/Money 3 50))
;; => "3.50"

;; Kotlin: Money.of(3)    -- `cents` has a default
(str (s/.of s/Money 3))
;; => "3.00"

;; Kotlin: Money.parse("12.5")
(str (s/.parse s/Money "12.5"))
;; => "12.50"

;; The receiver must be the class that owns the companion. The error shows what is expected.
(err (s/.parse s/Cart "12.5"))
;; => "kt: no Kotlin declaration of `.parse` fits (.parse s/Cart \"12.5\")"

;; ## Enum entries
;;
;; An enum entry is a var that holds the entry. The name keeps the dot: `Status.NEW`.

;; Kotlin: Status.NEW
(str s/Status.NEW)
;; => "NEW"

;; Kotlin: Status.PAID.code
(s/code s/Status.PAID)
;; => "paid"

;; Kotlin: Status.NEW.next()
(str (s/.next s/Status.NEW))
;; => "PAID"

;; Kotlin: Status.SHIPPED.next()    -- null
(s/.next s/Status.SHIPPED)
;; => nil

;; Entries are the same objects as in Kotlin, so `=` and `identical?` work.
;; Kotlin: cart.status == Status.NEW
(= s/Status.NEW (s/status cart))
;; => true

;; ## Nested classes
;;
;; A nested class keeps its dots in the name: `Cart.Line`.

;; Kotlin: Cart.Line(tea, 2)
(def line (s/Cart.Line tea 2))

;; Kotlin: line.subtotal
(str (s/subtotal line))
;; => "7.00"

;; Kotlin: line.quantity
(s/quantity line)
;; => 2

;; Kotlin: cart.lines.map { it.quantity }
(map s/quantity (s/lines cart))
;; => (2 1)

;; ## Top-level properties
;;
;; A top-level property is a function without arguments. `kt/set!` can write a `var` one (see example 10).

;; Kotlin: VERSION
(s/VERSION)
;; => "1.0"

;; Kotlin: taxPercent
(s/taxPercent)
;; => 20
