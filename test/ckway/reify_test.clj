(ns ckway.reify-test
  "Step 7: `kt/reify` (DESIGN-2 rule 10), rules R1 to R10 of the brief. Each rule is tested from the Kotlin side
  (a Kotlin function in `fx` calls the Clojure object) and from the Clojure side (`kt` calls on the object)."
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.call-test :as ct :refer [compile-error root-message expansions]]
            [ckway.core :as kt]
            [ckway.resolve :as r]
            [ckway.rt])
  (:import [java.io StringWriter]
           [java.util.concurrent CancellationException]))

(kt/require '[fx :as f] '[fx.legacy :as l] '[kotlinx.coroutines :as co])

(def ^:dynamic *v* :root)

(defn- thrown [f] (try (f) nil (catch Throwable t t)))
(defn- await-true [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 5) (recur))))))
(defn- hash-code-of [o] (.hashCode ^Object o))
(defn- methods-of [o] (map #(.getName ^java.lang.reflect.Method %) (.getMethods (class o))))

;; ---------------------------------------------------------------- R1 interfaces

(deftest r1-interfaces
  (testing "a kt class var, from Kotlin and from Clojure"
    (let [log (atom [])
          j (kt/reify f/Job2 (.run [this] (swap! log conj :ran)))]
      (is (instance? fx.Job2 j))
      (is (= "job" (f/runJob j)) "Kotlin calls run and reads the default name")
      (is (= [:ran] @log))
      (is (nil? (f/.run j)) "kt calls the suspend member on the object")
      (is (= [:ran :ran] @log))))
  (testing "a fully qualified Kotlin interface (a class that Clojure resolves)"
    (let [j (kt/reify fx.Job2 (.run [this] nil) (name [this] "fq"))]
      (is (= "fq" (f/runJob j)))))
  (testing "a Java interface"
    (let [n (atom 0)
          r (kt/reify java.lang.Runnable (run [this] (swap! n inc)))]
      (is (instance? Runnable r))
      (.run ^Runnable r)
      (.run (Thread. ^Runnable r))
      (Thread/sleep 50)
      (is (<= 2 @n))))
  (testing "Kotlin and Java interfaces together"
    (let [log (atom [])
          o (kt/reify f/Job2
              (.run [this] (swap! log conj :job))
              java.lang.Runnable
              (run [this] (swap! log conj :runnable)))]
      (is (and (instance? fx.Job2 o) (instance? Runnable o)))
      (f/runJob o)
      (.run ^Runnable o)
      (is (= [:job :runnable] @log))))
  (testing "a Kotlin interface that extends a Java interface: each member by the rule of the interface that declares it"
    (let [log (atom [])
          o (kt/reify f/Both (run [this] (swap! log conj :run)) (.extra [this] "x"))]
      (is (= "x" (f/useBoth o)))
      (is (= [:run] @log))))
  (testing "a class is not an interface"
    (let [e (compile-error '(kt/reify f/AbstractThing (.go [this] "x")))]
      (is (str/includes? e "kt/reify takes interfaces only") e)
      (is (str/includes? e "abstract class") e)
      (println "R1 abstract class ->" e))
    (is (str/includes? (compile-error '(kt/reify java.util.ArrayList (size [this] 1))) "kt/reify takes interfaces only"))
    (is (str/includes? (compile-error '(kt/reify f/Person (.x [this] 1))) "kt/reify takes interfaces only") "an open or final class too"))
  (testing "a name that is no interface"
    (is (str/includes? (compile-error '(kt/reify no.such.Thing (x [this] 1))) "cannot resolve `no.such.Thing`"))
    (is (str/includes? (compile-error '(kt/reify)) "needs at least one interface"))
    (is (str/includes? (compile-error '(kt/reify (run [this] 1))) "must follow an interface"))))

;; ---------------------------------------------------------------- R2 names

(deftest r2-names
  (testing "a function has the `.` prefix, a property its plain name; Kotlin and Clojure side"
    (let [o (kt/reify f/Job2
              (.run [this] nil)
              (allowParallelRun [this] true)
              (name [this] "mine"))]
      (is (= "mine/true" (f/jobFlags o)) "Kotlin reads the properties")
      (is (= "mine" (f/name o)) "kt reads the property")
      (is (true? (f/allowParallelRun o)))))
  (testing "a var property: arity [this] is the getter, [this value] the setter"
    (let [lvl (atom 0)
          o (kt/reify f/Holder2
              (level [this] @lvl)
              (level [this v] (reset! lvl v))
              (uid [this] (f/Uid 3))
              (.next [this u] u)
              (.label [this n] n))]
      (is (= "5/3/a/null" (f/useHolder o)) "Kotlin sets level to 5 and reads it back")
      (is (= 5 @lvl))
      (is (= 5 (f/level o)))
      (is (= 9 (kt/set! (f/level o) 9)) "the kt/set! form of the setter")
      (is (= 9 @lvl))))
  (testing "a member of a Java interface: its Java method name"
    (is (str/includes? (compile-error '(kt/reify java.lang.Runnable (.run [this] 1))) "`.run` is not a member"))
    (is (str/includes? (compile-error '(kt/reify f/Job2 (run [this] 1))) "`run` is not a member")))
  (testing "the members of a super-interface can be written; two levels"
    (let [o (kt/reify f/Sub (.base [this] "b") (.more [this] "m"))]
      (is (= "b+m" (f/useSub o)))
      (is (= "b" (f/.base o)))
      (is (= "m" (f/.more o)))))
  (testing "toString, hashCode and equals can be written, as in reify"
    (let [o (kt/reify f/Sub (.base [this] "b") (.more [this] "m") (toString [this] "sub!") (hashCode [this] 42))]
      (is (= "sub!" (str o)))
      (is (= 42 (hash-code-of o)))))
  (testing "an unknown name: the declared members with their Kotlin signatures"
    (let [e (compile-error '(kt/reify f/Holder2 (.nope [this] 1)))]
      (is (str/includes? e "`.nope` is not a member") e)
      (is (str/includes? e "(.next [this u])  fun fx.Holder2.next(u: fx.Uid): fx.Uid") e)
      (is (str/includes? e "(level [this])  var fx.Holder2.level: Int") e)
      (is (str/includes? e "(level [this value])  var fx.Holder2.level: Int") e)
      (is (str/includes? e "(uid [this])  val fx.Holder2.uid: fx.Uid") e)
      (println "R2 unknown name ->" e))
    (let [e (compile-error '(kt/reify f/Sub (.nope [this] 1)))]
      (is (str/includes? e "fun fx.Sup.base(): String") e))
    (let [e (compile-error '(kt/reify f/Both java.lang.Runnable (.nope [this] 1)))]
      (is (str/includes? e "void run()") e)))
  (testing "the same member twice"
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base [this] 1) (.base [this] 2) (.more [this] 3))) "written twice"))))

;; ---------------------------------------------------------------- R3 parameters

(deftest r3-parameters
  (testing "value-class parameters arrive as the boxed object; the result is converted back (a mangled JVM member)"
    (let [seen (atom nil)
          o (kt/reify f/Holder2
              (level [this] 0)
              (level [this v] nil)
              (uid [this] (f/Uid 40))
              (.next [this u] (reset! seen (class u)) (f/.plus u 2))
              (.label [this n] (some-> n f/.shout f/Name)))]
      (is (= "0/42/A/null" (f/useHolder o)) "uid 40, next adds 2, label upper-cases a Name?")
      (is (= fx.Uid @seen))
      (is (= 42 (f/v (f/.next o (f/Uid 40)))) "from Clojure: the call gives the boxed Uid")))
  (testing "a nullable value class (Name?) arrives as nil"
    (let [o (kt/reify f/Holder2 (level [this] 0) (uid [this] (f/Uid 1)) (.next [this u] u) (.label [this n] n))]
      (is (nil? (f/.label o nil)))
      (is (= (f/Name "z") (f/.label o (f/Name "z"))))))
  (testing "function-typed parameters arrive callable as Clojure functions; a Clojure fn result is adapted"
    (let [seen (atom nil)
          o (kt/reify f/Cb2
              (.onEvent [this g] (reset! seen g) (* 2 (g 5)))
              (.handler [this] (fn [x] (* x 3))))]
      (is (= 42 (f/useCb o)) "2 * (5 + 1) + 10 * 3")
      (is (fn? @seen))
      (is (= 12 (f/.onEvent o inc)) "from Clojure")
      (is (= 30 ((f/.handler o) 10)) "the returned Kotlin function is a Clojure function")))
  (testing "the receiver of a member extension comes first after `this`"
    (let [o (kt/reify f/WithExt (.emit [this sb s] (.append sb (str s "!"))))]
      (is (= "a!b!" (f/useExt o)))
      (let [sb (StringBuilder.)]
        (f/.emit o sb "x")
        (is (= "x!" (str sb))))))
  (testing "a suspend member has no continuation parameter"
    (let [o (kt/reify f/Job2 (.run [this] nil))]
      (is (= "job" (f/runJob o)))))
  (testing "the parameter count must match (`this` counts)"
    (let [e (compile-error '(kt/reify f/Holder2 (.next [this] 1)))]
      (is (str/includes? e "`.next` takes a different number of parameters than 1") e)
      (is (str/includes? e "(.next [this u])") e)
      (println "R3 count ->" e))
    (is (str/includes? (compile-error '(kt/reify f/WithExt (.emit [this s] 1))) "different number of parameters than 2")
        "the receiver of a member extension is a parameter")
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base [] 1))) "needs `this`"))
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base [this & more] 1))) "plain symbols"))
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base [this {:keys [a]}] 1))) "plain symbols")))
  (testing "parameter names are free"
    (let [o (kt/reify f/Cb2 (.onEvent [me whatever] (whatever 1)) (.handler [me] identity))]
      (is (= 2 (f/.onEvent o inc))))))

(deftest r3-static-types-in-the-body
  (testing "the parameters carry the Kotlin types: kt calls on them are static (no call-dyn, no reflection warning)"
    (let [e (expansions '(kt/reify f/Holder2
                           (level [this] 0)
                           (level [this v] nil)
                           (uid [this] (f/Uid 1))
                           (.next [this u] (f/.plus u 1))
                           (.label [this n] (when n (f/Name (f/.shout n))))))]
      (is (= "" (:warnings e)) (:warnings e))
      (is (empty? (:dynamic e)))
      (is (some #(str/includes? (pr-str %) "plus") (:static e)) "u is an fx.Uid: .plus is a static call")
      (is (not (str/includes? (pr-str (:static e)) "call-dyn")))))
  (testing "a user hint wins"
    (let [e (expansions '(kt/reify f/Sub (.base [this] "b") (.more [this] "m")))]
      (is (= "" (:warnings e))))
    (let [e (expansions '(kt/reify f/Cb2 (.onEvent [this g] 1) (.handler [this] identity)))]
      (is (= "" (:warnings e)))))
  (testing "Java interface parameters are typed by their Java class"
    (let [w (StringWriter.)]
      (binding [*warn-on-reflection* true *err* w]
        (ct/eval-here-fn '(kt/reify java.util.Comparator (compare [this a b] (compare (.length ^String a) (.length ^String b))))))
      (is (= "" (str w)))))
  (testing "`this` is typed: a kt call on `this` is static"
    (let [e (expansions '(kt/reify f/Shape2
                           (.area [this] 2.0)
                           (.scale [this k] this)
                           (.describe [this p] (str p (f/.area this)))))]
      (is (= "" (:warnings e)))
      (is (empty? (:dynamic e)))
      (is (some #(str/includes? (pr-str %) "area") (:static e))))))

;; ---------------------------------------------------------------- R4 overloads

(deftest r4-overloads
  (testing "same name and count: told apart by hints, as in reify"
    (let [o (kt/reify f/Over
              (.put [this ^Integer x] (str "i" x))
              (.put [this ^String x] (str "s" x)))]
      (is (= "i1,ss" (f/useOver o)))
      (is (= "i7" (f/.put o (int 7))))
      (is (= "sq" (f/.put o "q")))))
  (testing "primitive hints and either order"
    (let [o (kt/reify f/Over (.put [this ^String x] (str "s" x)) (.put [this ^int x] (str "i" x)))]
      (is (= "i1,ss" (f/useOver o)))))
  (testing "no hint: ambiguous, the candidates are listed"
    (let [e (compile-error '(kt/reify f/Over (.put [this x] 1)))]
      (is (str/includes? e "`.put` with 2 parameters is ambiguous") e)
      (is (str/includes? e "fun fx.Over.put(x: Int): String") e)
      (is (str/includes? e "fun fx.Over.put(x: String): String") e)
      (println "R4 ambiguous ->" e)))
  (testing "a hint that fits none of them"
    (let [e (compile-error '(kt/reify f/Over (.put [this ^Double x] 1)))]
      (is (str/includes? e "is ambiguous") e)
      (is (str/includes? e "fit none of them") e)))
  (testing "the same count in an interface with two overloads: two install overloads"
    (is (str/includes? (compile-error '(kt/reify java.util.Comparator (compare [this a] 1))) "different number"))))

;; ---------------------------------------------------------------- R5 return values

(deftest r5-return-values
  (let [log (atom [])
        o (kt/reify f/Kinds
            (.num [this a b c d e] (str "n:" a "," b "," c "," d "," e "|" (mapv #(.getSimpleName (class %)) [a b c d e])))
            (.unit [this] (swap! log conj :unit) 42)
            (.count [this] 7)
            (.maybe [this] nil)
            (.text [this] "t")
            (.slow [this n] (co/delay 5) (inc n))
            (.nothing [this] 99)
            (.uid [this u] (f/.plus u 1)))]
    (testing "Kotlin gets the declared types: a Long is an Int, nil is a null Int?, Unit ignores the value"
      (let [s (f/useKinds o)]
        (is (str/starts-with? s "n:1,2,3.5,true,c|[\"Integer\" \"Long\" \"Double\" \"Boolean\" \"Character\"]|kotlin.Unit|7|null|t") s)
        (is (= [:unit] @log))))
    (testing "suspend members: results are converted too (Int, Unit, a value class object)"
      (is (= "5/2" (f/useKindsSuspend o))))
    (testing "from Clojure"
      (is (= 7 (f/.count o)))
      (is (instance? Integer (f/.count o)))
      (is (nil? (f/.maybe o)))
      (is (nil? (f/.unit o))))
    (testing "a Clojure function returned for a function type is adapted"
      (is (= 30 ((f/.handler (kt/reify f/Cb2 (.onEvent [this g] 0) (.handler [this] #(* 3 %)))) 10))))
    (testing "nil for a non-null Int: a clear kt error (it passes through Kotlin unchanged)"
      (let [bad (kt/reify f/Kinds (.num [this a b c d e] "x") (.unit [this] nil) (.count [this] nil) (.maybe [this] 1) (.text [this] "t"))
            e (thrown #(f/useKinds bad))]
        (is (str/includes? (root-message e) "kt: nil where Kotlin expects a non-null Int") (str e))))
    (testing "a result of the wrong kind"
      (let [bad (kt/reify f/Kinds (.num [this a b c d e] "x") (.unit [this] nil) (.count [this] "seven") (.maybe [this] 1) (.text [this] "t"))]
        (is (str/includes? (root-message (thrown #(f/useKinds bad))) "expected an integer"))))
    (testing "a Long that is a Kotlin Int? returned as an Integer"
      (let [o2 (kt/reify f/Kinds (.num [this a b c d e] "x") (.unit [this] nil) (.count [this] 1) (.maybe [this] 5) (.text [this] "t"))]
        (is (str/includes? (f/useKinds o2) "|5|t"))))))

;; ---------------------------------------------------------------- R6 suspend

(deftest r6-suspend-members
  (testing "the body runs on a virtual thread; a suspend call in it waits; Kotlin sees a coroutine that suspended"
    (let [t (atom nil)
          o (kt/reify f/Job2 (.run [this] (reset! t (.isVirtual (Thread/currentThread))) (co/delay 20) (swap! t vector :after)))
          t0 (System/nanoTime)]
      (is (= "job" (f/runJob o)))
      (is (= [true :after] @t))
      (is (>= (/ (- (System/nanoTime) t0) 1e6) 20))))
  (testing "the coroutine context reaches the body: a ThreadContextElement of the caller is applied on the body's thread"
    (let [seen (atom nil)
          o (kt/reify f/Job2 (.run [this] (reset! seen [(f/currentTag) (f/ctxTag)])))]
      (is (= "job" (f/runJobTagged "ctx1" o)))
      (is (= ["ctx1" "ctx1"] @seen))
      (is (nil? (f/currentTag)))))
  (testing "an exception in the body is thrown to the Kotlin caller as it is"
    (let [o (kt/reify f/Job2 (.run [this] (co/delay 1) (throw (java.io.IOException. "io!"))))
          e (thrown #(f/runJob o))]
      (is (instance? java.io.IOException e))
      (is (= "io!" (ex-message e)))))
  (testing "cancelling the Job of the caller stops the body (interrupt), finally runs, CancellationException"
    (let [fin (promise) seen (atom nil)
          o (kt/reify f/Job2 (.run [this] (try (co/delay 10000)
                                               (catch Throwable e (reset! seen e) (throw e))
                                               (finally (deliver fin :fin)))))
          sc (f/defaultScope)
          job (f/launchJob sc o)]
      (Thread/sleep 60)
      (co/.cancel job)
      (is (= :fin (deref fin 3000 :timeout)))
      (is (await-true #(.isCancelled ^kotlinx.coroutines.Job job) 3000))
      (is (or (instance? CancellationException @seen) (instance? InterruptedException @seen)) (str @seen))
      (f/cancelScope sc)))
  (testing "the binding frame is the one captured when the object was made"
    (let [seen (atom nil)
          o (binding [*v* :at-creation] (kt/reify f/Job2 (.run [this] (co/delay 1) (reset! seen *v*))))]
      (binding [*v* :at-call] (f/runJob o))
      (is (= :at-creation @seen)))
    (let [seen (atom nil)
          o (kt/reify f/Job2 (.run [this] (reset! seen *v*)))]
      (binding [*v* :at-call] (f/runJob o))
      (is (= :root @seen))))
  (testing "a suspend member with parameters and a value-class parameter, from Kotlin"
    (let [o (kt/reify f/Kinds (.num [this a b c d e] "") (.unit [this] nil) (.count [this] 1) (.maybe [this] nil) (.text [this] "")
              (.slow [this n] (co/delay 1) (* n 10)) (.nothing [this] nil) (.uid [this u] (f/Uid (+ 5 (f/v u)))))]
      (is (str/starts-with? (f/useKindsSuspend o) "40/")))))

;; ---------------------------------------------------------------- R7 members not written

(deftest r7-members-not-written
  (testing "interface default bodies (JVM default methods) and default property getters stay"
    (let [o (kt/reify f/Job2 (.run [this] nil))]
      (is (= "job/false" (f/jobFlags o)))
      (is (= "job" (f/name o)))
      (is (false? (f/allowParallelRun o)))))
  (testing "overriding a default property getter works"
    (let [o (kt/reify f/Job2 (.run [this] nil) (allowParallelRun [this] true))]
      (is (= "job/true" (f/jobFlags o)))))
  (testing "a default body that calls an abstract member"
    (let [o (kt/reify f/Shape2 (.area [this] 4.0) (.scale [this k] this))]
      (is (= "shape:4.0" (f/.describe o)))
      (is (= "p:4.0" (f/.describe o "p")))
      (is (= "q:4.0" (f/.describe o :prefix "q")))))
  (testing "the older style, a default body in I$DefaultImpls (compiled with -jvm-default=disable)"
    (let [o (kt/reify fx.legacy.LegacyGreeter (.who [this] "bob"))]
      (is (= "hi bob/legacy-tag" (l/greetLegacy o)) "greet() and getTag() come from the DefaultImpls")
      (is (= "hi bob" (l/.greet o)))
      (is (= "legacy-tag" (l/tag o)))
      (is (some #{"greet"} (methods-of o)) "the class has the method, it calls DefaultImpls"))
    (let [o (kt/reify fx.legacy.LegacyGreeter (.who [this] "bob") (tag [this] "mine") (.greet [this] "hello"))]
      (is (= "hello/mine" (l/greetLegacy o)) "and it can be overridden")))
  (testing "an abstract member that is not written: AbstractMethodError that names the member (a choice: run time, as reify)"
    (let [o (kt/reify f/Shape2 (.scale [this k] this))
          e (thrown #(f/.area o))]
      (is (instance? AbstractMethodError e))
      (is (str/includes? (ex-message e) "`fun fx.Shape2.area(): Double`") (ex-message e))
      (is (str/includes? (ex-message e) "does not write it"))
      (println "R7 missing abstract member ->" (ex-message e))
      (testing "from Kotlin, the same error"
        (is (instance? AbstractMethodError (thrown #(f/useShape o)))))
      (testing "members that were written work"
        (is (identical? o (f/.scale o 3)))))
    (let [e (thrown #(f/.run (kt/reify f/Job2 (name [this] "x"))))]
      (is (instance? AbstractMethodError e))
      (is (str/includes? (ex-message e) "suspend fun fx.Job2.run(): Unit") (ex-message e))))
  (testing "how Kotlin calls a default argument of an interface method: the call site applies it (scale$default), so the body sees 2"
    (let [ks (atom [])
          o (kt/reify f/Shape2 (.area [this] 4.0) (.scale [this k] (swap! ks conj k) this))]
      (is (= "shape:4.0|4.0|4.0|p:4.0" (f/useShape o)) "s.scale() and s.scale(3)")
      (is (= [2 3] @ks) "Kotlin passed the default 2 and the explicit 3")
      (reset! ks [])
      (f/.scale o)
      (f/.scale o 5)
      (f/.scale o :k 6)
      (is (= [2 5 6] @ks) "the Clojure side: the kt call applies the same default (scale$default); the member in the body is never without k"))))

;; ---------------------------------------------------------------- R8 mangled names

(deftest r8-mangled-names
  (let [o (kt/reify f/Holder2 (level [this] 1) (level [this v] nil) (uid [this] (f/Uid 3)) (.next [this u] u) (.label [this n] n))]
    (testing "the class has the mangled JVM methods, with the underlying types"
      (let [ms (methods-of o)]
        (is (some #(str/starts-with? % "next-") ms))
        (is (some #(str/starts-with? % "getUid-") ms))
        (is (some #(str/starts-with? % "label-") ms))))
    (testing "Kotlin calls them"
      (is (= "1/3/a/null" (f/useHolder o))))
    (testing "the bytes come from ckway.bridge (kind R), with the other bridges"
      (is (str/starts-with? (.getName (class o)) "ckway.bridge.R_Holder2__")))))

;; ---------------------------------------------------------------- R9 annotations

(deftest r9-annotations
  (testing "a marker annotation on a member name reaches the JVM method"
    (let [o (kt/reify f/Job2 (^{fx.Marker true} .run [this] nil))]
      (is (= ["fx.Marker"] (f/annotationsOf o "run")))
      (is (= [] (f/annotationsOf o "getName")))))
  (testing "elements: a map gives named elements (a number is coerced to the element type)"
    (let [o (kt/reify f/Job2 (^{fx.Route {:path "/x" :code 201}} .run [this] nil))]
      (is (= "/x:201" (f/routeOf o "run")))
      (is (= ["fx.Route"] (f/annotationsOf o "run"))))
    (let [o (kt/reify f/Job2 (^{fx.Route {:path "/d"}} .run [this] nil))]
      (is (= "/d:200" (f/routeOf o "run")) "the default of the element stays")))
  (testing "a value that is no map is the `value` element"
    (let [o (kt/reify f/Job2 (^{fx.Tagged2 "hello"} .run [this] nil))]
      (is (= "hello" (f/tagOf o "run")))))
  (testing "several annotations; on a property and on a Java member"
    (let [o (kt/reify f/Job2 java.lang.Runnable
              (^{fx.Marker true fx.Tagged2 "a"} .run [this] nil)
              (^{fx.Marker true} allowParallelRun [this] true)
              (^{fx.Tagged2 "r"} run [this] nil))]
      (is (= #{"fx.Marker" "fx.Tagged2"} (set (f/annotationsOf o "run"))) "the Kotlin run and the Java run are two JVM methods with the same name")
      (is (= ["fx.Marker"] (f/annotationsOf o "getAllowParallelRun")))))
  (testing "errors"
    (is (str/includes? (compile-error '(kt/reify f/Job2 (^{fx.Route "x"} .run [this] nil))) "has no element `value`"))
    (is (str/includes? (compile-error '(kt/reify f/Job2 (^{fx.Route {:path 1}} .run [this] nil))) "takes String"))
    (is (str/includes? (compile-error '(kt/reify f/Job2 (^{fx.Tagged2 {:nope 1}} .run [this] nil))) "has no element `nope`")))
  (testing "metadata on the kt/reify form is ignored (the object has no name to put it on)"
    (let [o ^{:a 1} (kt/reify f/Job2 (.run [this] nil))]
      (is (nil? (meta o))))))

;; ---------------------------------------------------------------- R10 the object

(deftest r10-the-object
  (testing "with-meta and meta, like reify; the object is not the same after with-meta, but it works"
    (let [o (kt/reify f/Job2 (.run [this] nil) (name [this] "n"))
          o2 (with-meta o {:a 1})]
      (is (nil? (meta o)))
      (is (= {:a 1} (meta o2)))
      (is (not (identical? o o2)))
      (is (= "n" (f/name o2)))
      (is (instance? clojure.lang.IObj o))
      (is (= (class o) (class o2)))))
  (testing "instance? of each interface"
    (let [o (kt/reify f/Sub (.base [this] "") (.more [this] "") java.lang.Runnable (run [this] nil))]
      (is (and (instance? fx.Sub o) (instance? fx.Sup o) (instance? Runnable o)))))
  (testing "it is usable as a receiver, statically typed: no call-dyn, no reflection warning"
    (let [e (expansions '(fn [] (let [j (kt/reify f/Job2 (.run [this] nil))] [(f/name j) (f/allowParallelRun j) (f/.run j)])))]
      (is (= "" (:warnings e)) (:warnings e))
      (is (empty? (:dynamic e)))
      (is (seq (:static e)))))
  (testing "the form itself as a receiver: its static type is known (a subtype of every interface)"
    (let [e (expansions '(fn [] [(f/.area (kt/reify f/Shape2 (.area [this] 1.0) (.scale [this k] this) java.lang.Runnable (run [this] nil)))
                                 (f/.area (kt/reify f/Shape2 (.area [this] 2.0)))]))]
      (is (= "" (:warnings e)) (:warnings e))
      (is (empty? (:dynamic e)))))
  (testing "a reify form as an argument where Kotlin expects the interface"
    (is (= "job" (f/runJob (kt/reify f/Job2 (.run [this] nil))))))
  (testing "the object is a Clojure value that can be stored and used from many threads"
    (let [n (atom 0)
          o (kt/reify f/Job2 (.run [this] (swap! n inc)))
          fs (mapv (fn [_] (future (f/runJob o))) (range 50))]
      (run! deref fs)
      (is (= 50 @n)))))

(defn- expand-in-ns [form] (binding [*ns* (the-ns 'ckway.reify-test)] (macroexpand-1 form)))

(deftest macroexpansion-examples
  (testing "print the expansions used in the report"
    (let [form '(kt/reify f/Job2
                  (.run [this] (co/delay 10) (println "purge"))
                  (allowParallelRun [this] true))]
      (println "EXPANSION Job2:" (pr-str (expand-in-ns form)))
      (is (= 'new (first (expand-in-ns form)))))
    (let [form '(kt/reify f/Holder2
                  (level [this] 1)
                  (level [this v] nil)
                  (uid [this] (f/Uid 3))
                  (.next [this u] (f/.plus u 1))
                  (.label [this n] n))]
      (println "EXPANSION Holder2:" (pr-str (expand-in-ns form)))
      (is (= 'new (first (expand-in-ns form)))))))


(deftest closures-and-self-calls
  (testing "bodies close over locals; `this` is usable for kt calls on the object itself"
    (let [base 10
          calls (atom [])
          o (kt/reify f/Shape2
              (.area [this] (swap! calls conj :area) (* 1.0 base))
              (.scale [this k] (kt/reify f/Shape2 (.area [_] (* k (f/.area this))) (.scale [_ k2] this)))
              (.describe [this prefix] (str prefix "=" (f/.area this) "/" (f/.area (f/.scale this 3)))))]
      (is (= "x=10.0/30.0" (f/.describe o "x")) "self-calls through this, a nested reify that closes over this and k")
      (is (= [:area :area] @calls))
      (is (= "d=10.0/30.0" (f/useShape2 o)) "Kotlin calls describe on the object")))
  (testing "a loop of objects made in a loop each close over their own local"
    (let [os (mapv (fn [i] (kt/reify f/Sub (.base [this] (str "b" i)) (.more [this] (str "m" i)))) (range 3))]
      (is (= ["b0+m0" "b1+m1" "b2+m2"] (mapv f/useSub os))))))

(deftest java-interfaces
  (testing "return values of a Java interface are converted to the JVM type (a Long for an int, a Boolean)"
    (let [s (kt/reify java.util.function.IntSupplier (getAsInt [this] 5))
          p (kt/reify java.util.function.Predicate (test [this x] (= "a" x)))
          c (kt/reify java.util.Comparator (compare [this a b] (compare (count a) (count b))))
          call (kt/reify java.util.concurrent.Callable (call [this] :called))]
      (is (= 5 (.getAsInt ^java.util.function.IntSupplier s)))
      (is (true? (.test ^java.util.function.Predicate p "a")))
      (is (= ["a" "bb" "ccc"] (sort c ["ccc" "a" "bb"])))
      (is (= :called (.call ^java.util.concurrent.Callable call)))))
  (testing "an abstract member of a Java interface that is not written: AbstractMethodError, as in reify"
    (let [c (kt/reify java.util.Comparator (reversed [this] nil))
          e (thrown #(.compare ^java.util.Comparator c 1 2))]
      (is (instance? AbstractMethodError e))
      (is (str/includes? (ex-message e) "compare"))))
  (testing "a Java default method stays"
    (let [c (kt/reify java.util.Comparator (compare [this a b] (compare a b)))]
      (is (= [3 2 1] (sort (.reversed ^java.util.Comparator c) [1 3 2]))))))


(deftest generics-and-malformed-forms
  (testing "a generic interface: the member keeps its T (an Object on the JVM)"
    (let [done (atom 0)
          v (kt/reify f/Visitor (.visit [this x] (str x "!")) (.done [this] (swap! done inc)))]
      (is (= "a!" (f/useVisitor v "a")))
      (is (= 1 @done))
      (is (= "b!" (f/.visit v "b")))))
  (testing "DESIGN PROBLEM: T is never substituted, so a Long is not turned into an Int. For a Visitor<Int> the JVM method is visit(Object): Object"
    (let [v (kt/reify f/Visitor (.visit [this x] (inc x)) (.done [this] nil))]
      (is (= 3 (f/visitInt v)) "Kotlin unboxes through Number: works")
      (is (false? (f/visitIsTwo v)) "but Kotlin's `r == 2` is Integer.equals(Long): false. The body returned a Long"))
    (let [v (kt/reify f/Visitor (.visit [this x] (int (inc x))) (.done [this] nil))]
      (is (true? (f/visitIsTwo v)) "an explicit (int ...) in the body is the workaround")))
  (testing "malformed forms"
    (is (str/includes? (compile-error '(kt/reify f/Sub 42)) "expected an interface or a member"))
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base))) "a member is (name [this ...] body...)"))
    (is (str/includes? (compile-error '(kt/reify f/Sub (.base this 1))) "a member is (name [this ...] body...)")))
  (testing "an error in a reify form that is an argument of a kt call is reported by the reify, not hidden by the typing of the call"
    (is (str/includes? (compile-error '(f/runJob (kt/reify f/Job2 (.nope [this] 1)))) "`.nope` is not a member"))))

(deftest a-kotlin-property-and-a-java-method-with-the-same-name
  (testing "the interface that a member is written under selects it: both can be implemented in one form"
    (let [log (atom [])
          o (kt/reify f/Runs (run [this] true) java.lang.Runnable (run [this] (swap! log conj :ran)))]
      (is (true? (f/run o)))
      (.run ^Runnable o)
      (is (= [:ran] @log))))
  (testing "DESIGN PROBLEM: written under a sub-interface of both, the name is ambiguous and a hint cannot tell them apart"
    (let [e (compile-error '(kt/reify f/RunsToo (run [this] true)))]
      (is (str/includes? e "`run` with 1 parameter is ambiguous") e)
      (is (str/includes? e "val fx.Runs.run: Boolean") e)
      (is (str/includes? e "void run()") e)
      (println "RunsToo ->" e))))

(deftest the-dynamic-path-calls-the-object-too
  (let [o (kt/reify f/Shape2 (.area [this] 4.0) (.scale [this k] this))]
    (is (= 4.0 (ckway.rt/call-dyn #'f/.area [o] {})))
    (is (= "shape:4.0" (ckway.rt/call-dyn #'f/.describe [o] {})))
    (is (= "shape:4.0|4.0|4.0|p:4.0" ((fn [x] (f/useShape x)) o)))
    (is (= 4.0 ((fn [x] (f/.area x)) o)) "an untyped local: the dynamic path selects at run time")))

;; ---------------------------------------------------------------- the library

(deftest library-source-has-no-reflection-warnings
  (testing "loading the library with *warn-on-reflection* on (a fresh JVM, so nothing is reloaded here)"
    (let [r (clojure.java.shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e"
                                   "(set! *warn-on-reflection* true) (require 'ckway.core 'ckway.reify 'ckway.bridge.gen 'ckway.bridge 'ckway.resolve 'ckway.rt) (println :loaded)")]
      (is (zero? (:exit r)) (:err r))
      (is (str/ends-with? (str/trim (:out r)) ":loaded"))
      (is (not (str/includes? (str (:err r)) "Reflection warning")) (:err r)))))
