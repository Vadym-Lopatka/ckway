(ns ckway.static-test
  "Step 5, T6: @JvmStatic members of an object, @JvmField and const (the index said :static? false)."
  (:require [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct :refer [both expansions]]
            [ckway.core :as kt]
            [ckway.meta :as meta]
            [ckway.rt :as rt]))

(kt/require '[fx :as f] '[kotlinx.coroutines :as co])

(deftest index-knows-which-members-are-static
  (let [idx (meta/package-index "fx")
        jvm (fn [var-name owner] (->> (get idx var-name) (filter #(= owner (:owner %))) first :jvm))]
    (testing "@JvmStatic function and property of an object: static JVM members"
      (is (true? (:static? (jvm ".twice" "fx.Stat"))))
      (is (true? (:static? (:getter (->> (get idx "answer") (filter #(= "fx.Stat" (:owner %))) first)))))
      (is (true? (:static? (jvm ".dflt" "fx.Stat")))))
    (testing "@JvmField and const of an object: static JVM fields"
      (is (= {:field "field" :static? true} (select-keys (jvm "field" "fx.Stat") [:field :static?])))
      (is (= {:field "C" :static? true} (select-keys (jvm "C" "fx.Stat") [:field :static?]))))
    (testing "a plain member of the same object stays an instance member"
      (is (false? (:static? (jvm ".plain" "fx.Stat")))))
    (testing "a companion member stays a member of the Companion class (its @JvmStatic twin is on the outer class)"
      (is (false? (:static? (jvm ".jtwice" "fx.RComp$Companion"))))
      (is (= "fx.RComp" (:class (jvm "JC" "fx.RComp$Companion")))))))

(deftest object-members-static-and-dynamic
  (testing "@JvmStatic function"
    (both 8 f/.twice f/Stat 4)
    (both 11 f/.dflt f/Stat)
    (both 5 f/.dflt f/Stat :y 1 :x 4)
    (both 2 f/.dflt f/Stat 1 1))
  (testing "@JvmStatic property, @JvmField, const"
    (both 42 f/answer f/Stat)
    (both 7 f/field f/Stat)
    (both 5 f/C f/Stat))
  (testing "a plain member of the object"
    (both 2 f/.plain f/Stat 1))
  (testing "companion: @JvmStatic function and property, @JvmField, const"
    (both 8 f/.jtwice f/RComp 4)
    (both 43 f/jans f/RComp)
    (both 8 f/jfield f/RComp)
    (both 9 f/JC f/RComp))
  (testing "the top-level `answer` is a different declaration of the same var"
    (both 42 f/answer)))

(deftest kotlinx-dispatchers
  (testing "(co/Default co/Dispatchers) was `No matching method getDefault`"
    (is (instance? kotlinx.coroutines.CoroutineDispatcher (co/Default co/Dispatchers)))
    (is (identical? (kotlinx.coroutines.Dispatchers/getDefault) (co/Default co/Dispatchers)))
    (is (identical? (kotlinx.coroutines.Dispatchers/getDefault) (rt/call-dyn #'co/Default [co/Dispatchers] {})))
    (is (some? (co/IO co/Dispatchers)))
    (is (some? (co/Unconfined co/Dispatchers)))))

(deftest the-receiver-of-a-static-member-is-still-evaluated
  (let [seen (atom 0)
        obj (fn [] (swap! seen inc) f/Stat)]
    (is (= 8 (f/.twice (obj) 4)))
    (is (= 1 @seen) "the receiver form ran once")
    (is (= 1 (count (:static (expansions '(f/.twice f/Stat 4))))))
    (is (empty? (:dynamic (expansions '(f/.twice f/Stat 4)))))
    (is (empty? (:warnings (expansions '(f/.twice f/Stat 4)))))))
