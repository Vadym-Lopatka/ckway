(ns kt.set-test
  "Step 6, R8: `(kt/set! read-form value)`. Each case runs on the static path (a typed receiver) and on the
  dynamic path (`kt.set/set-dyn`, what an untyped receiver gets)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kt.call-test :as ct :refer [expansions]]
            [kt.core :as kt]
            [kt.rt :as rt]
            [kt.set :as set]))

(kt/require '[fx :as f])

(defn- dyn
  "kt/set! on the dynamic path: the kt var, the receivers, the value."
  [v recvs value] (set/set-dyn v recvs value))

(defn- msg [form] (ct/compile-error form))

(defn- dyn-msg [v recvs value]
  (try (dyn v recvs value) nil (catch Throwable t (ct/root-message t))))

;; ---------------------------------------------------------------- the basic cases

(deftest int-and-nullable-string
  (let [g (f/Gadget)]
    (testing "static: the value is returned (like set!) and written"
      (is (= 5 (kt/set! (f/level g) 5)))
      (is (= 5 (f/level g))))
    (testing "dynamic"
      (is (= 6 (dyn #'f/level [g] 6)))
      (is (= 6 (f/level g))))
    (testing "nullable String: a value, then nil"
      (is (= "x" (kt/set! (f/label g) "x")))
      (is (= "x" (f/label g)))
      (is (nil? (kt/set! (f/label g) nil)))
      (is (nil? (f/label g)))
      (dyn #'f/label [g] "y") (is (= "y" (f/label g)))
      (dyn #'f/label [g] nil) (is (nil? (f/label g))))))

(deftest number-width-like-an-argument
  (let [g (f/Gadget)]
    (testing "a Clojure Long to Int, Long, Short, Double"
      (kt/set! (f/level g) (long 7)) (is (= 7 (f/level g)))
      (kt/set! (f/big g) 5) (is (= 5 (f/big g)))
      (kt/set! (f/tiny g) 3) (is (= 3 (f/tiny g)))
      (is (instance? Short (f/tiny g)))
      (kt/set! (f/ratio g) 2) (is (= 2.0 (f/ratio g)))
      (kt/set! (f/flag g) true) (is (true? (f/flag g))))
    (testing "dynamic: the same conversion"
      (dyn #'f/level [g] (long 8)) (is (= 8 (f/level g)))
      (dyn #'f/tiny [g] 4) (is (instance? Short (f/tiny g)))
      (dyn #'f/ratio [g] 3) (is (= 3.0 (f/ratio g))))
    (testing "an integer literal that does not fit Int is a compile error (as for a call); nil, a String, a Double are errors too"
      (is (str/includes? (msg '(kt/set! (f/level (f/Gadget)) 3000000000)) "Int"))
      (is (str/includes? (msg '(kt/set! (f/level (f/Gadget)) nil)) "nil"))
      (is (str/includes? (msg '(kt/set! (f/level (f/Gadget)) "s")) "Int")))
    (testing "dynamic: nil for a non-null property, a wrong type"
      (is (str/includes? (dyn-msg #'f/level [g] nil) "nil"))
      (is (str/includes? (dyn-msg #'f/level [g] "s") "Int")))))

(deftest value-class-property
  (let [g (f/Gadget)]
    (testing "the setter has a mangled name (`setUid-xxx(long)`): the bridge unboxes the Uid"
      (is (= (f/Uid 42) (kt/set! (f/uid g) (f/Uid 42))))
      (is (= (f/Uid 42) (f/uid g)))
      (dyn #'f/uid [g] (f/Uid 43))
      (is (= (f/Uid 43) (f/uid g))))
    (testing "the underlying value is not a Uid: compile error, as for an argument"
      (is (str/includes? (msg '(kt/set! (f/uid (f/Gadget)) 5)) "fx.Uid"))
      (is (str/includes? (dyn-msg #'f/uid [g] 5) "fx.Uid")))
    (testing "Uid? over a primitive is boxed (no unboxing): object or nil"
      (kt/set! (f/maybeUid g) (f/Uid 9)) (is (= (f/Uid 9) (f/maybeUid g)))
      (kt/set! (f/maybeUid g) nil) (is (nil? (f/maybeUid g)))
      (dyn #'f/maybeUid [g] (f/Uid 10)) (is (= (f/Uid 10) (f/maybeUid g))))))

(deftest lateinit-and-jvmfield
  (let [g (f/Gadget)]
    (testing "lateinit: unset until the first set"
      (is (thrown? kotlin.UninitializedPropertyAccessException (f/late g)))
      (is (= "L" (kt/set! (f/late g) "L")))
      (is (= "L" (f/late g)))
      (dyn #'f/late [g] "M")
      (is (= "M" (f/late g))))
    (testing "@JvmField var: a public field, no setter"
      (is (nil? (:setter (first (filter #(= "fx.Gadget" (:owner %)) (:kt/decls (meta #'f/raw)))))))
      (is (= 9 (kt/set! (f/raw g) 9)))
      (is (= 9 (f/raw g)))
      (dyn #'f/raw [g] (long 10))
      (is (= 10 (f/raw g)))
      (is (= 10 (.-raw ^fx.Gadget g)) "the field itself"))))

(deftest property-of-an-interface-and-overrides
  (testing "declared in an interface, implemented (and overridden) by classes: the call is virtual"
    (let [c (f/Clock) c2 (f/Clock2)]
      (kt/set! (f/ticks c) 3) (is (= 3 (f/ticks c)))
      (kt/set! (f/ticks c2) 3) (is (= 6 (f/ticks c2)) "Clock2's own setter doubles")
      (dyn #'f/ticks [c2] 4) (is (= 8 (f/ticks c2)))))
  (testing "an open property that a subclass overrides"
    (let [child (f/Child) parent (f/Parent)]
      (kt/set! (f/width child) 9) (is (= 9 (f/width child)))
      (kt/set! (f/width parent) 2) (is (= 2 (f/width parent))))))

(deftest top-level-extension-object-companion
  (testing "top-level var"
    (is (= 3 (kt/set! (f/topVar) 3)))
    (is (= 3 (f/topVar)))
    (dyn #'f/topVar [] 4)
    (is (= 4 (f/topVar))))
  (testing "extension property of a JDK type (`var StringBuilder.firstChar`)"
    (let [sb (StringBuilder. "abc")]
      (is (= \z (kt/set! (f/firstChar sb) \z)))
      (is (= "zbc" (str sb)))
      (dyn #'f/firstChar [sb] \y)
      (is (= "ybc" (str sb)))))
  (testing "property of an object: instance, @JvmStatic, @JvmField (static field)"
    (kt/set! (f/volume f/Knob) 4) (is (= 4 (f/volume f/Knob)))
    (kt/set! (f/stat f/Knob) 5) (is (= 5 (f/stat f/Knob)))
    (kt/set! (f/fld f/Knob) 6) (is (= 6 (f/fld f/Knob)))
    (is (= 6 fx.Knob/fld) "the static field itself")
    (dyn #'f/volume [f/Knob] 40) (is (= 40 (f/volume f/Knob)))
    (dyn #'f/fld [f/Knob] 60) (is (= 60 (f/fld f/Knob))))
  (testing "property of a companion object"
    (kt/set! (f/setting f/Dial) 7) (is (= 7 (f/setting f/Dial)))
    (kt/set! (f/jsetting f/Dial) 8) (is (= 8 (f/jsetting f/Dial)))
    (dyn #'f/setting [f/Dial] 70) (is (= 70 (f/setting f/Dial)))))

(deftest function-valued-properties
  (let [g (f/Gadget)]
    (testing "a Clojure function goes where Kotlin has a function type"
      (kt/set! (f/hook g) (fn [x] (* x 10)))
      (is (= 20 (f/.callHook g 2)))
      (dyn #'f/hook [g] (fn [x] (* x 100)))
      (is (= 200 (f/.callHook g 2))))
    (testing "a fun interface, nullable"
      (kt/set! (f/pred g) (fn [x] (even? x)))
      (is (true? (f/check (f/pred g) 4)))
      (is (false? (f/check (f/pred g) 3)))
      (kt/set! (f/pred g) nil)
      (is (nil? (f/pred g))))
    (testing "a fn literal at the property gets its parameter types (the nested kt call is static)"
      (let [e (expansions '(fn [^fx.Gadget g] (kt/set! (f/hook g) (fn [n] (f/step n)))))]
        (is (= "" (:warnings e)))))))

;; ---------------------------------------------------------------- errors

(deftest compile-errors-have-the-kotlin-declaration
  (testing "val"
    (let [m (msg '(kt/set! (f/fixed (f/Gadget)) 1))]
      (is (str/includes? m "val fx.Gadget.fixed: Int"))
      (is (str/includes? m "read-only"))))
  (testing "private set"
    (let [m (msg '(kt/set! (f/secret (f/Gadget)) 1))]
      (is (str/includes? m "var fx.Gadget.secret: Int"))
      (is (str/includes? m "private"))))
  (testing "internal set"
    (let [m (msg '(kt/set! (f/inner (f/Gadget)) 1))]
      (is (str/includes? m "var fx.Gadget.inner: Int"))
      (is (str/includes? m "internal"))))
  (testing "a data class val"
    (is (str/includes? (msg '(kt/set! (f/firstName (f/Person 1 "a")) "b")) "val fx.Person.firstName")))
  (testing "the same errors at run time"
    (let [g (f/Gadget)]
      (is (str/includes? (dyn-msg #'f/fixed [g] 1) "val fx.Gadget.fixed: Int"))
      (is (str/includes? (dyn-msg #'f/secret [g] 1) "private"))
      (is (str/includes? (dyn-msg #'f/inner [g] 1) "internal"))
      (is (= 1 (.secretValue ^fx.Gadget g)) "the private property was not written")))
  (testing "the private property still reads"
    (is (= 1 (f/secret (f/Gadget))))))

(deftest not-a-property-read
  (testing "not a form, not a kt var, a function, wrong receivers, named arguments"
    (is (str/includes? (msg '(kt/set! x 1)) "must be a kt property read"))
    (is (str/includes? (msg '(kt/set! (inc 1) 2)) "not a var that kt made"))
    (is (str/includes? (msg '(kt/set! (f/greet "x") 1)) "no Kotlin property"))
    (is (str/includes? (msg '(kt/set! (f/level) 1)) "receiver"))
    (is (str/includes? (msg '(kt/set! (f/level "string") 1)) "no Kotlin declaration"))
    (is (str/includes? (msg '(kt/set! (f/level (f/Gadget) :x 1) 1)) "only its receivers"))))

(deftest several-candidates
  (testing "`level` is a property of Gadget, Settable and Registry. Typed receiver: static."
    (is (= "" (:warnings (expansions '(fn [^fx.Settable s] (kt/set! (f/level s) 3))))))
    (let [s (f/Settable)] (kt/set! (f/level s) 3) (is (= 3 (f/level s)))))
  (testing "untyped receiver: the dynamic path with a reflection warning, as for a call"
    (let [e (expansions '(fn [x] (kt/set! (f/level x) 3)))]
      (is (str/includes? (:warnings e) "Reflection warning"))
      (is (str/includes? (:warnings e) "level"))
      (is (re-find #"set-dyn" (pr-str (ct/eval-here-fn '(macroexpand-1 '(kt/set! (f/level x) 3)))))))
    (let [h (ct/eval-here-fn '(fn [x v] (kt/set! (f/level x) v)))
          g (f/Gadget) s (f/Settable)]
      (h g 11) (h s 12) (h g 13)
      (is (= 13 (f/level g)))
      (is (= 12 (f/level s)))
      (is (str/includes? (try (h "str" 1) (catch Throwable t (ct/root-message t))) "no Kotlin declaration")))))

(deftest evaluation-order-and-single-evaluation
  (let [log (atom []) g (f/Gadget)]
    (is (= 5 (kt/set! (f/level (do (swap! log conj :recv) g)) (do (swap! log conj :value) 5))))
    (is (= [:recv :value] @log) "the receiver, then the value, each once")
    (is (= 5 (f/level g)))))

;; ---------------------------------------------------------------- the static path is direct

(deftest static-path-has-no-reflection-and-no-dynamic-call
  (doseq [form ['(fn [^fx.Gadget g] (kt/set! (f/level g) 5))
                '(fn [^fx.Gadget g] (kt/set! (f/uid g) (f/Uid 1)))
                '(fn [^fx.Gadget g] (kt/set! (f/raw g) 5))
                '(fn [^fx.Gadget g] (kt/set! (f/late g) "x"))
                '(fn [] (kt/set! (f/topVar) 5))
                '(fn [^StringBuilder sb] (kt/set! (f/firstChar sb) \a))
                '(fn [] (kt/set! (f/volume f/Knob) 5))
                '(fn [] (kt/set! (f/fld f/Knob) 5))
                '(fn [] (kt/set! (f/setting f/Dial) 5))
                '(fn [^fx.Ticking t] (kt/set! (f/ticks t) 5))]]
    (let [e (expansions form)]
      (is (= "" (:warnings e)) (pr-str form))
      (is (empty? (:dynamic e)) (pr-str form))
      (is (pos? (count (:static e))) (pr-str form))))
  (testing "running the compiled forms calls neither call-dyn nor set-dyn"
    (let [calls (atom 0)
          g (f/Gadget)
          h (ct/eval-here-fn '(fn [^fx.Gadget g] (kt/set! (f/level g) 5) (kt/set! (f/uid g) (f/Uid 3)) (kt/set! (f/raw g) 1)))]
      (with-redefs [rt/call-dyn (fn [& _] (swap! calls inc) nil)
                    set/set-dyn (fn [& _] (swap! calls inc) nil)]
        (h g))
      (is (zero? @calls))
      (is (= [5 (f/Uid 3) 1] [(f/level g) (f/uid g) (f/raw g)])))))

(deftest macroexpansion-for-the-report
  (println "EXPANSION (kt/set! (f/level x) 5) with ^fx.Gadget x =>"
           (pr-str (first (:static (expansions '(fn [^fx.Gadget x] (kt/set! (f/level x) 5)))))))
  (println "EXPANSION (kt/set! (f/uid x) u) =>"
           (pr-str (first (:static (expansions '(fn [^fx.Gadget x u] (kt/set! (f/uid x) ^fx.Uid u)))))))
  (println "EXPANSION (kt/set! (f/raw x) 5) =>"
           (pr-str (first (:static (expansions '(fn [^fx.Gadget x] (kt/set! (f/raw x) 5)))))))
  (is true))

;; ---------------------------------------------------------------- the cache of the dynamic path

(deftest dynamic-set-is-cached-and-reselects
  (let [cache (:kt/cache (meta #'f/level))
        g (f/Gadget) s (f/Settable)]
    (.clear ^java.util.concurrent.ConcurrentHashMap cache)
    (dyn #'f/level [g] 1) (dyn #'f/level [g] 2)
    (is (= 1 (.size ^java.util.concurrent.ConcurrentHashMap cache)) "one entry for the same shape")
    (dyn #'f/level [s] 3)
    (is (= 2 (.size ^java.util.concurrent.ConcurrentHashMap cache)) "another receiver class, another entry")
    (is (= [2 3] [(f/level g) (f/level s)]))))
