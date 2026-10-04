;; # 13. Everything together
;;
;; One small program on the `shop` library, the kind of code you write when you use `kt` for real:
;; settings, a catalog, a cart from the builder DSL, discounts as functions and as `kt/reify` objects,
;; prices as value classes, a suspend checkout, a `Flow`, and references. Each section uses what the
;; earlier examples explained. Aliases: `:examples:kotlinc` (the reified calls need the compiler).
;; Aliases: :kotlinc
(ns examples.13-everything-together
  (:require [clojure.string :as str]
            [examples.util :refer [err]]
            [kt.core :as kt]))

(kt/require '[shop :as s]
            '[shop.pricing :as pr]
            '[kotlin.time :as t]
            '[kotlinx.coroutines :as co]
            '[kotlinx.coroutines.flow :as flow])

;; ## Settings: a reified call
;;
;; `Settings.get<Int>("tax")` needs a type argument. Then a top-level `var` is written with `kt/set!`.
(def settings (s/Settings {"tax" "20" "currency" "EUR"}))

(kt/set! (s/taxPercent) (s/.get settings "tax" :<> Int))
;; => 20

(s/.get settings "currency")
;; => "EUR"

;; ## The catalog: reified parsing and value classes
;;
;; `String.parseAs<Money>()` reads "3.50" to a `Money`. `Product` is a data class with defaults.
(defn parse-product [id line]
  (let [[name price tags] (str/split line #";")]
    (s/Product id name (s/.parseAs price :<> s/Money) (str/split tags #","))))

(def catalog
  (vec (map-indexed (fn [i line] (parse-product (inc i) line))
                    ["Tea;3.50;drink,hot" "Coffee;4.20;drink,hot" "Cake;5.00;food" "Water;1.00;drink"])))

(map s/name catalog)
;; => ("Tea" "Coffee" "Cake" "Water")

(map (comp str s/price) catalog)
;; => ("3.50" "4.20" "5.00" "1.00")

;; References work as functions, and as arguments for Kotlin.
(map (kt/ref s/Product name) (filter #(s/.hasTag % "drink") catalog))
;; => ("Tea" "Coffee" "Water")

(s/readProperty (first catalog) (kt/ref s/Product name))
;; => "Tea"

(defn product [name] (first (filter #(= name (s/name %)) catalog)))

;; ## A cart from the builder DSL
;;
;; Kotlin: cart("ann") { add(tea, 2); add(cake) }
(def cart
  (s/cart "ann" (fn [c]
                  (s/.add c (product "Tea") :quantity 2)
                  (s/.add c (product "Cake"))
                  (s/.add c (product "Coffee") :note "gift"))))

(str cart)
;; => "Cart(ann, 4 items)"

(kt/set! (s/priority cart) 2)
;; => 2

(select-keys (kt/data cart) [:owner])
;; => {:owner "ann"}

[(s/note cart) (str (s/total cart))]
;; => ["gift" "16.20"]

;; ## Prices: value classes and extensions
(str (pr/.priceWithTax (product "Tea")))
;; => "4.20"

(map s/name (remove pr/isFree catalog))
;; => ("Tea" "Coffee" "Cake" "Water")

;; ## Discounts: a function, a Kotlin lambda, and a `kt/reify` object
;;
;; 1. A Clojure function where Kotlin wants `(Money) -> Money`.
;; 2. A Kotlin lambda (`taxer`) that is a Clojure function: it composes with `comp`.
;; 3. A `Discount` object from `kt/reify`. A Kotlin `fun interface` is implemented in the Clojure way.
(def minus-one-euro (fn [m] (s/.minus m (s/Money 100))))

(def ten-percent
  (kt/reify s/Discount
    (.adjust [this total] (s/.minus total (s/.percent total 10)))))

(def with-tax (s/taxer (s/taxPercent)))

(def total (s/total cart))

(str (s/applyDiscount total minus-one-euro))
;; => "15.20"

(str (s/.adjust ten-percent total))
;; => "14.58"

(str ((comp with-tax #(s/.adjust ten-percent %) minus-one-euro) total))
;; => "16.41"

;; A rule: which items get the discount? `Rule` is a `fun interface`: a function or an object.
(def hot-drink
  (kt/reify s/Rule
    (.applies [this product] (and (s/.hasTag product "drink") (s/.hasTag product "hot")))))

[(s/.countWhere cart hot-drink)
 (s/.countWhere cart (fn [p] (s/.hasTag p "food")))]
;; => [3 1]

;; ## Checkout: suspend, a lambda that suspends, and a Flow
;;
;; `checkout` waits (it calls `delay`), calls our `charge` lambda, and returns a `Receipt`.
(def receipt
  (s/checkout cart :charge (fn [money]
                             (co/delay 5)
                             (< (s/cents money) 5000))))

(let [{:keys [owner status]} (kt/data receipt)]
  [owner (str status)])
;; => ["ann" "PAID"]

(str (:total (kt/data receipt)))
;; => "16.20"

;; The lines come as a `Flow`. `collect` takes a Clojure function.
(let [printed (atom [])]
  (flow/.collect (s/.lineFlow cart)
                 (fn [line]
                   (swap! printed conj (str (s/quantity line) " x " (s/name (s/product line))))))
  @printed)
;; => ["2 x Tea" "1 x Cake" "1 x Coffee"]

;; Many checkouts at the same time. Each body runs on a virtual thread.
(defn checkout-all [carts]
  (co/runBlocking :block
                  (fn [scope]
                    (mapv co/.await
                          (mapv (fn [c] (co/.async scope :block (fn [_] (s/checkout c)))) carts)))))

(let [carts (mapv (fn [i] (s/cart (str "customer-" i) (fn [c] (s/.add c (product "Water") :quantity i))))
                  (range 1 6))
      t0 (System/nanoTime)
      receipts (checkout-all carts)]
  [(mapv s/owner receipts)
   (map (comp str s/total) receipts)
   (< (/ (- (System/nanoTime) t0) 1e6) 1000)])
;; => [["customer-1" "customer-2" "customer-3" "customer-4" "customer-5"] ("1.00" "2.00" "3.00" "4.00" "5.00") true]

;; ## Error: a wrong call is still an error before it runs
(err (s/applyDiscount total (s/Money 100)))
;; => "kt: no Kotlin declaration of `applyDiscount` fits (applyDiscount total (s/Money 100))"

;; Put the tax setting back.
(kt/set! (s/taxPercent) 20)
;; => 20
