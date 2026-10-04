(ns kt.types-test
  "Step 5, T1: type forms (DESIGN-2 rule 5) as Kotlin source text and JVM classes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kt.core :as kt]
            [kt.types :as ty]))

(kt/require '[fx :as f] '[fx.other :as o])

(import 'java.util.UUID)

(def ^:private this-ns *ns*)

(defn- src [form] (ty/source (ty/resolve-form this-ns form)))
(defn- cls [form] (ty/jvm-class (ty/resolve-form this-ns form)))
(defn- err [form] (try (ty/resolve-form this-ns form) nil (catch Exception e (ex-message e))))

(deftest resolution-order
  (testing "1. a kt class var of a required Kotlin namespace"
    (is (= "fx.Box2" (src 'f/Box2)))
    (is (= fx.Box2 (cls 'f/Box2)))
    (is (= "fx.Uid" (src 'f/Uid)))
    (is (= "fx.Outer.Inner" (src 'f/Outer.Inner)) "a nested class keeps its dots")
    (is (= "fx.Registry" (src 'f/Registry)) "an object is a type too"))
  (testing "2. a Kotlin default-import name"
    (is (= "kotlin.Int" (src 'Int)))
    (is (= "kotlin.Long" (src 'Long)) "Long is the Kotlin type, not java.lang.Long")
    (is (= "kotlin.String" (src 'String)))
    (is (= "kotlin.Any" (src 'Any)))
    (is (= "kotlin.Unit" (src 'Unit)))
    (is (= "kotlin.Nothing" (src 'Nothing)))
    (is (= "kotlin.Number" (src 'Number)))
    (is (= "kotlin.collections.List<kotlin.Int>" (src '(List Int))))
    (is (= "kotlin.collections.MutableMap<kotlin.String, kotlin.Int>" (src '(MutableMap String Int))))
    (is (= "kotlin.sequences.Sequence<kotlin.Int>" (src '(Sequence Int))))
    (is (= "kotlin.Pair<kotlin.Int, kotlin.String>" (src '(Pair Int String))))
    (is (= "kotlin.Triple<kotlin.Int, kotlin.Int, kotlin.Int>" (src '(Triple Int Int Int))))
    (is (= "kotlin.Array<kotlin.String>" (src '(Array String))))
    (is (= "kotlin.IntArray" (src 'IntArray)))
    (is (= "kotlin.collections.Map.Entry<kotlin.String, kotlin.Int>" (src '(Map.Entry String Int))))
    (is (= [Integer Long String java.util.List java.util.Map java.util.Set kotlin.Pair kotlin.Unit]
           (map cls '[Int Long String (List Int) (Map Int Int) (Set Int) (Pair Int Int) Unit])))
    (is (= [(class (int-array 0)) (class (into-array String []))] (map cls '[IntArray (Array String)]))))
  (testing "3. a class that Clojure resolves: imports, fully qualified names"
    (is (= "java.util.UUID" (src 'UUID)))
    (is (= "java.util.UUID" (src 'java.util.UUID)))
    (is (= "java.util.concurrent.atomic.AtomicInteger" (src 'java.util.concurrent.atomic.AtomicInteger)))
    (is (= "java.util.ArrayList<kotlin.String>" (src '(java.util.ArrayList String))))
    (is (= UUID (cls 'UUID)))
    (is (= "java.lang.Object" (src 'Object)) "Object is not a Kotlin default name"))
  (testing "a kt class var beats a Kotlin default name"
    (let [ns (create-ns 'kt.types-test.shadow)]
      (intern ns (with-meta 'Pair (select-keys (meta #'f/Box2) [:kt/decls :kt/class])) nil)
      (is (= "fx.Box2" (ty/source (ty/resolve-form ns 'Pair))))
      (is (= "kotlin.Pair<kotlin.Int, kotlin.Int>" (ty/source (ty/resolve-form this-ns '(Pair Int Int))))))))

(deftest nullable-and-generic-forms
  (is (= "kotlin.String?" (src 'String?)))
  (is (= "kotlin.Any?" (src 'Any?)))
  (is (= "fx.Box2?" (src 'f/Box2?)))
  (is (= "java.util.UUID?" (src 'UUID?)))
  (is (= "kotlin.collections.List<kotlin.collections.Map<kotlin.String, kotlin.Any?>>"
         (src '(List (Map String Any?)))))
  (is (= "kotlin.collections.List<kotlin.String?>?" (src '(List? String?))))
  (is (= "kotlin.collections.List<*>" (src '(List *))))
  (is (= "kotlin.collections.Map<kotlin.String, *>" (src '(Map String *))))
  (is (= "kotlin.collections.List<fx.Box2?>" (src '(List f/Box2?))))
  (is (true? (:nullable? (ty/resolve-form this-ns 'Int?))))
  (is (false? (:nullable? (ty/resolve-form this-ns 'Int))))
  (testing "the value of :<> is one form or a vector of forms"
    (is (= ["kotlin.Int"] (map ty/source (ty/resolve-forms this-ns 'Int))))
    (is (= ["kotlin.Int" "kotlin.collections.List<kotlin.String>"]
           (map ty/source (ty/resolve-forms this-ns '[Int (List String)]))))
    (is (= [] (ty/resolve-forms this-ns [])))))

(deftest value-classes-are-known
  (is (some? (:value-class (ty/resolve-form this-ns 'f/Uid))))
  (is (some? (:value-class (ty/resolve-form this-ns 'UInt))) "an stdlib value class")
  (is (nil? (:value-class (ty/resolve-form this-ns 'Int)))))

(deftest every-default-name-has-a-jvm-class
  (testing "the names come from the stdlib; a built-in that is neither mapped nor a class would be a hole"
    (is (> (count @ty/default-names) 120))
    (doseq [[n {:keys [jvm alias]}] @ty/default-names
            ;; step 8 (H2b): a type alias (ArrayList, Exception...) has no class of its own; its target must exist
            :let [jvm (or jvm (some-> (ty/jvm-class (:type alias)) .getName))]]
      (is (class? (try (Class/forName ^String jvm false (clojure.lang.RT/baseLoader)) (catch Throwable _ nil)))
          (str n " -> " jvm))))
  (testing "the names that the brief lists"
    (doseq [n '[Any Unit Nothing Int Long Short Byte Double Float Boolean Char String Number List MutableList Map
                MutableMap Set MutableSet Collection Iterable Sequence Pair Triple Array IntArray LongArray
                ShortArray ByteArray DoubleArray FloatArray BooleanArray CharArray]]
      (is (contains? @ty/default-names (str n)) (str n)))))

(deftest type-form-errors
  (testing "an unknown name"
    (let [m (err 'Foo)]
      (is (str/starts-with? m "kt: unknown type name `Foo`"))
      (is (str/includes? m "never evaluated")))
    (is (str/includes? (err 'nope/Foo) "unknown type name `nope/Foo`"))
    (is (str/includes? (err 'f/NoSuchClass) "unknown type name `f/NoSuchClass`")))
  (testing "a kt var that is not a class"
    (is (str/includes? (err 'f/greet) "not a Kotlin class")))
  (testing "the wrong number of type arguments"
    (is (str/includes? (err 'List) "has 1 type parameter, but the form gives 0 type arguments"))
    (is (str/includes? (err '(List Int String)) "has 1 type parameter, but the form gives 2 type arguments"))
    (is (str/includes? (err '(Map Int)) "has 2 type parameters, but the form gives 1 type argument"))
    (is (str/includes? (err '(Int String)) "has no type parameters, but the form gives 1 type argument"))
    (is (str/includes? (err '(f/Box2 Int)) "has no type parameters"))
    (is (str/includes? (err '(List (Map Int))) "`kotlin.collections.Map` has 2 type parameters")))
  (testing "a form that is not a literal type form"
    (doseq [form ['"String" 5 :kw '(5 Int) '(fn [] Int) '[Int] '{Int Int}]]
      (is (str/includes? (str (err form)) "type form") (pr-str form))
      (is (str/includes? (str (err form)) "never evaluated") (pr-str form)))
    (is (str/includes? (err '(identity String)) "unknown type name `identity`"))
    (is (str/includes? (err 'some-local) "unknown type name `some-local`"))
    (is (str/includes? (str (try (ty/resolve-forms this-ns '[Int [String]]) (catch Exception e (ex-message e))))
                       "vector inside a vector"))))
