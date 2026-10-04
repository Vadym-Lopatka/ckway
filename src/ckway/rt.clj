(ns ckway.rt
  "Dynamic path: the same selection rules as the compile-time path (`ckway.resolve`),
  applied to the actual classes of the arguments, then a reflective JVM call.
  Also the small helpers that statically emitted forms call.

  Call cache (step 6, P1). Selecting and planning a call costs microseconds, and they depend only on the
  declarations of the var, the classes of the values (and nil), the names of the named arguments and the
  integer-literal marks. `call-dyn` therefore keeps the result of `prepare` (a function of the written
  values, with the reflective `Method` looked up once) in a ConcurrentHashMap under the key `call-key`.
  The map lives in the metadata of the var (`:kt/cache`, made by `kt/require`): a package that is required
  again gets new metadata and so an empty cache. Bound: 64 entries for each var; when a new entry comes
  to a full map the map is cleared (a call site with more shapes than that does not profit from a cache).
  An error is not cached. The key holds the Class objects of the arguments, so a class stays reachable
  while it is in a cache (at most 64 per var). `ckway.set` (the dynamic `kt/set!`) uses the same cache."
  (:require [ckway.co :as co]
            [ckway.resolve :as r])
  (:import [java.lang.reflect Method Constructor Field Array InvocationTargetException Proxy InvocationHandler]
           [java.lang.invoke MethodHandle MethodHandles MethodType]
           [java.util Arrays]
           [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- value helpers (also used by emitted code)

(defn integral
  "x if it is an integer number, else a kt error. Guards narrowing casts."
  [x]
  (if (r/integral-class? (class x))
    x
    (r/fail (str "kt: expected an integer for an Int/Long/Short/Byte parameter, got "
                 (if (nil? x) "nil" (str (.getName (class x)) " " (pr-str x)))))))

(defn box
  "Box `x` as the Java wrapper class `cname` (a nullable Kotlin Int?, Long?, ...). nil stays nil."
  [^String cname x]
  (when (some? x)
    (case cname
      "java.lang.Integer" (Integer/valueOf (int (integral x)))
      "java.lang.Long" (Long/valueOf (long (integral x)))
      "java.lang.Short" (Short/valueOf (short (integral x)))
      "java.lang.Byte" (Byte/valueOf (byte (integral x)))
      "java.lang.Double" (Double/valueOf (double x))
      "java.lang.Float" (Float/valueOf (float x))
      "java.lang.Character" (Character/valueOf (char x)))))

(defn coerce
  "Convert `v` for a JVM parameter of class `c`: Clojure number -> Kotlin width. Nothing else."
  [^Class c v]
  (let [n (.getName c)]
    (case n
      "int" (Integer/valueOf (int (integral v)))
      "long" (Long/valueOf (long (integral v)))
      "short" (Short/valueOf (short (integral v)))
      "byte" (Byte/valueOf (byte (integral v)))
      "double" (Double/valueOf (double v))
      "float" (Float/valueOf (float v))
      "char" (Character/valueOf (char v))
      ("java.lang.Integer" "java.lang.Long" "java.lang.Short" "java.lang.Byte" "java.lang.Double"
       "java.lang.Float" "java.lang.Character") (box n v)
      v)))

(defn ->array
  "Array of JVM class `array-class-name` from the collection `xs` (an array of that class passes through)."
  [^String array-class-name xs]
  (let [^Class ac (r/jvm-class array-class-name)]
    (if (.isInstance ac xs)
      xs
      (let [ct (.getComponentType ac)
            v (vec xs)
            a (Array/newInstance ct (count v))]
        (dotimes [i (count v)] (Array/set a i (coerce ct (nth v i))))
        a))))

(defn unit->nil
  "kotlin.Unit as a value is nil."
  [x]
  (if (identical? x kotlin.Unit/INSTANCE) nil x))

;; ---------------------------------------------------------------- objects whose initialiser failed

;; The var of a Kotlin `object` holds the instance, so `kt/require` initialises the class. If its initialiser
;; throws, the var holds a FailedObject instead (the require goes on); a kt call that gets it as an argument
;; throws `failure`.
(deftype FailedObject [^String what ^String class-name ^Throwable cause]
  Object
  (toString [_] (str "#kt/failed-object[" class-name " failed to initialise: " (ex-message cause) "]")))

(defn failure
  "The kt error for using the failed object `x`: names the object, carries the original cause."
  [^FailedObject x]
  (ex-info (str "kt: " (.-what x) " `" (let [^String n (.-class-name x)] (subs n (inc (.lastIndexOf n ".")))) "` (" (.-class-name x)
                ") failed to initialise: " (.getName (class (.-cause x))) ": " (ex-message (.-cause x))
                ". The var holds the instance, so kt/require initialises the object, and its initialiser threw then.")
           {:kt/error true :kt/object (.-class-name x)}
           (.-cause x)))

(defn live
  "`x`, or the error of `failure` when it is a FailedObject. The static path wraps a failed object's var with it."
  [x]
  (if (instance? FailedObject x) (throw (failure x)) x))

(def ^:private first-causes
  "Documented cache: class name -> the error that its first failed initialisation threw. The JVM keeps only the
  text of it for a later access (a NoClassDefFoundError), so a second `kt/require` reuses this one."
  (atom {}))

(defn failed-object
  "A FailedObject for the Throwable `t` that the class initialisation of `class-name` threw (`what`: \"object\" or
  \"enum entry\"). The cause is the first error that is not the JVM's wrapper (ExceptionInInitializerError, or the
  NoClassDefFoundError of a second access)."
  [what ^String class-name ^Throwable t]
  (let [cause (loop [t t]
                (if (and (or (instance? ExceptionInInitializerError t) (instance? NoClassDefFoundError t)) (.getCause t))
                  (recur (.getCause t))
                  t))]
    (->FailedObject what class-name (get (swap! first-causes #(if (contains? % class-name) % (assoc % class-name cause))) class-name))))

;; ---------------------------------------------------------------- value classes

(defn vc-arg
  "The value-class object `x` for a parameter of the value class `c` (named `name`), or a kt error.
  nil passes only for a nullable parameter. Emitted code and the dynamic path call this before
  they unbox."
  [^Class c name nullable? x]
  (cond
    (nil? x) (if nullable?
               nil
               (r/fail (str "kt: nil where Kotlin expects a non-null " (.getName c) " (`" name "`)")))
    (.isInstance c x) x
    :else (r/fail (str "kt: `" name "` expects " (.getName c) ", got " (.getName (class x)) " " (pr-str x)
                       ". A value class is always the object, not the underlying value."))))

(defn- got-text
  "How an offending value is named in a message: its class, and the value when it is short."
  [x]
  (str (.getName (class x)) " " (let [s (pr-str x)] (if (> (count s) 40) (str (subs s 0 37) "...") s))))

(defn- type-label
  "`text` (the Kotlin type), and the JVM class in parentheses when that says more."
  [text cname]
  (if (= text cname) text (str text " (" cname ")")))

(defn vc-result
  "The value-class object `x`, which a Clojure function returned where Kotlin expects the value class `c`, or a kt
  error that names the expected type and the class of the value. nil passes only for a nullable result."
  [^Class c nullable? x]
  (cond
    (.isInstance c x) x
    (nil? x) (if nullable?
               nil
               (r/fail (str "kt: nil where Kotlin expects a non-null " (.getName c))))
    :else (r/fail (str "kt: expected " (.getName c) " where Kotlin expects it, got " (got-text x)
                       ". A value class is always the object, not the underlying value."))))

(defn- same-name-hint
  "When the class `actual` has a member (a property or a function) with the Kotlin name that the call used, in the
  namespace of the called var, a hint that says how to write the call for it. nil otherwise."
  [{:keys [ns name alias recv]} ^Class actual]
  (when-let [nso (some-> ns symbol find-ns)]
    (let [bare (if (.startsWith ^String name ".") (subs name 1) name)
          shown (or alias ns)
          hits (for [n [bare (str "." bare)]
                     :let [v (get (ns-publics nso) (symbol n))]
                     :when v
                     d (:kt/decls (meta v))
                     :when (and (some #(= :dispatch (:role %)) (:receivers d))
                                (some-> ^Class (r/jvm-class (:owner d)) (.isAssignableFrom actual)))]
                 [n d])]
      (when-let [[n d] (first hits)]
        (let [prop? (= :property (:kind d))]
          (str "\n  Hint: `" bare "` is " (if prop? "a property" "a function") " of " (.getName actual)
               (when-not prop? (str " (" (:signature d) ")"))
               ": write (" shown "/" n " " (or recv "x") (when-not prop? " ...") ")"))))))

(defn wrong-class
  "The static path found no class for a receiver or argument at compile time and casts it. This is the error when the
  value is not an instance of `want` (nil passes). `site` = {:call :sig :what :ns :name :alias :recv}: the call as
  the user wrote it, the Kotlin declaration that was selected, what the value is (`the receiver`, `the argument ...`)."
  [^Class want site v]
  (when (some? v)
    (let [receiver? (= "the receiver" (:what site))]
      (r/fail (str "kt: " (:call site) ": " (:what site) (when (and receiver? (:recv site)) (str " `" (:recv site) "`"))
                   " is " (if receiver? (str "a " (.getName (class v))) (got-text v))
                   ", but the Kotlin declaration that was selected needs " (.getName want) "\n  Kotlin: " (:sig site)
                   (when receiver? (same-name-hint site (class v))))
              {:kt/wrong-class (.getName (class v))}))))

(defn check-obj
  "`v` if it fits the object descriptor `td` ({:k :obj :cls :text :nullable?}), else a kt error that names the
  Kotlin type and the class of `v`. `where` (optional) says which value it is, for example \"the result of the Clojure function\"."
  [td v]
  (if-let [cname (:cls td)]
    (cond
      (nil? v) (if (:nullable? td)
                 nil
                 (r/fail (str "kt: nil where Kotlin expects a non-null " (:text td))))
      (.isInstance ^Class (r/jvm-class cname) v) v
      :else (r/fail (str "kt: expected " (type-label (:text td) cname) " where Kotlin expects it, got " (got-text v)
                         (when (:vc? td) ". A value class is always the object, not the underlying value."))))
    v))

;; ---------------------------------------------------------------- functions

;; Clojure function -> Kotlin function type or fun interface: `adapt-arg` (run time) and the
;; reify forms that ckway.resolve emits (compile time). Kotlin function value -> Clojure function:
;; `<-kotlin`, a reify that implements the exact FunctionN, IFn and Fn (a Proxy only above 20 parameters).
;; Both directions convert at the boundary by a type descriptor (see ckway.resolve/type-descriptor).

(declare ->kotlin <-kotlin)

(def ^:private no-args (object-array 0))

(defn- object-method?
  "Is `m` one of toString/hashCode/equals, which a proxy sends to its handler too?"
  [^Method m]
  (= Object (.getDeclaringClass m)))

(defn- object-method [what ^Object proxy ^Method m args]
  (case (.getName m)
    "toString" (str "#kt/" what)
    "hashCode" (System/identityHashCode proxy)
    "equals" (identical? proxy (aget ^objects args 0))))

(def ^:private fn-invoke-method
  "Documented cache: arity -> the invoke Method of kotlin.jvm.functions.FunctionN."
  (memoize
   (fn [n]
     (.getMethod ^Class (r/jvm-class (str "kotlin.jvm.functions.Function" n)) "invoke"
                 ^"[Ljava.lang.Class;" (into-array Class (repeat n Object))))))

(defn- fn-iface-arity
  "Arity of the JVM FunctionN that a function descriptor stands for (a suspend function takes the Continuation too)."
  [td]
  (cond-> (:arity td) (:suspend? td) inc))

(declare ->kotlin)

(defn- arg->kotlin
  "`->kotlin` for argument number `i` (from 0) of the Kotlin function value of descriptor `td`: a kt error
  says which argument, the Kotlin type of the function and the class of the value."
  [td i d a]
  (try (->kotlin d a)
       (catch clojure.lang.ExceptionInfo e
         (if (and (:kt/error (ex-data e)) (not (:kt/arg (ex-data e))))
           (throw (ex-info (str "kt: argument " (inc i) " of the Kotlin function " (:text td) ": "
                                (let [m (ex-message e)] (if (.startsWith ^String m "kt: ") (subs m 4) m)))
                           (assoc (ex-data e) :kt/arg (inc i)) e))
           (throw e)))))

(defn- call-kotlin-fn
  "Call the Kotlin function value `g` (declared by descriptor `td`) with Clojure values. A suspend
  function value gets a continuation and the call waits for the result (ckway.co)."
  [g td args]
  (let [n (:arity td)
        _ (when-not (= n (count args))
            (throw (clojure.lang.ArityException. (count args) (str "Kotlin function with " n " parameter" (when (not= 1 n) "s")))))
        vals (doall (map-indexed (fn [i [d a]] (arg->kotlin td i d a)) (map vector (:params td) args)))]
    (<-kotlin (:ret td)
              (if (:suspend? td)
                (let [k (co/continuation)]
                  (co/wait-for k (try (.invoke ^Method (fn-invoke-method (inc n)) g (object-array (concat vals [k])))
                                      (catch InvocationTargetException e (throw (.getCause e))))))
                (try (.invoke ^Method (fn-invoke-method n) g (object-array vals))
                     (catch InvocationTargetException e (throw (.getCause e))))))))

(defn- invoke-wrapped
  "What a call of the wrapper of the Kotlin function value `g` (descriptor `td`) with `args` does. Kotlin
  itself calling the wrapper of a suspend function passes (args..., continuation): that goes straight on."
  [g td args]
  (if (and (:suspend? td) (= (inc (:arity td)) (count args)) (instance? kotlin.coroutines.Continuation (nth args (:arity td))))
    (try (.invoke ^Method (fn-invoke-method (inc (:arity td))) g (object-array args))
         (catch InvocationTargetException e (throw (.getCause e))))
    (call-kotlin-fn g td args)))

(def ^:private max-reify-arity
  "Most parameters of a Kotlin function value that get a reify wrapper (IFn has `invoke` up to 20 arguments;
  a FunctionN with more would clash with its varargs `invoke`). Above it: a java.lang.reflect.Proxy."
  20)

(defn- wrapper-form
  "The form of a function of [g td] that makes the wrapper of a Kotlin function value for the JVM interface
  kotlin.jvm.functions.Function<m>: a reify (a real class, so a checked exception passes as itself, as
  for the adapters of the other direction) of that interface, IFn and Fn."
  [m]
  (let [g (gensym "g") td (gensym "td")
        vars (fn [k] (mapv #(symbol (str "a" %)) (range k)))
        more (gensym "more")]
    `(fn [~g ~td]
       (with-meta
         (reify ~(symbol (str "kotlin.jvm.functions.Function" m)) clojure.lang.IFn clojure.lang.Fn
           ~@(for [k (range (inc max-reify-arity))]
               `(~'invoke [~'_ ~@(vars k)] (invoke-wrapped ~g ~td ~(vars k))))
           (~'invoke [~'_ ~@(vars max-reify-arity) ~more]
            (invoke-wrapped ~g ~td (into ~(vars max-reify-arity) (seq ~more))))
           (~'applyTo [~'_ args#] (invoke-wrapped ~g ~td (vec (seq args#))))
           (~'call [~'_] (invoke-wrapped ~g ~td []))
           (~'run [~'_] (invoke-wrapped ~g ~td []) nil)
           (~'toString [~'_] (str "#kt/fn[" ~g "]")))
         {:kt/fn ~g}))))

(def ^:private wrapper-factory
  "Documented cache: JVM arity -> (fn [g td] wrapper), compiled once with `eval` (like `adapter-factory`)."
  (memoize
   (fn [m] (binding [*ns* (the-ns 'ckway.rt)] (eval (wrapper-form m))))))

(deftype KtFn [g td]
  InvocationHandler
  (invoke [_ proxy m args]
    (let [args (or args no-args)]
      (if (object-method? m)
        (object-method (str "fn[" g "]") proxy m args)
        (case (.getName ^Method m)
          "invoke" (invoke-wrapped g td (vec args))
          "applyTo" (invoke-wrapped g td (vec (seq (aget ^objects args 0))))
          "call" (invoke-wrapped g td [])
          "run" (do (invoke-wrapped g td []) nil)
          (throw (UnsupportedOperationException. (str "kt: " (.getName ^Method m) " is not supported on a Kotlin function value"))))))))

(defn own
  "A Kotlin function value that `<-kotlin` wrapped, back to the original; anything else unchanged."
  [x]
  (cond
    (nil? x) x
    (Proxy/isProxyClass (class x)) (let [h (Proxy/getInvocationHandler x)] (if (instance? KtFn h) (.-g ^KtFn h) x))
    (and (instance? kotlin.Function x) (instance? clojure.lang.IMeta x)) (or (:kt/fn (meta x)) x)
    :else x))

(defn <-kotlin
  "A value that Kotlin returned or passed (descriptor `td`) as a Clojure value: a function value
  becomes a Clojure function that also is the original FunctionN; Unit becomes nil."
  [td v]
  (case (:k td)
    :unit nil
    :fn (if (and (some? v) (not (ifn? v)) (instance? kotlin.Function v))
          (let [m (fn-iface-arity td)]
            (if (<= m max-reify-arity)
              ((wrapper-factory m) v td)
              (Proxy/newProxyInstance (clojure.lang.RT/baseLoader)
                                      (into-array Class [(r/jvm-class (str "kotlin.jvm.functions.Function" m))
                                                         clojure.lang.IFn clojure.lang.Fn])
                                      (->KtFn v td))))
          v)
    v))

(defn adapt?
  "Must the Clojure function `g` be adapted to the JVM type `iface`? False for nil and for an
  instance (also a Kotlin function that `<-kotlin` wrapped). A value that is no function is an error."
  [g ^Class iface]
  (cond (nil? g) false
        (.isInstance iface (own g)) false
        (ifn? g) true
        :else (r/fail (str "kt: expected a function for a parameter of type " (.getName iface) ", got " (.getName (class g))))))

(defn arity-error
  "The error for a Clojure function of the wrong arity that Kotlin called (F1). `e` is the
  ArityException, `g` the Clojure function, `text` what Kotlin called (a Kotlin function type or
  `Iface.method(types)`), `n` the number of arguments Kotlin gave. An ArityException that was not
  raised by `g` itself (by a call deeper in its body) is returned unchanged."
  [^clojure.lang.ArityException e g text n]
  (let [f (if (var? g) (deref g) g)]
    (if (and (= n (.-actual e)) (let [nm (.-name e)] (or (= nm (.getName (class f))) (= nm (str f)))))
      (let [args #(str % (if (= 1 %) " argument" " arguments"))]
        (ex-info (str "kt: Kotlin called this function as " text " with " (args n)
                      ", but the Clojure function does not accept " (args n))
                 {:kt/error true :kt/arity n} e))
      e)))

(def ^:private adapter-factory
  "Documented cache: adapter spec -> (fn [clojure-fn] adapter). The factory is compiled once with
  `eval` from the same reify form that the static path emits, so an adapter made at run time is a
  real class: a checked exception that the Clojure function throws reaches Kotlin as itself
  (a java.lang.reflect.Proxy would wrap it in UndeclaredThrowableException)."
  (memoize
   (fn [spec]
     (let [g (gensym "g")]
       (binding [*ns* (the-ns 'ckway.rt)]
         (eval `(fn [~g] ~((case (:kind spec) :fn r/fn-reify :fi r/fi-reify) spec g {}))))))))

(defn adapt-arg
  "Value for a parameter of a function type or fun interface (`spec`, see
  ckway.resolve/adapter-spec): nil and instances pass on; a Clojure function is adapted by a reify
  that is compiled at run time (once per spec); the static path emits the same reify."
  [{:keys [kind iface feature sig] :as spec} v]
  (let [^Class c (r/jvm-class iface)
        o (own v)]
    (cond (nil? v) nil
          (.isInstance c o) o
          (not (ifn? v)) (r/fail (str "kt: expected a function for a parameter of type " iface ", got " (.getName (class v))))
          feature (r/not-supported* feature sig)
          :else ((adapter-factory (select-keys spec [:kind :iface :td :sam])) v))))

(def ^:private kind-names
  {:int "Int" :long "Long" :short "Short" :byte "Byte" :double "Double" :float "Float" :char "Char" :bool "Boolean"})

(defn ->kotlin
  "A Clojure value for a Kotlin position of descriptor `td` (a function parameter or result):
  number width (a Long for an Int becomes an Integer), a Clojure function becomes a Kotlin
  function or fun interface, any value for Unit becomes kotlin.Unit."
  [td v]
  (let [k (:k td)]
    (cond
      (= :unit k) kotlin.Unit/INSTANCE
      (nil? v) (cond (and (kind-names k) (not (:nullable? td)))
                     (r/fail (str "kt: nil where Kotlin expects a non-null " (kind-names k)))
                     (= :obj k) (check-obj td v)
                     :else nil)
      :else
      (case k
        :int (Integer/valueOf (int (integral v)))
        :long (Long/valueOf (long (integral v)))
        :short (Short/valueOf (short (integral v)))
        :byte (Byte/valueOf (byte (integral v)))
        :double (Double/valueOf (double v))
        :float (Float/valueOf (float v))
        :char (Character/valueOf (char v))
        :bool (if (instance? Boolean v) v (r/fail (str "kt: expected a Boolean where Kotlin expects a Boolean, got " (.getName (class v)))))
        :fn (adapt-arg {:kind :fn :iface (str "kotlin.jvm.functions.Function" (fn-iface-arity td)) :td td} v)
        :fi (adapt-arg (r/fi-spec (:class td) (:class td)) v)
        (check-obj td v)))))

;; ---------------------------------------------------------------- JVM members of a class that no bridge can name

(def ^:private handle-cache
  "Documented cache: [static? class name desc] -> MethodHandle."
  (ConcurrentHashMap.))

(defn jvm-handle
  "The MethodHandle of the JVM method `cname`.`mname``desc`, found with a private lookup, so a non-public method and a
  method of a package-private class (a part of a Kotlin multi-file class, `StringsKt__StringsJVMKt`) work. A bridge
  class (`ckway.bridge`) cannot name such a class; emitted code calls this instead (`call-jvm`). Cached."
  ^MethodHandle [static? ^String cname ^String mname ^String desc]
  (let [^ConcurrentHashMap cache handle-cache
        k [static? cname mname desc]]
    (or (.get cache k)
        (let [^Class c (r/jvm-class cname)
              lk (MethodHandles/privateLookupIn c (MethodHandles/lookup))
              mt (MethodType/fromMethodDescriptorString desc (clojure.lang.RT/baseLoader))
              mh (if static? (.findStatic lk c mname mt) (.findVirtual lk c mname mt))
              ;; a method with `Object...` is a variable-arity handle: invoked with Object arguments, it would
              ;; wrap the array that the call already passes
              mh (.asFixedArity mh)]
          (.put cache k mh)
          mh))))

(defn call-jvm
  "Call the MethodHandle `mh` (`jvm-handle`) with the vector `args` (the receiver first for a virtual method)."
  [^MethodHandle mh args]
  (.invokeWithArguments mh ^java.util.List args))

;; ---------------------------------------------------------------- reflection

(defn- descriptor ^String [^Class ret params]
  (.toMethodDescriptorString (MethodType/methodType ret ^"[Ljava.lang.Class;" params)))

(def ^:private find-member
  "Documented cache: [class name desc] -> reflective member."
  (memoize
   (fn [cname mname desc]
     (let [^Class c (r/jvm-class cname)
           m (cond
               (= "<init>" mname)
               (first (filter #(= desc (descriptor Void/TYPE (.getParameterTypes ^Constructor %))) (.getDeclaredConstructors c)))
               :else
               (first (filter #(and (= mname (.getName ^Method %))
                                    (= desc (descriptor (.getReturnType ^Method %) (.getParameterTypes ^Method %))))
                              (.getDeclaredMethods c))))]
       (or (some-> ^java.lang.reflect.AccessibleObject m (doto (.trySetAccessible)))
           (r/fail (str "kt: JVM member not found: " cname "." mname desc)))))))

(defn- unboxer
  "Function that gives the underlying value of a value-class object (conversion `{:vc :nullable? :name}`)."
  [{:keys [vc nullable? name]}]
  (let [c (r/jvm-class (:class vc))
        ^Method m (find-member (:class vc) (:name (:unbox vc)) (:desc (:unbox vc)))]
    (fn [x]
      (let [x (vc-arg c name nullable? x)]
        (when (some? x) (.invoke m x no-args))))))

(defn- boxer
  "Function that gives the value-class object for the underlying value (conversion `{:vc :nullable?}`)."
  [{:keys [vc nullable?]}]
  (let [prim? (.isPrimitive ^Class (r/jvm-class (:jvm-underlying vc)))
        ^Method m (find-member (:class vc) (:name (:box vc)) (:desc (:box vc)))]
    (fn [raw]
      (if (and nullable? (nil? raw) (not prim?))
        nil
        (.invoke m nil (object-array [raw]))))))

(defn- zero [^String jt]
  (case jt
    "int" (Integer/valueOf 0) "long" (Long/valueOf 0) "short" (Short/valueOf (short 0)) "byte" (Byte/valueOf (byte 0))
    "double" (Double/valueOf 0.0) "float" (Float/valueOf (float 0)) "char" (Character/valueOf (char 0))
    "boolean" false nil))

(defn- static-field [cname fname]
  (.get (.getField ^Class (r/jvm-class cname) ^String fname) nil))

(defn- entry-reader
  "Function of the argument vector that gives the value of a written argument (by its :idx), or the
  `Companion` instance of a companion target."
  [e]
  (if-let [{:keys [class field]} (:companion e)]
    (let [^java.lang.reflect.Field f (.getField ^Class (r/jvm-class class) ^String field)]
      (fn [_] (.get f nil)))
    (let [i (int (:idx e))]
      (fn [^objects argv] (aget argv i)))))

(defn- coerce-param
  "`coerce` for the parameter `pname` of Kotlin type `ptext` (of the declaration `sig`): a value of the wrong
  class is a kt error that names the parameter, the Kotlin type and the class of the value."
  [^Class c pname ptext sig v]
  (let [ok? (cond
              (nil? v) true
              (contains? #{Integer/TYPE Long/TYPE Short/TYPE Byte/TYPE Integer Long Short Byte} c) (r/integral-class? (class v))
              (contains? #{Double/TYPE Float/TYPE Double Float} c) (number? v)
              (contains? #{Character/TYPE Character} c) (char? v)
              (contains? #{Boolean/TYPE Boolean} c) (boolean? v)
              (.isPrimitive c) true
              :else (.isInstance c v))]
    (if ok?
      (coerce c v)
      (r/fail (str "kt: parameter `" pname "` (" ptext ") expects " (.getName c) ", got " (.getName (class v)) " " (pr-str v)
                   (when sig (str "\n  Kotlin: " sig)))))))

(defn- arg-fn
  "Function of [argument vector, continuation] that gives the JVM value for the plan argument `a`.
  `sig` is the Kotlin declaration, for messages."
  [a sig]
  (cond
    (:entry a) (let [get (entry-reader (:entry a))]
                 (if-let [c (some-> (:jvm-type a) r/jvm-class)]
                   (cond (:adapt a) (let [ad (:adapt a)] (fn [argv _] (adapt-arg ad (get argv))))
                         (:vc a) (let [un (unboxer (:vc a))] (fn [argv _] (un (get argv))))
                         (:pname a) (let [{:keys [pname ptext]} a]
                                      (fn [argv _] (coerce-param c pname ptext sig (get argv))))
                         :else (fn [argv _] (coerce c (get argv))))
                   (fn [argv _] (get argv))))
    (:zero a) (let [z (zero (:zero a))] (fn [_ _] z))
    (:mask a) (let [m (Integer/valueOf (int (:mask a)))] (fn [_ _] m))
    (:marker a) (fn [_ _] nil)
    (:cont a) (fn [_ k] k)
    (:vararg a) (let [rs (mapv entry-reader (:vararg a))
                      un (when-let [vc (:elem-vc a)] (unboxer vc))
                      jt (:jvm-type a)]
                  (fn [argv _] (->array jt (map (fn [r] (let [x (r argv)] (if un (un x) x))) rs))))
    (:vararg-coll a) (let [r (entry-reader (:vararg-coll a))
                           un (when-let [vc (:elem-vc a)] (unboxer vc))
                           jt (:jvm-type a)]
                       (fn [argv _] (->array jt (let [coll (r argv)] (if un (map un coll) coll)))))))

(defn- unwrap [^Throwable t] (if (instance? InvocationTargetException t) (.getCause t) t))

(defn prepare
  "Compile a plan (see `ckway.resolve/plan`) into a function of the vector of the written values, an
  object array indexed by the :idx of the entries (positional arguments, then the named ones in
  the written order). The function does the JVM call (reflection: the member is looked up once, here)
  and converts the result. All that depends on the plan only is done once, so a prepared plan is the
  thing that a cache keeps (`call-dyn`, `ckway.ref`, `ckway.data`)."
  [p]
  (let [fns (mapv #(arg-fn % (:sig p)) (:args p))
        n (count fns)
        target (some-> (:target p) entry-reader)
        tclass (when (and (= :virtual (:op p)) (:target p)) (r/jvm-class (:class p)))
        susp? (:suspend? p)
        op (:op p)
        cname (:class p)
        member (case op
                 (:static :virtual :new) (find-member cname (if (= :new op) "<init>" (:name p)) (:desc p))
                 nil)
        ^java.lang.reflect.Field field (case op
                                         (:get-static :get-field :set-static :set-field)
                                         (.getField ^Class (r/jvm-class cname) ^String (:field p))
                                         nil)
        post (cond (:ret-vc p) (boxer (:ret-vc p))
                   (:unit? p) unit->nil
                   (:ret-td p) (let [td (:ret-td p)] #(<-kotlin td %))
                   :else identity)]
    (fn [^objects argv]
      (let [k (when susp? (co/continuation))
            _ (when (and (= :virtual op) target)
                (let [t (target argv)]
                  (when-not (.isInstance ^Class tclass t)
                    (r/fail (str "kt: the receiver (`this`) expects " (.getName ^Class tclass) ", got "
                                 (if (nil? t) "nil" (got-text t)) (when-let [sig (:sig p)] (str "\n  Kotlin: " sig)))))))
            args (object-array n)
            _ (dotimes [i n] (aset args i ((nth fns i) argv k)))
            res (try
                  (case op
                    :static (.invoke ^Method member nil args)
                    :virtual (.invoke ^Method member (target argv) args)
                    :new (.newInstance ^Constructor member args)
                    :get-static (.get field nil)
                    :get-field (.get field (target argv))
                    :set-static (.set field nil (aget args 0))
                    :set-field (.set field (target argv) (aget args 0)))
                  (catch InvocationTargetException e (throw (unwrap e))))
            res (if k (co/wait-for k res) res)]
        (post res)))))

;; ---------------------------------------------------------------- the dynamic path and its cache

(def ^:private cache-bound
  "Most entries of the call cache of one var. When it is full, the next new entry clears it (no LRU: a
  call site that sees more than this many shapes is megamorphic, and a cache would not help it)."
  64)

(defn new-cache
  "An empty call cache. `kt/require` puts one in the metadata of each var (`:kt/cache`); a new var
  metadata is a new, empty cache, so a package that is required again has no stale entry."
  ^ConcurrentHashMap []
  (ConcurrentHashMap.))

(defn cached
  "The value of `build` (a function of no arguments) for `key` in `cache`, computed once. `cache` nil:
  no caching. A failure of `build` is not cached. Thread-safe; at most `cache-bound` entries."
  [^ConcurrentHashMap cache key build]
  (if cache
    (or (.get cache key)
        (let [v (build)]
          (when (>= (.size cache) cache-bound) (.clear cache))
          (.put cache key v)
          v))
    (build)))

(deftype CallKey [^objects parts ^int h]
  Object
  (hashCode [_] h)
  (equals [_ o] (and (instance? CallKey o) (Arrays/equals parts ^objects (.-parts ^CallKey o)))))

(def ^:private nil-part (Object.))

(defn- arg-part
  "What of a run-time value decides the selection (`r/value-info`): its class, nil, and for a class var
  the Kotlin class that it stands for."
  [x]
  (cond (nil? x) nil-part
        (instance? FailedObject x) (throw (failure x))
        (and (fn? x) (:kt/class (meta x))) [(class x) (:kt/class (meta x))]
        :else (class x)))

(defn call-key
  "The key of a dynamic call in the call cache: the number of positional arguments, the part of each
  value (`arg-part`), the names of the named arguments with the part of their values, and `lits`."
  [tag pos named lits]
  (let [n (count pos)
        m (count named)
        parts (object-array (+ 3 n (* 2 m)))]
    (aset parts 0 tag)
    (aset parts 1 (Long/valueOf n))
    (loop [i 0 s (seq pos)]
      (when s (aset parts (+ 2 i) (arg-part (first s))) (recur (inc i) (next s))))
    (loop [i 0 s (seq named)]
      (when s
        (let [[k x] (first s)]
          (aset parts (+ 2 n (* 2 i)) k)
          (aset parts (+ 3 n (* 2 i)) (arg-part x)))
        (recur (inc i) (next s))))
    (aset parts (+ 2 n (* 2 m)) (when (seq lits) lits))
    (CallKey. parts (Arrays/hashCode parts))))

(defn argv
  "The object array of the written values: positional, then the named ones (see `prepare`)."
  ^objects [pos named]
  (let [a (object-array (+ (count pos) (count named)))]
    (loop [i 0 s (seq pos)] (when s (aset a i (first s)) (recur (inc i) (next s))))
    (loop [i (count pos) s (seq named)] (when s (aset a i (val (first s))) (recur (inc i) (next s))))
    a))

(defn- prepare-call
  "Select and plan the call of the var `v` for the values `pos`/`named` (as in `call-dyn`), prepare it."
  [^clojure.lang.Var v decls pos named lits]
  (let [var-name (str (.sym v))
        n (count pos)
        info (fn [k x] (cond-> (r/value-info x) (and (contains? lits k) (some? x)) (assoc :lit (get lits k))))
        parsed {:positional (vec (map-indexed (fn [i x] {:arg x :info (info i x) :idx i}) pos))
                :named (vec (map-indexed (fn [i [k x]] [k {:arg x :info (info k x) :idx (+ n i)}]) named))}
        {:keys [decl items checks]} (r/choose var-name decls parsed false)]
    (r/check-supported! decl checks)
    (prepare (r/plan decl items))))

(defn call-dyn
  "Dynamic call of the kt var `v` with positional values `pos` and named values `named` ({name value}).
  `lits` ({position-or-name :int|:long}) marks arguments that were integer literals in the source.

  The selection and the plan depend only on the declarations of `v`, the classes of the values (and
  nil), the names of the named arguments and `lits`, so the prepared call is kept in the cache in the
  metadata of `v` under that key (at most `cache-bound` entries; see `new-cache`, `call-key`)."
  ([v pos named] (call-dyn v pos named {}))
  ([^clojure.lang.Var v pos named lits]
   (let [m (meta v)
         decls (:kt/decls m)
         call (cached (:kt/cache m) (call-key nil pos named lits) #(prepare-call v decls pos named lits))]
     (call (argv pos named)))))
