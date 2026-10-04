(ns ckway.meta
  "Kotlin metadata -> package index. Pure data, no code generation.

  Also public (step 5): `class-info` (value class / fun interface facts of a class), `static-method?`,
  `static-field?`, `classpath-files` (the class path as Files, incl. what Clojure's DynamicClassLoader
  added), `public-classes` (the public top-level Kotlin classes of a package, for `ckway.types`).

  Contract (later steps depend on it):

    (package-index \"fx\")  ; \"\" is the root package
    => {var-name [declaration ...]}

  `var-name` is the string name of the Clojure var (DESIGN-2 rules 2, 3):
    top-level function f            -> \"f\"
    function with a receiver        -> \".f\"   (member or extension)
    property (any)                  -> \"p\"
    class C / nested Outer.Inner    -> \"C\" / \"Outer.Inner\"   (object: same name)
    enum entry                      -> \"E.ENTRY\"
  All declarations with the same var-name share one var.

  Inherited members (DESIGN-2 rule 1): a class var set also holds the public members that
  the class inherits from its Kotlin supertypes (superclass and interfaces, transitively,
  also from another package or jar; includes interface members with a default body and
  members of an interface that the class delegates to). Such a member is the declaration of
  the supertype itself (:owner and the call target are the supertype, the call is virtual),
  so the same inherited declaration is the same map in every package that reaches it. A
  member that a class overrides is not listed again for that class. A member that comes from
  a supertype without Kotlin metadata (a Java class or interface, `kotlin.Any`, a mapped
  Kotlin type such as `kotlin.collections.List`) is NOT a var: plain Java interop
  `(.getX obj)` covers it, and the walk ends on that branch. Type arguments of a generic
  supertype are not substituted (the member keeps its `T`).

  A declaration is a plain map:
    :kind        :function | :property | :class | :object | :enum-entry | :alias
                 (:alias is a public `typealias`: `:alias {:params [\"T\"] :type <expanded type>}`. The
                 declarations that follow it under the same var-name are what the alias var calls: the
                 constructors or the object of the class it stands for - a Kotlin class from its metadata, a
                 Java class by reflection - or, for an alias of anything else (function type, `Map<String, Int>`),
                 one :no-constructor declaration with the flag :alias. See `ckway.core` for the var.)
    :name        Kotlin name (\"<init>\" is never used here; a constructor is a :class)
    :var-name    see above
    :owner       JVM class that holds the JVM member (facade class for top-level
                 declarations, the companion class for companion members)
    :receivers   [{:role :context|:dispatch|:extension :type <type> :jvm-type \"java.lang.String\"|nil}]
                 in Clojure argument order: context, dispatch, extension.
                 The dispatch receiver has no :jvm-type (it is the call target).
                 A companion member has {:role :dispatch :companion-of \"fx.C\" ...}:
                 the value is the class var of C, the call target is C.Companion.
    :params      [{:name :type <type> :default? :vararg? :jvm-type \"int\"|\"[Ljava.lang.String;\"|...}]
    :return      <type>
    :type-params [{:name \"T\" :reified? bool [:bounds [<type> ...]]}]
    :class-type-params  (a member of a generic class only) the type parameters of the class, same shape as
                 :type-params. The dispatch receiver's type mentions them (`GBox<T>`); `ckway.bridge.ksrc` declares
                 them on the generated function.
                 :bounds is there only for a parameter with an upper bound other than `Any?`
                 (`T : Number`, `T : Any`, `where T : A, T : B`). For a function these are its own
                 type parameters; for a constructor (:kind :class) the type parameters of the class.
    :flags       #{:suspend :inline :infix :operator :static :mangled
                   :value-class :abstract :sealed :interface :enum :inner :const :mutable :no-constructor ...}
                 :no-constructor marks the one declaration of a class var that has no public constructor (an
                 interface, abstract, sealed or enum class, a class with only non-public constructors); an enum
                 class's declaration also has :entries (the var names of its entries)
    :value-class (only on the declarations of a value class itself: its constructors) the
                 value-class description below
    :jvm         {:class \"fx.User\" :name \"greet\" :desc \"(Ljava/lang/String;)Ljava/lang/String;\"
                  :static? false   ; true for a top-level member and for a @JvmStatic member of an object
                                   ; (also @JvmField and const of an object): the JVM class says it, the
                                   ; Kotlin metadata does not (step 5). The dispatch receiver of such a
                                   ; member is still a receiver for Clojure; the call ignores it.
                  :default {:class .. :name \"greet$default\" :desc .. :static? true}}
                 or {:class .. :field \"NAME\" :static? true} for a field read,
                 or {:class .. :instance-field \"INSTANCE\"} for an object,
                 or nil when the declaration has no usable JVM member.
                 :name is the real JVM name, also when it is not a Clojure symbol (`next-yIzh284`,
                 `box-impl`). The index does not say how to call it: `ckway.bridge` does.
                 The JVM member can be non-public (an `inline` function that Kotlin hides, such as
                 `Duration.Companion.getSeconds-UwyO8pc`); `ckway.bridge` also handles that.
                 The constructor of a value class is the static `constructor-impl` (:static? true)
                 that returns the underlying value, its :default is the static
                 `constructor-impl$default` (with :marker). A constructor with a value-class
                 parameter can have a trailing DefaultConstructorMarker in the JVM descriptor.
                 A member of a companion also has :companion-field \"Companion\" and
                 :companion-owner \"fx.C\".  A constructor has :name \"<init>\"; its
                 :default has :marker \"kotlin.jvm.internal.DefaultConstructorMarker\".
    :getter / :setter   (properties) same shape as :jvm; :jvm is the getter (or field)
    :setter-visibility  (a `var` with an accessor) :public|:internal|:private|:protected - the Kotlin visibility of
                 the setter. `private set` has no JVM setter at all, `internal set` a mangled non-public one:
                 only a :public setter can be called (`ckway.set`).
    :signature   the Kotlin signature as text (used in docs and errors); a generic function shows its
                 type parameters: `inline fun <reified T : Number> sumAs(vararg xs: Int): String`

  <type> = {:class \"kotlin/String\"   ; Kotlin internal name, nil for a type parameter
            :type-param \"T\"          ; only for a type parameter
            :nullable? bool :args [<type>|{:star? true}] :alias \"pkg/Alias\"|nil
            :value-class? bool :fun-interface? bool
            :fn-type {:arity n :args [<type>] :return <type> :suspend? bool :receiver? bool}|nil}
  For a function type `T.(A) -> R` the :args hold the receiver first: [T A], arity 2, and
  :receiver? true; the JVM type is kotlin.jvm.functions.Function2. A `fun interface` is a type
  with :fun-interface? true; its single abstract method is read from the JVM class by ckway.resolve.
  A `suspend` function type has the Kotlin shape too: `suspend T.(A) -> R` is :suspend? true,
  :args [T A], :arity 2, :return R. The JVM type is Function<arity+1> (the Continuation is the last
  parameter, the result is Object); the metadata writes it that way and `suspend-fn-type` undoes it.

  Value class (DESIGN-2 rule 7, 3.1): a <type> of a value class also has
    :value-class {:class \"kotlin.time.Duration\"   ; the JVM class of the boxed object
                  :property \"rawValue\"            ; name of the underlying property
                  :underlying <type>                ; its Kotlin type
                  :jvm-underlying \"long\"          ; the JVM type of the unboxed value
                  :box   {:class .. :name \"box-impl\"   :desc \"(J)Lkotlin/time/Duration;\" :static? true}
                  :unbox {:class .. :name \"unbox-impl\" :desc \"()J\" :static? false}}
  `box` and `unbox` are read from the JVM class (they are not in the Kotlin metadata). Which
  JVM slot holds the box and which holds the underlying value is NOT in the type: compare the
  JVM type of the slot (:jvm-type, or the descriptor) with :class. A slot of the class itself
  holds the box (`Uid?` over a primitive, a generic position); any other slot holds the underlying
  value. A member of a value class is a static `name-impl(underlying, ...)` when the JVM class has
  such a static method (checked by reflection); the primary property is an instance getter."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.lang.invoke MethodType]
           [java.lang.reflect Method Modifier]
           [java.util.jar JarFile JarEntry]
           [kotlin.metadata Attributes KmType KmClassifier$Class KmClassifier$TypeParameter
            KmClassifier$TypeAlias KmTypeAlias KmDeclarationContainer KmClass KmFunction KmProperty
            KmConstructor KmValueParameter KmTypeParameter KmTypeProjection ClassKind MemberKind Visibility
            KmPropertyAccessorAttributes]
           [kotlin.metadata.jvm KotlinClassMetadata KotlinClassMetadata$Class KotlinClassMetadata$FileFacade
            KotlinClassMetadata$MultiFileClassPart JvmExtensionsKt JvmMethodSignature JvmFieldSignature]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- names

(defn internal->binary
  "\"fx/Outer.Inner\" -> \"fx.Outer$Inner\""
  [^String n]
  (let [i (.lastIndexOf n "/")
        pkg (if (neg? i) "" (subs n 0 (inc i)))
        cls (subs n (inc i))]
    (str (str/replace pkg "/" ".") (str/replace cls "." "$"))))

(defn- split-internal
  "\"fx/Outer.Inner\" -> [\"fx\" \"Outer.Inner\"]"
  [^String n]
  (let [i (.lastIndexOf n "/")]
    [(if (neg? i) "" (str/replace (subs n 0 i) "/" ".")) (subs n (inc i))]))

(defn- desc-type
  "JVM descriptor fragment -> Class.getName style name."
  [^String d]
  (case (.charAt d 0)
    \I "int" \J "long" \D "double" \F "float" \Z "boolean" \C "char" \B "byte" \S "short" \V "void"
    \L (str/replace (subs d 1 (dec (count d))) "/" ".")
    \[ (str/replace d "/" ".")))

(defn desc-types
  "\"(ILjava/lang/String;)V\" -> {:params [\"int\" \"java.lang.String\"] :return \"void\"}"
  [^String desc]
  (let [close (.indexOf desc ")")
        ps ^String (subs desc 1 close)]
    {:return (desc-type (subs desc (inc close)))
     :params (loop [i 0 out []]
               (if (>= i (count ps))
                 out
                 (let [start i
                       i (long (loop [i (long i)] (if (= \[ (.charAt ps i)) (recur (inc i)) i)))
                       end (if (= \L (.charAt ps i)) (inc (.indexOf ps ";" (int i))) (inc i))]
                   (recur end (conj out (desc-type (subs ps start end)))))))}))

;; ---------------------------------------------------------------- classpath scan

(defn classpath-files
  "Directories and jars of the classpath, from java.class.path and URLClassLoaders (what Clojure's
  DynamicClassLoader added too), as Files. `ckway.bridge.kotlinc` compiles against the same list."
  []
  (let [props (str/split (System/getProperty "java.class.path" "") (re-pattern File/pathSeparator))
        loaders (take-while some? (iterate #(.getParent ^ClassLoader %) (clojure.lang.RT/baseLoader)))
        urls (for [l loaders :when (instance? java.net.URLClassLoader l)
                   u (.getURLs ^java.net.URLClassLoader l) :when (= "file" (.getProtocol ^java.net.URL u))]
               (.getPath ^java.net.URL u))]
    (->> (concat props urls) (remove str/blank?) distinct (map #(File. ^String %)) (filter #(.exists ^File %)))))

(def ^:private jar-cache
  "Documented cache: jar path -> {package-dir -> [class simple names]}."
  (atom {}))

(defn- jar-packages [^File jar]
  (or (get @jar-cache (.getPath jar))
      (let [idx (with-open [jf (JarFile. jar)]
                  (->> (enumeration-seq (.entries jf))
                       (map #(.getName ^JarEntry %))
                       (filter #(str/ends-with? ^String % ".class"))
                       (group-by #(let [i (.lastIndexOf ^String % "/")] (if (neg? i) "" (subs % 0 i))))
                       (into {} (map (fn [[d ns]] [d (mapv #(subs % (inc (.lastIndexOf ^String % "/")) (- (count %) 6)) ns)])))))]
        (swap! jar-cache assoc (.getPath jar) idx)
        idx)))

(defn- class-names-in
  "Binary names of the class files that sit directly in `pkg` on the classpath."
  [pkg]
  (let [dir (str/replace pkg "." "/")
        prefix (if (str/blank? pkg) "" (str pkg "."))]
    (->> (classpath-files)
         (mapcat (fn [^File f]
                   (if (.isDirectory f)
                     (for [^File c (or (.listFiles (File. f dir)) []) :let [n (.getName c)]
                           :when (str/ends-with? n ".class")]
                       (subs n 0 (- (count n) 6)))
                     (get (jar-packages f) dir))))
         distinct sort
         (remove #(contains? #{"module-info" "package-info"} %))
         (mapv #(str prefix %)))))

;; ---------------------------------------------------------------- metadata access

(defn- load-class ^Class [^String n]
  (try (Class/forName n false (clojure.lang.RT/baseLoader))
       (catch LinkageError _ nil)
       (catch ClassNotFoundException _ nil)))

(defn- read-meta [^Class c]
  (when-let [a (.getAnnotation c kotlin.Metadata)]
    (try (KotlinClassMetadata/readStrict ^kotlin.Metadata a)
         (catch Exception e
           (throw (ex-info (str "kt: cannot read Kotlin metadata of " (.getName c)
                                " (written by a newer Kotlin than kotlin-metadata-jvm? update the dependency)")
                           {:class (.getName c)} e))))))

(declare km-type tparams)

(def ^:private class-info-cache
  "Documented cache: Kotlin internal class name -> {:value-class? :value-class :fun-interface?}."
  (atom {}))

(defn- method-desc ^String [^Method m]
  (.toMethodDescriptorString (MethodType/methodType (.getReturnType m) (.getParameterTypes m))))

(defn- declared-method
  "The declared method of `c` with this name and (when given) descriptor."
  ^Method [^Class c ^String n desc]
  (first (filter (fn [^Method m] (and (= n (.getName m)) (or (nil? desc) (= desc (method-desc m)))))
                 (.getDeclaredMethods c))))

(defn static-method?
  "Is the JVM method `owner`.`name``desc` static? nil when it cannot be found."
  [owner name desc]
  (when-let [m (some-> (load-class owner) (declared-method name desc))]
    (Modifier/isStatic (.getModifiers ^Method m))))

(defn static-field?
  "Is the JVM field `owner`.`name` static? nil when it cannot be found."
  [owner name]
  (when-let [c (load-class owner)]
    (when-let [f (first (filter (fn [^java.lang.reflect.Field f] (= name (.getName f))) (.getDeclaredFields c)))]
      (Modifier/isStatic (.getModifiers ^java.lang.reflect.Field f)))))

(defn- value-class-info
  "Description of a value class (see the namespace docstring), or nil if the JVM class has no
  box-impl/unbox-impl."
  [binary ^Class c ^KmClass k]
  (let [unbox (first (filter (fn [^Method m] (and (= "unbox-impl" (.getName m)) (zero? (.getParameterCount m))))
                             (.getDeclaredMethods c)))
        box (first (filter (fn [^Method m] (and (= "box-impl" (.getName m)) (= 1 (.getParameterCount m))
                                                (Modifier/isStatic (.getModifiers m))))
                           (.getDeclaredMethods c)))]
    (when (and unbox box)
      {:class binary
       :property (.getInlineClassUnderlyingPropertyName k)
       :underlying (km-type (tparams [(.getTypeParameters k)]) (.getInlineClassUnderlyingType k))
       :jvm-underlying (.getName (.getReturnType ^Method unbox))
       :box {:class binary :name "box-impl" :desc (method-desc box) :static? true}
       :unbox {:class binary :name "unbox-impl" :desc (method-desc unbox) :static? false}})))

(defn class-info
  "{:value-class? :value-class :fun-interface?} of the Kotlin class with this internal name (\"kotlin/UInt\").
  Documented cache."
  [internal]
  (if-let [e (find @class-info-cache internal)]
    (val e)
    (let [info (let [binary (internal->binary internal)
                     c (load-class binary)
                     m (some-> c read-meta)
                     k (when (instance? KotlinClassMetadata$Class m) (.getKmClass ^KotlinClassMetadata$Class m))
                     value? (boolean (and k (Attributes/isValue k)))]
                 {:value-class? value?
                  :value-class (when value? (value-class-info binary c k))
                  :fun-interface? (boolean (and k (Attributes/isFunInterface k)))})]
      (swap! class-info-cache assoc internal info)
      info)))

;; ---------------------------------------------------------------- types

(defn- suspend-fn-type
  "A `suspend` function type is written in the metadata as the JVM type: FunctionN+1 with the
  Continuation<R> and the Object result after the parameters. Give it the Kotlin shape
  (parameters, then R), so that :arity is the number of Kotlin parameters (without the continuation)."
  [args]
  (let [cont (last (butlast args))]
    (when (and (>= (count args) 2) (= "kotlin/coroutines/Continuation" (:class cont)))
      (let [r (first (:args cont))]
        {:args (vec (drop-last 2 args))
         :return (if (:star? r) {:class "kotlin/Any" :nullable? true :args []} r)}))))

(defn- fn-type-of [^String cname args suspend? receiver?]
  (when-let [[_ s n] (re-matches #"kotlin/(?:coroutines/)?(Suspend)?Function(\d+)" cname)]
    (let [susp? (boolean (or s suspend?))
          k (when susp? (suspend-fn-type args))
          ps (or (:args k) (vec (butlast args)))]
      {:arity (count ps) :args ps :return (or (:return k) (last args))
       :suspend? susp? :receiver? receiver? :declared-arity (parse-long n)})))

(defn- extension-fn-type? [^KmType t]
  (boolean (some #(= "kotlin/ExtensionFunctionType" (.getClassName ^kotlin.metadata.KmAnnotation %))
                 (JvmExtensionsKt/getAnnotations t))))

(defn- km-type [tps ^KmType t]
  (when t
    (let [c (.getClassifier t)
          cname (when (instance? KmClassifier$Class c) (.getName ^KmClassifier$Class c))
          args (mapv (fn [^KmTypeProjection p]
                       (if-let [pt (.getType p)] (km-type tps pt) {:star? true}))
                     (.getArguments t))
          info (when cname (class-info cname))
          abbr (some-> (.getAbbreviatedType t) .getClassifier)]
      {:class cname
       :type-param (when (instance? KmClassifier$TypeParameter c) (get tps (.getId ^KmClassifier$TypeParameter c) "?"))
       :nullable? (Attributes/isNullable t)
       :args args
       :alias (when (instance? KmClassifier$TypeAlias abbr) (.getName ^KmClassifier$TypeAlias abbr))
       :value-class? (boolean (:value-class? info))
       :value-class (:value-class info)
       :fun-interface? (boolean (:fun-interface? info))
       :fn-type (when (and cname (seq args)) (fn-type-of cname args (Attributes/isSuspend t) (extension-fn-type? t)))})))

(defn type-text
  "Kotlin-like text of a <type>."
  [{:keys [class type-param nullable? args fn-type star?]}]
  (if star?
    "*"
    (str (cond
           fn-type (str (when (:suspend? fn-type) "suspend ")
                        "(" (str/join ", " (map type-text (:args fn-type))) ") -> " (type-text (:return fn-type)))
           type-param type-param
           :else (let [n (str/replace (or class "?") #"^kotlin/" "")]
                   (str (str/replace n "/" ".")
                        (when (seq args) (str "<" (str/join ", " (map type-text args)) ">")))))
         (when nullable? "?"))))

(defn- tparams [lists]
  (into {} (for [^KmTypeParameter p (apply concat lists)] [(.getId p) (.getName p)])))

(defn- any-bound? [t]
  (and (= "kotlin/Any" (:class t)) (:nullable? t)))

(defn- type-params-of
  "{:name :reified? [:bounds [<type>...]]}; :bounds is absent for a parameter whose only bound is Any?."
  [tps ^java.util.List l]
  (mapv (fn [^KmTypeParameter p]
          (let [bounds (vec (remove any-bound? (map #(km-type tps %) (.getUpperBounds p))))]
            (cond-> {:name (.getName p) :reified? (Attributes/isReified p)}
              (seq bounds) (assoc :bounds bounds))))
        l))

(defn- tparams-text [tps]
  (when (seq tps)
    (str "<" (str/join ", " (for [{:keys [name reified? bounds]} tps]
                              (str (when reified? "reified ") name
                                   (when (seq bounds) (str " : " (str/join ", " (map type-text bounds)))))))
         "> ")))

;; ---------------------------------------------------------------- declarations

(defn- sig-map [owner static? ^JvmMethodSignature s]
  (when s {:class owner :name (.getName s) :desc (.getDescriptor s) :static? static?}))

(defn- with-access
  "A JVM member map of a top-level declaration of a multi-file class part (`:class` is the part that really
  declares the method, `facade` the public class that inherits it). Adds what the call needs to know:
  `:public?` (the method is public) and, when it is public but the part is not (the usual case: a part is a
  package-private class), `:call-class` - the facade, through which the public method is called directly (a static
  method is inherited). A method that is not public (an `@InlineOnly` function is `private` in its part) has no
  :call-class: the call goes through a bridge (`ckway.bridge`) that looks the method up in :class. A member that
  the JVM class does not have is returned as it is."
  [facade {:keys [class name desc] :as jvm}]
  (if-let [^Method m (and jvm facade (some-> (load-class class) (declared-method name desc)))]
    (let [pub? (Modifier/isPublic (.getModifiers m))
          cls-pub? (Modifier/isPublic (.getModifiers (.getDeclaringClass m)))]
      (cond-> (assoc jvm :public? pub?)
        (and pub? (not cls-pub?)) (assoc :call-class facade)))
    jvm))

(defn- field-map [owner static? ^JvmFieldSignature s]
  (when s {:class owner :field (.getName s) :desc (.getDescriptor s) :static? static?}))

(def ^:private marker-desc "Lkotlin/jvm/internal/DefaultConstructorMarker;")

(defn- default-jvm
  "The `$default` synthetic that Kotlin generates for a function or constructor with defaults.
  A constructor of a value class is the static `constructor-impl$default`. A trailing
  DefaultConstructorMarker of the original (a constructor with a value-class parameter) is not
  repeated."
  [{:keys [class name desc static?]} nvalue ctor? instance-desc]
  (let [masks (max 1 (quot (+ nvalue 31) 32))
        close (.indexOf ^String desc ")")
        head (str/replace (subs desc 1 close) (re-pattern (str (java.util.regex.Pattern/quote marker-desc) "$")) "")
        marker (if ctor? marker-desc "Ljava/lang/Object;")
        new-desc (str "(" (when-not static? instance-desc) head (apply str (repeat masks "I")) marker ")"
                      (subs desc (inc close)))]
    (cond
      (and ctor? static?) {:class class :name (str name "$default") :desc new-desc :static? true
                           :marker "kotlin.jvm.internal.DefaultConstructorMarker"}
      ctor? {:class class :name "<init>" :desc new-desc :static? false :marker "kotlin.jvm.internal.DefaultConstructorMarker"}
      :else {:class class :name (str name "$default") :desc new-desc :static? true})))

(defn- value-param [tps jvm-type ^KmValueParameter p]
  (let [vt (.getVarargElementType p)]
    {:name (.getName p) :type (km-type tps (.getType p)) :default? (Attributes/getDeclaresDefaultValue p)
     :vararg? (some? vt) :vararg-elem (when vt (km-type tps vt)) :jvm-type jvm-type}))

(defn- receivers
  "Receivers in Clojure argument order; `jvm-types` are the non-dispatch JVM parameter types, in order."
  [tps ctx-params ext-type dispatch jvm-types]
  (let [mk (fn [role t jt name] (cond-> {:role role :type (km-type tps t)} jt (assoc :jvm-type jt) name (assoc :name name)))
        nctx (count ctx-params)
        ctx (map-indexed (fn [i ^KmValueParameter p] (mk :context (.getType p) (nth jvm-types i nil) (.getName p))) ctx-params)
        ext (when ext-type [(mk :extension ext-type (nth jvm-types nctx nil) nil)])]
    (vec (concat ctx (when dispatch [dispatch]) ext))))

(defn- param-types-after
  "Last n of the JVM parameter types (drops synthetic leading parameters)."
  [types n]
  (vec (take-last n types)))

(defn- ctx-text [rcvs]
  (let [cs (filter #(= :context (:role %)) rcvs)]
    (when (seq cs)
      (str "context(" (str/join ", " (map #(str (:name %) ": " (type-text (:type %))) cs)) ") "))))

(defn- sig-text [kind name rcvs params ret & [tparams]]
  (let [r (first (filter #(= :extension (:role %)) rcvs))
        d (first (filter #(= :dispatch (:role %)) rcvs))
        ps (str/join ", " (for [p params]
                            (str (when (:vararg? p) "vararg ") (:name p) ": "
                                 (type-text (if (:vararg? p) (:vararg-elem p) (:type p)))
                                 (when (:default? p) " = ..."))))]
    (case kind
      :function (str (ctx-text rcvs) "fun " (tparams-text tparams) (when d (str (type-text (:type d)) ".")) (when r (str (type-text (:type r)) ".")) name "(" ps "): " (type-text ret))
      :property (str "val " (when d (str (type-text (:type d)) ".")) (when r (str (type-text (:type r)) ".")) name ": " (type-text ret))
      :class (str "class " name "(" ps ")")
      :object (str "object " name)
      :enum-entry (str "enum entry " name))))

(defn- visible? [vis] (= Visibility/PUBLIC vis))

(defn- fn-flags [^KmFunction f jvm-name]
  (cond-> #{}
    (Attributes/isSuspend f) (conj :suspend)
    (Attributes/isInline f) (conj :inline)
    (Attributes/isInfix f) (conj :infix)
    (Attributes/isOperator f) (conj :operator)
    (and jvm-name (str/includes? jvm-name "-")) (conj :mangled)))

;; A member of a value class is compiled to a static `name-impl(underlying, ...)` whose first
;; parameter is the receiver; the JVM class may also have an instance method (the primary
;; property's getter). The JVM class tells which.
(defn- self-static?
  [value-self owner ^JvmMethodSignature sig]
  (boolean (and value-self sig (static-method? owner (.getName sig) (.getDescriptor sig)))))

;; The Kotlin metadata does not say `@JvmStatic`. A member of an `object` (or a class) that is
;; @JvmStatic is a static JVM method, a `@JvmField` or `const` property of an object is a static
;; JVM field, and the JVM class is the only source of truth: ask it. (The dispatch receiver is still
;; a receiver for Clojure: the call ignores it. A companion member stays an instance member of the
;; Companion class, the @JvmStatic twin on the outer class is not in the metadata.)
(defn- jvm-static?
  [value-self owner ^JvmMethodSignature sig]
  (boolean (and (not value-self) sig (static-method? owner (.getName sig) (.getDescriptor sig)))))

(defn- function-decl
  "ctx = {:owner :tps :dispatch <receiver>|nil :jvm-extra {...}|nil :static? bool :instance-desc str
          :value-self bool}   ; value-self: the members of a value class (see `self-static`)"
  [{:keys [owner jvm-owner tps dispatch jvm-extra static? instance-desc value-self class-tparams]} ^KmFunction f]
  (let [tps (merge tps (tparams [(.getTypeParameters f)]))
        sig (JvmExtensionsKt/getSignature f)
        self? (self-static? value-self owner sig)
        static? (or static? self? (jvm-static? value-self owner sig))
        jvm (some->> (some-> (sig-map (or jvm-owner owner) static? sig) (merge jvm-extra)) (with-access (when jvm-owner owner)))
        dtypes (when jvm (:params (desc-types (:desc jvm))))
        dispatch (cond-> dispatch (and self? dtypes) (assoc :jvm-type (first dtypes)))
        vps (.getValueParameters f)
        ctx-types (vec (.getContextParameters f))
        nvalue (count vps)
        nrecv (+ (count ctx-types) (if (.getReceiverParameterType f) 1 0))
        suspend? (Attributes/isSuspend f)
        dtypes (when dtypes (if suspend? (vec (butlast dtypes)) dtypes))
        ptypes (when dtypes (param-types-after dtypes (+ nrecv nvalue)))
        rcvs (receivers tps ctx-types (.getReceiverParameterType f) dispatch (some-> ptypes (subvec 0 nrecv)))
        params (vec (map-indexed (fn [i p] (value-param tps (some-> ptypes (nth (+ nrecv i) nil)) p)) vps))
        ret (km-type tps (.getReturnType f))
        tp (type-params-of tps (.getTypeParameters f))
        flags (cond-> (fn-flags f (:name jvm)) (not dispatch) (conj :static))
        jvm (when jvm (cond-> jvm (some :default? params)
                              (assoc :default (with-access (when jvm-owner owner) (default-jvm jvm nvalue false instance-desc)))))
        name (.getName f)]
    (cond->
     {:kind :function :name name :var-name (if (seq rcvs) (str "." name) name)
      :owner owner :receivers rcvs :params params :return ret
      :type-params tp :flags flags :jvm jvm
      :signature (str (str/join (for [[k m] [[:suspend "suspend "] [:inline "inline "] [:infix "infix "] [:operator "operator "]]
                                       :when (k flags)] m))
                      (sig-text :function name rcvs params ret tp))}
      (seq class-tparams) (assoc :class-type-params class-tparams))))

(defn- property-decl
  [{:keys [owner jvm-owner tps dispatch static? field-owner value-self]} ^KmProperty p]
  (let [tps (merge tps (tparams [(.getTypeParameters p)]))
        jo (or jvm-owner owner)
        facade (when jvm-owner owner)
        gsig (JvmExtensionsKt/getGetterSignature p)
        self? (self-static? value-self owner gsig)
        getter (with-access facade (sig-map jo (or static? self? (jvm-static? value-self owner gsig)) gsig))
        ssig (JvmExtensionsKt/getSetterSignature p)
        setter (with-access facade (sig-map jo (or static? (jvm-static? value-self owner ssig)) ssig))
        fsig (JvmExtensionsKt/getFieldSignature p)
        field (field-map (or field-owner jo)
                         (boolean (or static? field-owner (and fsig (static-field? jo (.getName fsig)))))
                         fsig)
        const? (Attributes/isConst p)
        jvm (cond const? field getter getter :else field)
        ctx-types (vec (.getContextParameters p))
        nrecv (+ (count ctx-types) (if (.getReceiverParameterType p) 1 0))
        jtypes (when getter (:params (desc-types (:desc getter))))
        dispatch (cond-> dispatch (and self? jtypes) (assoc :jvm-type (first jtypes)))
        rcvs (receivers tps ctx-types (.getReceiverParameterType p) dispatch (some-> jtypes (param-types-after nrecv)))
        ret (km-type tps (.getReturnType p))
        name (.getName p)]
    {:kind :property :name name :var-name name :owner owner :receivers rcvs :params [] :return ret
     :type-params (type-params-of tps (.getTypeParameters p))
     :flags (cond-> (if dispatch #{} #{:static})
              const? (conj :const) (Attributes/isVar p) (conj :mutable)
              (and jvm (some-> (:name jvm) (str/includes? "-"))) (conj :mangled))
     :jvm jvm :getter (when-not const? getter) :setter setter
     :setter-visibility (when-let [^KmPropertyAccessorAttributes a (.getSetter p)] (keyword (str/lower-case (str (Attributes/getVisibility a)))))
     :signature (str (if (Attributes/isVar p) "var " "val ") (subs (sig-text :property name rcvs [] ret) 4))}))

(defn- own-member?
  "A fake override or a delegation is not a declaration: the walk over the supertypes finds the original."
  [kind]
  (not (contains? #{MemberKind/FAKE_OVERRIDE MemberKind/DELEGATION} kind)))

(defn- container-decls [ctx ^KmDeclarationContainer c]
  (concat
   (for [^KmFunction f (.getFunctions c)
         :when (and (visible? (Attributes/getVisibility f)) (own-member? (Attributes/getKind f)))]
     (function-decl ctx f))
   (for [^KmProperty p (.getProperties c)
         :when (and (visible? (Attributes/getVisibility p)) (own-member? (Attributes/getKind p)))]
     (property-decl ctx p))))

(defn- class-type [internal tps-list]
  {:class internal :nullable? false :args (mapv (fn [n] {:class nil :type-param n :nullable? false :args []}) tps-list)
   :value-class? false :fun-interface? false :fn-type nil})

(defn- ctor-decl [{:keys [binary internal kcls tps flags value-class]} ^KmConstructor c]
  (let [sig (JvmExtensionsKt/getSignature c)
        jvm (sig-map binary (boolean value-class) sig)
        vps (.getValueParameters c)
        dtypes (when jvm (let [ts (:params (desc-types (:desc jvm)))]
                           (if (= "kotlin.jvm.internal.DefaultConstructorMarker" (peek ts)) (pop ts) ts)))
        params (vec (map-indexed (fn [i p] (value-param tps (some-> dtypes (param-types-after (count vps)) (nth i nil)) p)) vps))
        ret (class-type internal (map #(.getName ^KmTypeParameter %) (.getTypeParameters ^KmClass kcls)))
        [_ simple] (split-internal internal)]
    (cond-> {:kind :class :name simple :var-name simple :owner binary :receivers [] :params params :return ret
             :type-params (type-params-of tps (.getTypeParameters ^KmClass kcls)) :flags flags
             :jvm (when jvm (cond-> jvm (some :default? params) (assoc :default (default-jvm jvm (count vps) true nil))))
             :signature (sig-text :class simple [] params ret)}
      value-class (assoc :value-class value-class))))

(defn- class-flags [^KmClass k kind]
  (cond-> #{}
    (Attributes/isValue k) (conj :value-class)
    (Attributes/isInner k) (conj :inner)
    (= ClassKind/INTERFACE kind) (conj :interface)
    (= ClassKind/ENUM_CLASS kind) (conj :enum)
    (= kotlin.metadata.Modality/ABSTRACT (Attributes/getModality k)) (conj :abstract)
    (= kotlin.metadata.Modality/SEALED (Attributes/getModality k)) (conj :abstract :sealed)))

(defn- class-var-decls
  "Declarations for the class var itself: constructors, object instance, enum entries."
  [{:keys [binary internal flags kcls tps kind] :as ctx} ^KmClass k]
  (let [[_ simple] (split-internal internal)
        ret (class-type internal [])]
    (case (str kind)
      "OBJECT" [{:kind :object :name simple :var-name simple :owner binary :receivers [] :params [] :return ret
                 :type-params [] :flags flags :jvm {:class binary :instance-field "INSTANCE" :static? true}
                 :signature (sig-text :object simple [] [] ret)}]
      (let [ctors (for [^KmConstructor c (.getConstructors k) :when (visible? (Attributes/getVisibility c))]
                    (ctor-decl ctx c))
            entries (for [n (.getEnumEntries k)]
                      {:kind :enum-entry :name n :var-name (str simple "." n) :owner binary :receivers [] :params []
                       :return ret :type-params [] :flags #{:static}
                       :jvm {:class binary :field n :static? true}
                       :signature (sig-text :enum-entry (str simple "." n) [] [] ret)})
            ctors (if (or (:enum flags) (:interface flags) (:abstract flags) (empty? ctors))
                    [(cond-> {:kind :class :name simple :var-name simple :owner binary :receivers [] :params [] :return ret
                              :type-params [] :flags (conj flags :no-constructor) :jvm nil
                              :signature (sig-text :class simple [] [] ret)}
                       (:value-class ctx) (assoc :value-class (:value-class ctx))
                       (seq entries) (assoc :entries (mapv :var-name entries)))]
                    ctors)]
        (concat ctors entries)))))

;; ---------------------------------------------------------------- type aliases

(defn- alias-decl
  "The declaration of a public `typealias`: the expanded type, with the alias's own type parameters by name."
  [owner ^KmTypeAlias a]
  (let [tps (tparams [(.getTypeParameters a)])
        names (mapv #(.getName ^KmTypeParameter %) (.getTypeParameters a))
        t (km-type tps (.getExpandedType a))
        name (.getName a)]
    {:kind :alias :name name :var-name name :owner owner :receivers [] :params [] :return t
     :type-params [] :flags #{} :jvm nil :alias {:params names :type t}
     :signature (str "typealias " name (when (seq names) (str "<" (str/join ", " names) ">")) " = " (type-text t))}))

(defn- java-type
  "A <type> for a Java class (no generics, platform nullability: a reference may be null)."
  [^Class c]
  (let [prim ({"int" "kotlin/Int" "long" "kotlin/Long" "short" "kotlin/Short" "byte" "kotlin/Byte" "double" "kotlin/Double"
               "float" "kotlin/Float" "char" "kotlin/Char" "boolean" "kotlin/Boolean"} (.getName c))]
    {:class (or prim (let [n (.getName c)
                           i (inc (.lastIndexOf n "."))]
                       (str (str/replace (subs n 0 i) "." "/") (str/replace (subs n i) "$" "."))))
     :nullable? (nil? prim) :args [] :alias nil :value-class? false :fun-interface? false :fn-type nil :type-param nil}))

(defn- java-ctor-decls
  "Constructor declarations of the public Java class `c`, read by reflection (a Java class has no Kotlin
  metadata): parameter names are what the class file says (`arg0`...), types are erased. A class with no
  public constructor, or an interface or abstract class, gets the :no-constructor declaration."
  [binary simple ^Class c]
  (let [ret {:class (:class (java-type c)) :nullable? false :args [] :value-class? false :fun-interface? false}
        ctors (when-not (or (.isInterface c) (Modifier/isAbstract (.getModifiers c)) (.isEnum c))
                (filter #(Modifier/isPublic (.getModifiers ^java.lang.reflect.Constructor %)) (.getConstructors c)))]
    (if (empty? ctors)
      [{:kind :class :name simple :var-name simple :owner binary :receivers [] :params [] :return ret
        :type-params [] :flags #{:no-constructor} :jvm nil :signature (str "class " simple)}]
      (for [^java.lang.reflect.Constructor k ctors
            :let [ps (vec (map-indexed (fn [i ^java.lang.reflect.Parameter p]
                                         (let [vararg? (and (.isVarArgs k) (= i (dec (.getParameterCount k))))
                                               pt (.getType p)]
                                           (cond-> {:name (.getName p) :type (java-type pt) :default? false
                                                    :vararg? vararg? :jvm-type (.getName pt)}
                                             vararg? (assoc :vararg-elem (java-type (.getComponentType pt))))))
                                       (.getParameters k)))]]
        {:kind :class :name simple :var-name simple :owner binary :receivers [] :params ps :return ret
         :type-params [] :flags #{}
         :jvm {:class binary :name "<init>" :static? false
               :desc (.toMethodDescriptorString (MethodType/methodType Void/TYPE (.getParameterTypes k)))}
         :signature (sig-text :class simple [] ps ret)}))))

(declare class-file-decls)

(defn- alias-target-decls
  "What a call of the alias var `d` (an :alias declaration) calls: the constructors (or the object) of the
  class it stands for, a Kotlin class from its metadata, a Java class by reflection. An alias of
  anything else (a function type, a generic instantiation of a mapped interface, a type parameter) is no
  class: the :no-constructor declaration with the flag :alias, which says so."
  [{:keys [name owner signature] {:keys [type]} :alias}]
  (let [internal (:class type)
        binary (when (and internal (not (:fn-type type)) (not (:type-param type))) (internal->binary internal))
        c (some-> binary load-class)
        simple (when internal (subs internal (inc (.lastIndexOf ^String internal "/"))))
        own (when c
              (if (read-meta c)
                (filter #(and (#{:class :object} (:kind %)) (= binary (:owner %)) (= simple (:name %))) (class-file-decls binary))
                (java-ctor-decls binary simple c)))]
    (or (seq (map #(assoc % :name name :var-name name) own))
        [{:kind :class :name name :var-name name :owner owner :receivers [] :params [] :return type
          :type-params [] :flags #{:no-constructor :alias} :jvm nil :signature signature}])))

;; ---------------------------------------------------------------- inherited members

(def ^:private kotlin-class-cache
  "Documented cache: Kotlin internal class name -> {:binary :km :internal} or nil (not a Kotlin class)."
  (atom {}))

(defn- kotlin-class [internal]
  (if-let [e (find @kotlin-class-cache internal)]
    (val e)
    (let [binary (internal->binary internal)
          m (some-> (load-class binary) read-meta)
          r (when (instance? KotlinClassMetadata$Class m)
              {:binary binary :internal internal :km (.getKmClass ^KotlinClassMetadata$Class m)})]
      (swap! kotlin-class-cache assoc internal r)
      r)))

(defn- member-ctx
  "Context for the members of a class: the call target is an instance of the class."
  [binary internal ^KmClass k]
  (let [vc (when (Attributes/isValue k) (:value-class (class-info internal)))
        self (class-type internal (map #(.getName ^KmTypeParameter %) (.getTypeParameters k)))]
    {:owner binary :tps (tparams [(.getTypeParameters k)]) :static? false
     :class-tparams (type-params-of (tparams [(.getTypeParameters k)]) (.getTypeParameters k))
     :value-self (some? vc)
     :dispatch {:role :dispatch :type (cond-> self vc (assoc :value-class? true :value-class vc))}
     :instance-desc (str "L" (str/replace binary "." "/") ";")}))

(def ^:private declared-members-cache
  "Documented cache: Kotlin internal class name -> the members that class declares."
  (atom {}))

(defn- declared-members [{:keys [binary internal km]}]
  (or (get @declared-members-cache internal)
      (let [ds (vec (container-decls (member-ctx binary internal km) km))]
        (swap! declared-members-cache assoc internal ds)
        ds)))

(defn- supertype-names [^KmClass k]
  (for [^KmType t (.getSupertypes k)
        :let [c (.getClassifier t)]
        :when (instance? KmClassifier$Class c)]
    (.getName ^KmClassifier$Class c)))

(defn- inherited-decls
  "Public members that the Kotlin supertypes of `k` declare, transitively (superclass and
  interfaces; they can be in another package or jar). The declarations are the ones of the
  supertype itself: owner and call target are the supertype, so the call is virtual. A supertype
  without Kotlin metadata (Java class, kotlin.Any, mapped types) ends the walk on its branch."
  [^KmClass k]
  (loop [queue (supertype-names k) seen #{} out []]
    (if-let [n (first queue)]
      (let [sc (when-not (seen n) (kotlin-class n))]
        (recur (if sc (concat (rest queue) (supertype-names (:km sc))) (rest queue))
               (conj seen n)
               (if (and sc (visible? (Attributes/getVisibility ^KmClass (:km sc))))
                 (into out (declared-members sc))
                 out)))
      out)))

(defn- member-key
  "Two members with the same key are the same Kotlin member (an override has the key of its original)."
  [d]
  (let [tt #(type-text (:type %))]
    (case (:kind d)
      :function [:function (:name d) (mapv tt (:params d)) (mapv tt (remove #(= :dispatch (:role %)) (:receivers d)))]
      [(:kind d) (:name d)])))

(defn- class-decls
  "All declarations contributed by one Kotlin class (its own var, members, companion members)."
  [binary ^KmClass k]
  (let [internal (.getName k)
        kind (Attributes/getKind k)
        flags (class-flags k kind)
        tps (tparams [(.getTypeParameters k)])
        [_ simple] (split-internal internal)
        vis (Attributes/getVisibility k)
        self-type (class-type internal (map #(.getName ^KmTypeParameter %) (.getTypeParameters k)))]
    (cond
      (not (visible? vis)) []
      (contains? #{ClassKind/ENUM_ENTRY ClassKind/ANNOTATION_CLASS} kind) []
      (= ClassKind/COMPANION_OBJECT kind)
      ;; members of a companion hang off the outer class var
      (let [outer-internal (subs internal 0 (.lastIndexOf ^String internal "."))
            outer (internal->binary outer-internal)
            dispatch {:role :dispatch :type self-type :companion-of outer
                      :companion-field (subs simple (inc (.lastIndexOf ^String simple ".")))}
            ctx {:owner binary :tps tps :dispatch dispatch :static? false :field-owner outer
                 :jvm-extra {:companion-field (subs simple (inc (.lastIndexOf ^String simple "."))) :companion-owner outer}
                 :instance-desc (str "L" (str/replace binary "." "/") ";")}]
        (container-decls ctx k))
      :else
      (let [ctx {:binary binary :internal internal :kcls k :tps tps :flags flags :kind kind
                 :value-class (when (Attributes/isValue k) (:value-class (class-info internal)))}
            inherited (inherited-decls k)
            inherited-keys (set (map member-key inherited))
            own (remove #(contains? inherited-keys (member-key %)) (container-decls (member-ctx binary internal k) k))]
        (concat (class-var-decls ctx k) own inherited)))))

(defn- package-decls
  "The declarations of a file facade or of a part of a multi-file class. `owner` is the Kotlin-visible class (for a
  part: the facade); `jvm-owner` (parts only) is the class that really declares the JVM members."
  [owner ^KmDeclarationContainer pkg & [jvm-owner]]
  (concat (container-decls (cond-> {:owner owner :tps {} :dispatch nil :static? true} jvm-owner (assoc :jvm-owner jvm-owner)) pkg)
          (for [^KmTypeAlias a (.getTypeAliases pkg) :when (visible? (Attributes/getVisibility a))]
            (alias-decl owner a))))

(defn- class-file-decls [binary]
  (when-let [c (load-class binary)]
    (let [m (read-meta c)]
      (cond
        (instance? KotlinClassMetadata$Class m) (class-decls binary (.getKmClass ^KotlinClassMetadata$Class m))
        (instance? KotlinClassMetadata$FileFacade m) (package-decls binary (.getKmPackage ^KotlinClassMetadata$FileFacade m))
        (instance? KotlinClassMetadata$MultiFileClassPart m)
        (let [part ^KotlinClassMetadata$MultiFileClassPart m]
          (package-decls (internal->binary (.getFacadeClassName part)) (.getKmPackage part) binary))
        :else []))))

(defn class-members
  "Declarations of the members that the JVM class `binary` declares itself (not inherited ones)."
  [binary]
  (filter #(= binary (:owner %)) (class-file-decls binary)))

(defn primary-properties
  "The properties that the primary constructor of the Kotlin class `c` declares, in constructor order
  (`kt/data`, DESIGN-2 rule 11). => nil when `c` has no Kotlin class metadata, else
  {:class \"fx.Person\" :primary? bool :props [{:name \"id\" :decl <property declaration>} ...]}.
  A constructor parameter is a property when the class declares a public member property of the same
  name and type (the metadata has no link between them: `class C(b: Int) { val b: Int = b * 2 }`
  looks like `class C(val b: Int)`). A parameter that is no property, and a property that is not public
  (`private val`), is left out. An overriding property is the class's own declaration."
  [^Class c]
  (let [m (read-meta c)]
    (when (instance? KotlinClassMetadata$Class m)
      (let [^KmClass k (.getKmClass ^KotlinClassMetadata$Class m)
            tps (tparams [(.getTypeParameters k)])
            ctor (first (remove #(Attributes/isSecondary ^KmConstructor %) (.getConstructors k)))
            members (declared-members {:binary (.getName c) :internal (.getName k) :km k})]
        {:class (.getName c)
         :primary? (some? ctor)
         :props (vec (for [^KmValueParameter p (some-> ^KmConstructor ctor .getValueParameters)
                           :let [t (type-text (km-type tps (.getType p)))
                                 d (first (filter #(and (= :property (:kind %)) (= (.getName p) (:name %))
                                                        (= [:dispatch] (mapv :role (:receivers %)))
                                                        (= t (type-text (:return %))))
                                                  members))]
                           :when d]
                       {:name (.getName p) :decl d}))}))))

(defn public-classes
  "The public top-level Kotlin classes (not nested, not file facades) of the package `pkg` on the
  class path: {\"Pair\" {:internal \"kotlin/Pair\" :binary \"kotlin.Pair\" :tparams 2}}."
  [pkg]
  (into {}
        (for [b (class-names-in pkg)
              :let [simple (subs b (if (str/blank? pkg) 0 (inc (count pkg))))]
              :when (not (str/includes? simple "$"))
              :let [c (load-class b)
                    m (some-> c read-meta)]
              :when (instance? KotlinClassMetadata$Class m)
              :let [k (.getKmClass ^KotlinClassMetadata$Class m)]
              :when (and (visible? (Attributes/getVisibility k)) (= (internal->binary (.getName k)) b))]
          [simple {:internal (.getName k) :binary b :tparams (count (.getTypeParameters k))}])))

(defn public-aliases
  "The public `typealias`es of the package `pkg` on the class path, read from the Kotlin metadata of its
  file facades: {\"ArrayList\" {:params [] :type <type>}} (the type is the expanded one; `:params` are the
  names of the alias's type parameters, which the type mentions as type parameters). For the stdlib this gives
  `kotlin.collections.ArrayList`, `kotlin.Exception`..., which have no class file."
  [pkg]
  (into {}
        (for [b (class-names-in pkg)
              :let [simple (subs b (if (str/blank? pkg) 0 (inc (count pkg))))]
              :when (and (not (str/includes? simple "$")) (str/ends-with? simple "Kt"))
              :let [m (some-> (load-class b) read-meta)]
              :when (instance? KotlinClassMetadata$FileFacade m)
              ^KmTypeAlias a (.getTypeAliases (.getKmPackage ^KotlinClassMetadata$FileFacade m))
              :when (visible? (Attributes/getVisibility a))]
          [(.getName a) (:alias (alias-decl b a))])))

(def ^:private package-cache
  "Documented cache: package name -> index."
  (atom {}))

(defn- same-function?
  "Are `a` and `b` one Kotlin function (or property)? A function that sits in two parts of one multi-file facade
  (kotlinx.coroutines 1.11: `runBlocking` in `BuildersKt__BuildersKt`, and its `@JvmName(\"runBlockingK\")` twin of
  another source set) is two JVM methods but one declaration: same facade, same Kotlin signature."
  [a b]
  (and (#{:function :property} (:kind a)) (= (:kind a) (:kind b))
       (:jvm a) (:jvm b)
       (= (:owner a) (:owner b)) (= (:signature a) (:signature b))
       (= (:flags a) (:flags b))
       (not= (:class (:jvm a)) (:owner a))))

(defn- add-decl
  "`ds` with the declaration `d` added, unless it is already there or is the same Kotlin function as one that
  is (see `same-function?`); then the one whose JVM name is the Kotlin name is kept."
  [ds d]
  (cond
    (some #(= d %) ds) ds
    :else
    (if-let [i (first (keep-indexed #(when (same-function? d %2) %1) ds))]
      (if (and (not= (:name (:jvm (nth ds i))) (:name d)) (= (:name d) (:name (:jvm d))))
        (assoc ds i d)
        ds)
      (conj ds d))))

(defn- build-index [pkg]
  (->> (class-names-in pkg)
       (mapcat class-file-decls)
       (mapcat (fn [d] (if (= :alias (:kind d)) (cons d (alias-target-decls d)) [d])))
       (reduce (fn [m d] (update m (:var-name d) #(add-decl (or % []) d)))
               (sorted-map))))

(defn package-index
  "Index of the Kotlin package `pkg` (see the namespace docstring). Cached per package."
  [pkg]
  (or (get @package-cache pkg)
      (let [idx (build-index pkg)]
        (swap! package-cache assoc pkg idx)
        idx)))

(defn clear-cache!
  "Forget cached indexes (tests, or after the classpath changed)."
  []
  (reset! package-cache {}))
