;; # 5. Extensions
;;
;; A Kotlin extension function or property has the receiver as its first argument, like a member.
;; You cannot see the difference in the call. The var lives in the namespace of the package that declares
;; the extension. Alias needed: `:examples`.
(ns examples.05-extensions
  (:require [clojure.repl :refer [doc]]
            [examples.util :refer [err]]
            [kt.core :as kt]))

(kt/require '[shop :as s]
            '[shop.pricing :as pr])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def water (s/Product 4 "Water" (s/Money 0) ["drink"]))

;; ## Extension functions on a JDK type
;;
;; `String.slug(separator = "-")` is declared in the package `shop`. The receiver is a `java.lang.String`.

;; Kotlin: "Hello Big World".slug()
(s/.slug "Hello Big World")
;; => "hello-big-world"

;; Kotlin: "Hello Big World".slug("_")
(s/.slug "Hello Big World" "_")
;; => "hello_big_world"

;; Kotlin: "Hello Big World".slug(separator = "_")
(s/.slug "Hello Big World" :separator "_")
;; => "hello_big_world"

;; An extension is a function value, so you can map it. Kotlin: listOf("A B", "C D").map(String::slug)
(map s/.slug ["A B" "C D"])
;; => ("a-b" "c-d")

(doc s/.slug)
;; prints: fun String.slug(separator: String = ...): String

;; ## Extension properties
;;
;; An extension property has no dot, like every property.

;; Kotlin: "ann marie lee".initials
(s/initials "ann marie lee")
;; => "AML"

;; Kotlin: product.label    -- val Product.label
(s/label tea)
;; => "Tea (3.50)"

;; ## Extensions on your own classes
;;
;; Kotlin: tea.withTag("new")
(s/tags (s/.withTag tea "new"))
;; => ["drink" "hot" "new"]

;; An extension on a Java collection type. A Clojure vector is a `java.util.List`.
;; Kotlin: listOf(tea, water).totalPrice()
(str (s/.totalPrice [tea water]))
;; => "3.50"

;; ## An extension in another package
;;
;; The extensions `Product.priceWithTax` and `Product.isFree` are declared in `shop.pricing`.
;; They are vars of the namespace of `shop.pricing`, not of `shop`. Require the package to use them.

;; Kotlin: import shop.pricing.*
;; Kotlin: tea.priceWithTax()
(str (pr/.priceWithTax tea))
;; => "4.20"

;; Kotlin: tea.priceWithTax(10)
(str (pr/.priceWithTax tea 10))
;; => "3.85"

;; Kotlin: tea.priceWithTax(percent = 5)
(str (pr/.priceWithTax tea :percent 5))
;; => "3.67"

;; Kotlin: tea.isFree
(pr/isFree tea)
;; => false

;; Kotlin: water.isFree
(pr/isFree water)
;; => true

;; Kotlin: tea.price.discounted(10)    -- an extension on a value class
(str (pr/.discounted (s/price tea) 10))
;; => "3.15"

;; The extension is not in the namespace of `shop`.
(err (s/.priceWithTax tea))
;; => "No such var: s/.priceWithTax"

;; ## A member wins over an extension
;;
;; `Product` has a member `summary()`. `Extensions.kt` declares an extension `Product.summary()` too.
;; Kotlin calls the member. `kt` does the same.

;; Kotlin: tea.summary()
(s/.summary tea)
;; => "member: Tea"

;; ## Inherited members
;;
;; Each name that a class of the package inherits is a var of the namespace. `MemoryRepository` gets
;; `count()` and `name` from the interface `Repository`, and `toString` and `hashCode` from `Any`.

(def repo (s/MemoryRepository (s/products s/Catalog)))

;; Kotlin: repo.count()    -- a default method of the interface
(s/.count repo)
;; => 4

;; Kotlin: repo.name       -- a property of the interface, implemented by the class
(s/name repo)
;; => "memory"

;; An extension of the interface works on each class that implements it.
;; Kotlin: repo.cheapest()?.name
(s/name (s/.cheapest repo))
;; => "Water"

;; Kotlin: repo.find("Cake")?.id   -- two overloads of `find`: the argument type decides
(s/id (s/.find repo "Cake"))
;; => 3

;; Kotlin: repo.find(2)?.name
(s/name (s/.find repo 2))
;; => "Coffee"

;; Kotlin: tea.equals(tea)
(s/.equals tea tea)
;; => true

;; ## The wrong receiver
;;
;; The compiler of `kt` knows the Kotlin types of a call. A receiver that cannot fit is an error.

(err (s/label "text"))
;; => "kt: no Kotlin declaration of `label` fits (label \"text\")"

(err (s/.summary 5))
;; => "kt: no Kotlin declaration of `.summary` fits (.summary 5)"

;; ## Context parameters
;;
;; Kotlin: `context(percent: Int) fun Money.withTax(): Money`. The receivers come first, in this order:
;; the context parameters, then the class instance, then the extension receiver.

;; Kotlin: with(20) { Money(1000).withTax() }
(str (s/.withTax 20 (s/Money 1000)))
;; => "12.00"

;; A missing receiver is an error that shows the declaration.
(err (s/.withTax (s/Money 1000)))
;; => "kt: (.withTax (s/Money 1000)): expects 2 receiver arguments first (context, extension), got 1"
