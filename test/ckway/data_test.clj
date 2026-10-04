(ns ckway.data-test
  "Step 6, R11: `(kt/data x)`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.core :as kt]
            [ckway.meta :as meta]))

(kt/require '[fx :as f])

(defn- error-of [x]
  (try (kt/data x) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-data-class
  (let [p (f/Person 7 "Ann" "a@b" (f/Uid 3))
        m (kt/data p)]
    (is (map? m))
    (is (= [:id :firstName :email :uid] (keys m)) "Kotlin names, constructor order")
    (is (= 7 (:id m)))
    (is (= "Ann" (:firstName m)))
    (is (= "a@b" (:email m)))
    (testing "a value-class value stays the boxed object; nothing is converted"
      (is (instance? fx.Uid (:uid m)))
      (is (= (f/Uid 3) (:uid m))))
    (testing "the values are what the property read returns"
      (is (= [(f/id p) (f/firstName p) (f/email p) (f/uid p)] (vec (vals m)))))
    (testing "a nil property"
      (is (nil? (:email (kt/data (f/Person 1 "x")))))
      (is (contains? (kt/data (f/Person 1 "x")) :email)))
    (testing "get, select-keys, =, print"
      (is (= {:id 7 :firstName "Ann"} (select-keys m [:id :firstName])))
      (is (= m (kt/data (f/Person 7 "Ann" "a@b" (f/Uid 3)))))
      (is (= "{:id 7, :firstName \"Ann\", :email \"a@b\", :uid " (subs (pr-str m) 0 45))))
    (testing "read-only: a Clojure map; java.util.Map writes fail; assoc leaves the object alone"
      (is (thrown? UnsupportedOperationException (.put ^java.util.Map m :x 1)))
      (assoc m :id 99)
      (is (= 7 (f/id p))))))

(deftest it-is-a-function
  (testing "nil, map, any run-time class, no type hint"
    (is (nil? (kt/data nil)))
    (is (= [{:id 1 :firstName "a" :email nil :uid (f/Uid 1)} {:id 2 :firstName "b" :email nil :uid (f/Uid 1)}]
           (map kt/data [(f/Person 1 "a") (f/Person 2 "b")])))
    (is (= [[:a :b] [:id :name]]
           (map (comp keys kt/data) [(f/CountMe 1 "x") (f/Person2 1 "x")])) "different classes in one call site")))

(deftest destructuring-both-ways
  (let [u (f/Person 7 "Ann" "a@b")]
    (is (= [7 "Ann"] (let [{:keys [id firstName]} (kt/data u)] [id firstName])))
    (is (= [7 "Ann" "a@b"] (let [[id name email] (vals (kt/data u))] [id name email])))
    (is (= [7 "Ann"] (let [{:keys [id firstName]} (kt/data u)] [id firstName])))))

(deftest twelve-properties-keep-their-order
  (let [w (f/Wide 1 "two" 3 4.0 true \6 nil 8 9 10 11 12)
        m (kt/data w)
        ks (mapv #(keyword (str "p" %)) (range 1 13))]
    (is (= 12 (count m)))
    (is (= ks (vec (keys m))))
    (is (= ks (mapv key (seq m))))
    (is (= [1 "two" 3 4.0 true \6 nil 8 9 10 11 12] (vec (vals m))))
    (is (= [:p1 :p2 :p3] (take 3 (keys m))))
    (is (= 12 (:p12 m)))
    (is (nil? (:p7 m)))
    (testing "a data class with 9 properties (more than the 8 of a small array map: see `web-data`)" (is true))))

(deftest constructor-parameters-that-are-not-properties
  (testing "Mixed(val a, b: Int, private val c, var d) { val e = b * 2 }: a and d only"
    (let [m (kt/data (f/Mixed 1 2 3 "d"))]
      (is (= [:a :d] (keys m)))
      (is (= {:a 1 :d "d"} m))
      (is (= 3 (f/.c (f/Mixed 1 2 3 "d"))))))
  (testing "a var property is read at the time of the call"
    (let [x (f/Mixed 1 2 3 "d")]
      (is (= "d" (:d (kt/data x))))
      (kt/set! (f/d x) "changed")
      (is (= "changed" (:d (kt/data x))))))
  (testing "documented limit: `class Shadowed(b: Int) { val b: Int = b * 2 }` looks like `class Shadowed(val b: Int)`"
    (is (= {:b 4} (kt/data (f/Shadowed 2))) "the metadata has no link between a parameter and a property")))

(deftest overridden-and-inherited-constructor-properties
  (testing "class Contact(override val email: String, val name: String) : Emailed"
    (is (= {:email "a@b" :name "n"} (kt/data (f/Contact "a@b" "n")))))
  (testing "class Dog(override val name, val tricks) : Animal(name, 4): Dog's own constructor, not Animal's `legs`"
    (is (= {:name "rex" :tricks ["sit"]} (into {} (map (fn [[k v]] [k (if (instance? java.util.List v) (vec v) v)]) (kt/data (f/Dog "rex" ["sit"])))))))
  (testing "@JvmField on a constructor property: a field"
    (is (= {:a 1 :b "x"} (kt/data (f/JvmFields 1 "x")))))
  (testing "a value class: its primary property"
    (is (= {:v 5} (kt/data (f/Uid 5))))))

(deftest errors
  (testing "no Kotlin metadata"
    (is (str/includes? (error-of "a string") "java.lang.String is not a Kotlin class"))
    (is (str/includes? (error-of {:a 1}) "not a Kotlin class"))
    (is (str/includes? (error-of (Object.)) "not a Kotlin class")))
  (testing "no property in the primary constructor"
    (is (str/includes? (error-of (f/NoProps 1)) "declares no property")))
  (testing "no primary constructor, an object"
    (is (str/includes? (error-of (f/SecondaryOnly 1)) "no primary constructor"))
    (is (str/includes? (error-of f/Single) "declares no property") "an object has a (private) primary constructor with no parameter"))
  (testing "the error is an ex-info marked as a kt error"
    (is (:kt/error (ex-data (try (kt/data "x") (catch clojure.lang.ExceptionInfo e e)))))))

(deftest accessors-are-made-once-per-class
  (let [calls (atom 0)
        orig meta/primary-properties]
    (with-redefs [meta/primary-properties (fn [c] (swap! calls inc) (orig c))]
      (dotimes [_ 5] (kt/data (f/CountMe 1 "x"))))
    (is (= 1 @calls) "CountMe is used by no other test of this class's accessors: 5 calls, 1 lookup"))
  (testing "fast on repeated calls"
    (let [p (f/Person 7 "Ann" "a@b" (f/Uid 3))
          n 200000]
      (dotimes [_ 50000] (kt/data p))
      (let [t0 (System/nanoTime)]
        (dotimes [_ n] (kt/data p))
        (let [ns (/ (double (- (System/nanoTime) t0)) n)]
          (println (format "KT/DATA %.0f ns/call (4 properties, one of them a value class)" ns))
          (is (< ns 20000) "far below a lookup of the metadata for every call"))))))
