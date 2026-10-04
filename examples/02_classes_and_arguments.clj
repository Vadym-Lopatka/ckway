;; # 2. Classes and arguments
;;
;; A Kotlin class var is a constructor. A keyword names a parameter. A parameter without an argument
;; takes its Kotlin default. Alias needed: `:examples`.
(ns examples.02-classes-and-arguments
  (:require [examples.util :refer [err]]
            [kt.core :as kt]))

(kt/require '[shop :as s])

;; ## Constructors
;;
;; Call the class var to make an object. A class var means what the class name means in Kotlin.

;; Kotlin: Cart("ann")
(str (s/Cart "ann"))
;; => "Cart(ann, 0 items)"

;; Kotlin: Cart()   -- `owner` has the default "guest"
(str (s/Cart))
;; => "Cart(guest, 0 items)"

;; Kotlin: Money(350)
(str (s/Money 350))
;; => "3.50"

;; Kotlin: Product(1, "Tea", Money(350))   -- `tags` has a default
(str (s/Product 1 "Tea" (s/Money 350)))
;; => "Product(id=1, name=Tea, price=3.50, tags=[])"

;; ## Named arguments
;;
;; A keyword literal names the parameter of the next argument. All arguments after it are named.

;; Kotlin: Cart(owner = "bob")
(str (s/Cart :owner "bob"))
;; => "Cart(bob, 0 items)"

;; Kotlin: Product(id = 2, name = "Coffee", price = Money(420), tags = listOf("drink"))
(str (s/Product :id 2 :name "Coffee" :price (s/Money 420) :tags ["drink"]))
;; => "Product(id=2, name=Coffee, price=4.20, tags=[\"drink\"])"

;; The order of named arguments is free.
;; Kotlin: Product(name = "Cake", price = Money(500), id = 3)
(str (s/Product :name "Cake" :price (s/Money 500) :id 3))
;; => "Product(id=3, name=Cake, price=5.00, tags=[])"

;; Positional arguments first, then named ones.
;; Kotlin: Product(4, "Water", tags = listOf("drink"), price = Money(100))
(str (s/Product 4 "Water" :tags ["drink"] :price (s/Money 100)))
;; => "Product(id=4, name=Water, price=1.00, tags=[\"drink\"])"

;; ## Defaults in members
;;
;; `Cart.add(product, quantity = 1, note = null)` returns the new quantity of the product.

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def coffee (s/Product 2 "Coffee" (s/Money 420) ["drink" "hot"]))
(def cake (s/Product 3 "Cake" (s/Money 500) ["food"]))
(def cart (s/Cart "ann"))

;; Kotlin: cart.add(tea)
(s/.add cart tea)
;; => 1

;; Kotlin: cart.add(tea, 2)
(s/.add cart tea 2)
;; => 3

;; Kotlin: cart.add(coffee, quantity = 2)
(s/.add cart coffee :quantity 2)
;; => 2

;; A parameter that you skip takes its default. Here `quantity` is skipped.
;; Kotlin: cart.add(cake, note = "gift")
(s/.add cart cake :note "gift")
;; => 1

;; Kotlin: cart.note
(s/note cart)
;; => "gift"

;; Kotlin: cart.count
(s/count cart)
;; => 6

;; ## Vararg
;;
;; A `vararg` parameter takes the remaining positional arguments.

;; Kotlin: joinLabel("a", "b", "c")
(s/joinLabel "a" "b" "c")
;; => "a b c"

;; Kotlin: joinLabel()
(s/joinLabel)
;; => ""

;; A parameter after a `vararg` is always named, in Kotlin and here.
;; Kotlin: joinLabel("a", "b", sep = "-")
(s/joinLabel "a" "b" :sep "-")
;; => "a-b"

;; When you name the `vararg` parameter, you give one collection. This is the Kotlin spread `*parts`.
;; Kotlin: joinLabel(*arrayOf("a", "b"), sep = "/")
(s/joinLabel :parts ["a" "b"] :sep "/")
;; => "a/b"

;; A vararg member, and the factory of a companion that has a vararg.
;; Kotlin: Cart.of("cy", tea, cake)
(str (s/.of s/Cart "cy" tea cake))
;; => "Cart(cy, 2 items)"

;; Kotlin: cart.addAll(*listOf(tea, cake).toTypedArray())
(s/.addAll (s/Cart) :products [tea cake])
;; => 2

;; ## Numbers
;;
;; A Clojure integer is a `Long`. Kotlin has `Int` and `Long`. `kt` follows the Kotlin rule for a literal:
;; a literal that fits `Int` is an `Int`, and it widens to `Long` or `Double` where the parameter needs that.

;; Kotlin: addUp(1, 2, 3)    -- addUp(a: Int, b: Long, c: Double)
(s/addUp 1 2 3)
;; => 6.0

;; Kotlin: addUp(1, 2, 3.5)
(s/addUp 1 2 3.5)
;; => 6.5

;; Two overloads, one for `Int` and one for `Long`: the Kotlin rule decides.
;; Kotlin: kind(1)
(s/kind 1)
;; => "Int"

;; Kotlin: kind(5_000_000_000)
(s/kind 5000000000)
;; => "Long"

;; You can say the width with a cast.
;; Kotlin: kind(1L)
(s/kind (long 1))
;; => "Long"

;; Kotlin: kind(1)
(s/kind (int 1))
;; => "Int"

;; A number that does not fit the parameter is an error. It does not wrap around.
(err (s/.add cart tea :quantity 5000000000))
;; => "kt: no Kotlin declaration of `.add` fits (.add cart tea :quantity 5000000000)"

;; A fraction is not an integer.
(err (s/.add cart tea :quantity 1.5))
;; => "kt: no Kotlin declaration of `.add` fits (.add cart tea :quantity 1.5)"

;; ## nil and nullable types
;;
;; Kotlin `null` is Clojure `nil`. `nil` is allowed only where the Kotlin type has a `?`.

;; Kotlin: nick(null)    -- nick(name: String?): String?
(s/nick nil)
;; => nil

;; Kotlin: nick("  ann ")
(s/nick "  ann ")
;; => "ann"

;; Kotlin: nick("   ")
(s/nick "   ")
;; => nil

;; Kotlin: cart.add(tea, note = null)
(s/.add (s/Cart) tea :note nil)
;; => 1

;; Kotlin: welcome(null)    -- does not compile in Kotlin
(err (s/welcome nil))
;; => "kt: no Kotlin declaration of `welcome` fits (welcome nil)"

;; ## Error messages
;;
;; Every error says what is wrong and shows the Kotlin declaration.

;; An unknown parameter name:
(err (s/welcome "Ann" :greetings "Hi"))
;; => "kt: (welcome \"Ann\" :greetings \"Hi\"): unknown parameter name `greetings`. Parameters: name, greeting, punct."

;; The same parameter twice:
(err (s/welcome "Ann" :punct "?" :punct "!"))
;; => "kt: (welcome \"Ann\" :punct \"?\" :punct \"!\"): parameter `punct` given twice"

;; A positional argument after a named argument:
(err (s/welcome :greeting "Hi" "Ann"))
;; => "kt: (welcome :greeting \"Hi\" \"Ann\"): positional argument \"Ann\" follows a named argument. Put all positional arguments before the first keyword."

;; A keyword without a value:
(err (s/welcome "Ann" :punct))
;; => "kt: (welcome \"Ann\" :punct): keyword :punct names a parameter but has no value. A keyword literal always names the next argument; to pass a keyword as a value, put it in a local."

;; Too many arguments:
(err (s/welcome "a" "b" "c" "d"))
;; => "kt: (welcome \"a\" \"b\" \"c\" \"d\"): too many arguments: 4 given, at most 3 expected"

;; A missing required parameter:
(err (s/Product 1 "Tea"))
;; => "kt: (Product 1 \"Tea\"): missing required parameter `price`. Pass it positionally or as `:price`."

;; An argument of the wrong type:
(err (s/Product 1 "Tea" 350))
;; => "kt: no Kotlin declaration of `Product` fits (Product 1 \"Tea\" 350)"

;; A class that has no public constructor, for example an enum class:
(err (s/Status))
;; => "kt: `Status` has no public constructor: it is an enum class. Use one of its entries: Status.NEW, Status.PAID, Status.SHIPPED."
