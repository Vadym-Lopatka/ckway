;; # 4. Data classes
;;
;; A Kotlin `data class` has `copy`, `equals`, `hashCode` and `toString`. `kt/data` turns an object
;; into a Clojure map. Kotlin vars work as functions in the usual Clojure pipelines.
;; Alias needed: `:examples`.
(ns examples.04-data-classes
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s])

(def tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
(def coffee (s/Product 2 "Coffee" (s/Money 420) ["drink" "hot"]))
(def cake (s/Product 3 "Cake" (s/Money 500) ["food"]))
(def water (s/Product 4 "Water" (s/Money 100) ["drink"]))
(def products [tea coffee cake water])

;; ## copy
;;
;; `copy` is a member with a default for each parameter. Name the parameters that you want to change.

;; Kotlin: tea.copy(name = "Green tea")
(str (s/.copy tea :name "Green tea"))
;; => "Product(id=1, name=Green tea, price=3.50, tags=[\"drink\" \"hot\"])"

;; Kotlin: tea.copy(price = Money(300), tags = emptyList())
(str (s/.copy tea :price (s/Money 300) :tags []))
;; => "Product(id=1, name=Tea, price=3.00, tags=[])"

;; ## Equality, hash and string
;;
;; `=` calls the Kotlin `equals`. `hash` and `str` call `hashCode` and `toString`.

;; Kotlin: tea == Product(1, "Tea", Money(350), listOf("drink", "hot"))
(= tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
;; => true

;; Kotlin: tea === Product(...)
(identical? tea (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"]))
;; => false

;; Kotlin: tea == tea.copy(id = 2)
(= tea (s/.copy tea :id 2))
;; => false

(= (hash tea) (hash (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"])))
;; => true

;; Kotlin: println(tea)
(str tea)
;; => "Product(id=1, name=Tea, price=3.50, tags=[\"drink\" \"hot\"])"

;; An object with a good `equals` and `hashCode` is a good Clojure map key or set member.
(count (conj #{tea} (s/Product 1 "Tea" (s/Money 350) ["drink" "hot"])))
;; => 1

;; Kotlin: val (id, name) = tea     -- the componentN functions
(s/.component2 tea)
;; => "Tea"

;; ## kt/data
;;
;; `(kt/data x)` is a read-only map of the properties of the primary constructor. The keys are keywords with
;; the Kotlin names, in constructor order. The values are not changed.

;; Kotlin: mapOf("id" to tea.id, "name" to tea.name, "tags" to tea.tags)
(select-keys (kt/data tea) [:id :name :tags])
;; => {:id 1, :name "Tea", :tags ["drink" "hot"]}

(keys (kt/data tea))
;; => (:id :name :price :tags)

(:name (kt/data tea))
;; => "Tea"

;; A value class value stays the object. Use a Kotlin function on it.
(s/cents (:price (kt/data tea)))
;; => 350

;; Destructuring with the keys:
(let [{:keys [id name]} (kt/data tea)]
  [id name])
;; => [1 "Tea"]

;; Destructuring by position, as in the Kotlin `val (id, name, price) = tea`:
(let [[id name] (vals (kt/data tea))]
  [id name])
;; => [1 "Tea"]

;; The map is a copy. Changing it does not change the object.
(let [m (assoc (kt/data tea) :name "Changed")]
  [(:name m) (s/name tea)])
;; => ["Changed" "Tea"]

;; `nil` gives `nil`. A class without Kotlin metadata is an error.
(kt/data nil)
;; => nil

(err (kt/data "text"))
;; => "kt/data: java.lang.String is not a Kotlin class (it has no Kotlin class metadata), so kt does not know its primary constructor"

;; ## Functions in pipelines
;;
;; A kt var is a function, so `map`, `filter`, `sort-by`, `comp` and `->` work with it.

;; Kotlin: products.map { it.name }
(map s/name products)
;; => ("Tea" "Coffee" "Cake" "Water")

;; Kotlin: products.filter { it.hasTag("drink") }.sortedBy { it.price.cents }.map { it.name }
(->> products
     (filter #(s/.hasTag % "drink"))
     (sort-by (comp s/cents s/price))
     (map s/name))
;; => ("Water" "Tea" "Coffee")

;; Kotlin: products.sumOf { it.price.cents }
(->> products (map (comp s/cents s/price)) (reduce +))
;; => 1370

;; Kotlin: products.associate { it.id to it.name }
(into {} (map (juxt s/id s/name)) products)
;; => {1 "Tea", 2 "Coffee", 3 "Cake", 4 "Water"}

;; `->` with a member that has a receiver first:
;; Kotlin: tea.copy(name = "Green tea").name
(-> tea (s/.copy :name "Green tea") s/name)
;; => "Green tea"

;; Kotlin: products.groupBy { it.tags.first() }.mapValues { (_, ps) -> ps.map { it.name } }
(update-vals (group-by #(first (s/tags %)) products) #(mapv s/name %))
;; => {"drink" ["Tea" "Coffee" "Water"], "food" ["Cake"]}

;; Kotlin: products.count { it.hasTag("hot") }
(count (filter #(s/.hasTag % "hot") products))
;; => 2

;; A class that is not Comparable cannot be sorted by itself. This is the same in Kotlin.
(err (sort-by s/price products))
;; => "class shop.Money cannot be cast to class java.lang.Comparable (shop.Money is in unnamed module of loader 'app'; java.lang.Comparable is in module java.base of loader 'bootstrap')"
