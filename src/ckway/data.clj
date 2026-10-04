(ns ckway.data
  "`(kt/data x)`: README rule 11, a read-only map of the properties that the primary constructor of the
  class of `x` declares (the Kotlin `val (id, name) = user`).

    keys      keywords with the Kotlin names (`:firstName`), in constructor order
    values    what the property read returns (`ckway.resolve/plan` + `ckway.rt/prepare`, the same code as a call
              `(alias/firstName x)`): a value-class value stays the boxed object, a Kotlin function value is a
              Clojure function, Unit is nil. Nothing is converted recursively.
    map       a PersistentArrayMap built directly, so the order holds for any number of properties
              (an `assoc` on a map of more than 8 entries gives an unordered hash map, as for every Clojure map)

  It is a function: it works with `map` and on any run-time class (the class of `x` is looked up, no type
  hint). The accessors of a class are made once and kept in a `ClassValue` (nothing keeps the class alive).

  What is a constructor property (`ckway.meta/primary-properties`): a parameter of the primary constructor for
  which the class declares a public member property of the same name and type. So

    class User(override val email: Email, ...)   an overriding property is a property: included
    class C(val a: Int, b: Int, private val c: Int, var d: String) { val e = b * 2 }
                                                 => {:a .. :d ..}: `b` is no property, `c` is not public
    data class                                   every parameter is a property
    class C(b: Int) { val b: Int = b * 2 }       looks like `class C(val b: Int)` (the metadata has no link): included

  An error (ex-info) for an object whose class has no Kotlin class metadata, no primary constructor, or no
  property in it."
  (:require [ckway.meta :as meta]
            [ckway.resolve :as r]
            [ckway.rt :as rt])
  (:import [clojure.lang PersistentArrayMap]))

(set! *warn-on-reflection* true)

(defn- reader
  "Function of an object that reads the property `decl`."
  [decl]
  (let [call (rt/prepare (r/plan decl [{:arg nil :info {} :idx 0}]))]
    (fn [x] (call (doto (object-array 1) (aset 0 x))))))

(defn- accessors-of
  "{:keys [kw ...] :readers [fn ...]} or {:error text} for the class `c`."
  [^Class c]
  (let [info (meta/primary-properties c)
        n (.getName c)]
    (cond
      (nil? info) {:error (str "kt/data: " n " is not a Kotlin class (it has no Kotlin class metadata), so kt does not know its primary constructor")}
      (not (:primary? info)) {:error (str "kt/data: the Kotlin class " n " has no primary constructor")}
      (empty? (:props info)) {:error (str "kt/data: the primary constructor of " n " declares no property "
                                          "(a parameter that is no public `val` or `var` is not a property)")}
      :else {:keys (mapv #(keyword (:name %)) (:props info))
             :readers (mapv #(reader (:decl %)) (:props info))})))

(def ^:private accessors
  (proxy [java.lang.ClassValue] []
    (computeValue [c] (accessors-of c))))

(defn data
  "A read-only map of the primary-constructor properties of `x` (see the namespace docstring). nil gives nil."
  [x]
  (when (some? x)
    (let [{:keys [error keys readers]} (.get ^java.lang.ClassValue accessors (class x))]
      (when error (throw (ex-info error {:kt/error true :class (class x)})))
      (let [n (count keys)
            arr (object-array (* 2 n))]
        (dotimes [i n]
          (aset arr (* 2 i) (nth keys i))
          (aset arr (inc (* 2 i)) ((nth readers i) x)))
        (PersistentArrayMap. arr)))))
