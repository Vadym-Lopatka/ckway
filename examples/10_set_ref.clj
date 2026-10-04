;; # 10. kt/set! and kt/ref
;;
;; Two forms copy two Kotlin syntax forms. `(kt/set! (alias/p x) v)` is `x.p = v`.
;; `(kt/ref X y)` is `X::y`. Alias needed: `:examples`.
(ns examples.10-set-ref
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def cake (s/Product 3 "Cake" (s/Money 500) ["food"]))
(def cart (s/Cart "ann"))

;; ## Write a property
;;
;; The first argument of `kt/set!` is the form that reads the property. The value follows the rules of
;; an argument. `kt/set!` returns the value, like `set!`.

;; Kotlin: cart.note = "gift"
(kt/set! (s/note cart) "gift")
;; => "gift"

(s/note cart)
;; => "gift"

;; A nullable property takes `nil`.
;; Kotlin: cart.note = null
(kt/set! (s/note cart) nil)
;; => nil

;; An `Int` property takes a Clojure integer. The value is an `Integer` in Kotlin.
;; Kotlin: cart.priority = 5
(kt/set! (s/priority cart) 5)
;; => 5

(class (s/priority cart))
;; => java.lang.Integer

;; An enum entry:
;; Kotlin: cart.status = Status.PAID
(str (kt/set! (s/status cart) s/Status.PAID))
;; => "PAID"

;; A property of a value class type needs the object, as an argument does.
;; Kotlin: cart.budget = Money(5000)
(str (kt/set! (s/budget cart) (s/Money 5000)))
;; => "50.00"

(str (s/budget cart))
;; => "50.00"

;; A top-level `var`:
;; Kotlin: taxPercent = 25
(kt/set! (s/taxPercent) 25)
;; => 25

(s/taxPercent)
;; => 25

(kt/set! (s/taxPercent) 20)
;; => 20

;; An extension property of a JDK type:
;; Kotlin: sb.firstChar = 'z'
(let [sb (StringBuilder. "abc")]
  (kt/set! (s/firstChar sb) \z)
  (str sb))
;; => "zbc"

;; ## What kt/set! refuses
;;
;; A `val` has no setter:
(err (kt/set! (s/owner cart) "bob"))
;; => "kt: (kt/set! (owner _) \"bob\"): `val shop.Cart.owner: String` is read-only: a `val` has no setter"

(err (kt/set! (s/VERSION) "2"))
;; => "kt: (kt/set! (s/VERSION) \"2\"): `val VERSION: String` is read-only: a `val` has no setter"

;; A value of the wrong type, `nil` for a non-null property, a number that does not fit:
(err (kt/set! (s/note cart) 1))
;; => "kt: (kt/set! (s/note cart) 1): `value` is String but got Long"

(err (kt/set! (s/priority cart) nil))
;; => "kt: (kt/set! (s/priority cart) nil): `nil` passed to non-nullable `value`"

(err (kt/set! (s/priority cart) 5000000000))
;; => "kt: the argument `value` (Int) is 5000000000, which is out of range for Int"

(err (kt/set! (s/budget cart) 5000))
;; => "kt: (kt/set! (s/budget cart) 5000): `value` is Money but got Long"

;; The first argument must be a read of a Kotlin property:
(err (kt/set! (s/welcome "x") 1))
;; => "kt: (kt/set! (s/welcome \"x\") 1): the first argument must be a kt property read like `(alias/name receiver)` or `(alias/topLevelVar)`, the form that reads the property; `s/welcome` has no Kotlin property. Kotlin declares:"

;; ## Class references
;;
;; `(kt/ref X class)` is `X::class`. Kotlin APIs that take a `KClass` get it: `fun className(type: KClass<*>)`.

;; Kotlin: className(Product::class)
(s/className (kt/ref s/Product class))
;; => "Product"

;; Kotlin: className(String::class)
(s/className (kt/ref String class))
;; => "String"

;; Kotlin: className(Catalog::class)    -- an object is a type
(s/className (kt/ref s/Catalog class))
;; => "Catalog"

;; ## Property references
;;
;; `(kt/ref X name)` is `X::name`, a `KProperty1`. Kotlin APIs get it as it is.
;; Kotlin: `fun <V> readProperty(p: Product, prop: KProperty1<Product, V>): V`.

;; Kotlin: propertyName(Product::name)
(s/propertyName (kt/ref s/Product name))
;; => "name"

;; Kotlin: readProperty(tea, Product::name)
(s/readProperty tea (kt/ref s/Product name))
;; => "Tea"

;; Kotlin: readProperty(tea, Product::id)
(s/readProperty tea (kt/ref s/Product id))
;; => 1

;; A `var` property gives a `KMutableProperty1`.
(instance? kotlin.reflect.KMutableProperty1 (kt/ref s/Cart note))
;; => true

(instance? kotlin.reflect.KMutableProperty1 (kt/ref s/Product name))
;; => false

;; Kotlin: Cart::note.set(c, "via ref")
(let [c (s/Cart "x")]
  (.set (kt/ref s/Cart note) c "via ref")
  (s/note c))
;; => "via ref"

;; An extension property: Kotlin: String::initials
((kt/ref String initials) "ann lee")
;; => "AL"

;; ## A reference is a Clojure function
;;
;; A Kotlin function value is a Clojure function (rule 6), and a reference is a Kotlin function value.

;; Kotlin: products.map(Product::name)
(map (kt/ref s/Product name) [tea cake])
;; => ("Tea" "Cake")

(map s/name (sort-by (kt/ref s/Product id) > [tea cake]))
;; => ("Cake" "Tea")

(map (kt/ref String initials) ["a b" "c d"])
;; => ("AB" "CD")

;; ## Function references
;;
;; `(kt/ref X .name)` is `X::name` for a member. A function name has its `.` prefix. A top-level function
;; has no `X`. A reference has all parameters of the function: a default value is not used.

;; Kotlin: ::welcome
((kt/ref s/welcome) "Ann" "Hi" "!")
;; => "Hi, Ann!"

;; Kotlin: Cart::add
((kt/ref s/Cart .add) (s/Cart) tea 2 nil)
;; => 2

;; Kotlin: String::slug
((kt/ref String .slug) "A B" "-")
;; => "a-b"

;; A reference takes all parameters. Too few arguments is an error that says so:
(err ((kt/ref s/welcome) "Ann"))
;; => "kt: (kt/ref s/welcome) takes 3 arguments (name, greeting, punct), got 1. Kotlin: fun welcome(name: String, greeting: String = ..., punct: String = ...): String. A function reference takes all parameters: Kotlin defaults are not applied."

;; ## Constructor references
;;
;; Kotlin: ::Product
(str ((kt/ref s/Product) 9 "Milk" (s/Money 120) ["drink"]))
;; => "Product(id=9, name=Milk, price=1.20, tags=[\"drink\"])"

;; Kotlin: ::Money
(str ((kt/ref s/Money) 5))
;; => "0.05"

;; ## References that are bound to an object
;;
;; Kotlin allows `Catalog::size` for an `object`, and `::taxPercent` for a top-level property.
;; The reference needs no receiver.

;; Kotlin: Catalog::size
((kt/ref s/Catalog size))
;; => 4

;; Kotlin: Catalog::byTag
(map s/name ((kt/ref s/Catalog .byTag) "food"))
;; => ("Cake")

;; Kotlin: ::taxPercent
((kt/ref s/taxPercent))
;; => 20

;; ## What kt/ref refuses
;;
;; A name that has more than one declaration. Kotlin picks by the expected type, `kt` has none:
(err (kt/ref s/kind))
;; => "kt: (kt/ref s/kind): `s/kind` names 3 Kotlin declarations. Kotlin chooses by the expected type, kt has none, so a reference must name exactly one:"

;; A name that does not exist:
(err (kt/ref s/Product nope))
;; => "kt: (kt/ref s/Product nope): no property `nope` of `s/Product` found. Looked in: shop. (Is the package of the declaration required with kt/require in this namespace?)"

;; A reference that is bound to a local value (`tea::name`). Write the type and pass the object when you call:
(err (let [p tea] (kt/ref p name)))
;; => "kt: (kt/ref p name): bound references are not supported (`user::email`, `obj::method`): `p` is a local, not a type. Write the type, e.g. (kt/ref users/User email), and pass the object to the reference when you call it."

;; A suspend function or a `reified` function:
(err (kt/ref s/fetchProduct))
;; => "kt: (kt/ref s/fetchProduct): a reference to a `suspend` function (a KSuspendFunction) is not supported yet"

(err (kt/ref s/typeName))
;; => "kt: (kt/ref s/typeName): `inline reified` functions cannot be referenced (they have no JVM method for a reference)"
