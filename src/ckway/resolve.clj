(ns ckway.resolve
  "Selection of exactly one Kotlin declaration and the JVM call for it.

  Pure functions (parse, bind, choose, plan) are shared with the run-time path
  in `ckway.rt`.  Only `emit` and the `kt-call` macro run at compile time.

  Type arguments (`:<>`, README rule 5): `choose` takes the pair out of the named arguments, keeps
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
  Documented cache (`ckway.meta/memo`: a class that is not found is NOT cached, it may be on the class path later)."
  (meta/memo
   ::jvm-class
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

(def ^:private array-tags
  "The tags of Clojure for an array of a primitive (`^bytes`) and for `^objects`."
  {"ints" (Class/forName "[I") "longs" (Class/forName "[J") "floats" (Class/forName "[F")
   "doubles" (Class/forName "[D") "chars" (Class/forName "[C") "shorts" (Class/forName "[S")
   "bytes" (Class/forName "[B") "booleans" (Class/forName "[Z") "objects" (Class/forName "[Ljava.lang.Object;")})

(defn tag->class [env-ns tag]
  (cond
    (class? tag) tag
    (string? tag) (or (prim-classes tag) (jvm-class tag))
    (symbol? tag) (or (prim-classes (name tag))
                      (when (nil? (namespace tag)) (array-tags (name tag)))
                      (let [r (try (ns-resolve env-ns tag) (catch Exception _ nil))] (when (class? r) r)))))

(defn- local-class [^Compiler$LocalBinding lb]
  (try (when (.hasJavaClass lb) (.getJavaClass lb)) (catch Throwable _ nil)))

(defn- bound-info
  "Info for an argument whose compile-time class is `c`, which is only an upper bound of the class
  of the value. No class, or Object, says nothing: unknown. (The static type of a local is Object
  whenever Clojure cannot infer more; it is not a mismatch with any parameter type.)"
  [c]
  (if (and c (not= Object c)) {:class c :upper true} {}))

(defn- hinted-info
  "Info for an argument whose class `c` the USER wrote (`^Type` on a local, a parameter or the form): it is the static type
  of the argument, as a declared type is in Kotlin, so selection takes it as the type (`:static`), not as an upper
  bound of the class of the value. `^Object` says nothing. A primitive hint is only an upper bound."
  [c]
  (cond (or (nil? c) (= Object c)) {}
        (.isPrimitive ^Class c) {:class c :upper true}
        :else {:class c :static true}))

(defn lib-tag
  "The symbol `p` with the class name `h` as a type hint that the LIBRARY adds (a `fn` literal at a function-type
  parameter, a `kt/reify` member). It is marked, so that it is never mistaken for a hint the user wrote: it is only
  an upper bound of the class of the value (see `user-hinted?`)."
  [p h]
  (vary-meta p assoc :tag (symbol h) :ckway/lib-tag true))

(defn- user-hinted?
  "Did the USER write a type hint on the local? A tag that the library added (`lib-tag`) does not count."
  [^Compiler$LocalBinding lb]
  (and (some? (.-tag lb)) (not (:ckway/lib-tag (meta (.-sym lb))))))

(defn- literal-info
  "Info for a literal. An integer literal is a Long to Clojure; Kotlin types it by size (Int if it fits)."
  [form]
  (cond
    (string? form) {:class String}
    (integer? form) (let [c (class form)]
                      (cond-> {:class c}
                        (= Long c) (assoc :lit (if (<= Integer/MIN_VALUE form Integer/MAX_VALUE) :int :long) :val form)))
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

(defn- fn-var?
  "Does the var hold a function (`defn`, an `:arglists`, a root that is a fn)? Its :tag is then the tag of the result."
  [^clojure.lang.Var v]
  (boolean (or (:arglists (meta v)) (fn? (try (deref v) (catch Throwable _ nil))))))

(declare form-info parse-args choose unsupported return-class typed-return-class vc-conv check-result? param-hints)

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

(def ^:private read-only-kotlin
  #{"kotlin/collections/Iterable" "kotlin/collections/Collection" "kotlin/collections/List" "kotlin/collections/Set"
    "kotlin/collections/Map"})

(defn- non-nil-result?
  "Is the result of a call of `decl` sure not to be nil? Its Kotlin type is not nullable (a type parameter whose bounds are
  nullable can be), and is no `Unit` or `Nothing` (`Unit` is nil here). A property read is a call too."
  [decl]
  (let [t (:return decl)]
    (boolean (or (and (= :class (:kind decl)) (not (:no-constructor (:flags decl))))   ; a constructor
                 (and t (#{:function :property} (:kind decl))
                      (not (:nullable? t)) (not (:type-param t)) (not (:star? t))
                      (not (#{"kotlin/Unit" "kotlin/Nothing"} (:class t))))))))

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
            (cond-> (bound-info (and (:decl r)
                                     (if (contains? r :tform)
                                       (typed-return-class (:decl r) (types/resolve-forms *ns* (:tform r)))
                                       (return-class (:decl r)))))
              ;; Kotlin declares the result as a read-only collection (see `applicability*`)
              (and (:decl r) (read-only-kotlin (:class (:return (:decl r))))) (assoc :read-only true)
              ;; Kotlin declares the result as a type that is not nullable: the value is not nil
              (and (:decl r) (not (contains? r :tform)) (non-nil-result? (:decl r))) (assoc :non-nil true)))
          (catch clojure.lang.ExceptionInfo e
            (if (:kt/error (ex-data e)) {} (throw e))))))))

(defn- fn-literal-info
  "Info for a `fn` literal: a function, and, when the USER wrote type hints on its parameters (`(fn [^Call c status] ...)`),
  `:fn-hints`, the class of each parameter (nil where there is none). Only a literal with one arity and no `&` has them;
  a hint that the library adds (`lib-tag`) is not one."
  [form]
  (let [params (first (filter vector? (take 2 (rest form))))
        hints (when (and params (not (some #{'&} params)))
                (mapv #(when-not (:ckway/lib-tag (meta %)) (some->> (:tag (meta %)) (tag->class *ns*))) params))]
    (cond-> {:class clojure.lang.AFunction}
      (some some? hints) (assoc :fn-hints hints))))

;; the :tag of a call of a var, as the Clojure compiler takes it (`InvokeExpr`, `sigTag`): the tag of the first arglist
;; that takes this number of arguments (a variadic one takes any number from its `&`), else the tag of the var
(defn- arglist-tag [arglists n]
  (when-let [sig (first (filter (fn [sig]
                                  (let [rest-at (.indexOf ^java.util.List sig '&)]
                                    (or (= n (count sig)) (and (>= rest-at 0) (>= n rest-at)))))
                                arglists))]
    (:tag (meta sig))))

(defn- call-tag-info
  "Info for a form that calls a var that is no macro and has no inline expansion for this number of arguments, from the
  tag that the Clojure compiler would give the call: the tag of the arglist, else of the var (`(defn u ^String [s] ...)`).
  The user wrote it, so it is the static type of the argument, as a hint on the form is (`hinted-info`)."
  [env form]
  (let [head (first form)
        v (when (and (symbol? head) (not (and (nil? (namespace head)) (contains? env head))))
            (try (ns-resolve *ns* head) (catch Exception _ nil)))]
    (if-not (and (var? v) (not (:macro (meta v))))
      {}
      (let [m (meta v) n (count (rest form))]
        (if (and (:inline m) (or (nil? (:inline-arities m)) ((:inline-arities m) n)))
          {}
          (let [tag (or (arglist-tag (:arglists m) n) (:tag m))
                c (when tag (or (tag->class (.ns ^clojure.lang.Var v) tag) (tag->class *ns* tag)))]
            (if c (hinted-info c) {})))))))

(defn form-info
  "Info for an argument form at compile time. Uses literals, :tag hints (a hint that the user wrote is the static
  type, see `hinted-info`), local binding classes (only an upper bound, unless the user hinted the local), kt class/object vars, `(long x)`-style casts, and calls of kt vars with one
  matching declaration. Everything else is unknown."
  [env form]
  (let [tagc (some->> (:tag (meta form)) (tag->class *ns*))
        head (when (seq? form) (first form))]
    (cond
      (nil? form) {:nil? true}
      tagc (hinted-info tagc)
      (vector? form) (let [elems (mapv #(form-info env %) form)]
                       ;; the element infos let a named vararg collection choose (`vararg-coll-check`)
                       (cond-> (literal-info form)
                         (every? #(and (or (:class %) (:nil? %)) (not (:upper %))) elems) (assoc :elems elems)))
      (literal-info form) (literal-info form)
      (symbol? form)
      (if-let [lb (get env form)]
        ((if (user-hinted? lb) hinted-info bound-info) (local-class lb))
        (let [r (try (ns-resolve *ns* form) (catch Exception _ nil))]
          (cond
            (and (var? r) (:kt/class (meta r))) {:class clojure.lang.AFn :companion-of (:kt/class (meta r))}
            (and (var? r) (:kt/object (meta r))) (if-let [c (jvm-class (:kt/object (meta r)))] {:class c} {})
            ;; an enum entry var holds the entry: its class is the enum class (an entry with a body is a subclass)
            (and (var? r) (some #(= :enum-entry (:kind %)) (:kt/decls (meta r))))
            (if-let [c (jvm-class (:owner (first (:kt/decls (meta r)))))] {:class c} {})
            ;; the :tag of a var that holds a function is the type of its RESULT (`(defn ^String f ...)`)
            (and (var? r) (not (fn-var? r)) (tag->class *ns* (:tag (meta r)))) (hinted-info (tag->class *ns* (:tag (meta r))))
            :else {})))
      (and (symbol? head) (#{'fn 'fn* 'clojure.core/fn} head)) (fn-literal-info form)
      (and (symbol? head) (= 'new head))
      (if-let [c (tag->class *ns* (second form))] {:class c} {})
      (and (symbol? head) (str/ends-with? (name head) ".") (nil? (namespace head)))
      (if-let [c (tag->class *ns* (symbol (subs (name head) 0 (dec (count (name head))))))] {:class c} {})
      (and (seq? form) (core-var? env head) (cast-classes (name head))) {:class (cast-classes (name head))}
      (and (seq? form) (ref-head? env head)) (or ((requiring-resolve 'ckway.ref/form-info) env form) {})
      (and (seq? form) (reify-head? env head)) (if-let [c ((requiring-resolve 'ckway.reify/form-info) form)] {:class c} {})
      (seq? form) (let [r (nested-call-info env form)] (if (seq r) r (call-tag-info env form)))
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
  methods): an implementation must call them. Read by reflection. Documented cache (`ckway.meta/memo`)."
  (meta/memo
   ::sam-of
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
  (meta/memo
   ::sam-kdecl
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

(defn- java-return-td
  "Type descriptor of the result of the SAM of the Java interface `iface` for the Kotlin type `t` (`Function<String, Int>`):
  when the method returns the interface's type parameter `R`, the descriptor of the matching type argument, so a
  Clojure Long becomes an Integer for `Int`. nil otherwise."
  [iface sam t]
  (when-let [^Class c (jvm-class iface)]
    (when-let [^Method m (first (filter #(and (= (:name sam) (.getName ^Method %)) (= (:desc sam) (.toMethodDescriptorString (MethodType/methodType (.getReturnType ^Method %) (.getParameterTypes ^Method %)))))
                                        (.getMethods c)))]
      (when (= c (.getDeclaringClass m))
        (let [gt (.getGenericReturnType m)]
          (when (instance? java.lang.reflect.TypeVariable gt)
            (let [i (.indexOf ^java.util.List (mapv #(.getName ^java.lang.reflect.TypeVariable %) (.getTypeParameters c))
                              (.getName ^java.lang.reflect.TypeVariable gt))
                  a (when (>= i 0) (nth (:args t) i nil))]
              (when (and a (:class a) (not (:type-param a)))
                (type-descriptor a)))))))))

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
         sam (if-let [td (and sam (nil? kd) t (java-return-td iface sam t))] (assoc sam :ret-td td) sam)
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

(defn- type-param-of
  "The declaration of the type parameter that the type `t` names (a function's or its class's), or nil."
  [decl t]
  (when-let [n (:type-param t)]
    (first (filter #(= n (:name %)) (concat (:type-params decl) (:class-type-params decl))))))

(defn- nullable-type?
  "Can a Kotlin value of type `t` be null? A type parameter whose bounds are all nullable (the default
  bound is `Any?`) can: `fun <T> echo(x: T)` takes nil; `T : Any` does not. A bound that is itself a type parameter
  is as nullable as that parameter (`<T, U : T>`)."
  ([decl t] (nullable-type? decl t 0))
  ([decl t depth]
   (boolean (or (:nullable? t)
                (when-let [tp (type-param-of decl t)]
                  (and (< depth 8)
                       (every? #(nullable-type? decl % (inc depth)) (:bounds tp))))))))

(defn- nonnull-tparam
  "The text of the non-null bound of a type parameter `t` that does not take nil (`T : Any`), or nil when `t` is no
  such type parameter. A nil for it is a `kt:` error, as it is for a parameter of a plain non-null type."
  [decl t]
  (when (and (:type-param t) (not (nullable-type? decl t)))
    (let [tp (type-param-of decl t)
          b (first (remove #(nullable-type? decl %) (:bounds tp)))]
      (str (:type-param t) " : " (if b (meta/type-text b) "Any")))))

(defn- plain-nn
  "{:type text} for a parameter or receiver of the plain non-null Kotlin type `t` (not a type parameter: `nonnull-tparam`)
  whose JVM slot `jt` holds a reference: a nil for it is a `kt:` error. nil for a nullable type, a primitive, `Unit`/`Nothing`,
  a value class (`vc-arg` checks it) and a type parameter."
  [decl t jt]
  (when (and t jt (not (:type-param t)) (not (:star? t)) (not (:value-class? t)) (not (:value-class t))
             (not (nullable-type? decl t)) (not (contains? prim-classes jt))
             (not (#{"kotlin/Unit" "kotlin/Nothing"} (:class t))))
    {:type (meta/type-text t)}))

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
         :elem-nullable? (when-let [et (:vararg-elem p)] (nullable-type? decl et))
         :elem-type (:vararg-elem p)
         :adapt (adapter-spec t (:jvm-type p) (:signature decl))
         :kotlin-type t})))))

;; ---------------------------------------------------------------- bind

(defn- missing-text
  "The error for the parameter `m` (at index `i`) that has no argument. A positional argument reaches it only when it is
  the next one in sequence: not after a `vararg`, not after a skipped default, not after a named argument."
  [m i nfixed vidx named?]
  (if (and (not named?) (= i nfixed) (or (nil? vidx) (< i vidx)))
    (str "missing required parameter `" m "`. Pass it positionally or as `:" m "`.")
    (str "missing required parameter `" m "`. A positional argument cannot reach it here: name it, `:" m "`.")))

(defn- bind*
  "`bind` by the usual rule: positional in sequence, a `vararg` takes the rest. A missing parameter is
  {:error text :missing [name index]}."
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
                  {:error (missing-text m (.indexOf ^java.util.List pnames m) (count fixed) vidx (boolean (seq named)))
                   :missing [m (.indexOf ^java.util.List pnames m)]}
                  {:items (vec (concat (take nr positional) items))})))))))))

(defn- trailing-lambda?
  "Can the last parameter of `decl` take a trailing lambda? It has a function type, or is a `fun interface` or a Java
  single-method interface (`adapter-spec`), and it has no default and is no `vararg`."
  [decl]
  (let [p (last (:params decl))]
    (boolean (and p (not (:default? p)) (not (:vararg? p))
                  (adapter-spec (:type p) (:jvm-type p) (:signature decl))))))

(defn bind
  "Bind parsed args to the slots of `decl`. => {:items [...]} or {:error text}.
  `trailing?` (README rule 4, a trailing lambda): when the usual binding leaves the LAST parameter without an argument,
  and it can take a function (`trailing-lambda?`), the last positional argument (after the receivers) is bound to it and
  the others are bound again without it (a skipped parameter takes its default, a `vararg` takes what is left). The
  result then has `:trailing true`. When the others cannot be bound that way, the error of the usual binding stays."
  ([decl parsed] (bind decl parsed false))
  ([decl {:keys [positional] :as parsed} trailing?]
   (let [r (bind* decl parsed)
         params (:params decl)]
     (if (and trailing? (:missing r) (= (second (:missing r)) (dec (count params)))
              (trailing-lambda? decl) (> (count positional) (count (:receivers decl))))
       (let [r2 (bind* (assoc decl :params (vec (butlast params))) (update parsed :positional pop))]
         (if (:items r2)
           {:items (conj (:items r2) (peek positional)) :trailing true}
           r))
       r))))

;; ---------------------------------------------------------------- applicability

(defn- item-entries [item]
  (cond (:omitted item) [] (:vararg item) (:vararg item) (:vararg-coll item) [] :else [item]))

(defn- numeric-tier
  "[tier conv?] of passing an argument of class `ac` to a numeric parameter of class `pc`, or nil. Tier 0 is best.
  `conv?` is true when the library changes the number to make it fit (an integer to a Double or Float, a Long to an
  Int, an Int to a Long...): Kotlin itself accepts no such value, so `choose` uses a candidate that needs it only when no
  other candidate takes the value as it is. `lit` is the literal kind of the argument (see `literal-info`) and `val` its
  value: Kotlin types an integer literal by its size, so one that fits Int prefers Int, can be a Long, and a Short or
  Byte when it is in their range; one that does not fit Int is a Long."
  [^Class pc ^Class ac lit val]
  (let [pb (box-class pc)
        in-range? (fn [lo hi] (or (not (integer? val)) (<= lo val hi)))]
    (cond
      (= :long lit) (cond (= pb Long) [0 false]
                          (contains? #{Integer Short Byte} pb) [2 true]
                          (contains? float-classes pb) [2 true])
      (= :int lit) (cond (= pb Integer) [0 false] (= pb Long) [1 false]
                         (= pb Short) [2 (not (in-range? Short/MIN_VALUE Short/MAX_VALUE))]
                         (= pb Byte) [2 (not (in-range? Byte/MIN_VALUE Byte/MAX_VALUE))]
                         (contains? float-classes pb) [2 true])
      (= pb ac) [0 false]
      (and (= pb Long) (contains? #{clojure.lang.BigInt java.math.BigInteger} ac)) [1 true]
      (contains? #{Long Integer Short Byte} pb) (when (integral-class? ac) [2 true])
      (contains? float-classes pb) (when (or (integral-class? ac) (contains? float-classes ac) (isa? ac Number)) [2 true]))))
      ;; only a Clojure character is a Kotlin Char

(defn- lit-note [info]
  (when (= :long (:lit info)) " (an integer literal that does not fit Int)"))

(declare applicability* mutable-kotlin?)

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
    (cond
      (and (= :no (first r)) (:upper info) pc ac (could-be? pc ac)) [:unknown :upper]
      ;; `^Number` written by the user for an argument that goes to an integer parameter: Number is not a width, kt
      ;; converts a number to the width the parameter declares, so the class of the value decides at run time
      (and (= :no (first r)) (:static info) (= Number ac) pc (integral-class? pc)) [:unknown :upper]
      :else r)))

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
      (.isPrimitive pc) (if-let [[t conv?] (if (= Boolean (box-class pc)) (when (= ac Boolean) [0 false]) (numeric-tier pc ac (:lit info) (:val info)))]
                          (if conv? [:ok t :conv] [:ok t])
                          [:no (str "`" (:name slot) "` is " (.getName pc) " but got " (.getSimpleName ac) (lit-note info))])
      (contains? #{Integer Long Short Byte Double Float Character} pc)
      (if-let [[t conv?] (numeric-tier pc ac (:lit info) (:val info))]
        (if conv? [:ok t :conv] [:ok t])
        [:no (str "`" (:name slot) "` is " (.getSimpleName pc) " but got " (.getSimpleName ac) (lit-note info))])
      ;; a reference type. How close the parameter type is to the class of the argument does not choose between
      ;; candidates: Kotlin's specificity rule does (`most-specific`), which compares the parameter types
      ;; A Clojure persistent collection implements the java.util interfaces, which are Kotlin's MUTABLE types, but it
      ;; cannot be changed; so cannot the result of a kt call that Kotlin declares as a read-only List, Set, Map...
      ;; Taking such a value at a `Mutable*` parameter is a conversion (`:conv`, as the function adapter below and the
      ;; conversion of a number): `choose` uses that candidate only when no candidate takes the values as they are.
      (.isAssignableFrom pc ac) (cond-> [:ok (if (= pc Object) 3 1)]
                                  (and (mutable-kotlin? (:kotlin-type slot))
                                       (or (:read-only info) (isa? ac clojure.lang.IPersistentCollection)))
                                  (conj :conv :read-only))
      ;; a function goes to a function type or a `fun interface` through an adapter. For a value that is no function
      ;; itself (a vector, a map, a set, a keyword, a var: all IFn) the adapter is a CONVERSION, like the library's own
      ;; conversion of a number: `choose` uses a candidate that needs one only when no candidate takes the values as
      ;; they are (a vector is a List before it is a function)
      (and (:adapt slot) (isa? ac clojure.lang.IFn))
      (cond-> [:adapter (:feature (:adapt slot))] (not (isa? ac clojure.lang.Fn)) (conj :conv))
      :else [:no (str "`" (:name slot) "` is " (.getSimpleName pc) " but got " (.getSimpleName ac))])))

(defn- vararg-slot
  "The slot of ONE element of the `vararg` slot `slot`: the class, nullability and Kotlin type of the element."
  [slot]
  (let [c (:class slot)]
    (assoc slot :class (or (:elem-class slot) (some-> ^Class c .getComponentType)) :vararg? false
           :nullable? (:elem-nullable? slot) :kotlin-type (:elem-type slot))))

(defn- vararg-coll-check
  "How well a collection passed as a whole (`:xs coll`) fits the `vararg` slot `slot`, from what is known about it: a
  primitive or typed array must be an array of the slot, and a vector literal is checked element by element (the tier
  is the worst of them). Any other collection is a Clojure collection that holds anything: it fits every vararg
  slot (the run time converts the elements), so it cannot choose between vararg overloads (see `ambiguity-help`); a
  collection of unknown class is not decided at compile time."
  [slot info]
  (let [^Class ac (:class info) ^Class pc (:class slot)
        simple (fn [^Class c] (.getSimpleName c))]
    (cond
      (and ac pc (.isArray ac) (.isArray pc))
      (cond (.isAssignableFrom pc ac) [:ok 0]
            (or (.isPrimitive (.getComponentType ac)) (.isPrimitive (.getComponentType pc)) (not= Object (.getComponentType ac)))
            [:no (str "`" (:name slot) "` is " (simple pc) " but got " (simple ac))]
            :else [:ok 0])
      (and ac (:elems info))
      (let [vs (vararg-slot slot)
            rs (map #(applicability vs %) (:elems info))]
        (if-let [bad (some #(when (= :no (first %)) (second %)) rs)]
          [:no bad]
          (let [ts (keep #(when (= :ok (first %)) (second %)) rs)]
            (cond-> [:ok (if (seq ts) (apply max ts) 0)]
              (some #(= :conv (nth % 2 nil)) rs) (conj :conv)))))
      (nil? ac) [:unknown]
      :else [:ok 0])))

(defn check-items
  "Applicability results, in slot order, one per written argument."
  [slots items]
  (vec
   (mapcat
    (fn [slot item]
      (cond
        (:omitted item) []
        (:vararg-coll item) [(vararg-coll-check slot (:info (:vararg-coll item)))]
        (:vararg item) (map #(applicability (vararg-slot slot) (:info %)) (:vararg item))
        :else [(applicability slot (:info item))]))
    slots items)))

(defn- member? [decl] (boolean (some #(= :dispatch (:role %)) (:receivers decl))))

;; ---------------------------------------------------------------- unsupported

(defn unsupported
  "Name of the feature that blocks calling `decl`, or nil."
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
  the underlying value. `called` (optional) is the JVM member that the call really runs when it is not the member of
  `decl` itself: the `$default` synthetic. For an override that inherits its defaults that is the synthetic of the
  ORIGINAL, with the original's JVM result type: where the original returns `Any` (JVM Object) and the override a value
  class over a primitive, the Object is the box, not the underlying value."
  ([decl] (return-conv decl nil))
  ([decl called]
   (cond
     (and (= :class (:kind decl)) (:value-class decl)) {:vc (:value-class decl) :nullable? false}
     ;; the JVM result of a suspend function is Object: the box when the call suspended and was resumed, the underlying
     ;; value when it returned at once (a class over a reference type), the box again over a primitive. Either way the
     ;; result is the value-class object (`:suspend?`: `box-form`, `ckway.rt/suspend-boxer`)
     (:suspend (:flags decl)) (some-> (vc-conv (:return decl) "java.lang.Object") (assoc :suspend? true))
     :else (let [jt (or (some-> (:desc called) meta/desc-types :return) (return-jvm-name decl))]
             (when-not (and called (= "java.lang.Object" jt)
                            (not= "java.lang.Object" (:jvm-underlying (:value-class (:return decl)))))
               (vc-conv (:return decl) jt))))))

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
         (cond
           (:unlinkable fl)
           (str "`" name "` cannot be used: it needs the class " (:missing decl) ", which is not on the class path. "
                "(The library that has it is an optional dependency that this program does not have.)")
           alias-type
           (str "`" name "` is a type alias of `" alias-type "`, not a class: it cannot be called as a constructor. "
                "A type alias like this is for type forms (`:<>`).")
           :else
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
  (when-let [m (:missing decl)]
    (fail (str "kt: `" (:name decl) "` cannot be called: it needs the class " m ", which is not on the class path. "
               "(The library that has it is an optional dependency that this program does not have.)"
               "\n  Kotlin: " (:signature decl))
          {:kt/missing-class m}))
  (when (no-constructor? decl)
    (fail (no-constructor-message decl nil)))
  (when-let [f (unsupported decl)]
    (not-supported f decl))
  (when-let [feature (some #(when (= :adapter (first %)) (second %)) checks)]
    (not-supported feature decl)))

;; ---------------------------------------------------------------- Kotlin specificity

(defn- mutable-kotlin? [t] (boolean (some-> (:class t) (str/includes? "/Mutable"))))

;; Type arguments in specificity. The JVM erases them, so a value cannot show them: `[1]` is a List, not a `List<Int>`.
;; Kotlin compares the DECLARED types, and so does this. The erased classes decide first (`slot-subtype?`); when both
;; types are generic and are different Kotlin classes (`List<T>` and `Collection<Int>`), their type arguments
;; must fit too, with the declaration-site variance of the class (`List<out E>`, `MutableList<E>`, `Map<K, out V>`). A pair
;; that this cannot decide is NOT a subtype: the other tie-breaks, or an error, decide, never a guess.

(def ^:private super-arg-indices
  "For the JVM class `cx` and its supertype `cy`: a vector with one entry per type parameter of `cy`, the index of the
  type parameter of `cx` that it is (through the generic superclasses and interfaces: `ArrayList<E>` is a `List<E>`),
  or nil for a parameter that is not simply one of `cx`'s. nil when `cy` is no supertype of `cx`. Documented cache."
  (meta/memo
   ::super-arg-indices
   (fn [^Class cx ^Class cy]
     (letfn [(names [^Class c] (mapv #(.getName ^java.lang.reflect.TypeVariable %) (.getTypeParameters c)))
             (walk [^java.lang.reflect.Type t env]
               (let [^Class raw (cond (instance? Class t) t
                                      (instance? java.lang.reflect.ParameterizedType t) (.getRawType ^java.lang.reflect.ParameterizedType t))]
                 (when raw
                   (let [args (when (instance? java.lang.reflect.ParameterizedType t)
                                (.getActualTypeArguments ^java.lang.reflect.ParameterizedType t))
                         tps (names raw)
                         env' (zipmap tps (concat (map (fn [a] (when (instance? java.lang.reflect.TypeVariable a)
                                                                 (get env (.getName ^java.lang.reflect.TypeVariable a))))
                                                       args)
                                                  (repeat nil)))]
                     (if (= raw cy)
                       (mapv #(get env' %) tps)
                       (some #(walk % env') (concat (when-let [sc (.getGenericSuperclass raw)] [sc])
                                                    (.getGenericInterfaces raw))))))))]
       (if (= cx cy)
         (vec (range (count (names cx))))
         (let [env (zipmap (names cx) (range))]
           (some #(walk % env) (concat (when-let [sc (.getGenericSuperclass cx)] [sc]) (.getGenericInterfaces cx)))))))))

(declare type-eq? type-sub? args-sub?)

;; Two generic candidates. Kotlin asks whether the parameter types of X could be passed to Y, with the type parameters of
;; Y as fresh variables that are inferred from X's types. `env` is that question for one pair of candidates:
;;   :own     the names of Y's type parameters (the variables)
;;   :bounds  {name [upper bounds]} of Y's type parameters, :xbounds the same for X's (they are fixed types here)
;;   :binds   an atom, {name {:exact <type>} | {:lower [<type> ...]}}: what each variable has to be so far. It is one
;;            for ALL parameters of the pair, so a variable is bound consistently (`u5(a: MutableList<T>, b: MutableList<T>)`).
;; What this cannot decide is NOT a subtype: the candidates are then unrelated, never a guess.

(def ^:private no-vars {:own #{}})

(defn- own-var? [t env] (boolean (and (:type-param t) (contains? (:own env) (:type-param t)))))

(defn- within-bounds?
  "Does the type `t` (in X's terms) satisfy the declared upper bounds of Y's type variable `n`?"
  [t n env]
  (every? (fn [b]
            (if (:type-param t)
              (some #(type-sub? % b env) (get (:xbounds env) (:type-param t)))
              (type-sub? t b env)))
          (get (:bounds env) n)))

(defn- bind-var!
  "Y's type variable `ty` meets X's type `tx`: `:eq` - it has to BE that type (an invariant position), `:sub` - `tx`
  has to be a subtype of it (it then is `tx` or a supertype). => true when the variable can still be bound so that
  everything seen so far holds (and the binding is recorded), else false. `bounds?` false: the caller has checked
  the upper bound itself (a parameter whose whole type is the variable: its JVM class is the bound)."
  [tx ty env mode bounds?]
  (let [n (:type-param ty)
        binds (:binds env)
        t (cond-> tx (:nullable? ty) (assoc :nullable? false))   ; `T?` takes `String?` with T = String
        b (get @binds n)
        sub? (fn [x y] (type-sub? x y no-vars))
        ok (cond
             (or (nil? tx) (:star? tx)) false
             ;; `String` is not `T?`, whatever T is
             (and (= :eq mode) (:nullable? ty) (not (:nullable? tx))) false
             (:exact b) (if (= :eq mode) (type-eq? t (:exact b) no-vars) (sub? t (:exact b)))
             (= :eq mode) (when (every? #(sub? % t) (:lower b)) {:exact t})
             ;; several lower bounds: the variable is a common supertype. Without a declared bound there always is one;
             ;; with one, only when the types are in line
             (or (empty? (:lower b)) (empty? (get (:bounds env) n))
                 (some #(sub? t %) (:lower b)) (every? #(sub? % t) (:lower b)))
             {:lower (conj (vec (:lower b)) t)})]
    (cond
      (not ok) false
      (true? ok) true
      :else (let [before @binds]
              (swap! binds assoc n ok)
              (if (or (not bounds?) (within-bounds? t n env))
                true
                (do (reset! binds before) false))))))

(defn- type-eq?
  "Are the Kotlin types `tx` and `ty` the same type? A type variable of Y is bound to `tx` (`bind-var!`)."
  [tx ty env]
  (cond
    (:star? ty) (boolean (:star? tx))
    (:star? tx) false
    (own-var? ty env) (bind-var! tx ty env :eq true)
    (:type-param ty) (and (= (:type-param tx) (:type-param ty)) (= (boolean (:nullable? tx)) (boolean (:nullable? ty))))
    (:type-param tx) false
    :else (and (= (:class tx) (:class ty))
               (= (boolean (:nullable? tx)) (boolean (:nullable? ty)))
               (= (count (:args tx)) (count (:args ty)))
               (every? true? (map #(type-eq? %1 %2 env) (:args tx) (:args ty))))))

(defn- type-sub?
  "Is the Kotlin type `tx` (of a nested type argument) a subtype of `ty`? See `args-sub?`."
  [tx ty env]
  (cond
    (:star? ty) true
    (:star? tx) false
    (own-var? ty env) (bind-var! tx ty env :sub true)
    (:type-param ty) (and (= (:type-param tx) (:type-param ty)) (or (:nullable? ty) (not (:nullable? tx))))
    (and (:class ty) (= "kotlin/Any" (:class ty)))
    (and (or (:nullable? ty) (not (:nullable? tx))) (not (and (:type-param tx) (not (:nullable? ty)))))
    (:type-param tx) false
    (not (or (:nullable? ty) (not (:nullable? tx)))) false
    (or (:fn-type tx) (:fn-type ty)) (type-eq? (assoc tx :nullable? false) (assoc ty :nullable? false) env)
    :else
    (let [^Class jx (types/jvm-class tx) ^Class jy (types/jvm-class ty)]
      (cond
        (or (nil? jx) (nil? jy)) false
        (not (.isAssignableFrom jy jx)) false
        (and (mutable-kotlin? ty) (not (mutable-kotlin? tx))) false
        (empty? (:args ty)) true
        :else (args-sub? tx ty jx jy env)))))

(defn- args-sub?
  "Do the type arguments of `tx` fit those of `ty`, where the class of `tx` (JVM `jx`) is a subtype of that of `ty` (`jy`)?
  The type arguments of `tx` are mapped to the parameters of `ty`'s class through the generic supertypes of `jx`; each
  pair is compared by the declared variance of `ty`'s class (a use-site projection overrides it)."
  [tx ty ^Class jx ^Class jy env]
  (let [idxs (super-arg-indices jx jy)
        variances (meta/class-variances (:class ty))
        axs (:args tx) ays (:args ty)]
    (boolean
     (and idxs (= (count idxs) (count ays)) (= (count (.getTypeParameters jx)) (count axs))
          (every? true?
                  (map-indexed
                   (fn [i ay]
                     (let [ix (nth idxs i)
                           ax (when ix (nth axs ix nil))
                           v (or (:variance ay) (nth variances i :inv))]
                       (cond
                         (:star? ay) true
                         (nil? ax) false
                         (and (:variance ax) (not= (:variance ax) v)) false
                         (= v :out) (type-sub? ax ay env)
                         ;; a contravariant position does not bind a variable: it only checks one that is bound exactly
                         (= v :in) (if (own-var? ay env)
                                     (boolean (when-let [t (:exact (get @(:binds env) (:type-param ay)))] (type-sub? t ax no-vars)))
                                     (and (not-any? #(own-var? % env) (tree-seq :args :args ay)) (type-sub? ay ax env)))
                         :else (type-eq? ax ay env))))
                   ays))))))

(defn- args-fit?
  "The type-argument part of `slot-subtype?`: true unless both types are generic, of different Kotlin classes, and
  the arguments of `sx`'s type do not fit those of `sy`'s."
  [sx sy env]
  (let [kx (:kotlin-type sx) ky (:kotlin-type sy)]
    (if (and (seq (:args kx)) (seq (:args ky)) (:class kx) (:class ky) (not= (:class kx) (:class ky))
             (not (:fn-type kx)) (not (:fn-type ky)))
      (let [jx (types/jvm-class kx) jy (types/jvm-class ky)]
        (boolean (and jx jy (.isAssignableFrom ^Class jy ^Class jx) (args-sub? kx ky jx jy env))))
      true)))

(defn- slot-subtype?
  "Is a value of the parameter slot `sx` always accepted where `sy` is declared (Kotlin: `sx` is a subtype of `sy`)?
  `T` is a subtype of `T?`; the JVM classes decide the rest, plus Kotlin's mapped types: `MutableList` is a subtype
  of `List`, not the other way round, though both are java.util.List; and the type arguments (`args-fit?`). A type
  that is no class (`T`) is `Any?`; when it is a type variable of the candidate of `sy`, it is bound to the type of
  `sx` (`env`, see above), so that it means the same type at every parameter."
  [sx sy env]
  (let [cx (or (some-> ^Class (:class sx) box-class) Object)
        cy (or (some-> ^Class (:class sy) box-class) Object)
        kx (:kotlin-type sx) ky (:kotlin-type sy)]
    (boolean
     (and (or (:nullable? sy) (not (:nullable? sx)))
          (.isAssignableFrom cy cx)
          ;; a read-only Kotlin collection is not a subtype of a mutable one
          (not (and (mutable-kotlin? ky) (not (mutable-kotlin? kx)) (:class kx)))
          (if (and kx (own-var? ky env))
            (bind-var! kx ky env :sub false)
            (args-fit? sx sy env))))))

(def ^:private interface-function-shape
  "[number of Kotlin parameters, suspend?] of the one abstract method of the interface `iface` (a JVM class name), or
  nil when it has not exactly one. The methods of Object do not count (`java.util.Comparator` declares `equals`), as in
  `ckway.meta/class-info`, which says what a `fun interface` is. Documented cache."
  (meta/memo
   ::interface-function-shape
   (fn [iface]
     (when-let [^Class c (jvm-class iface)]
       (let [object-method? (fn [^Method m]
                              (try (.getMethod Object (.getName m) (.getParameterTypes m)) true
                                   (catch NoSuchMethodException _ false)))
             ms (remove object-method? (filter #(Modifier/isAbstract (.getModifiers ^Method %)) (.getMethods c)))]
         (when (= 1 (count ms))
           (let [ps (.getParameterTypes ^Method (first ms))
                 suspend? (and (pos? (alength ps)) (= "kotlin.coroutines.Continuation" (.getName ^Class (aget ps (dec (alength ps))))))]
             [(cond-> (alength ps) suspend? dec) (boolean suspend?)])))))))

(defn- function-shape
  "For a Clojure function (`info`) that goes to a parameter of a Kotlin function type or a `fun interface` (`slot`):
  [number of Kotlin parameters, suspend?] of the function that Kotlin calls there; else nil. Kotlin compares a
  `fun interface` parameter that takes a lambda by the function type of its method, and a Clojure function shows only
  how many parameters that is. (The same test as `applicability*`: any IFn at an adapter slot.)"
  [slot info]
  (when-let [ad (:adapt slot)]
    (when (some-> ^Class (:class info) box-class (isa? clojure.lang.IFn))
      (case (:kind ad)
        :fn [(:arity (:td ad)) (boolean (:suspend? (:td ad)))]
        :fi (interface-function-shape (:iface ad))))))

(defn- entry-slots
  "{entry-idx slot} of the written arguments of the candidate `b` ({:decl :items :checks}) that Kotlin compares when
  it chooses the most specific candidate: the extension and context receivers and the parameters (the dispatch
  receiver is the same object for every member and does not count). This is the ONE alignment of two candidates: by the
  written argument that each parameter receives, wherever a named argument puts it. An element of a `vararg` has the
  slot of its element type; so has a vector literal passed as a whole (`:xs [1 2]`: its elements are known), any other
  collection passed as a whole is not compared. A slot also says how the argument fits it (`applicability`): `:tier`,
  and `:conv?` when it fits only after the library's own conversion of a number (`numeric-tier`); and `:fn-shape`
  when the argument is a Clojure function at a function type or a `fun interface` (`function-shape`)."
  [b]
  (let [fit (fn [slot check entry]
              (assoc slot :conv? (= :conv (nth check 2 nil)) :read-only-conv? (= :read-only (nth check 3 nil))
                     :tier (when (= :ok (first check)) (second check))
                     :fn-shape (function-shape slot (:info entry))))]
    (loop [sl (slots (:decl b)) items (:items b) checks (:checks b) out {}]
      (if (empty? sl)
        out
        (let [slot (first sl) item (first items)
              n (cond (:omitted item) 0 (:vararg item) (count (:vararg item)) :else 1)
              cs (take n checks)
              add (cond
                    (or (= :dispatch (:role slot)) (:omitted item)) nil
                    (:vararg item) (map (fn [e c] [(:idx e) (fit (vararg-slot slot) c e)]) (:vararg item) cs)
                    (:vararg-coll item) (when (:elems (:info (:vararg-coll item)))
                                          [[(:idx (:vararg-coll item)) (fit (vararg-slot slot) (first cs) nil)]])
                    :else [[(:idx item) (fit slot (first cs) item)]])]
          (recur (rest sl) (rest items) (drop n checks) (into out add)))))))

(def ^:private integer-preference
  "Kotlin's order of the integer types for an argument that several of them take as it is (an integer literal): Int
  before Long, Short and Byte; Short before Byte. Long is not compared with Short or Byte (kotlinc: `n(1)` with
  `n(Short)` and `n(Long)` is an overload resolution ambiguity)."
  {Integer #{Long Short Byte} Short #{Byte}})

(def ^:private number-classes #{Integer Long Short Byte Double Float})

(defn- number-preferred?
  "Is the number type of the slot `sx` preferred to that of `sy` for the argument? When both take the value as it is,
  Kotlin's order (`integer-preference`). When the library has to convert the number for one of them (Kotlin has no such
  call), the library's own order, the tier of `numeric-tier`: a BigInt is a Long before it is an Int."
  [sx sy]
  (let [cx (some-> ^Class (:class sx) box-class) cy (some-> ^Class (:class sy) box-class)]
    (boolean (and (number-classes cx) (number-classes cy)
                  (if (or (:conv? sx) (:conv? sy))
                    (and (:tier sx) (:tier sy) (< (long (:tier sx)) (long (:tier sy))))
                    (contains? (integer-preference cx) cy))))))

(defn- at-least-as-specific?
  "Kotlin: candidate `x` is at least as specific as `y` when, for every written argument, the type of the parameter of
  `x` that takes it is a subtype of the type of the parameter of `y` that takes it (or the preferred number type, or,
  for a Clojure function, a function of the same shape: `function-shape`)."
  [x y]
  (let [sx (entry-slots x) sy (entry-slots y)
        bounds (fn [tps] (into {} (map (fn [p] [(:name p) (vec (:bounds p))])) tps))
        env {:own (set (map :name (:type-params (:decl y))))
             :bounds (bounds (:type-params (:decl y)))
             :xbounds (bounds (concat (:class-type-params (:decl x)) (:type-params (:decl x))))
             :binds (atom {})}]
    (every? (fn [[i s]]
              (if-let [t (get sy i)]
                (or (slot-subtype? s t env) (number-preferred? s t)
                    (and (:fn-shape s) (= (:fn-shape s) (:fn-shape t))))
                true))
            sx)))

(def ^:private kotlin-interface?
  "Is the interface `iface` (a JVM class name) written in Kotlin (a `fun interface`), not in Java? Documented cache."
  (meta/memo ::kotlin-interface?
             (fn [iface] (if-let [^Class c (jvm-class iface)] (some? (.getAnnotation c kotlin.Metadata)) false))))

(defn- sam-conversion
  "How the candidate takes a Clojure function where Kotlin would convert a lambda (SAM conversion): nil - only at
  function types; :kotlin - at a Kotlin `fun interface`; :java - at a Java functional interface."
  [b]
  (let [kinds (set (for [s (vals (entry-slots b)) :when (and (:fn-shape s) (= :fi (:kind (:adapt s))))]
                     (if (kotlin-interface? (:iface (:adapt s))) :kotlin :java)))]
    (or (:java kinds) (:kotlin kinds))))

(defn- erased-same?
  "Do the candidates take the same JVM parameter types, though their Kotlin parameter types differ? Then they differ
  only in what the JVM erases (a type argument, the result type of a lambda)."
  [best]
  (and (apply = (map #(mapv :class (slots (:decl %))) best))
       (not (apply = (map #(mapv (comp meta/type-text :kotlin-type) (remove (fn [s] (= :dispatch (:role s))) (slots (:decl %)))) best)))))

(defn- generic? [b] (boolean (seq (:type-params (:decl b)))))

;; What a collection HOLDS. The JVM erases type arguments, so a value does not show them: an ArrayList is an
;; ArrayList, of numbers or of strings. A candidate whose parameter asks something of them - a type parameter with a
;; declared bound in a type-argument position (`MutableList<T>` with `T : Number`), one type variable at two
;; parameters of which one is an invariant position (`a: MutableList<T>, b: MutableList<T>`) - may not take this
;; value at all, and nothing here can tell. So such a
;; candidate is never put before one that asks less: two candidates are ordered only on what can be seen.

(defn- unseen-constraints
  "{written-argument idx -> what the parameter of the candidate `b` that takes it asks of the TYPE ARGUMENTS of the
  value}, only for the parameters that ask something: a vector with, for each type argument of the parameter type,
  nil (a type variable without a bound: any value fits; also a star and a concrete type) or [:bound texts] or [:linked].
  The type parameters that `:<>` gives (`:targs` of the candidate) are known, not unseen. `concrete?`: a concrete type
  argument counts too, as [:type text] (only where a read-only collection would go to a `Mutable*` parameter: `choose*`)."
  [b concrete?]
  (let [d (:decl b)
        given (when (:targs b) (set (map :name (:type-params d))))
        bounds (into {} (map (juxt :name :bounds)) (concat (:class-type-params d) (:type-params d)))
        slots (for [[i s] (entry-slots b) :let [t (:kotlin-type s)] :when t] [i t])
        positions (fn [t] (when-not (:fn-type t)
                            (let [vs (when (:class t) (meta/class-variances (:class t)))]
                              (map-indexed (fn [j a] [a (or (:variance a) (nth vs j :inv))]) (:args t)))))
        ;; a variable that is at an invariant position and at one more place has to be ONE type at both
        uses (frequencies (for [[_ t] slots n (concat (when (:type-param t) [(:type-param t)])
                                                      (keep (comp :type-param first) (positions t)))]
                            n))
        invariant (set (for [[_ t] slots [a v] (positions t) :when (and (:type-param a) (= :inv v))] (:type-param a)))
        ask (fn [[a v]]
              (let [n (:type-param a)]
                (cond (:star? a) nil
                      (and n (contains? given n)) nil
                      (and n (seq (bounds n))) [:bound (mapv meta/type-text (bounds n))]
                      (and n (= :inv v) (invariant n) (> (uses n 0) 1)) [:linked]
                      ;; A CONCRETE type argument (`Collection<Int>`) asks something too, but it is not counted here:
                      ;; `gl(xs: List<T>)` / `gl(xs: Collection<Int>)` with `[1]` is `Collection<Int>` by an older rule
                      ;; of this library ("the non-generic one wins"), which its tests assert (doc/limits.md, 18)
                      (and concrete? (not (and (= "kotlin/Any" (:class a)) (:nullable? a)))) [:type (meta/type-text a)]
                      :else nil)))]
    (into {} (for [[i t] slots :let [c (mapv ask (positions t))] :when (some some? c)] [i c]))))

(defn- type-argument-in-play?
  "Do the candidates differ only in a type argument of a parameter? The JVM erased it, so `Iterable<Double>.maxOrNull`
  and the generic `Iterable<T>.maxOrNull` cannot be told apart by a value, and the generic one may be the right one."
  [candidates]
  (boolean (and (erased-same? candidates)
                (some (fn [b] (some #(seq (:args (:kotlin-type %))) (vals (entry-slots b)))) candidates))))

(defn- may-order?
  "May the candidates `x` and `y` be ordered at all? Only when, for every written argument, their parameters ask the
  same of the type arguments of the value (`unseen-constraints`), or nothing. Where one asks more, the other can be the
  only one that takes the value, or the one that Kotlin would not choose: there is no order, in either direction."
  [x y]
  (let [cx (unseen-constraints x false) cy (unseen-constraints y false)]
    (every? #(= (get cx %) (get cy %)) (distinct (concat (keys cx) (keys cy))))))

(defn- best-shape
  "Of candidates that are equally specific by their parameter types, the one that Kotlin prefers by its shape: one
  without a `vararg` parameter before one with, then the one that leaves fewer default values to the declaration.
  nil when no single candidate is better than every other."
  [candidates]
  (let [vararg? (fn [b] (boolean (some :vararg? (:params (:decl b)))))
        defaults (fn [b] (count (filter :omitted (:items b))))
        better? (fn [x y] (if (= (vararg? x) (vararg? y))
                            (< (defaults x) (defaults y))
                            (vararg? y)))
        best (filter (fn [x] (every? #(or (identical? x %) (better? x %)) candidates)) candidates)]
    (when (= 1 (count best)) (first best))))

(defn most-specific
  "The choice among the applicable `candidates` (maps with :decl, :items and :checks), as Kotlin makes it; everything
  that compares two candidates is here.
    0. A candidate that takes a function at a JAVA functional interface is used only when every candidate does
       (kotlinc: `a(x: Any, f: FI)` is taken before `a(x: String, f: IntUnaryOperator)` for `a(\"s\") { it }`).
    1. The most specific candidates by parameter types: those that are at least as specific as every other one
       (`at-least-as-specific?`). One: it is chosen. Several: each is as specific as the other, and only then the
       shape decides (`best-shape`: no `vararg`, fewer defaults used).
    2. If that chose nothing: the same again, with a function that has no type parameters taken as more specific than
       a generic one, whatever their parameter types (not when only an erased type argument tells them apart).
  In 1 and 2 a candidate is never put before another one on what the JVM erased (`may-order?`).
    3. If that chose nothing: the same again among the candidates that take a function as a function type, when the
       others take it as a Kotlin `fun interface` (Kotlin: a candidate without SAM conversion is taken first).
  => [the candidate], or the candidates that the call is ambiguous between (those that no other candidate is strictly
  more specific than)."
  [candidates]
  (let [kotlin-only (remove #(= :java (sam-conversion %)) candidates)]
    (cond
      (< (count candidates) 2) (vec candidates)
      (< 0 (count kotlin-only) (count candidates)) (most-specific kotlin-only)
      :else
      (let [top (fn [at-least?] (filter (fn [c] (every? #(or (identical? c %) (at-least? c %)) candidates)) candidates))
            choose (fn [at-least?] (let [t (top at-least?)] (if (= 1 (count t)) (first t) (best-shape t))))
            by-types (fn [x y] (and (may-order? x y) (at-least-as-specific? x y)))
            non-generic-first (fn [x y] (if (= (generic? x) (generic? y)) (by-types x y) (and (generic? y) (may-order? x y))))
            plain (remove sam-conversion candidates)]
        (if-let [c (or (choose by-types)
                       (when-not (type-argument-in-play? candidates) (choose non-generic-first))
                       (when (< 0 (count plain) (count candidates))
                         (let [r (most-specific plain)] (when (= 1 (count r)) (first r)))))]
          [c]
          (vec (remove (fn [c] (some #(and (not (identical? c %)) (by-types % c) (not (by-types c %)))
                                     candidates))
                       candidates)))))))

;; ---------------------------------------------------------------- ambiguity messages

(defn- interop-form
  "The Java interop form that calls the JVM method of `decl` directly - `(kotlin.collections.CollectionsKt/sumOfInt x)`
  for a static method, `(.getName x)` for an instance one - or nil when it has none that Clojure can call (an
  `inline` function is private in its class)."
  [decl]
  (let [member (if (= :property (:kind decl)) (:getter decl) (:jvm decl))]
    (when (and member (:name member) (not (:field member)))
      (let [names (fn [n] (str/join " " (take n (concat ["x" "y" "z" "u" "v" "w"] (map #(str "x" %) (range))))))
            n (+ (count (:receivers decl)) (count (:params decl)))
            m (bridge/target-method {:class (:class member) :name (:name member) :desc (:desc member)})
            public? (if (contains? member :public?) (:public? member) (or (nil? m) (Modifier/isPublic (.getModifiers m))))]
        (when public?
          (if (:static? member)
            (str "(" (or (:call-class member) (:class member)) "/" (:name member) (when (pos? n) (str " " (names n))) ")")
            (str "(." (:name member) " " (names (max 1 n)) ")")))))))

(defn- pkg-alias
  "The alias that the namespace being compiled (`*ns*`; the namespace of the caller, also at run time in a REPL or a test)
  uses for the namespace of the Kotlin package `pkg` (`kt/require`), or nil."
  [^String pkg]
  (let [target (str "ckway.pkg." (if (str/blank? pkg) "<root>" pkg))]
    (some (fn [[a n]] (when (= target (str (ns-name n))) a)) (ns-aliases *ns*))))

(defn- class-var-text
  "The class var of the Kotlin class `internal` (`org/http4k/routing/RoutingHandler`) as the caller writes it: `r/RoutingHandler`
  when the namespace of its package has an alias in the calling namespace, else the full name."
  [^String internal]
  (let [i (.lastIndexOf internal "/")
        pkg (if (neg? i) "" (str/replace (subs internal 0 i) "/" "."))
        simple (subs internal (inc i))]
    (if-let [a (pkg-alias pkg)]
      (str a "/" simple)
      (if (str/blank? pkg) simple (str pkg "." simple)))))

(defn- call-name-text
  "The name of the var `var-name` of the Kotlin package of the declaration `d`, with the alias of the calling namespace."
  [var-name d]
  (let [owner (str (:owner d))
        i (.lastIndexOf owner ".")]
    (if-let [a (pkg-alias (if (neg? i) "" (subs owner 0 i)))]
      (str a "/" var-name)
      var-name)))

(defn- ambiguity-help
  "The explanation that follows the list of the `best` candidates of an ambiguous call, or nil. Two cases are
  understood: a function and a property of one name (one var), and declarations that differ only in what the JVM
  erases (a type argument, the result type of a lambda)."
  [var-name best]
  (let [decls (map :decl best)
        kinds (set (map :kind decls))
        jvm-desc (fn [d] (:desc (if (= :property (:kind d)) (:getter d) (:jvm d))))
        listing (fn [] (str/join "\n" (for [d decls]
                                        (str "    " (:signature d) "\n      ->  "
                                             (if-let [f (interop-form d)]
                                               (str f "    ; JVM descriptor " (jvm-desc d))
                                               "no public JVM method (it is `inline`): write it in Clojure")))))]
    (cond
      ;; every candidate needs the library's own conversion of a number (an integer for a Double or a Float...)
      (every? (fn [b] (some #(and (= :ok (first %)) (= :conv (nth % 2 nil)) (nil? (nth % 3 nil))) (:checks b))) best)
      (str "\n  Why: every candidate needs a number that the argument is not: kt would have to convert it (an integer "
           "to a Double or a Float, a Long to an Int...), and it cannot choose the target. Kotlin refuses such a call too."
           "\n  Way out: write the number as the type you mean: a floating-point literal (`4.0`) or a conversion "
           "(`(double x)`, `(float x)`, `(int x)`, `(long x)`).")

      (and (contains? kinds :function) (contains? kinds :property))
      (let [fun (first (filter #(and (= :function (:kind %)) (empty? (:receivers %))) decls))
            prop (first (filter #(= :property (:kind %)) decls))
            recv (first (:receivers prop))
            fname (:name (first (:params fun)))]
        (str "\n  Why: a function and a property are both named `" var-name "`, so they share one var, and this call fits both. "
             "Kotlin tells them apart by its syntax (`" var-name "(a)` or `a." var-name "`), a var call has only one form. kt does not guess."
             "\n  Way out:"
             (if fname
               (str "\n    the function: name a parameter, `(" (call-name-text var-name fun) " :" fname " ...)`: a property has no parameter"
                    "\n    ")
               "\n    ")
             (if recv
               (if-let [c (:class (:type recv))]
                 (str "the property: `((kt/ref " (class-var-text c) " " var-name ") x)`, which is Kotlin `"
                      (subs c (inc (.lastIndexOf ^String c "/"))) "::" var-name "`")
                 (str "the property: `((kt/ref X " var-name ") x)`, which is Kotlin `X::" var-name "`; `X` is the class var of `"
                      (meta/type-text (:type recv)) "`"))
               "the property has no receiver, so kt has no form that names it alone")
             (when-not (and fname recv)
               (str "\n  Or call one of them with Java interop:\n" (listing)))))

      (some (fn [b] (some :vararg-coll (:items b))) best)
      (str "\n  Why: a collection that is passed as a whole to a `vararg` is a Clojure collection, which holds anything. Its "
           "elements choose between these vararg overloads only when kt can see them: a vector literal of values of known classes, "
           "or at run time a collection that has elements, and every element must fit one candidate only. Here they cannot be seen "
           "(an empty collection, a value of unknown class) or they fit more than one candidate. kt does not guess."
           "\n  Way out: pass the elements positionally, e.g. `(" var-name " x y)`, or pass a typed array "
           "(`(into-array String xs)`, `(int-array xs)`), or a vector literal of literals.")

      (erased-same? best)
      (let [lam (fn [f] (map (fn [b] (keep (fn [s] (when-let [ft (:fn-type (:kotlin-type s))] (f ft))) (slots (:decl b)))) best))
            differ? (fn [f] (not (apply = (lam f))))
            ptext (fn [ft] (mapv meta/type-text (:args ft)))
            params? (differ? ptext)
            result? (differ? (comp meta/type-text :return))
            ;; the parameters of the first function type of each candidate: the ones that differ get a hint
            shapes (map first (lam ptext))
            first-params (first shapes)
            hint-at? (fn [j] (not (apply = (map #(nth % j nil) shapes))))]
        (str "\n  Why: these declarations take the same JVM parameter types. They differ only in "
             (cond (and params? result?) "the parameter and result types of a lambda"
                   params? "the parameter types of a lambda"
                   result? "the result type of a lambda"
                   :else "a type argument (`Iterable<Int>` or `Iterable<Long>`)")
             ", and the JVM erases "
             (if (or params? result?) "them" "it")
             ", so a Clojure value cannot choose between them. kt does not guess."
             (when params?
               (str "\n  Way out: write the lambda as a `fn` with a type hint on the parameters that tell the candidates apart, "
                    "e.g. `(fn [" (str/join " " (map-indexed (fn [i t] (str (when (hint-at? i) (str "^" t " ")) (nth ["x" "y" "z" "u" "v" "w"] i "a"))) first-params))
                    "] ...)` for the first candidate; the hint is the class of the parameter (a full name, or an imported one)."))
             (if params? "\n  Or call" "\n  Way out: call")
             " the JVM method of the one you mean with Java interop:\n" (listing)))

      (some #(seq (unseen-constraints % false)) best)
      (str "\n  Why: these declarations differ in what a collection holds (the bound of a type parameter such as "
           "`T : Number` in `MutableList<T>`, or one `T` for two collections). The JVM erases that, so a value does "
           "not show it, and kt cannot tell which declaration takes this value. kt does not guess."
           "\n  Way out: give the type arguments with `:<>` when the declaration you mean has type parameters, e.g. `("
           var-name " x :<> Int)`, or call its JVM method with Java interop:\n" (listing)))))

;; ---------------------------------------------------------------- choose

(defn- reasons-text [var-name args rejected]
  (str "kt: no Kotlin declaration of `" var-name "` fits " (call-text var-name args) "\n"
       (str/join "\n" (for [{:keys [decl reason]} rejected]
                        (str "    " (:signature decl) "\n      -> " reason)))))

(defn- overridden-ids
  "The `jvm-member-id`s of the declarations that one of the candidates (maps with :decl) overrides (`:overrides` of a
  declaration: the Kotlin override relation that `ckway.meta` establishes)."
  [candidates]
  (into #{} (mapcat (comp :overrides :decl)) candidates))

(defn drop-overridden
  "Of the candidates (maps with :decl), drop a member that another candidate overrides (`JobSupport.join` overrides
  `Job.join`). They are one Kotlin member, whatever their JVM names and result types are, so a call is not ambiguous;
  the override is the declaration that a caller sees."
  [candidates]
  (let [ids (overridden-ids candidates)]
    (remove #(contains? ids (meta/jvm-member-id (:decl %))) candidates)))

(defn- receiver-fits?
  "Is the dispatch receiver of the checked candidate `b` ({:decl :checks}) an instance of the declaring class for sure?"
  [b]
  (let [i (first (keep-indexed #(when (= :dispatch (:role %2)) %1) (:receivers (:decl b))))]
    (boolean (and i (= :ok (first (nth (:checks b) i nil)))))))

(defn- inherited-twice-key
  "What makes members of UNRELATED classes one member of an object that is an instance of both (a class that implements
  two interfaces with the same member, a superclass with the body and an interface): Kotlin gives such a class ONE
  member for the same kind, name and parameter types, suspend or not. nil for anything else: a top-level function or
  extension, a member of a companion (its receiver is the class var), and a member whose parameter types name a type
  parameter of its class (`A<T>.f(x: T)` and `B<T>.f(x: T)` are two members of a `C : A<Int>, B<String>`)."
  [d]
  (let [dispatch (first (filter #(= :dispatch (:role %)) (:receivers d)))
        class-vars (set (map :name (:class-type-params d)))
        others (remove #(= :dispatch (:role %)) (:receivers d))
        types (concat (map :type (:params d)) (map :type others))
        names-class-var? (fn [t] (some #(contains? class-vars (:type-param %))
                                       (tree-seq #(or (seq (:args %)) (:fn-type %))
                                                 #(concat (:args %) (:args (:fn-type %)) (some-> (:return (:fn-type %)) vector))
                                                 t)))]
    (when (and (#{:function :property} (:kind d)) dispatch (not (:companion-of dispatch))
               (not-any? names-class-var? types))
      [(:kind d) (:name d) (mapv meta/type-text types) (mapv :role others) (boolean (:suspend (:flags d)))])))

(defn- one-virtual-call
  "The candidates, with those that are ONE member of the receiver made one candidate. Every candidate fits for
  sure, so the receiver is an instance of the declaring class of each (and what one of them overrides is no candidate:
  `choose*`). Of declaring classes that are not related (`inherited-twice-key`) the first
  stands for all, or the one whose JVM result type is narrower: the JVM runs the same method of the object for each."
  [candidates]
  (let [cs (vec candidates)
        key-of (fn [c] (or (inherited-twice-key (:decl c)) c))
        ret (fn [c] (some-> (return-jvm-name (:decl c)) jvm-class))
        narrower (fn [a b] (let [^Class ra (ret a) ^Class rb (ret b)]
                             (if (and ra rb (not= ra rb) (.isAssignableFrom ra rb)) b a)))
        groups (group-by key-of cs)]
    (mapv #(reduce narrower (groups %)) (distinct (map key-of cs)))))

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

(defn- same-member-root
  "Of candidates that are all ONE member (an original and its overrides: `:overrides`), the original: the candidate
  that every other candidate overrides; else nil. The call of that member is virtual, so it runs the
  override of the object. Only used where the receiver is of unknown class (`choose*`): with several candidates
  of REAL overloads (other parameter types, or unrelated classes) this is nil."
  [viable]
  (when (> (count viable) 1)
    (first (filter (fn [r]
                     (let [id (meta/jvm-member-id (:decl r))]
                       (and (vector? id)
                            (every? #(or (identical? r %) (contains? (:overrides (:decl %)) id)) viable))))
                   viable))))

(defn- targs-fit-bounds?
  "Do the type arguments `targs` (resolved types of `:<>`) fit the declared bounds of the type parameters of `decl`, as
  far as the JVM classes tell (`String` is no `Number`; a nullable type is not within a bound)? What the classes do not
  tell (`Comparable<T>`) fits."
  [decl targs]
  (every? true?
          (map (fn [tp t]
                 (every? (fn [b]
                           (let [^Class jb (some-> (types/jvm-class b) box-class) ^Class jt (some-> (types/jvm-class t) box-class)]
                             (and (or (:nullable? b) (not (:nullable? t)))
                                  (or (nil? jb) (nil? jt) (.isAssignableFrom jb jt)))))
                         (:bounds tp)))
               (:type-params decl) targs)))

(defn- property-function-clash
  "The property and the functions without a receiver among `candidates`, when there are both, else nil. Both are one var
  (`routes(h)` and `h.routes` in Kotlin are `(routes h)` here), and a call that fits both is not decided by any rule."
  [candidates]
  (let [props (filter #(= :property (:kind (:decl %))) candidates)
        funs (filter #(and (= :function (:kind (:decl %))) (empty? (:receivers (:decl %)))) candidates)]
    (when (and (seq props) (seq funs)) (concat props funs))))

(defn- receiver-doubt-only?
  "Are the `candidates` members, with only the dispatch receiver in doubt (its class is an upper bound)?"
  [candidates]
  (every? (fn [b]
            (let [i (first (keep-indexed #(when (= :dispatch (:role %2)) %1) (:receivers (:decl b))))]
              (and i (every? (fn [[j c]] (or (= j i) (= :ok (first c)))) (map-indexed vector (:checks b))))))
          candidates))

(defn- lambda-hints-fit?
  "Do the type hints on the parameters of the `fn` literals among the written arguments of the candidate `b` fit the
  parameter types of the function types that take them? A hinted parameter fits when its class is the parameter type
  or one that extends it (the lambda is then given a value that it takes). A parameter without hint, a parameter type
  that the JVM does not tell (`Any`, a type parameter), and a literal that has not the arity of the function type, fit."
  [b]
  (every? (fn [[slot item]]
            (let [hints (:fn-hints (:info item))
                  ps (when (and hints (:adapt slot)) (param-hints (:adapt slot) (:kotlin-type slot)))]
              (or (not= (count ps) (count hints))
                  (every? true?
                          (map (fn [^Class h p]
                                 (let [pc (some-> p jvm-class box-class)]
                                   (boolean (or (nil? h) (nil? pc) (.isAssignableFrom ^Class pc (box-class h))))))
                               hints ps)))))
          (map vector (slots (:decl b)) (:items b))))

(defn- narrow-by-lambda-hints
  "Of the ambiguous candidates `best`, those that the type hints on the parameters of a `fn` literal leave (Ktor:
  `(fn [^ApplicationCall call status] ...)` for `(ApplicationCall, HttpStatusCode) -> Unit` and `(StatusContext,
  HttpStatusCode) -> Unit`). `best` itself when no candidate gets a hint, or none is left: a hint never makes a call
  an error that it was not, it only chooses where a call is ambiguous."
  [best]
  (let [fit (filter lambda-hints-fit? best)]
    (if (and (seq fit) (some (fn [b] (some (comp :fn-hints :info) (:items b))) best)) (vec fit) best)))

(defn- generic-function-invoke?
  "Is `d` the `invoke(p1: P1, ...): R` that the JVM interface `kotlin.jvm.functions.FunctionN` declares?"
  [d]
  (boolean (and (= "invoke" (:name d)) (some? (re-matches #"kotlin\.jvm\.functions\.Function\d+" (str (:owner d)))))))

(defn- choose*
  "Pick exactly one declaration (rule 7). `trailing?` allows the binding of a trailing lambda (`bind`).
  => {:decl d :items items :checks checks [:tform form]}      one candidate
     {:dynamic? true [:tform form]}                            several candidates, types unknown
  or throws a kt error. `compile?` allows unknowns. `:tform` is the form after `:<>` (see the namespace
  docstring)."
  [var-name decls parsed compile? trailing?]
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
        ;; the type arguments that `:<>` gives are known: a declaration whose bound they do not fit is no candidate
        targs (when tf (try (types/resolve-forms *ns* tform) (catch clojure.lang.ExceptionInfo _ nil)))
        decls (if targs (let [fit (filter #(targs-fit-bounds? % targs) decls)] (if (seq fit) fit decls)) decls)
        bound (for [d decls] (cond-> (assoc (bind d parsed trailing?) :decl d) targs (assoc :targs targs)))
        failed (filter :error bound)
        ok (filter :items bound)]
    (when (empty? ok)
      (fail (if (= 1 (count decls))
              (str "kt: " (call-text var-name args) ": " (:error (first failed)) "\n  Kotlin declares:\n" (signatures decls))
              (reasons-text var-name args (map #(hash-map :decl (:decl %) :reason (:error %)) failed)))
            {:kt/candidates (map :signature decls)}))
    (let [checked (for [b ok :let [sl (slots (:decl b))]] (assoc b :checks (check-items sl (:items b))))
          ;; the generic `Function1.invoke(p1: P1)` of a class that implements a function type is for the objects of the classes
          ;; that have no `invoke` of their own with the types of the supertype (`ckway.meta/function-type-invoke`): where
          ;; the receiver fits one of those, the arguments are checked against the types of the supertype, not as `P1`
          checked (if (some #(and (:fn-supertype (:decl %)) (= :ok (first (first (:checks %))))) checked)
                    (remove #(generic-function-invoke? (:decl %)) checked)
                    checked)
          no-reason (fn [b] (some #(when (= :no (first %)) (second %)) (:checks b)))
          ;; a member that the class of the receiver overrides is no candidate for that receiver: the override is the
          ;; member, also when the arguments do not fit it (`Two : A<Int>` with `f(x: Int)`: `A<T>.f(x: T)` is gone)
          overridden (overridden-ids (filter receiver-fits? checked))
          viable (remove #(or (no-reason %) (contains? overridden (meta/jvm-member-id (:decl %)))) checked)
          ;; a candidate that fits by the usual binding for sure always wins over one that fits only with a trailing
          ;; lambda. One that only could fit (unknown types at compile time) does not: the run time decides (`choose`)
          viable (if (some #(and (not (:trailing %)) (every? (fn [c] (= :ok (first c))) (:checks %))) viable)
                   (remove :trailing viable)
                   (if (and (some (complement :trailing) viable) (not compile?)) (remove :trailing viable) viable))]
      (when (empty? viable)
        (fail (reasons-text var-name args (concat (map #(hash-map :decl (:decl %) :reason (:error %)) failed)
                                                  (map #(hash-map :decl (:decl %) :reason (no-reason %)) checked)))
              {:kt/candidates (map :signature decls) :kt/no-fit true}))
      (let [;; the library's own conversion of a number (an integer to a Double, a Long to an Int...) is the last
            ;; resort: Kotlin accepts no such value, so it counts only when no candidate takes the values as they are
            ;; (a conversion is also a function adapter for a value that is no function, and a `Mutable*` parameter for
            ;; a read-only collection: `applicability*`.) Of those two kinds, a candidate that converts the arguments
            ;; that another one converts AND more is dropped too: `removeAll(elements)` on a vector converts the receiver, `removeAll(predicate)`
            ;; with a vector as the predicate converts the receiver and the argument
            convs (fn [b] (let [es (entry-slots b)]
                            {:any? (boolean (some #(= :conv (nth % 2 nil)) (:checks b)))
                             ;; (not the conversions of numbers: among those the library does not choose)
                             :idx (set (for [[i s] es :when (and (:conv? s) (or (:read-only-conv? s) (:fn-shape s)))] i))
                             :read-only (set (for [[i s] es :when (:read-only-conv? s)] i))
                             :asks (unseen-constraints b true)}))
            viable (let [cs (mapv (fn [b] [b (convs b)]) viable)]
                     (map first
                          (remove (fn [[b cb]]
                                    (some (fn [[o co]]
                                            (and (not (identical? o b))
                                                 (or (and (:any? cb) (not (:any? co)))
                                                     (and (not= (:idx co) (:idx cb)) (every? (:idx cb) (:idx co))))
                                                 ;; a read-only collection: the candidate with the `Mutable*` parameter
                                                 ;; gives way only to one that asks the same of what the collection
                                                 ;; holds (`MutableList<Int>` to `Iterable<Int>`, not to `Collection<String>`)
                                                 (every? #(= (get (:asks cb) %) (get (:asks co) %))
                                                         (remove (:idx co) (:read-only cb)))))
                                          cs))
                                  cs)))
            ;; a property and a function of one name that this call fits (rule 7: no guess). It is decided before the
            ;; rules below, which would take the member (a property of the receiver) or the one without a vararg
            clash (property-function-clash viable)
            unknown? (fn [b] (some #(= :unknown (first %)) (:checks b)))
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
        (cond
          ;; the classes of the values decide at run time which of them fit
          (and clash compile? (some unknown? viable)) (cond-> {:dynamic? true} tf (assoc :tform tform))
          ;; the elements of a collection passed as a whole to a vararg are known (the dynamic path, `ckway.rt`): a
          ;; candidate fits only if its elements do, and more than one that fits is no choice
          (and (:strict-elems parsed) (> (count (filter #(some (comp :elems :info :vararg-coll) (:items %)) viable)) 1))
          (let [cs (filter #(some (comp :elems :info :vararg-coll) (:items %)) viable)]
            (fail (str "kt: " (call-text var-name args) " is ambiguous. Candidates:\n" (signatures (map :decl cs))
                       (ambiguity-help var-name cs))
                  {:kt/candidates (map (comp :signature :decl) cs) :kt/ambiguous true}))
          clash (fail (str "kt: " (call-text var-name args) " is ambiguous. Candidates:\n" (signatures (map :decl clash))
                           (ambiguity-help var-name clash))
                      {:kt/candidates (map (comp :signature :decl) clash) :kt/ambiguous true})
          :else
          (let [;; every candidate fits for sure. Members that are one virtual call are one candidate; a member is taken
                ;; before an extension; then Kotlin's choice of the most specific one (`most-specific`), or no choice
                settle (fn [vs]
                         (let [best (one-virtual-call vs)
                               best (if (and (> (count best) 1) (some (comp member? :decl) best))
                                      (filter (comp member? :decl) best)
                                      best)]
                           (most-specific best)))
                ;; no choice left: the hints on the parameters of a `fn` literal may decide (`narrow-by-lambda-hints`)
                finish (fn [best]
                         (let [best (if (> (count best) 1) (narrow-by-lambda-hints best) best)]
                           (if (= 1 (count best))
                             (result (first best))
                             (fail (str "kt: " (call-text var-name args) " is ambiguous. Candidates:\n"
                                        (signatures (map :decl best))
                                        (or (ambiguity-help var-name best)
                                            "\n  Add a type hint to an argument, or use Java interop (.getX) for this call."))
                                   {:kt/candidates (map (comp :signature :decl) best) :kt/ambiguous true
                                    ;; the elements of a collection that is passed as a whole are not seen yet (`ckway.rt/call-dyn`)
                                    :kt/elements-needed (boolean (some (fn [b] (some #(and (:vararg-coll %) (not (:elems (:info (:vararg-coll %))))) (:items b))) best))}))))]
            (cond
              (= 1 (count viable)) (result (first viable))
              (and compile? (some unknown? viable))
              ;; one member that narrowing overrides repeat: a static virtual call of the most general declaration
              (if-let [root (same-member-root viable)]
                (result root)
                ;; Members of one class that differ only in what the JVM erases are ambiguous whatever class the receiver
                ;; has (a subclass has both): when only the receiver is in doubt, that is known now, and so are the hints
                (let [best (when (receiver-doubt-only? viable) (settle viable))]
                  (if (and (> (count best) 1) (erased-same? best))
                    (finish best)
                    (cond-> {:dynamic? true} tf (assoc :tform tform)))))
              :else (finish (settle viable)))))))))

(defn- relax-hints
  "`parsed` with every type hint that the user wrote (`:static`) taken as an upper bound (`:upper`)."
  [parsed]
  (let [relax (fn [e] (cond-> e (:static (:info e)) (update :info #(-> % (dissoc :static) (assoc :upper true)))))]
    (-> parsed
        (update :positional #(mapv relax %))
        (update :named #(mapv (fn [[n e]] [n (relax e)]) %)))))

(defn- has-static-hint? [parsed]
  (boolean (some (comp :static :info) (concat (:positional parsed) (map second (:named parsed))))))

(defn- choose-hinted
  "`choose*`, with the retry for a type hint (see `choose`)."
  [var-name decls parsed compile? trailing?]
  (try (choose* var-name decls parsed compile? trailing?)
       (catch clojure.lang.ExceptionInfo e
         (if (and compile? (:kt/no-fit (ex-data e)) (has-static-hint? parsed))
           (try (choose* var-name decls (relax-hints parsed) compile? trailing?)
                (catch clojure.lang.ExceptionInfo e2 (if (:kt/no-fit (ex-data e2)) (throw e) (throw e2))))
           (throw e)))))

(defn choose
  "Pick exactly one declaration (rule 7; `choose*`). A type hint that the user wrote decides between the candidates
  that it fits for sure, as a declared type does in Kotlin. When it fits no candidate for sure, but the value could still
  fit (the hint is an interface, or a class that a parameter type extends), it is only an upper bound: the call is
  selected on the other information or checked at run time. A hint that can never fit stays an error.
  A call that this fails for may still be one with a trailing lambda (`bind`): that binding is tried only then, so a call
  that is selected without it is never changed. Its error is the first one, except an ambiguity between candidates."
  [var-name decls parsed compile?]
  (try (let [r (choose-hinted var-name decls parsed compile? false)]
         ;; the usual binding selected a candidate whose types only COULD fit (unknown at compile time), and another
         ;; declaration fits with a trailing lambda: the values decide at run time, which can try both
         (if (and compile? (not (:dynamic? r)) (seq (:positional parsed)) (some trailing-lambda? decls)
                  (some #(not= :ok (first %)) (:checks r)))
           (let [r2 (try (choose-hinted var-name decls parsed compile? true) (catch clojure.lang.ExceptionInfo _ nil))]
             (if (:dynamic? r2) r2 r))
           r))
       (catch clojure.lang.ExceptionInfo e
         (if (and (:kt/error (ex-data e)) (seq (:positional parsed)) (some trailing-lambda? decls))
           (try (choose-hinted var-name decls parsed compile? true)
                (catch clojure.lang.ExceptionInfo e2 (if (:kt/ambiguous (ex-data e2)) (throw e2) (throw e))))
           (throw e)))))

;; ---------------------------------------------------------------- the call of a class var

(defn var-form
  "The form that reads the var `v` from the current namespace: `alias/Name` when the namespace has an alias for it."
  [^clojure.lang.Var v]
  (let [alias (some (fn [[a n]] (when (= n (.ns v)) a)) (ns-aliases *ns*))]
    (symbol (if alias (str alias) (str (ns-name (.ns v)))) (str (.sym v)))))

(defn with-class-first
  "`parsed` with the class var `form` (its value `root`) as a new first positional argument: the receiver of a companion
  member. The positions of the written arguments move by one."
  [parsed form info]
  (let [up (fn [e] (update e :idx inc))]
    {:positional (into [{:arg form :info info :idx 0}] (map up) (:positional parsed))
     :named (mapv (fn [[n e]] [n (up e)]) (:named parsed))
     :strict-elems (:strict-elems parsed)}))

(defn- indent-text [msg n]
  (str/join "\n" (map #(str (apply str (repeat n \space)) %) (str/split-lines (str/replace-first msg #"^kt: " "")))))

(defn choose-call
  "`choose` for the call of a class var, which Kotlin resolves as: the constructors first; when none is applicable (or
  the class has none), the `operator fun invoke` members of its companion object, and the `operator fun invoke`
  extensions on the Companion (`invokes`: the declarations, see `ckway.core`). A constructor that fits always wins; an
  ambiguity is an error of its own kind. When neither fits, the error shows both. `with-class` makes the parsed call that
  has the class var as the receiver. The result of the `invoke` choice has `:via-invoke true`: its items start with the class
  var."
  [var-name decls invokes parsed with-class compile?]
  (try (choose var-name decls parsed compile?)
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (or (empty? invokes) (not (:kt/error d)) (:kt/ambiguous d) (:kt/elements-needed d))
             (throw e)
             (try (assoc (choose ".invoke" invokes (with-class parsed) compile?) :via-invoke true)
                  (catch clojure.lang.ExceptionInfo e2
                    (let [d2 (ex-data e2)]
                      (if (or (not (:kt/error d2)) (:kt/ambiguous d2) (:kt/elements-needed d2))
                        (throw e2)
                        (fail (str "kt: " (call-text var-name (concat (map :arg (:positional parsed))
                                                                      (mapcat (fn [[n e]] [(keyword n) (:arg e)]) (:named parsed))))
                                   " fits no constructor and no `operator fun invoke` of the companion object.\n"
                                   "  As a constructor:\n" (indent-text (ex-message e) 4) "\n"
                                   "  As a companion `invoke` (Kotlin calls it for `" var-name "(...)`; the class var is the first argument here):\n" (indent-text (ex-message e2) 4))
                              {:kt/candidates (concat (:kt/candidates d) (:kt/candidates d2)) :kt/no-fit true}))))))))))

(defn object-invoke?
  "Has the object `obj` (a declaration of kind :object) an `operator fun invoke`, a member of it or of a supertype, or
  an extension on it? `invokes` are the `.invoke` declarations of the package."
  [obj invokes]
  (let [^Class oc (jvm-class (:owner obj))]
    (boolean
     (when oc
       (some (fn [d]
               (and (contains? (:flags d) :operator)
                    (= 1 (count (:receivers d)))
                    (let [r (first (:receivers d))
                          rc (jvm-class (if (= :dispatch (:role r)) (:owner d) (or (:jvm-type r) (some-> (:class (:type r)) meta/internal->binary))))]
                      (and rc (not (:companion-of r)) (.isAssignableFrom ^Class rc oc)))))
             invokes)))))

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
        ;; the class var for the receiver of an extension on a Companion is the Companion object (the plain Companion
        ;; object is passed as it is)
        comp-entry (fn [r it] (if (and (:companion-of r) (:companion-of (:info it)))
                                (assoc it :companion {:class (:companion-of r) :field (:companion-field r)})
                                it))
        other (for [[r it] (map vector recvs ritems) :when (not= :dispatch (:role r))]
                (cond-> {:entry (comp-entry r it) :jvm-type (:jvm-type r)}
                  (arg-conv (:type r) (:jvm-type r) (or (:name r) "receiver"))
                  (assoc :vc (arg-conv (:type r) (:jvm-type r) (or (:name r) "receiver")))
                  (and (not (:companion-of r)) (plain-nn decl (:type r) (:jvm-type r))) (assoc :nn (plain-nn decl (:type r) (:jvm-type r)))))
        self (when-let [jt (:jvm-type disp-recv)]
               (cond-> {:entry disp-item :jvm-type jt}
                 (arg-conv (:type disp-recv) jt "this") (assoc :vc (arg-conv (:type disp-recv) jt "this"))
                 (and (not (:companion-of disp-recv)) (plain-nn decl (:type disp-recv) jt)) (assoc :nn (plain-nn decl (:type disp-recv) jt))))
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
                                 (nonnull-tparam decl (:vararg-elem p)) (assoc :elem-nn (nonnull-tparam decl (:vararg-elem p)))
                                 (:elem-jvm p) (assoc :elem-jvm (:elem-jvm p)))
                  (:vararg-coll it) (cond-> {:vararg-coll (:vararg-coll it) :jvm-type (:jvm-type p)}
                                      (elem-conv p) (assoc :elem-vc (elem-conv p)))
                  :else (let [ad (adapter-spec (:type p) (:jvm-type p) (:signature decl))
                              vc (arg-conv (:type p) (:jvm-type p) (:name p))]
                          (cond-> {:entry it :jvm-type (:jvm-type p) :pname (:name p) :ptext (meta/type-text (:type p))}
                            ad (assoc :adapt ad) vc (assoc :vc vc)
                            (nonnull-tparam decl (:type p)) (assoc :nn (nonnull-tparam decl (:type p)))
                            (and (not vc) (plain-nn decl (:type p) (:jvm-type p))) (assoc :nn (plain-nn decl (:type p) (:jvm-type p)))))))
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
        ret-vc (return-conv decl (when defaults? jvm))]
    (cond-> (assoc p :desc desc :sig (:signature decl))
      ;; a public method of a multi-file class part is called through the public facade that inherits it
      (and (#{:static :virtual} (:op p))
           (:call-class (if (= :property (:kind decl)) (:getter decl) jvm)))
      (assoc :call-class (:call-class (if (= :property (:kind decl)) (:getter decl) jvm)))
      ;; the JVM class cannot reflect: Clojure's compiler could not look the method up
      (and (#{:static :virtual :new} (:op p)) (:partial? (if (= :property (:kind decl)) (:getter decl) jvm)))
      (assoc :partial? true)
      companion? (update :ignored (fnil conj []) disp-item)
      ;; a @JvmStatic member of an object is a static JVM method: its receiver is evaluated, not passed
      (and (not companion?) disp-item (= :static (:op p))
           (not-any? #(and (:entry %) (= (:idx (:entry %)) (:idx disp-item))) (:args p)))
      (update :ignored (fnil conj []) disp-item)
      (:suspend (:flags decl)) (assoc :suspend? true)
      ;; the dispatch receiver of a member takes no nil
      (and disp-recv (not companion?) (not (:companion-of disp-recv)) (plain-nn decl (:type disp-recv) "java.lang.Object"))
      (assoc :target-nn (plain-nn decl (:type disp-recv) "java.lang.Object"))
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
        ;; the `$default` synthetic of an instance member is static and takes the object first. The type of that
        ;; parameter is in its descriptor; it is not always the class that holds the synthetic (`Iface$DefaultImpls`)
        {:op :static :class (:class jvm) :name (:name jvm) :unit? unit?
         :args (vec (concat [{:entry target :jvm-type (first (:params (meta/desc-types (:desc jvm)))) :target? true}]
                            all masks [{:marker true}]))}
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
  class that is only an upper bound is if that class is a subtype. The type hint that the USER wrote (`:static`) is
  not sure: a wrong hint is a `kt:` error at run time, `checked-form`.)"
  [cname info]
  (boolean (or (:trusted info)
               (when-let [^Class ic (when-not (:static info) (:class info))]
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

(defn- tag-as [tag form] (with-meta form {:tag tag}))

(def ^:private int-ranges
  "JVM integer type -> [lo hi converter boxed-class unwrap-method cast]. Every symbol is qualified: the emitted code
  must not read a local of the consumer (`(let [int inc] ...)`)."
  {"int" [Integer/MIN_VALUE Integer/MAX_VALUE 'ckway.rt/to-int 'java.lang.Integer '.intValue 'clojure.core/int]
   "short" [Short/MIN_VALUE Short/MAX_VALUE 'ckway.rt/to-short 'java.lang.Short '.shortValue 'clojure.core/short]
   "byte" [Byte/MIN_VALUE Byte/MAX_VALUE 'ckway.rt/to-byte 'java.lang.Byte '.byteValue 'clojure.core/byte]
   "long" [Long/MIN_VALUE Long/MAX_VALUE 'ckway.rt/to-long 'java.lang.Long '.longValue 'clojure.core/long]})

(defn- check-literal-range!
  "An integer literal that is outside the range of the integer type `jt` of its parameter is an error at compile
  time, in the words of the run-time check (`ckway.rt/to-int`...). `what` says which value it is."
  [jt form site what]
  (let [[lo hi] (int-ranges (case jt "java.lang.Integer" "int" "java.lang.Short" "short" "java.lang.Byte" "byte" "java.lang.Long" "long" jt))
        tname (case jt ("int" "java.lang.Integer") "Int" ("short" "java.lang.Short") "Short" ("byte" "java.lang.Byte") "Byte" "Long")]
    (when (and lo (integer? form) (not (<= lo form hi)))
      (fail (str "kt: " (when (:call site) (str (:call site) ": ")) what " is " form
                 ", which is out of range for " tname
                 (when (:sig site) (str "\n  Kotlin: " (:sig site))))))))

(defn- conv-form
  "[form tag] that passes `form` (with `info`) to a JVM parameter of type `jt`. `site` (see `checked-form`) is
  nil when the value is trusted. The conversions of a number are calls of library functions (`ckway.rt/to-int`...)
  that check the range and the class, and then unwrap the result with a method call: the casts `(int x)` of the
  consumer's code would follow the consumer's `*unchecked-math*` and truncate silently. The only conversions are
  the ones that the DECLARED type asks for (`Int`, `Short`, `Byte`, `Long`, `Float`, `Double`, `Char`, `Boolean`): at
  `Any`, `Number` or a type parameter a Clojure value goes on unchanged."
  [jt form info site what]
  (let [what (or what (:what site) "the argument")]
    (when (or (int-ranges jt) (boxed-names jt)) (check-literal-range! jt form site what))
    (if-let [[lo hi to ptag meth cast] (int-ranges jt)]
      (if (and (integer? form) (<= lo form hi))
        [`(~cast ~form) nil]
        [`(~meth ~(tag-as ptag `(~to ~what ~form))) nil])
      (case jt
        "double" [`(.doubleValue ~(tag-as 'java.lang.Number `(ckway.rt/to-number ~what ~(checked-form "java.lang.Number" form info site)))) nil]
        "float" [`(.floatValue ~(tag-as 'java.lang.Number `(ckway.rt/to-number ~what ~(checked-form "java.lang.Number" form info site)))) nil]
        "char" [`(.charValue ~(tag-as 'java.lang.Character `(ckway.rt/to-char ~what ~(checked-form "java.lang.Character" form info site)))) nil]
        ;; the primitive boolean, so that a (boolean) and a (Boolean) overload are told apart
        "boolean" [`(.booleanValue ~(tag-as 'java.lang.Boolean `(ckway.rt/to-bool ~what ~(checked-form "java.lang.Boolean" form info site)))) nil]
        (cond
          (boxed-names jt) [`(ckway.rt/box ~what ~jt ~form) jt]
          ;; a number literal or primitive local would stay primitive; box it so overloads resolve
          (or (number? form) (some-> ^Class (:class info) .isPrimitive)
              (and (seq? form) (symbol? (first form)) (contains? cast-classes (name (first form)))))
          [`(clojure.core/identity ~form) jt]
          :else [(checked-form jt form info site) jt])))))

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
  "Form that gives the value-class object for the underlying value `x` (a form that returns it). The result of a suspend
  call (`:suspend?`) is the object already, or the underlying value (`Object` in the JVM signature): the object stays."
  [{:keys [vc nullable? suspend?]} x]
  (let [raw (:jvm-underlying vc)
        b (bridge-sym (:box vc))
        cls (symbol (:class vc))]
    (cond
      suspend? (let [o (gensym "o")
                     arg (if (contains? prim-classes raw)
                           `(~(symbol "clojure.core" (case raw "char" "char" "boolean" "boolean" raw)) ~o)
                           (with-meta o {:tag raw}))]
                 `(let [~o ~x]
                    (if (or (instance? ~cls ~o) ~(and nullable? `(nil? ~o)))
                      ~o
                      (. ~b (~'call ~arg)))))
      (and nullable? (not (contains? prim-classes raw)))
      (let [r (with-meta (gensym "r") {:tag raw})] `(let [~r ~x] (when ~r (. ~b (~'call ~r)))))
      :else `(. ~b (~'call ~x)))))

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
                                  (if (:pname a) (str "the argument `" (:pname a) "` (" (:ptext a) ")") "the receiver")
                                  (:nn a)]]
                     (:vararg a) (map (fn [e] [e (or (:elem-jvm a) (elem (:jvm-type a))) nil (:elem-vc a)
                                               nil (:elem-nn a)])
                                      (:vararg a))
                     (:vararg-coll a) [[(:vararg-coll a) nil]]))
        target (:target p)]
    (concat (when target [[(companion-entry target) (:class p) nil nil "the receiver" (:target-nn p)]])
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
  says what Kotlin called (`text`) and how many arguments it gave."
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

(defn- known-non-nil?
  "Is the argument sure not to be nil? A literal, a number cast or a trusted entry is; a hint, a class that Clojure
  inferred and a call are not."
  [info]
  (boolean (or (:trusted info) (:non-nil info)
               (some-> ^Class (:class info) .isPrimitive)
               (and (:class info) (not (:upper info)) (not (:static info))))))

(defn- nn-guard
  "The entry `e`, whose argument form is wrapped in a check for nil when the parameter takes no nil and the argument may
  be nil: `nn` is the text of the non-null bound of a type parameter (`T : Any`), or {:type text} for a parameter of a plain
  non-null type. A nil that is only known at run time is a `kt:` error, as the nil that is known at compile time is, not
  the NullPointerException of Kotlin. The check is a plain `nil?` test in the expansion: the error call is only in the branch
  that fails (no var lookup and no allocation on the way of a value that is not nil)."
  [e nn what p]
  (if (and nn (not (known-non-nil? (:info e)))
           ;; the var of an object whose initialiser failed throws its own error (`guard-failed-objects`)
           (not (and (seq? (:arg e)) (= `ckway.rt/live (first (:arg e))))))
    (let [v (gensym "v")
          site (merge {:call (:call-text p) :sig (:sig p) :what (or what "a vararg element")}
                      (if (map? nn) nn {:bound nn}))]
      (update e :arg (fn [f] `(let* [~v ~f] (if (nil? ~v) (ckway.rt/nn-fail '~site) ~v)))))
    e))

(defn emit
  "Form for a plan. Arguments are bound once, in written order, with exact tags."
  [p]
  (let [target-ent (:target p)
        pe (plan-entries p)
        entries (->> pe
                     (reduce (fn [m [e jt ad :as x]] (if (contains? m (:idx e)) m (assoc m (:idx e) x))) {})
                     (sort-by key) (map val))
        binds (for [[e0 jt ad vc what nn] entries
                    :let [site (when (and what (:call-text p)) (assoc (:site p) :what what :call (:call-text p) :sig (:sig p)))
                          e (nn-guard e0 nn what p)
                          [f tag] (cond ad (adapter-form ad jt (:arg e))
                                        vc [(unbox-form vc (:arg e)) (when-not (contains? prim-classes jt) jt)]
                                        jt (conv-form jt (:arg e) (:info e) site what)
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
        ;; A constructor of a class that is not public, and any member of a class that cannot reflect (it needs a class that
        ;; is not there), are called through a MethodHandle that is found with a private lookup. A method of a class that
        ;; is not public (the part of a multi-file class: `StringsKt__StringsJVMKt`) goes through a call bridge, like
        ;; any member that is not callable with a plain `.` form (`needed?`): the bridge keeps the handle in a static
        ;; final field and calls it with `invokeExact`.
        hidden-class? (or (and (= :new (:op p)) (not (:call-class p))
                               (let [c (jvm-class (:class p))] (and c (not (Modifier/isPublic (.getModifiers ^Class c))))))
                          (and (#{:static :virtual :new} (:op p)) (:partial? p)))
        handle (when hidden-class?
                 (if (= :new (:op p))
                   `(ckway.rt/jvm-ctor-handle ~(:class p) ~(:desc p))
                   `(ckway.rt/jvm-handle ~(= :static (:op p)) ~(:class p) ~(:name p) ~(:desc p))))
        bridge-of (when (and (not hidden-class?) (#{:static :virtual} (:op p))
                             (if (:call-class p)
                               (not (bridge/clojure-name? (:name p)))
                               (bridge/needed? {:kind (:op p) :class (:class p) :name (:name p) :desc (:desc p)})))
                    (bridge-sym {:class (:class p) :name (:name p) :desc (:desc p) :static? (= :static (:op p))}))
        call (case (:op p)
               :static (cond handle `(ckway.rt/call-jvm ~handle [~@args])
                             bridge-of `(. ~bridge-of (~'call ~@args))
                             :else `(. ~cls (~(sym (:name p)) ~@args)))
               :virtual (cond handle `(ckway.rt/call-jvm ~handle [~tgt ~@args])
                              bridge-of `(. ~bridge-of (~'call ~tgt ~@args))
                              :else `(. ~tgt (~(sym (:name p)) ~@args)))
               :new (if handle `(ckway.rt/call-jvm ~handle [~@args]) `(new ~cls ~@args))
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
      `(let* [~@(mapcat (fn [[_ s f]] [s f]) binds) ~@(mapcat (fn [[_ s]] [s nil]) holders)
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

(defn- type-classes
  "The JVM classes that the type `t` names, its type arguments included (a class that is not found is left out)."
  [t]
  (concat (when-let [c (and (:class t) (not (:type-param t)) (types/jvm-class t))] [c])
          (mapcat type-classes (:args t))
          (when-let [f (:fn-type t)] (mapcat type-classes (concat (:args f) [(:return f)])))))

(defn- check-class-files!
  "The error for a class in `targs` that exists in this JVM but has no class file on the class path: Clojure made it at run
  time (`defprotocol`, `definterface`, `deftype`, `defrecord`, a `gen-class` that is not compiled, an earlier `kt/reify`),
  and the Kotlin compiler of the bridge cannot read it (it would say \"unresolved reference\"). A class of the JDK
  or of another loader that is no part of the class path is not asked for. Called before the compiler runs, only when a
  bridge has to be compiled: a stored bridge (AOT, disk cache) loads as it is."
  [form targs]
  (doseq [^Class c (distinct (mapcat type-classes targs))
          :let [l (.getClassLoader c)]
          :when (and l (not= l (ClassLoader/getPlatformClassLoader)) (not (.isArray c)) (not (.isPrimitive c))
                     (not (meta/class-file-on-classpath? (.getName c))))]
    (fail (str "kt: " (short-form form) ": the class `" (.getName c) "` in `:<>` was made at run time (a `defprotocol`, "
               "`definterface`, `deftype`, `defrecord`, a `gen-class` that is not compiled yet, or an earlier `kt/reify`). "
               "It exists in this JVM only, with no class file on the class path, so the Kotlin compiler that compiles "
               "this call cannot read it.\n  Ways out:\n"
               "    1. AOT-compile the namespace that defines it and put the classes directory on the class path.\n"
               "    2. Use an overload that takes a `KClass`, if the library has one: `(kt/ref " (last (str/split (.getName c) #"[.$]")) " class)`."))))

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
                                                   :before-compile #(check-class-files! form targs)
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

(declare tagged-result)

(defn- expand-typed
  "Expansion of a call with `:<>`. `uform` is the call as the user wrote it, `mform` the form the compiler gave us."
  [mform form decl items tform v forms]
  (when (and (reified? decl) (not= :function (:kind decl)))
    (not-supported "inline reified properties" decl))
  (let [targs (types/resolve-forms *ns* tform)
        [d its] (if (reified? decl)
                  (expand-reified form decl items targs)
                  [(typed-decl decl targs) items])]
    (check-typed! form d its)
    (let [p (with-site (plan d (type-literals d its)) v form forms)]
      (tagged-result mform d p (emit p)))))

;; ---------------------------------------------------------------- macro

(defn warn-dynamic [form var-name]
  (when *warn-on-reflection*
    (binding [*out* *err*]
      ;; the form of an :inline expansion has no position; the compiler knows the one it is analysing
      (println (str "Reflection warning, " *file* ":" (or (:line (meta form)) (.deref clojure.lang.Compiler/LINE))
                    ":" (or (:column (meta form)) (.deref clojure.lang.Compiler/COLUMN))
                    " - call to " var-name " can't be resolved statically (argument or receiver types unknown); using the dynamic path.")))))

(defn dynamic-form
  "Form that calls the dynamic path with parsed arguments. An integer literal is a Long value at
  run time; `lits` tells the dynamic path which arguments were literals (by position or name),
  so it chooses between an Int and a Long overload as the static path does."
  [v parsed]
  (let [lits (into {} (concat (keep-indexed (fn [i e] (when-let [l (:lit (:info e))] [i l])) (:positional parsed))
                              (keep (fn [[n e]] (when-let [l (:lit (:info e))] [n l])) (:named parsed))))
        ;; the argument forms are evaluated once, in the written order (a map literal has none), and the names go
        ;; to an array map, which keeps it
        pos (mapv (fn [e] [(gensym "p") (:arg e)]) (:positional parsed))
        named (mapv (fn [[n e]] [n (gensym "n") (:arg e)]) (:named parsed))]
    `(let* [~@(mapcat identity pos) ~@(mapcat (fn [[_ g f]] [g f]) named)]
       (ckway.rt/call-dyn (var ~(symbol (str (ns-name (.ns ^clojure.lang.Var v))) (str (.sym ^clojure.lang.Var v))))
                          [~@(map first pos)]
                          (array-map ~@(mapcat (fn [[n g _]] [n g]) named))
                          ~@(when (seq lits) [lits])))))

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
      (vec (concat (map (fn [p h] (if (and (symbol? p) h (not (:tag (meta p)))) (lib-tag p h) p))
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
  kt calls on its parameters are resolved statically (the static path, see README How it works)."
  [decl items]
  (vec (map (fn [slot item]
              (let [ad (:adapt slot)]
                (if (and ad (not (:feature ad)) (fn-literal? (:arg item)))
                  (update item :arg hint-fn-literal (param-hints ad (:kotlin-type slot)))
                  item)))
            (slots decl) items)))

(defn companion-unknown?
  "Does the call pass a receiver of unknown type where the class var of a companion is expected?
  The static path would ignore it, so such a call checks the receiver at run time. The receiver of an extension on a
  Companion is used as it is when it is not the class var, so there only a class var or a class that the compiler knows
  for sure is known."
  [decl items]
  (boolean (some (fn [[slot item]]
                   (and (:companion-of slot)
                        (let [info (:info item)]
                          (if (= :extension (:role slot))
                            (not (or (:companion-of info) (and (:class info) (not (:upper info)))))
                            (empty? (select-keys info [:class :companion-of]))))))
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

(defn- result-tag
  "The type hint of the expansion of a call of `decl` (plan `p`): the user's own (`(meta form)`), else the JVM class
  that the call returns when the expression is the plain JVM call (a converted result has no class). With it plain
  Java interop on the result of a kt call needs no hint. nil for a primitive result (a local cannot be hinted with
  a primitive initializer) and for a field read."
  [form decl p]
  (let [jn (when (and decl p) (return-jvm-name decl))
        prim? (or (nil? jn) (contains? prim-classes jn))
        plain? (and (#{:static :virtual} (:op p)) (not (:unit? p)) (not (:ret-td p)) (not (:suspend? p)) (not (:ret-vc p)))]
    (cond
      (and prim? (not (:ret-vc p))) nil
      (:tag (meta form)) (:tag (meta form))
      plain? (when-let [^Class c (return-class decl)]
               (when-not (.isPrimitive c) (symbol (.getName c))))
      (:ret-vc p) (when-let [^Class c (return-class decl)] (symbol (.getName c))))))

(defn- tagged-result
  "`result` as a form that Clojure knows the type of: a `let*` has no tag of its own, so the value goes through a
  hinted local."
  [form decl p result]
  (if-let [t (result-tag form decl p)]
    (let [g (with-meta (gensym "r") {:tag t})]
      `(let* [~g ~result] ~g))
    result))

(defn expand
  "Compile-time expansion of a call of var `v` with argument forms `forms`."
  [env form v forms0]
  (let [forms (guard-failed-objects env forms0)
        uform (user-form v forms0)
        decls (:kt/decls (meta v))
        var-name (str (.sym ^clojure.lang.Var v))
        parsed (parse-args var-name decls forms (fn [f] {:arg f :info (form-info env f)}))
        invokes (:kt/invokes (meta v))
        {:keys [decl items checks] :as r} (try (choose-call var-name decls invokes parsed
                                                            #(with-class-first % (var-form v) (value-info (.getRawRoot ^clojure.lang.Var v))) true)
                                               (catch clojure.lang.ExceptionInfo e
                                                 ;; the elements of a collection passed as a whole to a vararg choose at run time
                                                 (if (and (:kt/elements-needed (ex-data e)) (not-any? #(= "<>" (first %)) (:named parsed)))
                                                   {:dynamic? true}
                                                   (throw e))))]
    (cond
      (and (:dynamic? r) (contains? r :tform))
      (fail (str "kt: " (short-form uform) ": `:<>` needs the static path, but the declaration cannot be selected at compile "
                 "time (the type of an argument or receiver is unknown and several declarations fit). Add a type hint, "
                 "e.g. `^web.Exchange ex`.\n  Kotlin declares:\n" (signatures decls)))
      (:dynamic? r) (do (warn-dynamic form var-name)
                        (let [d (dynamic-form v parsed)]
                          (if-let [t (:tag (meta form))]
                            (let [g (with-meta (gensym "r") {:tag t})] `(let* [~g ~d] ~g))
                            d)))
      :else
      (do (check-supported! decl checks)
          (cond
            (and (companion-unknown? decl items) (contains? r :tform))
            (fail (str "kt: " (short-form uform) ": `:<>` needs the static path, but the receiver of a companion member is not "
                       "known to be the class var at compile time. Call it with the class var itself."))
            (companion-unknown? decl items) (do (warn-dynamic form var-name) (dynamic-form v parsed))
            (contains? r :tform) (expand-typed form uform decl items (:tform r) v forms0)
            :else (let [p (with-site (plan decl (type-literals decl items)) v uform forms0)]
                    (tagged-result form decl p (emit p))))))))

(defmacro kt-call
  "Static path for a call of a kt var. `var-form` is `(var ns/name)`."
  [var-form & args]
  (let [v (resolve (second var-form))]
    (expand &env &form v args)))
