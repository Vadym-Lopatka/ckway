;; # 11. kt/reify
;;
;; `kt/reify` implements Kotlin interfaces from Clojure. It has the shape of `reify`: the interface,
;; then its members, with `this` as the first parameter. Member names follow the usual rule:
;; a function has the prefix `.`, a property has not. Alias needed: `:examples`.
(ns examples.11-reify
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s]
            '[kotlinx.coroutines :as co])

(def products (vec (s/products s/Catalog)))

;; ## One interface
;;
;; Kotlin: `fun interface Rule { fun applies(product: Product): Boolean }`.

;; Kotlin: object : Rule { override fun applies(product: Product) = product.hasTag("food") }
(def food-rule
  (kt/reify s/Rule
    (.applies [this product] (s/.hasTag product "food"))))

;; Kotlin: Catalog.products.filter { food.applies(it) }.map { it.name }
(map s/name (filter #(s/.applies food-rule %) products))
;; => ("Cake")

;; Kotlin calls the object. Here is a Kotlin function that takes a `Rule`:
;; Kotlin: cart.countWhere(food)
(s/.countWhere (s/cart :build (fn [c] (s/.addAll c (nth products 0) (nth products 2)))) food-rule)
;; => 1

;; The object is a real Kotlin interface instance.
(instance? shop.Rule food-rule)
;; => true

;; `toString`, `hashCode` and `equals` can be written, as in `reify`.
(str (kt/reify s/Rule
       (.applies [this product] true)
       (toString [this] "always")))
;; => "always"

;; ## Properties, setters and defaults
;;
;; `Repository` has a `var name`, an `all()`, two `find` overloads, `price`, a default method `count()`,
;; and a `suspend fun load(id)`. A property is written without a dot: `(name [this] ...)` is the
;; getter and `(name [this value] ...)` is the setter.

(def label (atom "mine"))

(def repo
  (kt/reify s/Repository
    (name [this] @label)
    (name [this value] (reset! label value))
    (.all [this] products)
    (.find [this ^long id] (first (filter #(= id (s/id %)) products)))
    (.find [this ^String name] (first (filter #(= name (s/name %)) products)))
    (.price [this id] (s/Money (* 100 id)))
    (.load [this id]
      (co/delay 5)
      (first (filter #(= id (s/id %)) products)))))

;; Kotlin: repo.name
(s/name repo)
;; => "mine"

;; Kotlin: repo.name = "again"
(kt/set! (s/name repo) "again")
;; => "again"

@label
;; => "again"

;; A member that you did not write keeps its Kotlin default. `count()` is a default method of the interface;
;; it calls `all()`, which you wrote.
;; Kotlin: repo.count()
(s/.count repo)
;; => 4

;; ## Overloads with a hint
;;
;; `find(id: Long)` and `find(name: String)` have the same name and the same number of parameters.
;; A type hint on a parameter tells them apart, as in `reify`. Without it, the error lists the candidates.

;; Kotlin: repo.find("Cake")?.name
(s/name (s/.find repo "Cake"))
;; => "Cake"

;; Kotlin: repo.find(2)?.name
(s/name (s/.find repo 2))
;; => "Coffee"

(err (kt/reify s/Repository
  (.find [this id] nil)))
;; => "kt/reify: `.find` with 2 parameters is ambiguous. Candidates:"

;; ## A value class member
;;
;; `price(id): Money` returns a value class. The Clojure body returns the object.

;; Kotlin: repo.price(3)
(str (s/.price repo 3))
;; => "3.00"

;; A number is not a `Money`. The error says so.
(err (s/.price (kt/reify s/Repository
            (name [this] "x")
            (.all [this] [])
            (.find [this ^long id] nil)
            (.find [this ^String name] nil)
            (.price [this id] 5)
            (.load [this id] nil))
          1))
;; => "kt: expected shop.Money where Kotlin expects it, got java.lang.Long 5. A value class is always the object, not the underlying value."

;; The same for a `fun interface` with a value class in its method:
;; `fun interface Discount { fun adjust(total: Money): Money }`.
(def minus-one
  (kt/reify s/Discount
    (.adjust [this total] (s/.minus total (s/Money 100)))))

(def ann-cart (s/cart "ann" (fn [c] (s/.add c (nth products 0)) (s/.add c (nth products 2) 2))))

;; Kotlin: minus1.adjust(cart.total)
(str (s/.adjust minus-one (s/total ann-cart)))
;; => "12.50"

;; Kotlin: cart.total(minus1)
(str (s/.total ann-cart minus-one))
;; => "12.50"

;; ## A suspend member
;;
;; `load` is a `suspend` member. The body runs on a virtual thread, and it can call suspend functions.
;; Kotlin: runBlocking { repo.load(1)?.name }
(s/name (s/.load repo 1))
;; => "Tea"

;; A body can say where it runs. It returns a `Product?`, as Kotlin declares, and writes the answer to an atom.
(let [virtual (atom nil)
      repo (kt/reify s/Repository
             (name [this] "x")
             (.all [this] [])
             (.find [this ^long id] nil)
             (.find [this ^String name] nil)
             (.price [this id] (s/Money 1))
             (.load [this id]
               (reset! virtual (.isVirtual (Thread/currentThread)))
               nil))]
  (s/.load repo 1)
  @virtual)
;; => true

;; An exception in the body reaches the caller as it is.
(err (s/.load (kt/reify s/Repository
           (name [this] "x")
           (.all [this] [])
           (.find [this ^long id] nil)
           (.find [this ^String name] nil)
           (.price [this id] (s/Money 1))
           (.load [this id] (co/delay 5) (throw (IllegalStateException. "no such product"))))
         1))
;; => "no such product"

;; ## Kotlin calls the Clojure object
;;
;; Kotlin: `fun describeRepository(repo: Repository)` and `fun rename(repo: Repository, name: String)`.

(s/describeRepository repo)
;; => "again: 4 products, first=Tea"

;; Kotlin: rename(repo, "renamed")    -- Kotlin sets the property, and reads it back
(s/rename repo "renamed")
;; => "renamed"

@label
;; => "renamed"

;; ## Two interfaces
;;
;; A Kotlin interface and a Java interface in one object. Each interface is followed by its members.

(def both
  (kt/reify s/Repository
    (name [this] "both")
    (.all [this] [])
    (.find [this ^long id] nil)
    (.find [this ^String name] nil)
    (.price [this id] (s/Money 1))
    (.load [this id] nil)
    Runnable
    (run [this] (println "running"))))

(.run ^Runnable both)
;; prints: running

[(instance? shop.Repository both) (instance? Runnable both)]
;; => [true true]

;; ## Errors
;;
;; A name that is not a member lists the members you can write, with their Kotlin declarations.
(err (kt/reify s/Rule
  (.nope [this] 1)))
;; => "kt/reify: `.nope` is not a member of the interfaces. Members that you can write:"

;; A wrong number of parameters (`this` counts):
(err (kt/reify s/Rule
  (.applies [this] true)))
;; => "kt/reify: `.applies` takes a different number of parameters than 1 (`this` counts). Declared:"

;; Only interfaces. A class is refused:
(err (kt/reify s/Product
  (.x [this] 1)))
;; => "kt/reify takes interfaces only: `s/Product` is a class (shop.Product). A class cannot be implemented by kt/reify."
