(ns kt.meta-test
  (:require [clojure.test :refer [deftest is testing]]
            [kt.meta :as meta]))

(def idx (meta/package-index "fx"))

(defn decl [var-name pred] (first (filter pred (get idx var-name))))

(deftest index-shape
  (testing "top-level function with defaults"
    (let [d (first (get idx "greet"))]
      (is (= {:kind :function :name "greet" :var-name "greet" :owner "fx.BasicsKt" :receivers []} (select-keys d [:kind :name :var-name :owner :receivers])))
      (is (= ["name" "greeting" "punct"] (map :name (:params d))))
      (is (= [false true true] (map :default? (:params d))))
      (is (= "kotlin/String" (-> d :return :class)))
      (is (= "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;" (-> d :jvm :desc)))
      (is (= "greet$default" (-> d :jvm :default :name)))
      (is (= "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;"
             (-> d :jvm :default :desc)))
      (is (= "fun greet(name: String, greeting: String = ..., punct: String = ...): String" (:signature d)))))
  (testing "extension function: var name has a dot, receiver is :extension"
    (let [d (first (get idx ".shout"))]
      (is (= [:extension] (map :role (:receivers d))))
      (is (= "kotlin/String" (-> d :receivers first :type :class)))
      (is (= "java.lang.String" (-> d :receivers first :jvm-type)))
      (is (:static (:flags d)))))
  (testing "member function: dispatch receiver, instance method, $default is static with the instance first"
    (let [d (decl ".greet" #(= "fx.User" (:owner %)))]
      (is (= [:dispatch] (map :role (:receivers d))))
      (is (false? (-> d :jvm :static?)))
      (is (= "(Lfx/User;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;" (-> d :jvm :default :desc)))))
  (testing "property: plain name, getter in :jvm and :getter, setter only for var"
    (let [nick (decl "nickname" #(= "fx.User" (:owner %)))
          nm (decl "name" #(= "fx.User" (:owner %)))]
      (is (= :property (:kind nick)))
      (is (= "getNickname" (-> nick :getter :name)))
      (is (= "setNickname" (-> nick :setter :name)))
      (is (nil? (:setter nm)))
      (is (= (:getter nm) (:jvm nm)))))
  (testing "class: constructors share the var, synthetic constructor has the marker"
    (let [ds (get idx "User")]
      (is (= 2 (count ds)))
      (is (every? #(= :class (:kind %)) ds))
      (is (= "kotlin.jvm.internal.DefaultConstructorMarker"
             (-> (first (filter #(= 3 (count (:params %))) ds)) :jvm :default :marker)))))
  (testing "companion member, enum entry, object, nested"
    (let [mk (first (get idx ".make"))]
      (is (= "fx.WithCompanion" (-> mk :receivers first :companion-of)))
      (is (= "fx.WithCompanion$Companion" (-> mk :jvm :class))))
    (is (= :enum-entry (-> (get idx "Color.RED") first :kind)))
    (is (= :object (-> (get idx "Registry") first :kind)))
    (is (= "fx.Outer$Inner" (-> (get idx "Outer.Inner") first :owner))))
  (testing "multi-file facade members are owned by the facade"
    (is (= "fx.MultiKt" (-> (get idx "multiA") first :owner)))
    (is (= "fx.MultiKt" (-> (get idx "multiB") first :owner)))))

(deftest types-and-flags
  (testing "unsupported kinds are described"
    (is (:suspend (:flags (first (get idx "slow")))))
    (is (:mangled (:flags (first (get idx "nextUid")))))
    (is (-> (get idx "nextUid") first :params first :type :value-class?))
    (is (-> (get idx "applyTwice") first :params first :type :fn-type :arity (= 1)))
    ;; step 7 added a second `useCb` (the Cb2 one, one parameter): select the declaration with two
    (is (->> (get idx "useCb") (filter #(= 2 (count (:params %)))) first :params first :type :fun-interface?))
    (is (= [{:name "T" :reified? true}] (:type-params (first (get idx "typeName")))))
    (is (:inline (:flags (first (get idx "typeName"))))))
  (testing "nullable and vararg"
    (is (-> (get idx "nick") first :params first :type :nullable?))
    (is (-> (get idx "nick") first :return :nullable?))
    (is (:vararg? (first (:params (first (get idx "join"))))))))

(deftest second-package
  (let [d (first (get (meta/package-index "fx.other") ".shoutName"))]
    (is (= "fx.User" (meta/internal->binary (-> d :receivers first :type :class))))))

(deftest inherited-members
  (let [other (meta/package-index "fx.other")
        owners (fn [ix n] (set (map :owner (get ix n))))]
    (testing "a class of another package inherits members of an abstract class and an interface in fx"
      (is (= #{"fx.Named"} (owners other ".hello")) "the override in Sub is not listed again")
      (is (= #{"fx.Named"} (owners other ".bye")) "a member with a body in the interface")
      (is (= #{"fx.Named"} (owners other "label")))
      (is (= #{"fx.Base"} (owners other ".shared")))
      (is (= #{"fx.Base"} (owners other "tag")))
      (is (= #{"fx.other.Sub"} (owners other ".own"))))
    (testing "the same declaration (equal data) is in both packages"
      (is (= (get other ".hello") (get idx ".hello")))
      (is (= (get other ".shared") (get idx ".shared"))))
    (testing "interface delegation: the member is found in the interface, not in Deleg"
      (is (= #{"fx.Named"} (owners idx ".hello"))))
    (testing "the call target of an inherited member is the supertype"
      (let [d (first (get other ".hello"))]
        (is (= "fx.Named" (-> d :jvm :class)))
        (is (false? (-> d :jvm :static?)))
        (is (not (contains? (:flags d) :inherited)))))
    (testing "an interface from another jar (kotlin-stdlib)"
      (is (= #{"kotlin.Lazy"} (owners idx "value"))))
    (testing "members of a pure Java supertype are not vars"
      (is (nil? (get idx ".getMessage")))
      (is (nil? (get idx ".printStackTrace")))
      (is (some? (get idx ".own"))))))

(deftest value-class-description
  (testing "the class declaration and every use of the type carry the same description"
    (let [vc (:value-class (first (get idx "Uid")))
          param-vc (-> (get idx "nextUid") first :params first :type :value-class)]
      (is (every? #(= vc (:value-class %)) (get idx "Uid")) "primary and secondary constructors")
      (is (= 3 (count (get idx "Uid"))))
      (is (= vc param-vc))
      (is (= "fx.Uid" (:class vc)))
      (is (= "v" (:property vc)))
      (is (= "long" (:jvm-underlying vc)))
      (is (= "kotlin/Long" (-> vc :underlying :class)))
      (is (= {:class "fx.Uid" :name "box-impl" :desc "(J)Lfx/Uid;" :static? true} (:box vc)))
      (is (= {:class "fx.Uid" :name "unbox-impl" :desc "()J" :static? false} (:unbox vc)))))
  (testing "other underlying types: reference, generic value class, stdlib"
    (is (= "java.lang.String" (-> (get idx "Name") first :value-class :jvm-underlying)))
    (is (= "java.lang.String" (-> (get idx "Id") first :value-class :jvm-underlying)))
    (let [vc #(-> (get idx %) first :params first :type :value-class)]
      (is (= "long" (:jvm-underlying (-> (get idx "pause") first :params first :type :value-class))))
      (is (= "kotlin.time.Duration" (:class (vc "pause"))))
      (is (= "int" (:jvm-underlying (vc "u"))))
      (is (= "kotlin.UInt" (:class (vc "u")))))
    (is (= "java.lang.Object" (-> (get idx "tryIt") first :return :value-class :jvm-underlying))))
  (testing "a type that is no value class has no description"
    (is (nil? (-> (get idx "greet") first :params first :type :value-class)))
    (is (nil? (-> (get idx "uidList") first :params first :type :value-class))))
  (testing "constructors of a value class are the static constructor-impl; a default is constructor-impl$default"
    (let [[c1 c2 c3] (sort-by (comp count :params) (get idx "Uid"))
          cnt (first (get idx "Cnt"))]
      (is (= {:name "constructor-impl" :desc "(J)J" :static? true} (select-keys (:jvm c1) [:name :desc :static?])))
      (is (= "(Ljava/lang/String;)J" (-> (first (filter #(= "s" (-> % :params first :name)) (get idx "Uid"))) :jvm :desc)))
      (is (= {:name "constructor-impl$default" :desc "(JJILkotlin/jvm/internal/DefaultConstructorMarker;)J" :static? true}
             (select-keys (-> (first (filter #(= 2 (count (:params %))) (get idx "Uid"))) :jvm :default) [:name :desc :static?])))
      (is (= "(IILkotlin/jvm/internal/DefaultConstructorMarker;)I" (-> cnt :jvm :default :desc)))))
  (testing "a constructor with a value-class parameter: the DefaultConstructorMarker of the JVM descriptor is not a parameter"
    (let [d (first (get idx "Holder"))]
      (is (= ["long"] (map :jvm-type (:params d))))
      (is (= "(JLkotlin/jvm/internal/DefaultConstructorMarker;)V" (-> d :jvm :desc)))
      (is (= "(JILkotlin/jvm/internal/DefaultConstructorMarker;)V" (-> d :jvm :default :desc)))))
  (testing "members of a value class: static name-impl(underlying, ...) with the receiver first, or the plain getter"
    (let [plus (first (filter #(= "fx.Uid" (:owner %)) (get idx ".plus")))
          describe (first (filter #(= "fx.Uid" (:owner %)) (get idx ".describe")))
          v (first (filter #(= "fx.Uid" (:owner %)) (get idx "v")))
          doubled (first (get idx "doubled"))]
      (is (true? (-> plus :jvm :static?)))
      (is (= "long" (-> plus :receivers first :jvm-type)))
      (is (= :dispatch (-> plus :receivers first :role)))
      (is (= "(JJ)J" (-> plus :jvm :desc)))
      (is (= {:name "describe-impl$default" :desc "(JLjava/lang/String;ILjava/lang/Object;)Ljava/lang/String;" :static? true}
             (select-keys (-> describe :jvm :default) [:name :desc :static?])))
      (is (true? (-> doubled :getter :static?)))
      (is (= "long" (-> doubled :receivers first :jvm-type)))
      (is (false? (-> v :getter :static?)) "the primary property is an instance getter of the box")
      (is (nil? (-> v :receivers first :jvm-type))))))
