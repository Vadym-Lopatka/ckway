(ns ckway.resolve
  "Selection of exactly one Kotlin declaration and the JVM call for it.

  Pure functions (parse, bind, choose, plan) are shared with the run-time path
  in `ckway.rt`.  Only `emit` and the `kt-call` macro run at compile time.

  Type arguments (`:<>`, DESIGN-2 rule 5): `choose` takes the pair out of the named arguments, keeps
  only the declarations with that number of type parameters (a call without `:<>` never selects a
  declaration with a reified type parameter) and returns the form as :tform. `expand` resolves it
  (`ckway.types`). For a reified declaration the call goes through a Kotlin bridge (`expand-reified`: a
  synthetic static declaration `call` whose parameters are the receivers and the supplied parameters,
  with the type arguments substituted, planned and emitted like any other); for another generic
  declaration the type arguments only change the static types (`typed-decl`).

  Vocabulary
    entry  {:arg form-or-value :info info :idx written-order}   one written argument
    item   an entry | {:omitted true} | {:vararg [entry ...]} | {:vararg-coll entry}
           one item per slot; slots are the receivers, then the parameters
    info   what is known about an argument: {:class Class :nil? :companion-of :lit :upper}
           `{}` means unknown (compile time only). A compile-time class of java.lang.Object (a
           local from `doseq`, `first`, `get`...) tells nothing, so it is `{}` too (`bound-info`).
           :upper marks a :class that is only the compile-time type of a local, a hint or a call
           result (`bound-info`): the value can be an instance of a subclass. Clojure infers such a
           type for a `loop` variable whose recur value is not a number it knows (`Number`).
           :lit is :int for an integer literal that fits Kotlin Int and :long for one that does
           not (Kotlin types literals by size)."
  (:require [clojure.string :as str]
            [ckway.bridge :as bridge]
            [ckway.meta :as meta]
            [ckway.types :as types])
  (:import [clojure.lang Compiler$LocalBinding]
           [java.lang.invoke MethodType]
           [java.lang.reflect Method Modifier]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- errors

(defn fail
  "Throw a kt error. Messages start with `kt:` and carry the Kotlin signature."
  ([msg] (fail msg {}))
  ([msg data] (throw (ex-info msg (assoc data :kt/error true)))))

(defn not-supported*
  [feature signature]
  (fail (str "kt: " feature " is not supported yet (" signature ")")
        {:kt/not-supported feature}))

(defn not-supported
  [feature decl]
  (not-supported* feature (:signature decl)))

(defn short-pr [x]
  (let [s (pr-str x)] (if (> (count s) 60) (str (subs s 0 57) "...") s)))

(defn- call-text [var-name entries]
  (str "(" var-name (when (seq entries) " ") (str/join " " (map short-pr entries)) ")"))

(defn- signatures [decls]
  (str/join "\n" (map #(str "    " (:signature %)) decls)))

;; ---------------------------------------------------------------- classes

(def ^:private prim-classes
  {"int" Integer/TYPE "long" Long/TYPE "short" Short/TYPE "byte" Byte/TYPE "double" Double/TYPE
   "float" Float/TYPE "boolean" Boolean/TYPE "char" Character/TYPE "void" Void/TYPE})

(def jvm-class
  "Class for a Class.getName style name, or nil if it cannot be loaded.
  Documented cache (memoize)."
  (memoize
   (fn [^String n]
     (when n
       (or (prim-classes n)
           (try (Class/forName n false (clojure.lang.RT/baseLoader))
                (catch ClassNotFoundException _ nil)
                (catch LinkageError _ nil)))))))

(defn box-class ^Class [^Class c]
  (case (.getName c)
    "int" Integer "long" Long "short" Short "byte" Byte "double" Double "float" Float
    "boolean" Boolean "char" Character "void" Void c))

(def ^:private integral-classes #{Long Integer Short Byte clojure.lang.BigInt java.math.BigInteger})
(def ^:private float-classes #{Double Float})

(defn integral-class? [^Class c] (contains? integral-classes (when c (box-class c))))

;; ---------------------------------------------------------------- argument info

(defn value-info
  "Info for a run-time value."
  [v]
  (cond
    (nil? v) {:nil? true}
    (and (fn? v) (:kt/class (meta v))) {:class (class v) :companion-of (:kt/class (meta v))}
    :else {:class (class v)}))

(defn tag->class [env-ns tag]
  (cond
    (class? tag) tag
    (string? tag) (or (prim-classes tag) (jvm-class tag))
    (symbol? tag) (or (prim-classes (name tag))
                      (let [r (try (ns-resolve env-ns tag) (catch Exception _ nil))] (when (class? r) r)))))

(defn- local-class [^Compiler$LocalBinding lb]
  (try (when (.hasJavaClass lb) (.getJavaClass lb)) (catch Throwable _ nil)))

(defn- bound-info
  "Info for an argument whose compile-time class is `c`, which is only an upper bound of the class
  of the value. No class, or Object, says nothing: unknown. (The static type of a local is Object
  whenever Clojure cannot infer more; it is not a mismatch with any parameter type.)"
  [c]
  (if (and c (not= Object c)) {:class c :upper true} {}))

(defn- literal-info
  "Info for a literal. An integer literal is a Long to Clojure; Kotlin types it by size (Int if it fits)."
  [form]
  (cond
    (string? form) {:class String}
    (integer? form) (let [c (class form)]
                      (cond-> {:class c}
                        (= Long c) (assoc :lit (if (<= Integer/MIN_VALUE form Integer/MAX_VALUE) :int :long))))
    (float? form) {:class Double}
    (char? form) {:class Character}
    (boolean? form) {:class Boolean}
    (vector? form) {:class clojure.lang.APersistentVector}
    (map? form) {:class clojure.lang.APersistentMap}
    (set? form) {:class clojure.lang.APersistentSet}))

(def ^:private cast-classes
  "`(long x)` and friends have a known class."
  {"int" Integer "long" Long "short" Short "byte" Byte "double" Double "float" Float "char" Character "boolean" Boolean})

(defn- core-var? [env head]
  (and (symbol? head)
       (or (nil? (namespace head)) (= "clojure.core" (namespace head)))
       (not (contains? env head))
       (let [r (try (ns-resolve *ns* head) (catch Exception _ nil))]
         (and (var? r) (= 'clojure.core (ns-name (.ns ^clojure.lang.Var r)))))))

(declare form-info parse-args choose unsupported return-class typed-return-class vc-conv check-result?)

(defn- ref-head?
  "Is `head` the `kt/ref` macro? (its result is a Kotlin reflection object of a known class, see `ckway.ref`)"
  [env head]
  (and (symbol? head)
       (not (and (nil? (namespace head)) (contains? env head)))
       (let [r (try (ns-resolve *ns* head) (catch Exception _ nil))]
         (and (var? r) (= 'ckway.core (ns-name (.ns ^clojure.lang.Var r))) (= 'ref (.sym ^clojure.lang.Var r))))))

(defn- reify-head?
  "Is `head` the `kt/reify` macro? (its expansion is `(new <generated class> ...)`, see `ckway.reify/form-info`)"
  [env head]
  (and (symbol? head)
       (not (and (nil? (namespace head)) (contains? env head)))
       (let [r (try (ns-resolve *ns* head) (catch Exception _ nil))]
         (and (var? r) (= 'ckway.core (ns-name (.ns ^clojure.lang.Var r))) (= 'reify (.sym ^clojure.lang.Var r))))))

(defn- nested-call-info
  "Info for a form that is itself a call of a kt var: when exactly one declaration fits, its
  Kotlin return type. Errors are left for the expansion of that inner call."
  [env form]
  (let [head (first form)
        v (when (and (symbol? head) (not (and (nil? (namespace head)) (contains? env head))))
            (try (ns-resolve *ns* head) (catch Exception _ nil)))]
    (if-not (and (var? v) (:kt/decls (meta v)) (:inline (meta v)))
      {}
      (let [decls (:kt/decls (meta v))
            var-name (str (.sym ^clojure.lang.Var v))]
        (try
          (let [parsed (parse-args var-name decls (rest form) (fn [f] {:arg f :info (form-info env f)}))
                r (choose var-name decls parsed true)]
            (bound-info (and (:decl r)
                             (if (contains? r :tform)
                               (typed-return-class (:decl r) (types/resolve-forms *ns* (:tform r)))
                               (return-class (:decl r))))))
          (catch clojure.lang.ExceptionInfo e
            (if (:kt/error (ex-data e)) {} (throw e))))))))

(defn form-info
  "Info for an argument form at compile time. Uses literals, :tag hints, local binding
  classes, kt class/object vars, `(long x)`-style casts, and calls of kt vars with one
  matching declaration. Everything else is unknown."
  [env form]
  (let [tagc (some->> (:tag (meta form)) (tag->class *ns*))
        head (when (seq? form) (first form))]
    (cond
      (nil? form) {:nil? true}
      tagc (bound-info tagc)
      (literal-info form) (literal-info form)
      (symbol? form)
      (if-let [lb (get env form)]
        (bound-info (local-class lb))
        (let [r (try (ns-resolve *ns* form) (catch Exception _ nil))]
          (cond
            (and (var? r) (:kt/class (meta r))) {:class clojure.lang.AFn :companion-of (:kt/class (meta r))}
            (and (var? r) (:kt/object (meta r))) (if-let [c (jvm-class (:kt/object (meta r)))] {:class c} {})
            (and (var? r) (tag->class *ns* (:tag (meta r)))) (bound-info (tag->class *ns* (:tag (meta r))))
            :else {})))
      (and (symbol? head) (#{'fn 'fn* 'clojure.core/fn} head)) {:class clojure.lang.AFunction}
      (and (symbol? head) (= 'new head))
      (if-let [c (tag->class *ns* (second form))] {:class c} {})
      (and (symbol? head) (str/ends-with? (name head) ".") (nil? (namespace head)))
      (if-let [c (tag->class *ns* (symbol (subs (name head) 0 (dec (count (name head))))))] {:class c} {})
      (and (seq? form) (core-var? env head) (cast-classes (name head))) {:class (cast-classes (name head))}
      (and (seq? form) (ref-head? env head)) (or ((requiring-resolve 'ckway.ref/form-info) env form) {})
      (and (seq? form) (reify-head? env head)) (if-let [c ((requiring-resolve 'ckway.reify/form-info) form)] {:class c} {})
      (seq? form) (nested-call-info env form)
      :else {})))

;; ---------------------------------------------------------------- parse

(defn parse-args
  "Split written arguments (already wrapped as entries) into positional and named.
  A keyword literal always names the parameter of the next argument, and everything
  after the first keyword is named (rule 4). `->entry` makes an entry from a form."
  [var-name decls forms ->entry]
  (let [entries (vec (map-indexed (fn [i f] (assoc (->entry f) :idx i)) forms))
        [pos named] (split-with #(not (keyword? (:arg %))) entries)
        bad (fn [msg] (fail (str "kt: " (call-text var-name (map :arg entries)) ": " msg
                                 "\n  Kotlin declares:\n" (signatures decls))))]
    (loop [named (vec named) out []]
      (cond
        (empty? named) {:positional (vec pos) :named out}
        (not (keyword? (:arg (first named))))
        (bad (str "positional argument " (short-pr (:arg (first named))) " follows a named argument. "
                  "Put all positional arguments before the first keyword."))
        (< (count named) 2)
        (bad (str "keyword " (short-pr (:arg (first named))) " names a parameter but has no value. "
                  "A keyword literal always names the next argument; to pass a keyword as a value, put it in a local."))
        :else (recur (subvec named 2) (conj out [(name (:arg (first named))) (second named)]))))))

;; ---------------------------------------------------------------- function types

(def ^:private kotlin-kinds
  {"kotlin/Int" :int "kotlin/Long" :long "kotlin/Short" :short "kotlin/Byte" :byte "kotlin/Double" :double
   "kotlin/Float" :float "kotlin/Char" :char "kotlin/Boolean" :bool "kotlin/Unit" :unit})

(defn type-descriptor
  "Plain-data descriptor of a Kotlin <type> for converting values at a function boundary:
  {:k :int|:long|:short|:byte|:double|:float|:char|:bool|:unit|:obj|:fi|:fn  :nullable? true}.
  :fn has :arity, :params, :ret (descriptors) and :text (the Kotlin type, for errors), :fi has :class.
  A value class is :obj: in the generic FunctionN slots it is the boxed object, so it passes unchanged.
  A suspend function type is :fn too, with :suspend? true: :arity counts the Kotlin parameters, the
  JVM type is Function<arity+1> (the Continuation is the last parameter). Emitted code embeds it."
  [t]
  (cond-> (cond
            (:fn-type t)
            (let [f (:fn-type t)]
              {:k :fn :arity (:arity f) :suspend? (:suspend? f) :params (mapv type-descriptor (:args f)) :ret (type-descriptor (:return f))
               :text (meta/type-text (assoc t :nullable? false))})
            (:fun-interface? t) {:k :fi :class (meta/internal->binary (:class t))}
            (kotlin-kinds (:class t)) {:k (kotlin-kinds (:class t))}
            ;; :cls = the JVM class that a value must have (a value class: the box), :text = the Kotlin type,
            ;; for the checks and messages of `ckway.rt/->kotlin`; absent for a type parameter, `Any` and the like
            :else (let [cls (if-let [vc (:value-class t)]
                              (:class vc)
                              (some-> ^Class (types/jvm-class t) .getName))]
                    (cond-> {:k :obj}
                      cls (assoc :cls cls :text (meta/type-text (assoc t :nullable? false)))
                      (and cls (:value-class t)) (assoc :vc? true))))
    (:nullable? t) (assoc :nullable? true)))

(def ^:private jvm-kinds
  {"int" :int "long" :long "short" :short "byte" :byte "double" :double "float" :float "char" :char
   "boolean" :bool "java.lang.Integer" :int "java.lang.Long" :long "java.lang.Short" :short
   "java.lang.Byte" :byte "java.lang.Double" :double "java.lang.Float" :float
   "java.lang.Character" :char "java.lang.Boolean" :bool})

(defn jvm-descriptor
  "Descriptor (as `type-descriptor`) of a JVM return type name, or nil when no conversion applies."
  [^String cname]
  (when-let [k (jvm-kinds cname)]
    (cond-> {:k k} (not (prim-classes cname)) (assoc :nullable? true))))

(def sam-of
  "The single abstract method of the `fun interface` with JVM name `iface`:
  {:name :params [jvm type names] :return jvm type name :suspend? bool
   :delegates [{:name :params :impls \"I$DefaultImpls\"}]}, or nil if there is not exactly one.
  `delegates` are abstract methods whose body is in `I$DefaultImpls` (Kotlin without JVM default
  methods): an implementation must call them. Read by reflection. Documented cache (memoize)."
  (memoize
   (fn [^String iface]
     (when-let [^Class c (jvm-class iface)]
       (let [impls (jvm-class (str iface "$DefaultImpls"))
             delegating? (fn [^Method m]
                           (boolean (and impls (some #(and (= (.getName m) (.getName ^Method %))
                                                           (Modifier/isStatic (.getModifiers ^Method %)))
                                                     (.getDeclaredMethods ^Class impls)))))
             names (fn [^Method m] (mapv #(.getName ^Class %) (.getParameterTypes m)))
             desc (fn [^Method m] (.toMethodDescriptorString (MethodType/methodType (.getReturnType m) (.getParameterTypes m))))
             impls-desc (fn [^Method m]
                          (let [want (vec (cons c (.getParameterTypes m)))]
                            (some (fn [^Method s] (when (and (= (.getName m) (.getName s)) (Modifier/isStatic (.getModifiers s))
                                                             (= want (vec (.getParameterTypes s))))
                                                    (.toMethodDescriptorString (MethodType/methodType (.getReturnType s) (.getParameterTypes s)))))
                                  (.getDeclaredMethods ^Class impls))))
             {dels true sams false} (group-by delegating? (filter #(Modifier/isAbstract (.getModifiers ^Method %))
                                                                  (.getMethods c)))]
         (when (= 1 (count sams))
           (let [^Method m (first sams)]
             {:name (.getName m) :params (names m) :return (.getName (.getReturnType m)) :desc (desc m)
              :suspend? (= "kotlin.coroutines.Continuation" (last (names m)))
              :delegates (mapv (fn [m] {:name (.getName ^Method m) :params (names m) :impls (.getName ^Class impls)
                                        :desc (desc m) :impls-desc (impls-desc m)})
                               dels)})))))))

(def ^:private sam-kdecl
  "The Kotlin declaration (read from the Kotlin metadata) of the JVM method `sam` ({:name :desc ...}) of the
  interface `iface`, or nil (a Java interface). Matched by the JVM name and descriptor, so a mangled name
  (`adjust-TKBkzuY`) finds `adjust`. Documented cache."
  (memoize
   (fn [iface sam]
     (first (filter #(and (= :function (:kind %)) (= (:name sam) (-> % :jvm :name)) (= (:desc sam) (-> % :jvm :desc)))
                    (meta/class-members iface))))))

(defn- sam-return-td-for
  "Type descriptor of the return type of the declaration `d` of the interface type `t` with type arguments
  (`Conv<Int>`): the method's `T` is replaced by the argument, so a Clojure Long result goes to Kotlin as an
  Int. nil when `t` gives no argument that is a known type."
  [d t]
  (when (some #(and (:class %) (not (:type-param %))) (:args t))
    (let [names (map :type-param (:args (:type (first (filter #(= :dispatch (:role %)) (:receivers d))))))]
      (when (and (seq names) (= (count names) (count (:args t))))
        (type-descriptor (types/subst (zipmap names (:args t)) (:return d)))))))

(defn- sam-slots
  "One map per JVM parameter of the SAM (the Continuation of a suspend one left out) from its Kotlin declaration
  `kd`: {:t <kotlin type> :jvm \"long\"}, or nil when the declaration does not fit the JVM method."
  [kd sam]
  (let [jts (cond-> (vec (:params sam)) (:suspend? sam) pop)
        ks (concat (for [r (:receivers kd) :when (not= :dispatch (:role r))] (:type r))
                   (map :type (:params kd)))]
    (when (= (count jts) (count ks))
      (mapv (fn [t jt] {:t t :jvm jt}) ks jts))))

(defn- sam-hint [{:keys [t jvm]}]
  (if-let [vc (:value-class t)]
    (:class vc)
    (let [c (jvm-class jvm)]
      (when (and c (not (.isArray ^Class c)) (not= Object (box-class c))) (.getName (box-class c))))))

(defn- sam-class-info
  "How a generated class (not a Clojure `reify`) implements the SAM: needed when its JVM name has a `-` (a value class
  in its signature mangles it; a Clojure `reify` cannot define such a name) or a value class crosses its slots.
  => {:params [{:vc conv} | {:td td} | {}] :ret {:vc conv} | nil}, or nil when `reify` does it."
  [kd sam slots]
  (let [params (mapv (fn [{:keys [t jvm]}]
                       (let [td (type-descriptor t)]
                         (cond (vc-conv t jvm) {:vc (assoc (vc-conv t jvm) :name "parameter")}
                               (#{:fn :unit} (:k td)) {:td td}
                               :else {})))
                     slots)
        ret (when-not (:suspend? sam) (vc-conv (:return kd) (:return sam)))]
    (when (or (not (bridge/clojure-name? (:name sam))) ret (some :vc params))
      {:params params :ret (when ret {:vc ret})
       :text (str (:name kd) "(" (str/join ", " (map #(meta/type-text (:t %)) slots)) ")")})))

(defn fi-spec
  "Adapter spec of a `fun interface` with JVM name `iface` (see `adapter-spec`). The :sam of a
  `suspend` method also has :ret-td, the descriptor of the Kotlin return type. `t` (optional) is the Kotlin
  type, whose type arguments, when known, replace the interface's `T` in that return type.
  When the Kotlin declaration of the method is known, :sam has :hints (the class that each parameter has for the
  Clojure function, a value class is the box) and, when a Clojure `reify` cannot implement the method (a value
  class in its signature), :cls (see `sam-class-info`)."
  ([iface signature] (fi-spec iface signature nil))
  ([iface signature t]
   (let [sam (sam-of iface)
         kd (when sam (sam-kdecl iface sam))
         rtd (some-> kd :return type-descriptor)
         sam (cond-> sam (and kd (or (:suspend? sam) (:cls rtd))) (assoc :ret-td rtd))
         sam (if-let [td (and sam kd t (sam-return-td-for kd t))] (assoc sam :ret-td td) sam)
         slots (when kd (sam-slots kd sam))
         sam (cond-> sam
               slots (assoc :hints (mapv sam-hint slots))
               slots (as-> s (if-let [ci (sam-class-info kd sam slots)] (assoc s :cls ci) s)))]
     (cond-> {:kind :fi :iface iface :sam sam}
       (nil? sam) (assoc :feature "fun interface without a single abstract method" :sig signature)))))

(defn adapter-spec
  "How a Clojure function is adapted at a parameter of Kotlin type `t` (JVM type `jvm-type`), or nil
  when the parameter is not a function type or fun interface.
    {:kind :fn :iface \"kotlin.jvm.functions.Function1\" :td <type-descriptor>}
    {:kind :fi :iface \"fx.Pred\" :sam <sam-of>}
  A spec with :feature (and :sig) names a feature that is not supported yet; it is an error only
  if a Clojure function has to be adapted."
  [t jvm-type signature]
  (when jvm-type
    (cond
      (:fn-type t) (let [f (:fn-type t)]
                     (cond-> {:kind :fn :iface jvm-type :td (type-descriptor t)}
                       (> (:arity f) 20) (assoc :feature "function types with more than 20 parameters" :sig signature)))
      (:fun-interface? t) (fi-spec jvm-type signature t))))

;; ---------------------------------------------------------------- slots

(defn- value-class-of
  "The boxed JVM class of a value-class type, or nil."
  ^Class [t]
  (some-> t :value-class :class jvm-class))

(defn- nullable-type?
  "Can a Kotlin value of type `t` be null? A type parameter whose bounds are all nullable (the default
  bound is `Any?`) can: `fun <T> echo(x: T)` takes nil."
  [decl t]
  (boolean (or (:nullable? t)
               (when-let [n (:type-param t)]
                 (let [tp (first (filter #(= n (:name %)) (concat (:type-params decl) (:class-type-params decl))))]
                   (and tp (every? :nullable? (:bounds tp))))))))

(defn slots
  "One slot per receiver, then per parameter: {:name :class :nullable? :companion-of :adapt :kotlin-type}.
  `:class` is the class of the Clojure argument: for a value-class type it is the value class
  itself (the box), whatever the JVM slot holds."
  [decl]
  (let [kt-class-of (fn [t] (when-let [c (:class t)] (meta/internal->binary c)))]
    (vec
     (concat
      (for [r (:receivers decl)
            :let [t (:type r)]]
        {:name (or (:name r) (if (= :dispatch (:role r)) "this" "receiver"))
         :role (:role r)
         :class (or (value-class-of t)
                    (jvm-class (if (= :dispatch (:role r)) (:owner decl) (or (:jvm-type r) (kt-class-of t)))))
         :nullable? (:nullable? t) :companion-of (:companion-of r) :kotlin-type t})
      (for [p (:params decl)
            :let [t (:type p)]]
        {:name (:name p) :class (or (value-class-of t) (jvm-class (:jvm-type p))) :nullable? (nullable-type? decl t)
         :vararg? (:vararg? p) :default? (:default? p)
         :elem-class (value-class-of (:vararg-elem p))
         :elem-nullable? (:nullable? (:vararg-elem p))
         :adapt (adapter-spec t (:jvm-type p) (:signature decl))
         :kotlin-type t})))))

;; ---------------------------------------------------------------- bind

(defn bind
  "Bind parsed args to the slots of `decl`. => {:items [...]} or {:error text}."
  [decl {:keys [positional named]}]
  (let [recvs (:receivers decl)
        params (:params decl)
        nr (count recvs)
        pnames (mapv :name params)
        vidx (first (keep-indexed #(when (:vararg? %2) %1) params))]
    (if (< (count positional) nr)
      {:error (str "expects " nr " receiver argument" (when (> nr 1) "s") " first ("
                   (str/join ", " (map #(name (:role %)) recvs)) "), got " (count positional))}
      (let [rest-pos (drop nr positional)
            nfixed (or vidx (count params))
            fixed (vec (take nfixed rest-pos))
            extra (vec (drop nfixed rest-pos))]
        (if (and (nil? vidx) (seq extra))
          {:error (str "too many arguments: " (count positional) " given, at most "
                       (+ nr (count params)) " expected")}
          (let [step (fn [acc [n entry]]
                       (let [i (.indexOf ^java.util.List pnames n)]
                         (cond
                           (neg? i) (reduced {:error (str "unknown parameter name `" n "`. Parameters: "
                                                          (if (seq pnames) (str/join ", " pnames) "(none)") ".")})
                           (or (contains? (:bound acc) i) (and (= i vidx) (seq extra)))
                           (reduced {:error (str "parameter `" n "` given twice")})
                           :else (update acc :bound assoc i entry))))
                r (reduce step {:bound (zipmap (range) fixed)} named)]
            (if (:error r)
              r
              (let [items (for [[i p] (map-indexed vector params)]
                            (cond
                              (and (= i vidx) (seq extra)) {:vararg extra}
                              (contains? (:bound r) i) (if (= i vidx) {:vararg-coll (get (:bound r) i)} (get (:bound r) i))
                              (= i vidx) {:vararg []}
                              (:default? p) {:omitted true}
                              :else {:missing (:name p)}))]
                (if-let [m (some :missing items)]
                  {:error (str "missing required parameter `" m "`. Pass it positionally or as `:" m "`.")}
                  {:items (vec (concat (take nr positional) items))})))))))))

;; ---------------------------------------------------------------- applicability

(defn- item-entries [item]
  (cond (:omitted item) [] (:vararg item) (:vararg item) (:vararg-coll item) [] :else [item]))

(defn- numeric-tier
  "Tier (0 best) of passing an argument of class `ac` to a numeric parameter of class `pc`, or nil.
  `lit` is the literal kind of the argument (see `literal-info`): Kotlin types an integer literal
  by its size, so one that fits Int prefers Int, one that does not fit is a Long."
  [^Class pc ^Class ac lit]
  (let [pb (box-class pc)]
    (cond
      (= :long lit) (cond (= pb Long) 0 (contains? float-classes pb) 2)
      (= :int lit) (cond (= pb Integer) 0 (= pb Long) 1
                         (or (contains? #{Short Byte Character} pb) (contains? float-classes pb)) 2)
      (= pb ac) 0
      (and (= pb Long) (contains? #{clojure.lang.BigInt java.math.BigInteger} ac)) 1
      (contains? #{Long Integer Short Byte} pb) (when (integral-class? ac) 2)
      (contains? float-classes pb) (when (or (integral-class? ac) (contains? float-classes ac) (isa? ac Number)) 2)
      (= pb Character) (when (integral-class? ac) 2))))

(defn- lit-note [info]
  (when (= :long (:lit info)) " (an integer literal that does not fit Int)"))

(declare applicability*)

(defn- could-be?
  "Can a value whose compile-time class is the upper bound `ac` be an instance of `pc`? True when
  `ac` is a supertype of `pc` (Number for an Int, a collection interface for a List), or when a
  subclass of `ac` could implement the interface `pc` (or the other way round)."
  [^Class pc ^Class ac]
  (let [pb (box-class pc)]
    (or (.isAssignableFrom ac pb)
        (and (.isInterface pb) (not (Modifier/isFinal (.getModifiers ac))))
        (and (.isInterface ac) (not (Modifier/isFinal (.getModifiers pb)))))))

(defn applicability
  "How well `info` fits `slot`: [:ok tier] (0 best), [:unknown], [:unknown :upper], [:adapter] or
  [:no reason]. A class that is only an upper bound (`:upper`) is no mismatch if the value could
  still fit: that is [:unknown :upper], which a candidate without any doubt beats (see `choose`)."
  [slot info]
  (let [r (applicability* slot info)
        ^Class pc (:class slot)
        ^Class ac (:class info)]
    (if (and (= :no (first r)) (:upper info) pc ac (could-be? pc ac))
      [:unknown :upper]
      r)))

(defn- applicability*
  [slot info]
  (let [^Class pc (:class slot)
        ^Class ac (some-> (:class info) box-class)]
    (cond
      (:nil? info) (if (and (:nullable? slot) (not (some-> pc .isPrimitive)))
                     [:ok 0]
                     [:no (str "`nil` passed to non-nullable `" (:name slot) "`")])
      (:companion-of slot) (cond (= (:companion-of slot) (:companion-of info)) [:ok 0]
                                 (nil? ac) [:unknown]
                                 (and pc (.isAssignableFrom pc ac)) [:ok 1]
                                 :else [:no (str "`" (:name slot) "` expects the class var of " (:companion-of slot))])
      (nil? pc) [:ok 3]
      (nil? ac) [:unknown]
      (.isPrimitive pc) (if-let [t (if (= Boolean (box-class pc)) (when (= ac Boolean) 0) (numeric-tier pc ac (:lit info)))]
                          [:ok t]
                          [:no (str "`" (:name slot) "` is " (.getName pc) " but got " (.getSimpleName ac) (lit-note info))])
      (contains? #{Integer Long Short Byte Double Float Character} pc)
      (if-let [t (numeric-tier pc ac (:lit info))]
        [:ok t]
        [:no (str "`" (:name slot) "` is " (.getSimpleName pc) " but got " (.getSimpleName ac) (lit-note info))])
      (.isAssignableFrom pc ac) [:ok (cond (= pc ac) 0 (= pc Object) 3 :else 1)]
      (and (:adapt slot) (isa? ac clojure.lang.IFn)) [:adapter (:feature (:adapt slot))]
      :else [:no (str "`" (:name slot) "` is " (.getSimpleName pc) " but got " (.getSimpleName ac))])))

(defn- vararg-slot [slot]
  (let [c (:class slot)]
    (assoc slot :class (or (:elem-class slot) (some-> ^Class c .getComponentType)) :vararg? false
           :nullable? (:elem-nullable? slot))))

(defn check-items
  "Applicability results, in slot order, one per written argument."
  [slots items]
  (vec
   (mapcat
    (fn [slot item]
      (cond
        (:omitted item) []
        (:vararg-coll item) []
        (:vararg item) (map #(applicability (vararg-slot slot) (:info %)) (:vararg item))
        :else [(applicability slot (:info item))]))
    slots items)))

(defn- tiers [checks] (mapv #(if (= :ok (first %)) (second %) 0) checks))

(defn- dominates? [a b]
  (and (every? true? (map <= a b)) (some true? (map < a b))))

(defn- member? [decl] (boolean (some #(= :dispatch (:role %)) (:receivers decl))))

;; ---------------------------------------------------------------- unsupported

(defn unsupported
  "Name of the feature that blocks calling `decl` in this step, or nil."
  [decl]
  (let [fl (:flags decl)]
    (cond
      (and (:inline fl) (some :reified? (:type-params decl)) (not= :function (:kind decl))) "inline reified properties"
      (:inner fl) "inner-class constructors"
      (nil? (:jvm decl)) "declarations without a JVM member")))

(defn vc-conv
  "How a value-class value crosses a JVM slot of type `jt` (a JVM type name): nil when the slot
  holds the value class itself (the object passes unchanged: `Uid?` over a primitive, a generic
  position) or the type is no value class; else {:vc <value-class description> :nullable? bool}
  (the slot holds the underlying value: unbox an argument, box a result). The decision compares
  the JVM slot with the Kotlin type; nothing is guessed from the name."
  [t jt]
  (when-let [vc (:value-class t)]
    (when (and jt (not= jt (:class vc)))
      {:vc vc :nullable? (boolean (:nullable? t))})))

(defn- return-jvm-name
  "JVM name of the type that the JVM member of `decl` returns, or nil."
  [decl]
  (let [jvm (:jvm decl)]
    (when (and jvm (not (:field jvm)))   ; the assignment of a field (`ckway.set`) has no method descriptor
      (case (:kind decl)
        :function (:return (meta/desc-types (:desc jvm)))
        :property (:return (meta/desc-types (if-let [g (:getter decl)] (:desc g) (str "()" (:desc jvm)))))
        nil))))

(defn- return-conv
  "Conversion of the result of `decl` (see `vc-conv`); the constructor of a value class returns
  the underlying value."
  [decl]
  (cond
    (and (= :class (:kind decl)) (:value-class decl)) {:vc (:value-class decl) :nullable? false}
    ;; the JVM result of a suspend function is Object: a value class is the object itself
    (:suspend (:flags decl)) nil
    :else (vc-conv (:return decl) (return-jvm-name decl))))

(defn- return-class
  "Boxed JVM class of the value that a call of `decl` returns, when it is known and more specific
  than Object. A function-typed result is wrapped at run time, so it has no class."
  [decl]
  (when-not (or (unsupported decl) (:no-constructor (:flags decl)))
    (if-let [{:keys [vc]} (return-conv decl)]
      (jvm-class (:class vc))
      (if-let [rc (:result-class decl)]
        (some-> (jvm-class rc) box-class)
        (let [jvm (:jvm decl)
              name (case (:kind decl)
                     (:class :object :enum-entry) (:owner decl)
                     :function (when-not (:field jvm) (:return (meta/desc-types (:desc jvm))))
                     :property (:return (meta/desc-types (if-let [g (:getter decl)] (:desc g) (str "()" (:desc jvm))))))
              ret (:return decl)]
          (when (and name (not= "void" name) (not (:fn-type ret)) (not= "kotlin/Unit" (:class ret)))
            (let [c (jvm-class name)]
              (when (and c (not= Object c)) (box-class c)))))))))

(defn no-constructor?
  "Is `decl` the placeholder declaration of a class var that cannot be called as a constructor?"
  [decl]
  (contains? (:flags decl) :no-constructor))

(defn no-constructor-message
  "The error for a call of a class var that has no public constructor: what the class is, and what to do
  instead when that is cheap to say. `decl` is the :no-constructor declaration; `call` the call as text, or nil."
  [decl call]
  (let [fl (:flags decl)
        name (:name decl)
        alias-type (when (:alias fl) (meta/type-text (:return decl)))]
    (str "kt: "
         (if alias-type
           (str "`" name "` is a type alias of `" alias-type "`, not a class: it cannot be called as a constructor. "
                "A type alias like this is for type forms (`:<>`).")
           (str "`" name "` has no public constructor: it is "
                (cond (:enum fl) "an enum class" (:interface fl) "an interface" (:sealed fl) "a sealed class"
                      (:abstract fl) "an abstract class" :else "a class whose constructors are not public")
                "."
                (cond (:enum fl) (str " Use one of its entries" (when (seq (:entries decl)) (str ": " (str/join ", " (:entries decl)))) ".")
                      (:interface fl) " Implement it with kt/reify, or call a function that returns one."
                      (:abstract fl) " Make an instance of a subclass, or call a function that returns one."
                      (seq (:factories decl))
                      (let [fs (:factories decl)]
                        (str " Its companion offers: " (str/join ", " (take 8 fs)) (when (> (count fs) 8) ", ...")
                             ". Call one with the class var first: (alias/" (first fs) " alias/" name " ...)."))
                      :else " Look for a companion or top-level function that makes one.")))
         (when call (str "\n  Called as: " call))
         (when alias-type (str "\n  Kotlin declares: " (:signature decl))))))

(defn check-supported!
  "Throw for features that are not supported yet. `checks` as from `check-items`."
  [decl checks]
  (when (no-constructor? decl)
    (fail (no-constructor-message decl nil)))
  (when-let [f (unsupported decl)]
    (not-supported f decl))
  (when-let [feature (some #(when (= :adapter (first %)) (second %)) checks)]
    (not-supported feature decl)))

;; ---------------------------------------------------------------- Kotlin specificity

(defn- mutable-kotlin? [t] (boolean (some-> (:class t) (str/includes? "/Mutable"))))

(defn- slot-subtype?
  "Is a value of the parameter slot `sx` always accepted where `sy` is declared (Kotlin: `sx` is a subtype of `sy`)?
  `T` is a subtype of `T?`; the JVM classes decide the rest, plus Kotlin's mapped types: `MutableList` is a subtype
  of `List`, not the other way round, though both are java.util.List. A type that is no class (`T`) is `Any?`."
  [sx sy]
  (let [cx (or (some-> ^Class (:class sx) box-class) Object)
        cy (or (some-> ^Class (:class sy) box-class) Object)
        kx (:kotlin-type sx) ky (:kotlin-type sy)]
    (boolean
     (and (or (:nullable? sy) (not (:nullable? sx)))
          (.isAssignableFrom cy cx)
          ;; a read-only Kotlin collection is not a subtype of a mutable one
          (not (and (mutable-kotlin? ky) (not (mutable-kotlin? kx)) (:class kx)))))))

(defn- entry-slots
  "{entry-idx slot} of the written arguments of the candidate `b` ({:decl :items}) that Kotlin compares when it
  chooses the most specific candidate: the extension and context receivers and the parameters (the dispatch
  receiver is the same object for every member and does not count). An element of a `vararg` has the slot of its
  element type. A collection passed as a whole (`:vararg-coll`) is not compared."
  [b]
  (into {}
        (mapcat (fn [slot item]
                  (cond
                    (= :dispatch (:role slot)) []
                    (:vararg item) (map (fn [e] [(:idx e) (vararg-slot slot)]) (:vararg item))
                    (or (:omitted item) (:vararg-coll item)) []
                    :else [[(:idx item) slot]]))
                (slots (:decl b)) (:items b))))

(defn- at-least-as-specific?
  "Kotlin: candidate `x` is at least as specific as `y` when each parameter of `x` that takes a written argument
  has a type that is a subtype of the type of the parameter of `y` that takes the same argument."
  [x y]
  (let [sx (entry-slots x) sy (entry-slots y)]
    (every? (fn [[i s]] (if-let [t (get sy i)] (slot-subtype? s t) true)) sx)))

(defn most-specific
  "Of the candidates (maps with :decl and :items) the one that is more specific than all others, in the sense of
  Kotlin (see `at-least-as-specific?`): the candidates that are at least as specific as every other one; all of
  them when none is."
  [candidates]
  (if (< (count candidates) 2)
    candidates
    (let [top (filter (fn [c] (every? #(or (identical? c %) (at-least-as-specific? c %)) candidates)) candidates)]
      (if (seq top) (vec top) candidates))))

;; ---------------------------------------------------------------- choose

(defn- reasons-text [var-name args rejected]
  (str "kt: no Kotlin declaration of `" var-name "` fits " (call-text var-name args) "\n"
       (str/join "\n" (for [{:keys [decl reason]} rejected]
                        (str "    " (:signature decl) "\n      -> " reason)))))

(defn- member-signature [decl]
  [(:name decl) (mapv (comp meta/type-text :type) (:params decl))
   (mapv (comp meta/type-text :type) (remove #(= :dispatch (:role %)) (:receivers decl)))])

(defn drop-overridden
  "Of the candidates (maps with :decl), drop a member that a candidate of a subclass overrides: same
  name and parameter types, and the owner of the other is a strict subclass (`JobSupport.join`
  overrides `Job.join`). They are one Kotlin member, so a call is not ambiguous."
  [candidates]
  (let [owner (fn [c] (jvm-class (:owner (:decl c))))]
    (remove (fn [c]
              (some (fn [o]
                      (let [oc (owner c) oo (owner o)]
                        (and (not (identical? o c)) (member? (:decl c)) (member? (:decl o))
                             (= (member-signature (:decl c)) (member-signature (:decl o)))
                             oc oo (not= oc oo) (.isAssignableFrom ^Class oc ^Class oo))))
                    candidates))
            candidates)))

(defn reified?
  "Does `decl` have a reified type parameter?"
  [decl]
  (boolean (some :reified? (:type-params decl))))

(defn- type-args-form
  "The form after `:<>` in the parsed arguments as [form], or nil when there is no `:<>`."
  [var-name parsed]
  (let [ts (filter #(= "<>" (first %)) (:named parsed))]
    (when (> (count ts) 1)
      (fail (str "kt: " var-name ": `:<>` given twice. Give all type arguments in one vector: `:<> [A B]`.")))
    (when-let [[_ e] (first ts)] [(:arg e)])))

(defn- without-type-args [parsed]
  (update parsed :named (fn [ns] (vec (remove #(= "<>" (first %)) ns)))))

(defn- select-by-type-args
  "The declarations that the type arguments allow (rule 5): without `:<>` those with no reified type
  parameter; with `:<>` those with exactly that many type parameters."
  [var-name decls tform n compile?]
  (if tform
    (let [ok (filter #(= n (count (:type-params %))) decls)]
      (when (empty? ok)
        (fail (str "kt: `:<>` gives " n " type argument" (when (not= 1 n) "s") " to " var-name
                   ", but no declaration has " n " type parameter" (when (not= 1 n) "s") ". Kotlin declares:\n"
                   (signatures decls))
              {:kt/candidates (map :signature decls)}))
      ok)
    (let [ok (remove reified? decls)]
      (when (empty? ok)
        (fail (str "kt: " var-name " is `inline reified`: it needs its type arguments, which only the Kotlin compiler can "
                   "give it. Write them with `:<>`, e.g. `(" var-name " ... :<> Type)`; a type form is a literal, "
                   "kt does not infer it."
                   (when-not compile? " A var used as a value cannot take `:<>`: call it as a form.")
                   "\n  Kotlin declares:\n" (signatures decls))
              {:kt/candidates (map :signature decls)}))
      ok)))

(defn choose
  "Pick exactly one declaration (rule 7).
  => {:decl d :items items :checks checks [:tform form]}      one candidate
     {:dynamic? true [:tform form]}                            several candidates, types unknown
  or throws a kt error. `compile?` allows unknowns. `:tform` is the form after `:<>` (see the namespace
  docstring)."
  [var-name decls parsed compile?]
  (let [real (remove no-constructor? decls)
        _ (when (and (seq decls) (empty? real))
            (fail (no-constructor-message (first decls)
                                          (call-text var-name (concat (map :arg (:positional parsed))
                                                                      (mapcat (fn [[n e]] [(keyword n) (:arg e)]) (:named parsed)))))
                  {:kt/candidates (map :signature decls)}))
        decls real
        [tform :as tf] (type-args-form var-name parsed)
        parsed (without-type-args parsed)
        _ (when (and tf (not compile?))
            (fail (str "kt: " var-name ": `:<>` needs the static path; a call that is made at run time cannot take type arguments.")))
        decls (select-by-type-args var-name decls tf (when tf (count (types/arg-forms tform))) compile?)
        args (concat (map :arg (:positional parsed))
                     (mapcat (fn [[n e]] [(keyword n) (:arg e)]) (:named parsed))
                     (when tf [:<> tform]))
        bound (for [d decls] (assoc (bind d parsed) :decl d))
        failed (filter :error bound)
        ok (filter :items bound)]
    (when (empty? ok)
      (fail (if (= 1 (count decls))
              (str "kt: " (call-text var-name args) ": " (:error (first failed)) "\n  Kotlin declares:\n" (signatures decls))
              (reasons-text var-name args (map #(hash-map :decl (:decl %) :reason (:error %)) failed)))
            {:kt/candidates (map :signature decls)}))
    (let [checked (for [b ok :let [sl (slots (:decl b))]] (assoc b :checks (check-items sl (:items b))))
          no-reason (fn [b] (some #(when (= :no (first %)) (second %)) (:checks b)))
          viable (remove no-reason checked)]
      (when (empty? viable)
        (fail (reasons-text var-name args (concat (map #(hash-map :decl (:decl %) :reason (:error %)) failed)
                                                  (map #(hash-map :decl (:decl %) :reason (no-reason %)) checked)))
              {:kt/candidates (map :signature decls)}))
      (let [unknown? (fn [b] (some #(= :unknown (first %)) (:checks b)))
            weak? (fn [b] (some #(= [:unknown :upper] %) (:checks b)))
            doubt? (fn [b] (some #(not= :ok (first %)) (:checks b)))
            ;; Kotlin: an applicable member always wins over an extension. Only when a member fits for sure; a
            ;; member that only could fit is left to the run time, which knows the classes.
            viable (if (some #(and (member? (:decl %)) (not (doubt? %))) viable)
                     (filter (comp member? :decl) viable)
                     viable)
            ;; a candidate that fits the compile-time types for sure beats one that only could fit (but not an
            ;; extension over a member that could fit: that is the run time's to decide)
            viable (let [sure (remove unknown? viable)]
                     (if (and (seq sure) (some weak? viable)
                              (or (every? (comp member? :decl) sure) (not-any? (comp member? :decl) (filter weak? viable))))
                       sure
                       viable))
            result (fn [b] (cond-> (select-keys b [:decl :items :checks]) tf (assoc :tform tform)))]
        (if (= 1 (count viable))
          (result (first viable))
          (if (and compile? (some unknown? viable))
            (cond-> {:dynamic? true} tf (assoc :tform tform))
            (let [best (remove (fn [b] (some #(dominates? (tiers (:checks %)) (tiers (:checks b))) viable)) viable)
                  best (if (and (> (count best) 1) (some (comp member? :decl) best))
                         (filter (comp member? :decl) best)
                         best)
                  ;; Kotlin: the most specific candidate by parameter types
                  best (most-specific best)
                  ;; then, of equally specific candidates: no vararg, not generic, no default value needed
                  prefer (fn [best keep?] (if (> (count best) 1)
                                            (let [k (filter keep? best)] (if (seq k) k best))
                                            best))
                  best (prefer best (fn [b] (not-any? :vararg? (:params (:decl b)))))
                  ;; (not when a type argument is in play: the JVM erased it, so `Iterable<Double>.maxOrNull` and
                  ;; the generic `Iterable<T>.maxOrNull` cannot be told apart by a value, and the generic one may be right)
                  best (if (some (fn [b] (some #(seq (:args (:kotlin-type %))) (vals (entry-slots b)))) best)
                         best
                         (prefer best (fn [b] (empty? (:type-params (:decl b))))))
                  best (prefer best (fn [b] (not-any? :omitted (:items b))))
                  best (if (> (count best) 1) (drop-overridden best) best)]
              (if (= 1 (count best))
                (result (first best))
                (fail (str "kt: " (call-text var-name args) " is ambiguous. Candidates:\n"
                           (signatures (map :decl best))
                           "\n  Add a type hint to an argument, or use Java interop (.getX) for this call.")
                      {:kt/candidates (map (comp :signature :decl) best)})))))))))

;; ---------------------------------------------------------------- plan

(declare plan*)

(defn- arg-conv
  "Value-class conversion of a slot, with the parameter name for error messages."
  [t jt name]
  (when-let [c (vc-conv t jt)] (assoc c :name name)))

(defn plan
  "JVM call plan for a chosen declaration.
  {:op :static|:virtual|:new|:get-static|:get-field|:set-static|:set-field
   :class :name :field :target <entry|{:companion {:class :field}}|nil>
   :args [{:entry e :jvm-type t} | {:vararg [e ..] :jvm-type t} | {:vararg-coll e :jvm-type t}
          | {:zero t} | {:mask n} | {:marker true} | {:cont true}]
   :unit? bool   ; result is kotlin.Unit as an object, convert to nil
   :suspend? bool   ; a suspend function: its args end with {:cont true} (before the masks); wait for the result
   :ret-td <type-descriptor>   ; the result is a Kotlin function: make it a Clojure function
   :ret-vc {:vc .. :nullable? ..}   ; the result is the underlying value of a value class: box it
   :ignored [entry ...]}   ; written arguments that the call does not use
  An arg of a function-type or fun-interface parameter also has :adapt (see `adapter-spec`).
  An arg whose JVM slot holds the underlying value of a value class has :vc {:vc .. :nullable? ..
  :name ..} (a vararg has :elem-vc): unbox it. The receiver of a member that the JVM compiles to
  a static `name-impl(underlying, ...)` is the first :args element."
  [decl items]
  (let [nr (count (:receivers decl))
        recvs (:receivers decl)
        ritems (subvec items 0 nr)
        pitems (subvec items nr)
        dispatch-i (first (keep-indexed #(when (= :dispatch (:role %2)) %1) recvs))
        disp-recv (when dispatch-i (nth recvs dispatch-i))
        disp-item (when dispatch-i (nth ritems dispatch-i))
        other (for [[r it] (map vector recvs ritems) :when (not= :dispatch (:role r))]
                (cond-> {:entry it :jvm-type (:jvm-type r)}
                  (arg-conv (:type r) (:jvm-type r) (or (:name r) "receiver"))
                  (assoc :vc (arg-conv (:type r) (:jvm-type r) (or (:name r) "receiver")))))
        self (when-let [jt (:jvm-type disp-recv)]
               (cond-> {:entry disp-item :jvm-type jt}
                 (arg-conv (:type disp-recv) jt "this") (assoc :vc (arg-conv (:type disp-recv) jt "this"))))
        params (:params decl)
        omitted (set (keep-indexed #(when (:omitted %2) %1) pitems))
        defaults? (seq omitted)
        jvm (:jvm decl)
        jvm (if defaults? (:default jvm) jvm)
        elem-conv (fn [p] (when-let [et (:vararg-elem p)]
                            (arg-conv et (some-> ^Class (jvm-class (:jvm-type p)) .getComponentType .getName) (:name p))))
        pargs (for [[i p it] (map vector (range) params pitems)]
                (cond
                  (:omitted it) {:zero (:jvm-type p)}
                  (:vararg it) (cond-> {:vararg (:vararg it) :jvm-type (:jvm-type p)}
                                 (elem-conv p) (assoc :elem-vc (elem-conv p))
                                 (:elem-jvm p) (assoc :elem-jvm (:elem-jvm p)))
                  (:vararg-coll it) (cond-> {:vararg-coll (:vararg-coll it) :jvm-type (:jvm-type p)}
                                      (elem-conv p) (assoc :elem-vc (elem-conv p)))
                  :else (let [ad (adapter-spec (:type p) (:jvm-type p) (:signature decl))
                              vc (arg-conv (:type p) (:jvm-type p) (:name p))]
                          (cond-> {:entry it :jvm-type (:jvm-type p) :pname (:name p) :ptext (meta/type-text (:type p))}
                            ad (assoc :adapt ad) vc (assoc :vc vc)))))
        nmask (max 1 (quot (+ (count params) 31) 32))
        masks (for [m (range nmask)]
                {:mask (unchecked-int (reduce (fn [acc i] (bit-or acc (bit-shift-left 1 (- i (* 32 m)))))
                                              0 (filter #(= m (quot % 32)) omitted)))})
        companion? (boolean (:companion-of disp-recv))
        target (cond companion? {:idx -1 :companion {:class (:companion-of disp-recv) :field (:companion-field disp-recv)}}
                     :else disp-item)
        unit? (and (= "kotlin/Unit" (:class (:return decl)))
                   (not (str/ends-with? (str (:desc jvm)) ")V")))
        p (plan* decl jvm defaults? other pargs masks target disp-item unit? self)
        desc (if (= :property (:kind decl)) (:desc (:getter decl)) (:desc jvm))
        ret-vc (return-conv decl)]
    (cond-> (assoc p :desc desc :sig (:signature decl))
      ;; a public method of a multi-file class part is called through the public facade that inherits it
      (and (#{:static :virtual} (:op p))
           (:call-class (if (= :property (:kind decl)) (:getter decl) jvm)))
      (assoc :call-class (:call-class (if (= :property (:kind decl)) (:getter decl) jvm)))
      companion? (update :ignored (fnil conj []) disp-item)
      ;; a @JvmStatic member of an object is a static JVM method: its receiver is evaluated, not passed
      (and (not companion?) disp-item (= :static (:op p))
           (not-any? #(and (:entry %) (= (:idx (:entry %)) (:idx disp-item))) (:args p)))
      (update :ignored (fnil conj []) disp-item)
      (:suspend (:flags decl)) (assoc :suspend? true)
      ret-vc (assoc :ret-vc ret-vc)
      (:fn-type (:return decl)) (assoc :ret-td (type-descriptor (:return decl))))))

(defn- marker-last? [desc]
  (str/ends-with? (str desc) "Lkotlin/jvm/internal/DefaultConstructorMarker;)V"))

(defn- plan* [decl jvm defaults? other pargs masks target disp-item unit? self]
  (cond
    ;; the assignment of a field (`ckway.set`): a pseudo function whose JVM member is a field
    (and (= :function (:kind decl)) (:field jvm))
    (if (:static? jvm)
      {:op :set-static :class (:class jvm) :field (:field jvm) :args (vec pargs)
       :ignored (vec (when (and disp-item (not (:companion target))) [disp-item]))}
      {:op :set-field :class (:class jvm) :field (:field jvm) :target disp-item :args (vec pargs)})

    (= :property (:kind decl))
    (if (:field jvm)
      (if (:static? jvm)
        {:op :get-static :class (:class jvm) :field (:field jvm) :args [] :ignored (vec (when (and disp-item (not (:companion target))) [disp-item]))}
        {:op :get-field :class (:class jvm) :field (:field jvm) :target disp-item :args []})
      (let [getter (:getter decl)]
        (if (:static? getter)
          {:op :static :class (:class getter) :name (:name getter) :args (vec (concat (when self [self]) other))}
          {:op :virtual :class (:class getter) :name (:name getter) :target target :args (vec other)})))

    (and (= :class (:kind decl)) (:value-class decl))
    {:op :static :class (:class jvm) :name (:name jvm)
     :args (vec (concat pargs (when defaults? (concat masks [{:marker true}]))))}

    (= :class (:kind decl))
    {:op :new :class (:class jvm)
     :args (vec (concat pargs (cond defaults? (concat masks [{:marker true}])
                                    (marker-last? (:desc jvm)) [{:marker true}])))}

    :else
    ;; a suspend function takes the continuation after its parameters, before the default masks
    (let [all (vec (concat (when self [self]) other pargs (when (:suspend (:flags decl)) [{:cont true}])))]
      (cond
        (and defaults? (not (:static? (:jvm decl))))
        {:op :static :class (:class jvm) :name (:name jvm) :unit? unit?
         :args (vec (concat [{:entry target :jvm-type (:class (:jvm decl)) :target? true}] all masks [{:marker true}]))}
        defaults?
        {:op :static :class (:class jvm) :name (:name jvm) :unit? unit?
         :args (vec (concat all masks [{:marker true}]))}
        (:static? jvm)
        {:op :static :class (:class jvm) :name (:name jvm) :unit? unit? :args all}
        :else
        {:op :virtual :class (:class jvm) :name (:name jvm) :target target :unit? unit? :args all}))))

;; ---------------------------------------------------------------- static emission

(defn- sym [s] (symbol s))

(defn- tagged
  "A local for an argument. Its tag is the JVM parameter type, also Object: Clojure then sees an
  exact match for the whole parameter list, so JVM overloads such as (int, Object) and
  (long, Object) are not ambiguous."
  [name tag]
  (with-meta (gensym name) (when tag {:tag tag})))

(def ^:private boxed-names
  #{"java.lang.Integer" "java.lang.Long" "java.lang.Short" "java.lang.Byte" "java.lang.Double"
    "java.lang.Float" "java.lang.Character"})

(defn- fits?
  "Is a value with the compile-time `info` sure to be an instance of the class `cname`? (A trusted entry is, a
  class that is only an upper bound is if that class is a subtype.)"
  [cname info]
  (boolean (or (:trusted info)
               (when-let [^Class ic (:class info)]
                 (when-let [^Class jc (jvm-class cname)]
                   (.isAssignableFrom jc (box-class ic)))))))

(defn- checked-form
  "`form`, but a value that is not an instance of the class `cname` is a kt error (`ckway.rt/wrong-class`), not the
  JVM's ClassCastException. nil passes. `site` says where the value goes: {:what .. :call .. :sig ..}."
  [cname form info site]
  (if (or (fits? cname info) (str/starts-with? cname "[") (= "java.lang.Object" cname) (nil? site)
          ;; a failed object (`guard-failed-objects`) throws on its own, and its class cannot be initialised
          (and (seq? form) (= `ckway.rt/live (first form))))
    form
    (let [v (gensym "v")]
      `(let [~v ~form] (if (instance? ~(sym cname) ~v) ~v (ckway.rt/wrong-class ~(sym cname) '~site ~v))))))

(defn- conv-form
  "[form tag] that passes `form` (with `info`) to a JVM parameter of type `jt`. `site` (see `checked-form`) is
  nil when the value is trusted."
  [jt form info site]
  (let [integral? (integral-class? (:class info))
        checked (fn [f] (if integral? form `(ckway.rt/integral ~form)))]
    (case jt
      "int" [`(int ~(checked form)) nil]
      "long" [`(long ~(checked form)) nil]
      "short" [`(short ~(checked form)) nil]
      "byte" [`(byte ~(checked form)) nil]
      "double" [`(double ~(checked-form "java.lang.Number" form info site)) nil]
      "float" [`(float ~(checked-form "java.lang.Number" form info site)) nil]
      "char" [`(char ~(checked-form "java.lang.Character" form info site)) nil]
      ;; the primitive boolean, so that a (boolean) and a (Boolean) overload are told apart
      "boolean" (let [b (with-meta (gensym "b") {:tag 'java.lang.Boolean})]
                  [`(let [~b ~(checked-form "java.lang.Boolean" form info site)] (.booleanValue ~b)) nil])
      (cond
        (boxed-names jt) [`(ckway.rt/box ~jt ~form) jt]
        ;; a number literal or primitive local would stay primitive; box it so overloads resolve
        (or (number? form) (some-> ^Class (:class info) .isPrimitive)) [`(identity ~form) jt]
        :else [(checked-form jt form info site) jt]))))

(defn- zero-form [jt]
  (case jt
    "int" `(int 0) "long" `(long 0) "short" `(short 0) "byte" `(byte 0) "double" `(double 0)
    "float" `(float 0) "char" `(char 0) "boolean" `(boolean false) nil))

(defn- bridge-sym
  "Symbol of the bridge class for the JVM member `m` ({:class :name :desc :static?})."
  [{:keys [class name desc static?]}]
  (symbol (bridge/bridge-class {:kind (if static? :static :virtual) :class class :name name :desc desc})))

(defn unbox-form
  "Form that gives the underlying value of the value-class object `x` (a form or symbol). `conv` is
  {:vc .. :nullable? .. :name ..}. An object of another class is a kt error (`ckway.rt/vc-arg`; with :result?,
  the result of a Clojure function: `ckway.rt/vc-result`)."
  [{:keys [vc nullable? name result?]} x]
  (let [v (with-meta (gensym "v") {:tag (symbol (:class vc))})
        raw (:jvm-underlying vc)
        un `(. ~(bridge-sym (:unbox vc)) (~'call ~v))]
    `(let [~v ~(if result?
                 `(ckway.rt/vc-result ~(symbol (:class vc)) ~nullable? ~x)
                 `(ckway.rt/vc-arg ~(symbol (:class vc)) ~name ~nullable? ~x))]
       ~(if (and nullable? (not (contains? prim-classes raw))) `(when ~v ~un) un))))

(defn box-form
  "Form that gives the value-class object for the underlying value `x` (a form that returns it)."
  [{:keys [vc nullable?]} x]
  (let [raw (:jvm-underlying vc)
        b (bridge-sym (:box vc))]
    (if (and nullable? (not (contains? prim-classes raw)))
      (let [r (with-meta (gensym "r") {:tag raw})] `(let [~r ~x] (when ~r (. ~b (~'call ~r)))))
      `(. ~b (~'call ~x)))))

(defn- arg-form [bindings-by-idx a]
  (cond
    (:entry a) (get bindings-by-idx (:idx (:entry a)))
    (:zero a) (zero-form (:zero a))
    (:mask a) `(int ~(:mask a))
    (:marker a) nil
    (:cont a) (::k bindings-by-idx)
    (:vararg a) `(ckway.rt/->array ~(:jvm-type a) [~@(map #(get bindings-by-idx (:idx %)) (:vararg a))])
    (:vararg-coll a) `(ckway.rt/->array ~(:jvm-type a)
                                     ~(let [coll (get bindings-by-idx (:idx (:vararg-coll a)))]
                                        (if-let [vc (:elem-vc a)]
                                          (let [x (gensym "x")] `(map (fn [~x] ~(unbox-form vc x)) ~coll))
                                          coll)))))

(defn- companion-entry
  "A companion target becomes an entry whose form reads the Companion field."
  [e]
  (if-let [{:keys [class field]} (:companion e)]
    (assoc e :arg `(. ~(sym class) ~(sym (str "-" field))) :info {:trusted true})
    e))

(defn- plan-entries
  "[entry jvm-type adapt vc what] for every written argument that the plan uses, plus ignored ones. `what` says
  what the value is, for the error of a wrong class (`checked-form`)."
  [p]
  (let [elem (fn [jt] (when-let [^Class c (some-> jt jvm-class)] (some-> (.getComponentType c) .getName)))
        from-arg (fn [a]
                   (cond
                     (:entry a) [[(companion-entry (:entry a)) (:jvm-type a) (:adapt a) (:vc a)
                                  (if (:pname a) (str "the argument `" (:pname a) "` (" (:ptext a) ")") "the receiver")]]
                     (:vararg a) (map (fn [e] [e (or (:elem-jvm a) (elem (:jvm-type a))) nil (:elem-vc a)]) (:vararg a))
                     (:vararg-coll a) [[(:vararg-coll a) nil]]))
        target (:target p)]
    (concat (when target [[(companion-entry target) (:class p) nil nil "the receiver"]])
            (mapcat from-arg (:args p))
            (map (fn [e] [e nil]) (:ignored p)))))

(defn annotation-meta
  "The annotation metadata of a `fn` form: `^{fx.Marker true} (fn ...)` => {fx.Marker true}.
  Only entries whose key is a symbol that names an annotation class count."
  [form]
  (into {} (for [[k v] (meta form)
                 :when (and (symbol? k) (some-> ^Class (tag->class *ns* k) .isAnnotation))]
             [k v])))

(defn fn-literal?
  "Is `form` a `fn`, `fn*` or `#()` literal?"
  [form]
  (boolean (and (seq? form) (symbol? (first form)) (#{'fn 'fn* 'clojure.core/fn} (first form)))))

(defn- ifn-sym [g] (vary-meta g assoc :tag 'clojure.lang.IFn))

(defn- arity-guard
  "Wrap the body of an adapter method: a Clojure function of the wrong arity gets a kt error that
  says what Kotlin called (`text`) and how many arguments it gave (F1)."
  [g text n body]
  `(try ~body
        (catch clojure.lang.ArityException e#
          (throw (ckway.rt/arity-error e# ~g ~text ~n)))))

(defn- suspend-method
  "The method of a suspend adapter: it takes the Continuation `k` after `args`, runs `body` (which
  calls the Clojure function and converts its result) on a virtual thread and returns
  COROUTINE_SUSPENDED (ckway.co/run-body). `frame` is the binding frame captured with the adapter."
  [name ann args k frame body]
  `(~(with-meta name ann) [~'_ ~@args ~k] (ckway.co/run-body ~k ~frame (fn [] ~body))))

(defn fn-reify
  "reify of exactly kotlin.jvm.functions.FunctionN that calls the Clojure function `g`: arguments
  that are Kotlin function values become Clojure functions, the result is converted to the declared
  Kotlin return type (a Long to Int, anything to Unit). Used by the static path at compile time and
  by the dynamic path through `eval`, so both behave the same (also for a checked exception).
  For a suspend function type the iface is Function<arity+1> and `g` takes the Kotlin parameters
  only: it runs as a coroutine body (see ckway.co)."
  [{:keys [iface td]} g ann]
  (let [args (vec (repeatedly (count (:params td)) #(gensym "p")))
        call `(.invoke ~(ifn-sym g) ~@(map (fn [d a] (if (#{:fn :unit} (:k d)) `(ckway.rt/<-kotlin ~d ~a) a)) (:params td) args))
        ret (:ret td)
        body (arity-guard g (:text td) (count args)
                          (case (:k ret)
                            :unit `(do ~call kotlin.Unit/INSTANCE)
                            :obj (if (check-result? ret (:suspend? td)) `(ckway.rt/->kotlin ~ret ~call) call)
                            `(ckway.rt/->kotlin ~ret ~call)))]
    (if (:suspend? td)
      (let [k (gensym "k") fr (gensym "frame")]
        `(let [~fr (ckway.co/capture-frame)]
           (reify ~(symbol iface) ~(suspend-method 'invoke ann args k fr body))))
      `(reify ~(symbol iface)
         (~(with-meta 'invoke ann) [~'_ ~@args] ~body)))))

(defn sam-text
  "Kotlin-like text of the single abstract method of a fun interface, for errors."
  [iface sam]
  (str iface "." (:name sam) "(" (str/join ", " (map #(last (str/split % #"\.")) (:params sam))) ")"))

;; The result of a `suspend` function or member is Object on the JVM and a Clojure body there is not checked for the
;; class of a normal Kotlin type (it was not before, and `kt/reify` bodies of suspend members are loose in practice);
;; a value class is always checked, because Kotlin unboxes it.
(defn check-result?
  "Does the result of a Clojure function, of descriptor `td`, go through `ckway.rt/->kotlin` (conversion and class check)?"
  [td susp?]
  (boolean (or (not= :obj (:k td))
               (and (:cls td) (or (not susp?) (:vc? td))))))

(defn- fi-class-form
  "Form for a `fun interface` whose method a Clojure `reify` cannot define (a value class in its signature mangles the
  JVM name: `adjust-TKBkzuY`). It makes an instance of a generated class (`ckway.bridge/reify-class`, the class of
  `kt/reify`, one for each interface) with ONE function that is called with `this` and the raw JVM arguments. The
  function boxes a value class that the slot holds as its underlying value, makes a Kotlin function value a Clojure
  function, calls `g` and converts the result (unboxes a value class, checks the class). A default method of the
  interface is inherited by the class; a body in `I$DefaultImpls` is forwarded."
  [{:keys [iface sam]} g ann]
  (let [susp? (:suspend? sam)
        {:keys [params ret]} (:cls sam)
        raw (vec (repeatedly (count params) #(gensym "raw")))
        ps (vec (repeatedly (count params) #(gensym "p")))
        binds (vec (mapcat (fn [p r {:keys [vc td]}]
                             [p (cond vc (box-form vc r) td `(ckway.rt/<-kotlin ~td ~r) :else r)])
                           ps raw params))
        call `(.invoke ~(ifn-sym g) ~@ps)
        rtd (or (:ret-td sam) (some-> (sam-kdecl iface sam) :return type-descriptor))
        result (cond
                 (:vc ret) (unbox-form (assoc (:vc ret) :name "result" :result? true) call)
                 (and rtd (check-result? rtd susp?)) `(ckway.rt/->kotlin ~rtd ~call)
                 :else call)
        body (arity-guard g (str iface "." (:text (:cls sam))) (count ps) result)
        k (gensym "k") fr (gensym "frame")
        this (gensym "this")
        f (if susp?
            `(fn* [~this ~@raw ~k] (ckway.co/run-body ~k ~fr (fn* [] (let ~binds ~body))))
            `(fn* [~this ~@raw] (let ~binds ~body)))
        annotations (if (seq ann) ((requiring-resolve 'ckway.reify/annotations-of) (with-meta 'm ann)) [])
        spec {:ifaces [iface]
              :methods (vec (concat [{:impl :fn :name (:name sam) :desc (:desc sam) :idx 0 :annotations annotations}]
                                    (for [{:keys [name desc impls impls-desc]} (:delegates sam)]
                                      {:impl :delegate :name name :desc desc :impls impls :impls-desc impls-desc})))}
        cname (bridge/reify-class (last (str/split iface #"[.$]")) spec)
        make `(new ~(symbol cname) (object-array [~f]) nil)]
    (if susp? `(let [~fr (ckway.co/capture-frame)] ~make) make)))

(defn fi-reify
  "reify of a `fun interface` whose single method calls the Clojure function `g`. Methods with a
  Kotlin body that sit in I$DefaultImpls are forwarded there; JVM default methods are inherited.
  A method with a value class in its signature (mangled JVM name) is implemented by a generated class
  instead (`fi-class-form`): the function gets the boxed value-class objects."
  [{:keys [iface sam] :as spec} g ann]
  (if (:cls sam)
    (fi-class-form spec g ann)
    (let [susp? (:suspend? sam)
          args (vec (repeatedly (cond-> (count (:params sam)) susp? dec) #(gensym "p")))
          call `(.invoke ~(ifn-sym g) ~@args)
          ;; the JVM result of a suspend method is Object: convert to the Kotlin return type
          ret (or (:ret-td sam) (jvm-descriptor (:return sam)))
          body (arity-guard g (sam-text iface sam) (count args) (if (and ret (check-result? ret susp?)) `(ckway.rt/->kotlin ~ret ~call) call))
          method (if susp?
                   (let [k (gensym "k") fr (gensym "frame")]
                     [fr (suspend-method (symbol (:name sam)) ann args k fr body)])
                   [nil `(~(with-meta (symbol (:name sam)) ann) [~'_ ~@args] ~body)])
          form `(reify ~(symbol iface)
                  ~(second method)
                  ~@(for [{:keys [name params impls]} (:delegates sam)
                          :let [as (vec (repeatedly (count params) #(gensym "d")))]]
                      `(~(symbol name) [~'this ~@as] (. ~(symbol impls) (~(symbol name) ~'this ~@as)))))]
      (if (first method) `(let [~(first method) (ckway.co/capture-frame)] ~form) form))))

(defn adapter-form
  "[form tag] that passes the argument `form` to a parameter of a function type or fun interface.
  A function that is not yet an instance of the JVM type is adapted by a reify (a `fn` literal
  directly, other forms after a run-time check); an instance (also a Kotlin function that came
  from a kt call) is passed on itself. Annotation metadata of a `fn` literal goes to the method."
  [{:keys [kind iface feature] :as spec} jt form]
  (cond
    (nil? form) [nil jt]
    feature [`(ckway.rt/adapt-arg ~spec ~form) jt]
    :else
    (let [g (gensym "g")
          ann (annotation-meta form)
          build ((case kind :fn fn-reify :fi fi-reify) spec g ann)]
      [(if (fn-literal? form)
         `(let [~g ~form] ~build)
         `(let [~g ~form] (if (ckway.rt/adapt? ~g ~(symbol iface)) ~build (ckway.rt/own ~g))))
       jt])))

(defn emit
  "Form for a plan. Arguments are bound once, in written order, with exact tags."
  [p]
  (let [target-ent (:target p)
        pe (plan-entries p)
        entries (->> pe
                     (reduce (fn [m [e jt ad :as x]] (if (contains? m (:idx e)) m (assoc m (:idx e) x))) {})
                     (sort-by key) (map val))
        binds (for [[e jt ad vc what] entries
                    :let [site (when (and what (:call-text p)) (assoc (:site p) :what what :call (:call-text p) :sig (:sig p)))
                          [f tag] (cond ad (adapter-form ad jt (:arg e))
                                        vc [(unbox-form vc (:arg e)) (when-not (contains? prim-classes jt) jt)]
                                        jt (conv-form jt (:arg e) (:info e) site)
                                        :else [(:arg e) nil])]]
                [(:idx e) (tagged "a" tag) f])
        ksym (when (:suspend? p) (with-meta (gensym "k") {:tag 'kotlin.coroutines.Continuation}))
        by-idx (cond-> (into {} (map (fn [[i s _]] [i s]) binds)) ksym (assoc ::k ksym))
        tgt (when target-ent (get by-idx (:idx target-ent)))
        ;; omitted reference parameters are typed nil locals, so same-arity overloads still resolve
        ;; The JVM parameter types, from the descriptor: the plan's arguments are its parameters, one for one. Every
        ;; slot that is not a written argument (nil placeholder, marker, vararg array) gets a local with that exact
        ;; type, so the Clojure compiler finds the one method (an exact match) and never falls back to reflection.
        ptypes (when (and (#{:static :new} (:op p)) (:desc p))
                 (let [ts (:params (meta/desc-types (:desc p)))] (when (= (count ts) (count (:args p))) ts)))
        slot-type (fn [i a] (or (nth ptypes i nil)
                                (cond (:zero a) (:zero a)
                                      (:marker a) (when (= :new (:op p)) "kotlin.jvm.internal.DefaultConstructorMarker")
                                      :else (:jvm-type a))))
        holders (for [[i a] (map-indexed vector (:args p))
                      :let [jt (when (or (:marker a) (and (:zero a) (not (contains? prim-classes (:zero a))))) (slot-type i a))]
                      :when jt]
                  [a (tagged "z" jt)])
        holder-of (into {} holders)
        ;; a vararg array is a local with the exact array type of the parameter
        arrays (for [[i a] (map-indexed vector (:args p))
                     :when (or (:vararg a) (:vararg-coll a))]
                 [a (tagged "v" (slot-type i a)) (arg-form by-idx a)])
        array-of (into {} (map (fn [[a s _]] [a s]) arrays))
        args (map #(or (holder-of %) (array-of %) (arg-form by-idx %)) (:args p))
        cls (sym (or (:call-class p) (:class p)))
        bridge-of (when (and (#{:static :virtual} (:op p))
                             (if (:call-class p)
                               (not (bridge/clojure-name? (:name p)))
                               (bridge/needed? {:kind (:op p) :class (:class p) :name (:name p) :desc (:desc p)})))
                    (bridge-sym {:class (:class p) :name (:name p) :desc (:desc p) :static? (= :static (:op p))}))
        ;; a class that is not public (a part of a multi-file class) cannot be named by a bridge class
        hidden-class? (and (#{:static :virtual} (:op p)) (not (:call-class p))
                           (let [c (jvm-class (:class p))] (and c (not (Modifier/isPublic (.getModifiers ^Class c))))))
        handle (when hidden-class?
                 `(ckway.rt/jvm-handle ~(= :static (:op p)) ~(:class p) ~(:name p) ~(:desc p)))
        bridge-of (when-not hidden-class? bridge-of)
        call (case (:op p)
               :static (cond handle `(ckway.rt/call-jvm ~handle [~@args])
                             bridge-of `(. ~bridge-of (~'call ~@args))
                             :else `(. ~cls (~(sym (:name p)) ~@args)))
               :virtual (cond handle `(ckway.rt/call-jvm ~handle [~tgt ~@args])
                              bridge-of `(. ~bridge-of (~'call ~tgt ~@args))
                              :else `(. ~tgt (~(sym (:name p)) ~@args)))
               :new `(new ~cls ~@args)
               :get-static `(. ~cls ~(sym (str "-" (:field p))))
               :get-field `(. ~tgt ~(sym (str "-" (:field p))))
               :set-static `(set! (. ~cls ~(sym (str "-" (:field p)))) ~(first args))
               :set-field `(set! (. ~tgt ~(sym (str "-" (:field p)))) ~(first args)))
        call (if ksym `(ckway.co/wait-for ~ksym ~call) call)
        call (cond
               (:ret-vc p) (box-form (:ret-vc p) call)
               (:unit? p) `(ckway.rt/unit->nil ~call)
               (:ret-td p) `(ckway.rt/<-kotlin ~(:ret-td p) ~call)
               :else call)]
    (if (or (seq binds) (seq holders) (seq arrays) ksym)
      `(let [~@(mapcat (fn [[_ s f]] [s f]) binds) ~@(mapcat (fn [[_ s]] [s nil]) holders)
             ~@(mapcat (fn [[_ s f]] [s f]) arrays)
             ~@(when ksym [ksym `(ckway.co/continuation)])]
         ~call)
      call)))

;; ---------------------------------------------------------------- type arguments (rule 5)

(declare type-literals)

(defn- tmap-of [decl targs] (zipmap (map :name (:type-params decl)) targs))

(defn- plain-type
  "`t` as a JVM generic position holds it: the boxed object, never the underlying value."
  [t]
  (assoc t :value-class nil :value-class? false))

(defn- result-class-name
  "JVM class name of a value of the Kotlin type `ret` when it is known and is no function, Unit or Nothing."
  [ret]
  (when-not (or (:fn-type ret) (:type-param ret) (:star? ret) (#{"kotlin/Unit" "kotlin/Nothing"} (:class ret)))
    (if-let [vc (:value-class ret)]
      (:class vc)
      (some-> ^Class (types/jvm-class ret) .getName))))

(defn typed-return-class
  "Boxed class of the result of `decl` called with the (resolved) type arguments `targs`, or the class of its JVM return type."
  [decl targs]
  (or (some-> (result-class-name (types/subst (tmap-of decl targs) (:return decl))) jvm-class box-class)
      (return-class decl)))

(defn typed-decl
  "`decl` (a generic function without a reified type parameter) with the type arguments `targs`
  substituted in its types, for static typing only: nothing changes at run time (the JVM has erased
  them). A parameter or result whose type is exactly a type parameter gets the class of the type
  argument: `(echo x :<> String)` is a String for the nested calls and `x` is checked against it."
  [decl targs]
  (let [tmap (tmap-of decl targs)
        exact? (fn [t] (and (:type-param t) (contains? tmap (:type-param t))))
        rt (:return decl)
        ret (types/subst tmap rt)
        rc (when (exact? rt) (result-class-name ret))]
    (-> decl
        (assoc :return (if (exact? rt) (plain-type ret) ret))
        (cond-> rc (assoc :result-class rc))
        (update :params (fn [ps]
                          (mapv (fn [p]
                                  (let [t (types/subst tmap (:type p))
                                        c (when (and (exact? (:type p)) (not (:vararg? p))) (result-class-name t))
                                        et (when (:vararg? p) (types/subst tmap (:vararg-elem p)))
                                        ec (when (and et (exact? (:vararg-elem p))) (result-class-name et))]
                                    (cond-> (assoc p :type t)
                                      c (assoc :jvm-type c)
                                      et (assoc :vararg-elem et)
                                      ec (assoc :elem-jvm ec))))
                                ps))))))

(defn short-form [form]
  (let [s (pr-str form)] (if (> (count s) 100) (str (subs s 0 97) "...") s)))

(defn- compile-failure
  "The error for a Kotlin compiler failure on a bridge: the user's form, the Kotlin signature, the type
  arguments and the compiler's messages. Not the generated source (it is in ex-data :kt/bridge-source)."
  [form decl targs e]
  (let [d (ex-data e)]
    (if-let [msgs (:kt/compile-failed d)]
      (ex-info (str "kt: the Kotlin compiler rejected " (short-form form) "\n"
                    "  Kotlin signature: " (:signature decl) "\n"
                    "  type arguments: " (str/join ", " (map types/source targs)) "\n"
                    "  Kotlin says:\n    "
                    (str/join "\n    " (if (seq msgs)
                                         msgs
                                         [(let [o (str/trim (str (:kt/output d)))] (if (> (count o) 400) (subs o 0 400) o))])))
               d)
      e)))

(defn- bridge-decl
  "The synthetic static declaration `call` of the compiled bridge class `bc`: its parameters are the
  receivers and the supplied parameters of `decl`, typed with the type arguments (see `ckway.bridge.ksrc`)."
  [decl spec ^Class bc]
  (let [^Method m (first (filter #(and (= "call" (.getName ^Method %)) (not (.isSynthetic ^Method %))) (.getDeclaredMethods bc)))
        suspend? (:suspend? spec)
        pts (cond-> (vec (.getParameterTypes m)) suspend? pop)
        ret (:ret spec)
        tmap (tmap-of decl (:targs spec))]
    {:kind :function :name "call" :var-name (:var-name decl) :owner (.getName bc)
     :receivers []
     :params (mapv (fn [p ^Class pt]
                     {:name (:label p) :type (:type p) :default? false
                      :vararg? (boolean (:vararg? p)) :vararg-elem (:elem p)
                      :jvm-type (if-let [vc (:value-class (:type p))] (:class vc) (.getName pt))})
                   (:params spec) pts)
     :return (plain-type ret)
     :result-class (result-class-name ret)
     :type-params (vec (:class-type-params decl)) :flags (cond-> #{:static} suspend? (conj :suspend))
     :jvm {:class (.getName bc) :name "call" :static? true
           :desc (.toMethodDescriptorString (MethodType/methodType (.getReturnType m) (.getParameterTypes m)))}
     :signature (str (:signature decl) "  [" (str/join ", " (for [[n t] tmap] (str n " = " (types/source t)))) "]")}))

(defn- check-context-types!
  "A bridge passes a context argument with `with(arg) { ... }`, and Kotlin resolves it by type: two context
  parameters of one type would both get the innermost argument. Kotlin has no syntax to pass them apart, so
  such a reified call is refused."
  [form decl targs]
  (let [tmap (tmap-of decl targs)
        ctxs (filter #(= :context (:role %)) (:receivers decl))
        same (first (filter #(> (count %) 1) (vals (group-by #(types/source (types/subst tmap (:type %))) ctxs))))]
    (when same
      (fail (str "kt: " (short-form form) ": the context parameters "
                 (str/join " and " (map #(str "`" (:name %) "`") same)) " have the same type `"
                 (types/source (types/subst tmap (:type (first same)))) "`. A reified call is compiled as Kotlin source, "
                 "and Kotlin resolves a context argument by its type, so every such parameter would get the same value. "
                 "Kotlin has no syntax to pass them apart, so kt cannot make this call.\n  Kotlin signature: " (:signature decl))))))

(defn- expand-reified
  "[bridge-decl items] for the call of the reified `decl`: compiles (or loads) the Kotlin bridge."
  [form decl items targs]
  (check-context-types! form decl targs)
  (let [nr (count (:receivers decl))
        pitems (subvec items nr)
        supplied (set (keep-indexed (fn [i it] (when-not (or (:omitted it) (and (:vararg it) (empty? (:vararg it)))) i)) pitems))
        build (requiring-resolve 'ckway.bridge.ksrc/build)
        source (requiring-resolve 'ckway.bridge.ksrc/source)
        spec (assoc (build decl targs supplied) :targs targs)
        bc (try (bridge/kotlin-bridge-class (assoc (select-keys spec [:readable :identity :stamp-classes])
                                                   :source #(source spec %)
                                                   :what (str "the call `" (short-form form) "`")))
                (catch clojure.lang.ExceptionInfo e (throw (compile-failure form decl targs e))))]
    [(bridge-decl decl spec bc) (vec (map #(nth items (:slot %)) (:params spec)))]))

(defn- check-typed!
  "The arguments must also fit the parameter types with the type arguments substituted
  (`(describe nil :<> String)`: nil for a String)."
  [form d items]
  (when-let [bad (some #(when (= :no (first %)) (second %)) (check-items (slots d) items))]
    (fail (str "kt: " (short-form form) ": " bad "\n  Kotlin signature: " (:signature d)))))

(defn- with-site
  "The plan `p` of the call of the var `v` written as `uform` (with the argument forms `forms`): it also knows the call
  and where it came from, for the error of a value of a wrong class (`ckway.rt/wrong-class`)."
  [p ^clojure.lang.Var v uform forms]
  (assoc p :call-text (short-form uform)
         :site {:ns (str (ns-name (.ns v))) :name (str (.sym v))
                :alias (some-> (some (fn [[a n]] (when (= n (.ns v)) a)) (ns-aliases *ns*)) str)
                :recv (some-> (first forms) short-pr)}))

(defn- expand-typed
  "Expansion of a call with `:<>`."
  [form decl items tform v forms]
  (when (and (reified? decl) (not= :function (:kind decl)))
    (not-supported "inline reified properties" decl))
  (let [targs (types/resolve-forms *ns* tform)
        [d its] (if (reified? decl)
                  (expand-reified form decl items targs)
                  [(typed-decl decl targs) items])]
    (check-typed! form d its)
    (emit (with-site (plan d (type-literals d its)) v form forms))))

;; ---------------------------------------------------------------- macro

(defn warn-dynamic [form var-name]
  (when *warn-on-reflection*
    (binding [*out* *err*]
      (println (str "Reflection warning, " *file* ":" (:line (meta form)) ":" (:column (meta form))
                    " - call to " var-name " can't be resolved statically (argument or receiver types unknown); using the dynamic path.")))))

(defn dynamic-form
  "Form that calls the dynamic path with parsed arguments. An integer literal is a Long value at
  run time; `lits` tells the dynamic path which arguments were literals (by position or name),
  so it chooses between an Int and a Long overload as the static path does."
  [v parsed]
  (let [lits (into {} (concat (keep-indexed (fn [i e] (when-let [l (:lit (:info e))] [i l])) (:positional parsed))
                              (keep (fn [[n e]] (when-let [l (:lit (:info e))] [n l])) (:named parsed))))]
    `(ckway.rt/call-dyn (var ~(symbol (str (ns-name (.ns ^clojure.lang.Var v))) (str (.sym ^clojure.lang.Var v))))
                     [~@(map :arg (:positional parsed))]
                     ~(into {} (map (fn [[n e]] [n (:arg e)]) (:named parsed)))
                     ~@(when (seq lits) [lits]))))

(def ^:private kotlin-hint-classes
  {"kotlin/Int" "java.lang.Integer" "kotlin/Long" "java.lang.Long" "kotlin/Short" "java.lang.Short"
   "kotlin/Byte" "java.lang.Byte" "kotlin/Double" "java.lang.Double" "kotlin/Float" "java.lang.Float"
   "kotlin/Char" "java.lang.Character" "kotlin/Boolean" "java.lang.Boolean" "kotlin/String" "java.lang.String"
   "kotlin/CharSequence" "java.lang.CharSequence" "kotlin/Number" "java.lang.Number" "kotlin/Any" nil
   "kotlin/collections/List" "java.util.List" "kotlin/collections/MutableList" "java.util.List"
   "kotlin/collections/Map" "java.util.Map" "kotlin/collections/MutableMap" "java.util.Map"
   "kotlin/collections/Set" "java.util.Set" "kotlin/collections/MutableSet" "java.util.Set"
   "kotlin/collections/Collection" "java.util.Collection" "kotlin/collections/Iterable" "java.lang.Iterable"})

(defn- hint-class-of
  "JVM class name that a value of Kotlin <type> `t` has, if there is a useful one."
  [t]
  (when-let [c (:class t)]
    (if (contains? kotlin-hint-classes c)
      (kotlin-hint-classes c)
      (let [b (meta/internal->binary c)] (when (jvm-class b) b)))))

(defn- param-hints
  "Class names (or nil) of the arguments that Kotlin passes to the function at an adapter slot."
  [spec t]
  (case (:kind spec)
    :fn (mapv hint-class-of (:args (:fn-type t)))
    :fi (or (:hints (:sam spec))
            (mapv (fn [n] (let [c (jvm-class n)]
                            (when (and c (not (.isArray ^Class c)) (not= Object (box-class c))) (.getName (box-class c)))))
                  (cond-> (:params (:sam spec)) (:suspend? (:sam spec)) butlast)))))

(defn- hint-params
  "Type hints on the plain symbols of a parameter vector, when it takes the arguments that Kotlin
  passes. A hint that the user wrote wins; a destructuring pattern is left alone."
  [params hints]
  (let [[fixed more] (split-with #(not= '& %) params)]
    (if (= (count fixed) (count hints))
      (vec (concat (map (fn [p h] (if (and (symbol? p) h (not (:tag (meta p)))) (vary-meta p assoc :tag (symbol h)) p))
                        fixed hints)
                   more))
      params)))

(defn- hint-fn-literal
  "The `fn` literal with the Kotlin parameter types as type hints on its parameters (the
  user's own hints stay). Handles single and multiple arity forms and a name."
  [form hints]
  (let [[head & more] form
        [nm more] (if (symbol? (first more)) [[(first more)] (rest more)] [[] more])
        clause (fn [c] (if (and (seq? c) (vector? (first c)))
                         (with-meta (apply list (hint-params (first c) hints) (rest c)) (meta c))
                         c))]
    (with-meta
      (apply list head (if (vector? (first more))
                         (concat nm [(hint-params (first more) hints)] (rest more))
                         (concat nm (map clause more))))
      (meta form))))

(defn type-literals
  "A `fn` literal at an adapter slot gets the Kotlin parameter types as local type information, so
  kt calls on its parameters are resolved statically (DESIGN-2 section 2.5)."
  [decl items]
  (vec (map (fn [slot item]
              (let [ad (:adapt slot)]
                (if (and ad (not (:feature ad)) (fn-literal? (:arg item)))
                  (update item :arg hint-fn-literal (param-hints ad (:kotlin-type slot)))
                  item)))
            (slots decl) items)))

(defn- companion-unknown?
  "Does the call pass a receiver of unknown type where the class var of a companion is expected?
  The static path would ignore it, so such a call checks the receiver at run time."
  [decl items]
  (boolean (some (fn [[slot item]]
                   (and (:companion-of slot) (empty? (select-keys (:info item) [:class :companion-of]))))
                 (map vector (slots decl) items))))

(defn- user-form
  "The call as the user wrote it, `(alias/name args...)`, for messages."
  [^clojure.lang.Var v forms]
  (let [alias (some (fn [[a n]] (when (= n (.ns v)) a)) (ns-aliases *ns*))]
    (apply list (symbol (some-> alias str) (str (.sym v))) forms)))

(defn- guard-failed-objects
  "An argument form that is the var of an object whose initialiser failed (`:kt/failed`, see `ckway.core`) becomes
  `(ckway.rt/live form)`: the call then throws the error that names the object instead of a ClassCastException."
  [env forms]
  (map (fn [f]
         (if (and (symbol? f) (not (and (nil? (namespace f)) (contains? env f)))
                  (let [r (try (ns-resolve *ns* f) (catch Exception _ nil))] (and (var? r) (:kt/failed (meta r)))))
           `(ckway.rt/live ~f)
           f))
       forms))

(defn expand
  "Compile-time expansion of a call of var `v` with argument forms `forms`."
  [env form v forms0]
  (let [forms (guard-failed-objects env forms0)
        uform (user-form v forms0)
        decls (:kt/decls (meta v))
        var-name (str (.sym ^clojure.lang.Var v))
        parsed (parse-args var-name decls forms (fn [f] {:arg f :info (form-info env f)}))
        {:keys [decl items checks] :as r} (choose var-name decls parsed true)]
    (cond
      (and (:dynamic? r) (contains? r :tform))
      (fail (str "kt: " (short-form uform) ": `:<>` needs the static path, but the declaration cannot be selected at compile "
                 "time (the type of an argument or receiver is unknown and several declarations fit). Add a type hint, "
                 "e.g. `^web.Exchange ex`.\n  Kotlin declares:\n" (signatures decls)))
      (:dynamic? r) (do (warn-dynamic form var-name) (dynamic-form v parsed))
      :else
      (do (check-supported! decl checks)
          (cond
            (and (companion-unknown? decl items) (contains? r :tform))
            (fail (str "kt: " (short-form uform) ": `:<>` needs the static path, but the receiver of a companion member is not "
                       "known to be the class var at compile time. Call it with the class var itself."))
            (companion-unknown? decl items) (do (warn-dynamic form var-name) (dynamic-form v parsed))
            (contains? r :tform) (expand-typed uform decl items (:tform r) v forms0)
            :else (emit (with-site (plan decl (type-literals decl items)) v uform forms0)))))))

(defmacro kt-call
  "Static path for a call of a kt var. `var-form` is `(var ns/name)`."
  [var-form & args]
  (let [v (resolve (second var-form))]
    (expand &env &form v args)))
