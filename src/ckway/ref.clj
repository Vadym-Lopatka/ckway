(ns ckway.ref
  "`(kt/ref X y)` is the Kotlin `X::y`, `(kt/ref y)` is `::y` (DESIGN-2 rule 9).

  What the form gives, and how it is made. Nothing needs the Kotlin compiler and nothing needs
  kotlin-reflect: the objects are the classes that kotlinc itself emits for a reference
  (`kotlin.jvm.internal.*Reference*Impl`, `kotlin.jvm.internal.Reflection`), from kotlin-stdlib.

    form                          value                            made by
    (kt/ref X class)              KClass                           Reflection.getOrCreateKotlinClass(Class)
    (kt/ref X p)                  KProperty1 (KMutableProperty1    PropertyReference1Impl / Mutable...1Impl,
                                  for a var with a public setter)  with `get`/`set` overridden (see below)
    (kt/ref f/topP)               KProperty0 / KMutableProperty0   PropertyReference0Impl / Mutable...0Impl
    (kt/ref X .f)  (kt/ref f/f)   KFunction + FunctionN            FunctionReferenceImpl + kotlin.jvm.functions.FunctionN
    (kt/ref f/C)                  the constructor reference ::C    the same, name \"<init>\"

  `PropertyReference1Impl.get` calls `getGetter()`, which needs kotlin-reflect. So `get` and `set` are
  overridden (a `proxy` of the Impl class, which clojure writes to the AOT output): they run the same
  prepared call as `(alias/p x)` (`ckway.rt/prepare`), so a value class is boxed, a number gets its width.
  `.name`, `.get`, `.set`, `invoke` (a property reference is also a `(T) -> V`), `equals` and `hashCode`
  work without kotlin-reflect; every reference is also a Clojure `IFn` (`(map (kt/ref f/Person firstName) ps)`); `.getter`, `.returnType`, `.call` need it (they ask the Kotlin metadata that
  kotlin-reflect reads). A function reference is also the FunctionN that Kotlin expects (receiver first):
  `invoke` runs the prepared call with all parameters (a reference never uses a default value, as in Kotlin).
  Arity up to 19 (a Clojure fn takes 20 parameters, and the proxy method has `this` too); a `suspend`, `reified` or `vararg`... function reference: see `spec-of`.

  A reference is a constant. The expansion is `(ckway.ref/const '{...})`: a quoted map of plain data (class
  names, JVM names, the prepared plans of the read and the write), which the Clojure compiler keeps as ONE
  constant of the compiled form, and `const` looks it up in a table (a ConcurrentHashMap) and builds the
  object the first time. The form does not build anything on later evaluations; two forms for the same
  declaration give the same (identical?) object. AOT-compiled code needs this namespace (and the stdlib),
  not the metadata reader or the Kotlin compiler.

  Where `y` is found. `(kt/ref X y)`: a kt var named `y` (property) or `.y` (function) in the namespace of X
  (when X is a kt class var) and in every kt namespace that the current namespace aliases - what `import pkg.*`
  makes visible in Kotlin. Of the declarations whose single receiver accepts X, a member wins over an
  extension (as in Kotlin), an override over its original. If more than one is left, compile error with the
  list: Kotlin would use the expected type, kt has none. `(kt/ref f/y)`: the var `f/y`, its declarations
  without a receiver (a top-level function or property, or the constructors of a class var).

  Bound references. `(kt/ref Obj y)` where `Obj` is a Kotlin `object` var is `Obj::y`, which Kotlin makes a reference
  bound to the object: a KProperty0 (KMutableProperty0) or a KFunction without the receiver parameter, the object
  as its `boundReceiver`. The spec carries `:bound` (the JVM class of the object); the read and write plans are
  those of the unbound member, and the object is put in front of the arguments. Any other bound reference
  (`user::email` on a value, a local, an enum entry) is not supported: a compile error says so."
  (:require [clojure.string :as str]
            [ckway.resolve :as r]
            [ckway.rt :as rt]
            [ckway.set :as set]
            [ckway.types :as types])
  (:import [java.util.concurrent ConcurrentHashMap]
           [kotlin.jvm.internal PropertyReference1Impl MutablePropertyReference1Impl PropertyReference0Impl
            MutablePropertyReference0Impl FunctionReferenceImpl Reflection CallableReference]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- run time: build a reference from its spec

(defn- argv1 ^objects [a] (doto (object-array 1) (aset 0 a)))
(defn- argv2 ^objects [a b] (doto (object-array 2) (aset 0 a) (aset 1 b)))

(defn- arity-check [s n]
  (when-not (= n (count s))
    (throw (clojure.lang.ArityException. (count s) "kt/ref"))))

;; Every reference is also a Clojure IFn (DESIGN-2 rule 6: a Kotlin function value that Clojure gets is a
;; Clojure function), so `(map (kt/ref f/Person firstName) people)` and `((kt/ref f/Person .greet) p "Hi")` work.
;; `invoke` of the IFn and of FunctionN is the same JVM method.
(defn- bound-instance
  "The object that a bound reference is bound to (the INSTANCE of the Kotlin `object` class), or nil."
  [bound]
  (when bound (.get (.getField ^Class (r/jvm-class bound) "INSTANCE") nil)))

(defn- receiver-of [inst] (or inst CallableReference/NO_RECEIVER))

(defn- build-prop [{:keys [arity owner name sig flags read write bound]}]
  (let [^Class c (r/jvm-class owner)
        flags (int flags)
        inst (bound-instance bound)
        rcv (receiver-of inst)
        args0 (fn [] (if inst (argv1 inst) (object-array 0)))
        set0 (fn [v] (if inst (argv2 inst v) (argv1 v)))
        rd (rt/prepare read)
        wr (some-> write rt/prepare)]
    (case [arity (some? write)]
      [1 false] (proxy [PropertyReference1Impl clojure.lang.IFn] [c name sig flags]
                  (get [x] (rd (argv1 x)))
                  (invoke [x] (rd (argv1 x)))
                  (applyTo [s] (arity-check s 1) (rd (argv1 (first s)))))
      [1 true] (proxy [MutablePropertyReference1Impl clojure.lang.IFn] [c name sig flags]
                 (get [x] (rd (argv1 x)))
                 (set [x v] (wr (argv2 x v)) nil)
                 (invoke [x] (rd (argv1 x)))
                 (applyTo [s] (arity-check s 1) (rd (argv1 (first s)))))
      [0 false] (proxy [PropertyReference0Impl clojure.lang.IFn] [rcv c name sig flags]
                  (get [] (rd (args0)))
                  (invoke [] (rd (args0)))
                  (applyTo [s] (arity-check s 0) (rd (args0))))
      [0 true] (proxy [MutablePropertyReference0Impl clojure.lang.IFn] [rcv c name sig flags]
                 (get [] (rd (args0)))
                 (set [v] (wr (set0 v)) nil)
                 (invoke [] (rd (args0)))
                 (applyTo [s] (arity-check s 0) (rd (args0)))))))

(defn- fn-arity-error
  "The kt error for a function reference called with `got` arguments. `spec` has :text (the reference as written),
  :pnames (receiver and parameter names) and :sigtext (the Kotlin declaration). The ArityException is the cause."
  [{:keys [text pnames sigtext defaults?]} got]
  (let [n (count pnames)
        args #(str % (if (= 1 %) " argument" " arguments"))]
    (ex-info (str "kt: " text " takes " (args n) (when (pos? n) (str " (" (str/join ", " pnames) ")")) ", got " got
                  ". Kotlin: " sigtext
                  (when defaults? ". A function reference takes all parameters: Kotlin defaults are not applied."))
             {:kt/error true :kt/arity got}
             (clojure.lang.ArityException. got "kt/ref"))))

(defmacro ^:private fn-makers
  "A vector, index = arity (0..19), of functions [receiver owner name sig flags impl spec] that make a
  FunctionReferenceImpl that is also kotlin.jvm.functions.Function<arity> and a Clojure IFn. A call with
  another number of arguments (IFn.invoke of 0..19 arguments, `applyTo`) is a kt error (`fn-arity-error`)."
  []
  (vec (for [n (range 20)]
         (let [args (vec (map #(symbol (str "a" %)) (range n)))
               impl (gensym "impl") spec (gensym "spec") s (gensym "s")]
           `(fn [rcv# c# nm# sig# flags# ~impl ~spec]
              (proxy [FunctionReferenceImpl ~(symbol (str "kotlin.jvm.functions.Function" n)) clojure.lang.IFn]
                     [(int ~n) rcv# c# nm# sig# (int flags#)]
                (~'invoke ~(list args `(~impl (object-array ~args)))
                          ~@(for [k (range 20) :when (not= k n)]
                              (list (vec (map #(symbol (str "b" %)) (range k))) `(throw (fn-arity-error ~spec ~k)))))
                (~'applyTo [~s] (if (= ~n (count ~s))
                                  (~impl (object-array ~s))
                                  (throw (fn-arity-error ~spec (count ~s)))))))))))

(def ^:private fn-ref-makers (fn-makers))

(defn- build-fn [{:keys [arity owner name sig flags call unit? bound] :as spec}]
  (let [p (rt/prepare call)
        inst (bound-instance bound)
        p (if inst (fn [^objects argv] (p (object-array (cons inst (seq argv))))) p)
        impl (if unit? (fn [argv] (p argv) kotlin.Unit/INSTANCE) p)]
    ((nth fn-ref-makers arity) (receiver-of inst) (r/jvm-class owner) name sig flags impl spec)))

(defn- build [spec]
  (case (:kind spec)
    :class (Reflection/getOrCreateKotlinClass ^Class (r/jvm-class (:class spec)))
    :prop (build-prop spec)
    :fn (build-fn spec)))

(def ^:private ^ConcurrentHashMap table
  "Documented cache: spec -> the reference. One entry for each declaration that a compiled form refers to."
  (ConcurrentHashMap.))

(defn const
  "The reference that the quoted `spec` describes; made the first time, the same object afterwards."
  [spec]
  (or (.get table spec)
      (let [v (build spec)]
        (or (.putIfAbsent table spec v) v))))

;; ---------------------------------------------------------------- compile time: the spec

(defn- uform [args] (str "(kt/ref " (str/join " " (map r/short-pr args)) ")"))

(defn- bound-error [args x why]
  (r/fail (str "kt: " (uform args) ": bound references are not supported (`user::email`, `obj::method`): `" (r/short-pr x) "` " why
               ". Write the type, e.g. (kt/ref users/User email), and pass the object to the reference when you call it.")))

(def ^:private primitive-classes
  {"kotlin/Int" Integer/TYPE "kotlin/Long" Long/TYPE "kotlin/Short" Short/TYPE "kotlin/Byte" Byte/TYPE
   "kotlin/Double" Double/TYPE "kotlin/Float" Float/TYPE "kotlin/Char" Character/TYPE "kotlin/Boolean" Boolean/TYPE})

(defn- resolve-type
  "{:type <ckway.types type> :class Class (boxed) :kclass Class (the class of X::class) :ns Namespace|nil} for the
  symbol `x` in `(kt/ref x ...)`, or a compile error (a bound reference, an unknown name)."
  [env args x]
  (when-not (symbol? x)
    (bound-error args x "is not a type name (a symbol)"))
  (when (and (nil? (namespace x)) (contains? env x))
    (bound-error args x "is a local, not a type"))
  (when (str/ends-with? (name x) "?")
    (r/fail (str "kt: " (uform args) ": `" x "` is a nullable type; Kotlin has no `T?::class` or `T?::member`")))
  (let [v (try (ns-resolve *ns* x) (catch Exception _ nil))
        kt-var? (and (var? v) (:kt/decls (meta v)))
        t (try (types/resolve-class *ns* x)
               (catch clojure.lang.ExceptionInfo e
                 (if (:kt/error (ex-data e))
                   (bound-error args x "is not a type that kt knows (a kt class var, a Kotlin default type name, or a class)")
                   (throw e))))
        object? (and kt-var? (:kt/object (meta v)) (not (:kt/class (meta v))))
        c (or (types/jvm-class t) (when (= "kotlin/Any" (:class t)) Object))]
    (when-not c
      (r/fail (str "kt: " (uform args) ": the class of `" x "` cannot be loaded")))
    {:type t :class c :kclass (get primitive-classes (:class t) c) :object? object?
     :ns (when kt-var? (.ns ^clojure.lang.Var v))}))

(defn- kt-namespaces
  "The kt namespaces the current namespace aliases."
  []
  (->> (vals (ns-aliases *ns*))
       (filter #(str/starts-with? (str (ns-name %)) "ckway.pkg."))
       distinct))

(defn- decls-named
  "The declarations of the vars named `var-name` in the namespaces."
  [nss var-name]
  (distinct (for [n nss
                  :let [v (get (ns-interns n) (symbol var-name))]
                  :when v
                  d (:kt/decls (meta v))]
              d)))

(defn- receiver-fits? [decl ^Class xc]
  (let [rs (:receivers decl)
        ^Class pc (:class (first (r/slots decl)))]
    (and (= 1 (count rs))
         (not (:companion-of (first rs)))
         pc (.isAssignableFrom pc xc))))

(defn- member? [decl] (boolean (some #(= :dispatch (:role %)) (:receivers decl))))

(defn- signatures [ds] (str/join "\n" (map #(str "    " (:signature %)) ds)))

(defn- pick-one
  "Exactly one declaration of `cands`, else a compile error. A member wins over an extension, an override
  over its original."
  [args what cands]
  (let [cands (if (some member? cands) (filter member? cands) cands)
        cands (map :decl (r/drop-overridden (map #(hash-map :decl %) cands)))]
    (case (count cands)
      0 nil
      1 (first cands)
      (r/fail (str "kt: " (uform args) ": " what " names " (count cands) " Kotlin declarations. Kotlin chooses by the expected type, "
                   "kt has none, so a reference must name exactly one:\n" (signatures cands))
              {:kt/candidates (map :signature cands)}))))

(defn- check-reference-supported! [args decl]
  (let [fl (:flags decl)]
    (when (:suspend fl)
      (r/fail (str "kt: " (uform args) ": a reference to a `suspend` function (a KSuspendFunction) is not supported yet\n  " (:signature decl))
              {:kt/not-supported "reference to a suspend function"}))
    (when (r/reified? decl)
      (r/fail (str "kt: " (uform args) ": `inline reified` functions cannot be referenced (they have no JVM method for a reference)\n  "
                   (:signature decl))))
    (when (and (:inline fl) (not (:jvm decl)))
      (r/not-supported "reference to an inline function without a JVM member" decl))
    (r/check-supported! decl [])))

(defn- entries [n] (vec (for [i (range n)] {:arg nil :info {} :idx i})))

(defn- jvm-sig [{:keys [name desc]}] (str name desc))

(defn- prop-spec
  "Spec of a reference to the property `decl` (one receiver, or none for a top-level property). `bound`: the JVM
  class name of the object that the reference is bound to (its receiver is then not a parameter), or nil."
  [args decl bound]
  (check-reference-supported! args decl)
  (let [nr (count (:receivers decl))
        top? (or (zero? nr) (not (member? decl)))
        getter (:getter decl)
        jvm (:jvm decl)
        a (set/assignable decl)
        read (r/plan decl (entries nr))
        write (when (:how a)
                (let [sd (set/setter-decl decl a)
                      {:keys [items]} (r/bind sd {:positional (entries (inc nr)) :named []})]
                  (r/plan sd items)))]
    {:kind :prop :arity (if bound (dec nr) nr) :mutable? (boolean write) :bound bound
     :owner (:owner decl) :name (:name decl)
     :sig (if getter (jvm-sig getter) (str (:name decl) ":" (:desc jvm)))
     :flags (if top? 1 0)
     :read read :write write}))

(defn- fn-spec
  "Spec of a reference to the function or constructor `decl`: the receiver (if any) and all parameters
  are the arguments of FunctionN; with `bound` (see `prop-spec`) the receiver is not."
  [args decl bound]
  (check-reference-supported! args decl)
  (let [recvs (:receivers decl)
        nr (count recvs)
        params (:params decl)
        arity (+ (if bound (dec nr) nr) (count params))
        ;; every parameter is written by name: a `vararg` then takes the array that Kotlin passes
        parsed {:positional (entries nr)
                :named (vec (map-indexed (fn [i p] [(:name p) {:arg nil :info {} :idx (+ nr i)}]) params))}
        {:keys [items error]} (r/bind decl parsed)
        _ (when error (r/fail (str "kt: " (uform args) ": " error)))
        ctor? (= :class (:kind decl))
        jvm (:jvm decl)]
    (when (> arity 19)
      (r/not-supported "a function reference with more than 19 parameters" decl))
    {:kind :fn :arity arity :bound bound
     :text (uform args) :sigtext (:signature decl) :defaults? (boolean (some :default? params))
     :pnames (vec (concat (map #(or (:name %) (if (= :dispatch (:role %)) "this" "receiver")) (if bound (rest recvs) recvs))
                          (map :name params)))
     :owner (:owner decl) :name (if ctor? "<init>" (:name decl))
     :sig (jvm-sig (if ctor? (assoc jvm :name "<init>") jvm))
     :flags (if (or ctor? (member? decl)) 0 1)
     :unit? (and (not ctor?) (= "kotlin/Unit" (:class (:return decl))))
     :call (r/plan decl items)}))

(defn- no-decl-error [args what x-name nss]
  (r/fail (str "kt: " (uform args) ": no " what " found. Looked in: "
               (str/join ", " (map #(str/replace (str (ns-name %)) #"^ckway\.pkg\." "") nss))
               ". (Is the package of the declaration required with kt/require in this namespace?)")))

(defn- spec-two
  "Spec of `(kt/ref X y)`."
  [env args]
  (let [[x y] args]
    (when-not (symbol? y)
      (r/fail (str "kt: " (uform args) ": the second argument must be `class`, a property name, or a function name with its "
                   "`.` prefix (`.greet`), got " (r/short-pr y))))
    (let [{:keys [class kclass object? ns] :as tx} (resolve-type env args x)]
      (cond
        (= 'class y) {:kind :class :class (.getName ^Class kclass)}

        :else
        (let [nss (distinct (concat (when ns [ns]) (kt-namespaces)))
              name-str (str y)
              fun? (str/starts-with? name-str ".")
              cands (filter #(and (= (if fun? :function :property) (:kind %)) (receiver-fits? % class))
                            (decls-named nss name-str))
              what (str (if fun? "function" "property") " `" name-str "` of `" x "`")
              decl (pick-one args what cands)]
          (when-not decl (no-decl-error args what name-str nss))
          (let [bound (when object? (.getName ^Class class))]
            (if fun? (fn-spec args decl bound) (prop-spec args decl bound))))))))

(defn- spec-one
  "Spec of `(kt/ref y)`: y is a kt var."
  [env args]
  (let [y (first args)
        v (when (and (symbol? y) (not (and (nil? (namespace y)) (contains? env y))))
            (try (ns-resolve *ns* y) (catch Exception _ nil)))]
    (when-not (and (var? v) (:kt/decls (meta v)))
      (bound-error args y "is not a kt var (a top-level function or property, or a class, of a package that kt/require made)"))
    (let [all (:kt/decls (meta v))
          cands (filter #(and (#{:function :property :class} (:kind %)) (empty? (:receivers %))) all)
          what (str "`" y "`")
          decl (pick-one args what cands)]
      (cond
        (nil? decl)
        (r/fail (str "kt: " (uform args) ": " what " has no declaration that `::" (name y) "` can name"
                     (if (some #(seq (:receivers %)) all)
                       (str "; it needs a receiver type, e.g. (kt/ref Type " (name y) ")")
                       "")
                     ". Kotlin declares:\n" (signatures all)))

        (= :property (:kind decl)) (prop-spec args decl nil)
        :else (fn-spec args decl nil)))))

(defn spec-of
  "The spec (plain data, see the namespace docstring) of the arguments `args` of `(kt/ref ...)`, or a
  compile error."
  [env args]
  (case (count args)
    1 (spec-one env args)
    2 (spec-two env args)
    (r/fail (str "kt: " (uform args) ": kt/ref takes (kt/ref X y) for X::y or (kt/ref y) for ::y"))))

(defn expand
  "Expansion of `(kt/ref args...)`."
  [env args]
  `(ckway.ref/const '~(spec-of env args)))

(defn form-info
  "Info (see `ckway.resolve`) of the form `(kt/ref ...)` for static typing of a call that takes it: the Kotlin
  reflection interface of the value. nil when the form is not valid (the expansion reports it)."
  [env form]
  (try
    (let [{:keys [kind arity mutable?]} (spec-of env (rest form))]
      {:class (case kind
                :class kotlin.reflect.KClass
                :fn kotlin.reflect.KFunction
                :prop (case [arity mutable?]
                        [1 false] kotlin.reflect.KProperty1 [1 true] kotlin.reflect.KMutableProperty1
                        [0 false] kotlin.reflect.KProperty0 [0 true] kotlin.reflect.KMutableProperty0))
       :upper true})
    (catch clojure.lang.ExceptionInfo e
      (when-not (:kt/error (ex-data e)) (throw e)))))
