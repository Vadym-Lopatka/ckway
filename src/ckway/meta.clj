(ns ckway.meta
  "Kotlin metadata -> package index. Pure data, no code generation.

  Also public: `class-info` (value class / fun interface facts of a class), `static-method?`,
  `static-field?`, `classpath-files` (the class path as Files, incl. what Clojure's DynamicClassLoader
  added), `public-classes` (the public top-level Kotlin classes of a package, for `ckway.types`).

  Contract (later steps depend on it):

    (package-index \"fx\")  ; \"\" is the root package
    => {var-name [declaration ...]}

  `var-name` is the string name of the Clojure var (README rules 2, 3):
    top-level function f            -> \"f\"
    function with a receiver        -> \".f\"   (member or extension)
    property (any)                  -> \"p\"
    class C / nested Outer.Inner    -> \"C\" / \"Outer.Inner\"   (object: same name)
    enum entry                      -> \"E.ENTRY\"
  All declarations with the same var-name share one var.

  Inherited members (README rule 1): a class var set also holds the public members that
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
                                   ; Kotlin metadata does not). The dispatch receiver of such a
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

  Value class (see README rule 7): a <type> of a value class also has
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
            KmConstructor KmValueParameter KmTypeParameter KmTypeProjection KmVariance ClassKind MemberKind Visibility
            KmPropertyAccessorAttributes]
           [kotlin.metadata.jvm KotlinClassMetadata KotlinClassMetadata$Class KotlinClassMetadata$FileFacade
            KotlinClassMetadata$MultiFileClassPart JvmExtensionsKt JvmMethodSignature JvmFieldSignature]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- caches

(defonce ^:private resets (atom {}))

(defn register-reset!
  "Register the function `f` under the key `k` (a keyword) as the reset of one cache of the library. `clear-caches!`
  calls all of them. A namespace registers its caches when it is loaded; the same key replaces the old function."
  [k f]
  (swap! resets assoc k f)
  nil)

(defonce ^:private tracked-maps (atom []))

(defn track-cache!
  "Remember the java.util.Map `m` (a call cache), weakly (by identity: a map's hash changes with its content), so that
  `clear-caches!` can empty it. => m."
  [m]
  (swap! tracked-maps (fn [refs]
                        (let [refs (if (zero? (mod (count refs) 4096))   ; drop the dead ones now and then
                                     (filterv #(some? (.get ^java.lang.ref.WeakReference %)) refs)
                                     refs)]
                          (conj refs (java.lang.ref.WeakReference. m)))))
  m)

(defn memo
  "`(memoize f)` for the library: the cache is registered under `k` (`clear-caches!` empties it) and a nil result is
  never kept (a class that is not on the class path yet may be tomorrow)."
  [k f]
  (let [c (atom {})]
    (register-reset! k #(reset! c {}))
    (fn [& args]
      (let [key (vec args)]
        (if-let [e (find @c key)]
          (val e)
          (let [v (apply f args)]
            (when (some? v) (swap! c assoc key v))
            v))))))

(defn clear-caches!
  "Forget everything the library has cached: the package indexes, the jar listings, the class facts, the
  declared-members and Kotlin-class tables, every memoized lookup (`jvm-class`...), the cached run-time
  calls of every var and the table of `kt/ref` values. For the REPL workflow in which the class path changes
  (a jar added with `add-lib`, a directory recompiled). Not public API: a repeated `kt/require` already notices
  a changed class path by itself (`package-index` compares the class path it scanned with the current one).
  Vars that `kt/require` made before keep their (now empty) caches and declarations; require the package again."
  []
  (doseq [f (vals @resets)] (f))
  (doseq [^java.lang.ref.WeakReference r @tracked-maps
          :let [m (.get r)] :when m]
    (.clear ^java.util.Map m))
  nil)

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

(defn- url->file
  "The File of a `file:` URL, or of the jar of a `jar:file:...!/` URL; nil for anything else. The URL form
  (`%20` for a space) is decoded; a URL with a raw space (`File.toURL`) is taken as it is."
  ^File [^java.net.URL u]
  (try
    (case (.getProtocol u)
      "file" (try (File. (.toURI u)) (catch java.net.URISyntaxException _ (File. (.getPath u))))
      "jar" (let [spec (.getPath u) i (.indexOf spec "!/")]
              (url->file (java.net.URL. (if (neg? i) spec (subs spec 0 i)))))
      nil)
    (catch Exception _ nil)))

(def ^:private manifest-cache
  "Documented cache: [jar path, modified time] -> the Files of the `Class-Path:` of its manifest."
  (atom {}))

(defn- manifest-class-path
  "The Files named by the `Class-Path:` attribute of the manifest of `jar` (URLs relative to the jar, separated by
  spaces), the ones that exist."
  [^File jar]
  (let [k [(.getPath jar) (.lastModified jar)]]
    (or (get @manifest-cache k)
        (let [fs (try
                   (with-open [jf (JarFile. jar)]
                     (let [cp (some-> (.getManifest jf) .getMainAttributes (.getValue "Class-Path"))
                           base (.toURI jar)]
                       (vec (for [e (str/split (str/trim (or cp "")) #"\s+") :when (not (str/blank? e))
                                  :let [f (try (File. (.resolve base ^String e)) (catch Exception _ nil))]
                                  :when (and f (.exists ^File f))]
                              f))))
                   (catch Exception _ []))]
          (swap! manifest-cache assoc k fs)
          fs))))

(defn- with-manifest-class-paths
  "`files` and, after each jar, the jars that the `Class-Path:` of its manifest names (what the JVM follows too),
  each once."
  [files]
  (loop [queue (seq files) seen #{} out []]
    (if-let [^File f (first queue)]
      (let [k (.getPath f)]
        (if (seen k)
          (recur (rest queue) seen out)
          (recur (concat (when (.isFile f) (manifest-class-path f)) (rest queue)) (conj seen k) (conj out f))))
      out)))

(defn classpath-files
  "Directories and jars of the classpath, from java.class.path and URLClassLoaders (what Clojure's
  DynamicClassLoader added too), and the jars that their manifests' `Class-Path:` name, as Files that exist.
  `ckway.bridge.kotlinc` compiles against the same list. (A class loader that is no URLClassLoader is asked for the
  resources of a package by `package-roots`.)"
  []
  (let [props (str/split (System/getProperty "java.class.path" "") (re-pattern File/pathSeparator))
        loaders (take-while some? (iterate #(.getParent ^ClassLoader %) (clojure.lang.RT/baseLoader)))
        urls (for [l loaders :when (instance? java.net.URLClassLoader l)
                   u (.getURLs ^java.net.URLClassLoader l) :let [f (url->file u)] :when f]
               f)]
    (->> (concat (map #(File. ^String %) (remove str/blank? props)) urls)
         (filter #(.exists ^File %))
         (reduce (fn [[seen out] ^File f] (let [k (.getPath f)] (if (seen k) [seen out] [(conj seen k) (conj out f)]))) [#{} []])
         second
         with-manifest-class-paths)))

(defn- package-roots
  "The classpath entries (Files: directories and jars) that can hold the package `pkg`: `classpath-files`, and what
  the class loaders say about the resources `pkg/` (a loader that is no URLClassLoader, an entry the property
  `java.class.path` does not list). Both are used: a jar without directory entries has no resource for a package
  directory, and a custom loader has no URL list."
  [pkg]
  (let [dir (str/replace pkg "." "/")
        loaders (distinct (remove nil? [(clojure.lang.RT/baseLoader) (.getContextClassLoader (Thread/currentThread))]))
        found (when-not (str/blank? pkg)
                (for [^ClassLoader l loaders
                      ^java.net.URL u (try (enumeration-seq (.getResources l dir)) (catch Exception _ []))
                      :let [f (url->file u)] :when f
                      :let [root (if (.isDirectory f)
                                   (let [p (.getPath f)]
                                     (when (str/ends-with? p (str File/separator (str/replace dir "/" File/separator)))
                                       (File. (subs p 0 (- (count p) (count dir) 1)))))
                                   f)]
                      :when root]
                  root))]
    (->> (concat (classpath-files) found)
         (reduce (fn [[seen out] ^File f] (let [k (.getPath f)] (if (seen k) [seen out] [(conj seen k) (conj out f)]))) [#{} []])
         second)))

(def ^:private jar-cache
  "Documented cache: jar path and modified time -> {package-dir -> [class simple names]}."
  (atom {}))
(register-reset! ::jar-cache #(reset! jar-cache {}))
(register-reset! ::manifest-cache #(reset! manifest-cache {}))

(defn- jar-packages [^File jar]
  (let [k [(.getPath jar) (.lastModified jar)]]
    (or (get @jar-cache k)
        (let [idx (with-open [jf (JarFile. jar)]
                    (->> (enumeration-seq (.entries jf))
                         (map #(.getName ^JarEntry %))
                         (filter #(str/ends-with? ^String % ".class"))
                         (group-by #(let [i (.lastIndexOf ^String % "/")] (if (neg? i) "" (subs % 0 i))))
                         (into {} (map (fn [[d ns]] [d (mapv #(subs % (inc (.lastIndexOf ^String % "/")) (- (count %) 6)) ns)])))))]
          (swap! jar-cache assoc k idx)
          idx))))

(defn- class-names-in
  "Binary names of the class files that sit directly in `pkg` on the classpath."
  [pkg]
  (let [dir (str/replace pkg "." "/")
        prefix (if (str/blank? pkg) "" (str pkg "."))]
    (->> (package-roots pkg)
         (mapcat (fn [^File f]
                   (if (.isDirectory f)
                     (for [^File c (or (.listFiles (File. f dir)) []) :let [n (.getName c)]
                           :when (str/ends-with? n ".class")]
                       (subs n 0 (- (count n) 6)))
                     (get (try (jar-packages f) (catch Exception _ {})) dir))))
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

(declare km-type tparams value-class-info*)

(def ^:private class-info-cache
  "Documented cache: Kotlin internal class name -> {:value-class? :value-class :fun-interface?}. A class that cannot
  be loaded is not cached (it may be on the class path later)."
  (atom {}))
(register-reset! ::class-info-cache #(reset! class-info-cache {}))

(defn- method-desc ^String [^Method m]
  (.toMethodDescriptorString (MethodType/methodType (.getReturnType m) (.getParameterTypes m))))

(defn- declared-method
  "The declared method of `c` with this name and (when given) descriptor, or nil. Also nil when the class cannot
  reflect (a member mentions a class that is not on the class path): see `member-flags`."
  ^Method [^Class c ^String n desc]
  (try (first (filter (fn [^Method m] (and (= n (.getName m)) (or (nil? desc) (= desc (method-desc m)))))
                      (.getDeclaredMethods c)))
       (catch LinkageError _ nil)))

;; Reflection on a class fails as a whole (NoClassDefFoundError from getDeclaredMethods) when ONE member mentions a
;; class that is not there. The access flags are then read from the class file itself.

(defn- class-file-members
  "{:methods {[name desc] flags} :fields {name flags}} read from the bytes of the class `binary`, or nil."
  [^String binary]
  (when-let [in (.getResourceAsStream (clojure.lang.RT/baseLoader) (str (str/replace binary "." "/") ".class"))]
    (with-open [in in]
      (let [d (java.io.DataInputStream. (java.io.BufferedInputStream. in))
            _ (do (.readInt d) (.readUnsignedShort d) (.readUnsignedShort d))
            n (.readUnsignedShort d)
            cp (object-array n)]
        (loop [i 1]
          (when (< i n)
            (let [tag (.readUnsignedByte d)]
              (case (int tag)
                1 (do (aset cp i (.readUTF d)) (recur (inc i)))
                (3 4) (do (.readInt d) (recur (inc i)))
                (5 6) (do (.readLong d) (recur (+ i 2)))
                (7 8 16 19 20) (do (.readUnsignedShort d) (recur (inc i)))
                (9 10 11 12 17 18) (do (.readInt d) (recur (inc i)))
                15 (do (.readUnsignedByte d) (.readUnsignedShort d) (recur (inc i)))))))
        (.readUnsignedShort d) (.readUnsignedShort d) (.readUnsignedShort d)
        (dotimes [_ (.readUnsignedShort d)] (.readUnsignedShort d))
        (let [skip-attrs (fn [] (dotimes [_ (.readUnsignedShort d)] (.readUnsignedShort d) (.skipBytes d (.readInt d))))
              members (fn [] (vec (for [_ (range (.readUnsignedShort d))]
                                    (let [flags (.readUnsignedShort d) nm (aget cp (.readUnsignedShort d)) ds (aget cp (.readUnsignedShort d))]
                                      (skip-attrs)
                                      [nm ds flags]))))
              fields (members)
              methods (members)]
          {:fields (into {} (map (fn [[n _ f]] [n f]) fields))
           :methods (into {} (map (fn [[n ds f]] [[n ds] f]) methods))
           :kotlin? (boolean (some #(= "Lkotlin/Metadata;" %) cp))})))))

(defn- member-flags
  "The access flags (an int) of the JVM method `binary`.`name``desc`, or nil if the class has none such. Reflection,
  and the class file when the class cannot reflect."
  [^String binary ^String n desc]
  (when-let [c (load-class binary)]
    (if-let [^Method m (declared-method c n desc)]
      (.getModifiers m)
      (when-not (try (.getDeclaredMethods c) true (catch LinkageError _ false))
        (let [ms (:methods (class-file-members binary))]
          (if desc
            (get ms [n desc])
            (some (fn [[[mn _] f]] (when (= mn n) f)) ms)))))))

(defn static-method?
  "Is the JVM method `owner`.`name``desc` static? nil when it cannot be found."
  [owner name desc]
  (some-> (member-flags owner name desc) Modifier/isStatic))

(defn static-field?
  "Is the JVM field `owner`.`name` static? nil when it cannot be found."
  [owner name]
  (when-let [c (load-class owner)]
    (if-let [f (first (filter (fn [^java.lang.reflect.Field f] (= name (.getName f)))
                              (try (.getDeclaredFields c) (catch LinkageError _ nil))))]
      (Modifier/isStatic (.getModifiers ^java.lang.reflect.Field f))
      (some-> (get (:fields (class-file-members owner)) name) Modifier/isStatic))))

(defn- value-class-info
  "Description of a value class (see the namespace docstring), or nil if the JVM class has no
  box-impl/unbox-impl (or cannot reflect)."
  [binary ^Class c ^KmClass k]
  (try (value-class-info* binary c k) (catch LinkageError _ nil)))

(defn- value-class-info*
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

(defn- object-method? [^Method m]
  (some (fn [^Method o] (and (= (.getName o) (.getName m)) (= (vec (.getParameterTypes o)) (vec (.getParameterTypes m)))))
        (.getMethods Object)))

(defn- java-sam?
  "Is `c` a Java interface with exactly one abstract method (not counting those of Object)? Kotlin converts a lambda to
  such an interface; so does kt (like a Kotlin `fun interface`, rule 6). Not a Kotlin built-in (`kotlin/Comparable`
  is no SAM type in Kotlin) and not a Kotlin interface (its metadata says whether it is a `fun interface`)."
  [^Class c internal]
  (boolean (and c (.isInterface c) (not (.isAnnotation c)) (not (str/starts-with? internal "kotlin/"))
                (nil? (read-meta c))
                (try (= 1 (count (remove object-method?
                                         (filter (fn [^Method m] (Modifier/isAbstract (.getModifiers m))) (.getMethods c)))))
                     (catch LinkageError _ false)))))

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
                  :fun-interface? (boolean (or (and k (Attributes/isFunInterface k)) (java-sam? c internal)))})]
      (when (load-class (internal->binary internal)) (swap! class-info-cache assoc internal info))
      info)))

(def ^:private builtin-variances
  "Declaration-site variance of the type parameters of the Kotlin built-in types, which the JVM shows as Java classes
  (`kotlin/collections/List` is `java.util.List`): there is no Kotlin metadata to read them from."
  {"kotlin/collections/Iterable" [:out] "kotlin/collections/Collection" [:out] "kotlin/collections/List" [:out]
   "kotlin/collections/Set" [:out] "kotlin/collections/Map" [:inv :out] "kotlin/collections/Map.Entry" [:out :out]
   "kotlin/collections/MutableIterable" [:inv] "kotlin/collections/MutableCollection" [:inv]
   "kotlin/collections/MutableList" [:inv] "kotlin/collections/MutableSet" [:inv]
   "kotlin/collections/MutableMap" [:inv :inv] "kotlin/collections/MutableMap.MutableEntry" [:inv :inv]
   "kotlin/collections/MutableIterator" [:inv] "kotlin/collections/Iterator" [:out]
   "kotlin/Array" [:inv] "kotlin/Pair" [:out :out] "kotlin/Triple" [:out :out :out] "kotlin/Comparable" [:in]
   "kotlin/Lazy" [:out] "kotlin/Result" [:out] "kotlin/sequences/Sequence" [:out]})

(def class-variances
  "Declaration-site variance of the type parameters of the Kotlin class with this internal name: a vector of :out, :in
  or :inv (invariant), or nil when it is not known (the class is not found). A Java class is invariant in every
  parameter. Documented cache."
  (memo
   ::class-variances
   (fn [internal]
     (or (builtin-variances internal)
         (when-let [[_ n] (re-matches #"kotlin/Function(\d+)" internal)]
           (conj (vec (repeat (parse-long n) :in)) :out))
         (when-let [c (load-class (internal->binary internal))]
           (let [m (read-meta c)]
             (if (instance? KotlinClassMetadata$Class m)
               (mapv (fn [^KmTypeParameter p]
                       (condp = (.getVariance p) KmVariance/OUT :out KmVariance/IN :in :inv))
                     (.getTypeParameters (.getKmClass ^KotlinClassMetadata$Class m)))
               (vec (repeat (count (.getTypeParameters c)) :inv)))))))))

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
          ;; a use-site projection (`MutableList<out Int>`) is kept as :variance :out or :in
          args (mapv (fn [^KmTypeProjection p]
                       (if-let [pt (.getType p)]
                         (let [v (.getVariance p)]
                           (cond-> (km-type tps pt)
                             (and v (not= KmVariance/INVARIANT v)) (assoc :variance (if (= KmVariance/OUT v) :out :in))))
                         {:star? true}))
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
  (if-let [flags (and jvm facade (member-flags class name desc))]
    (let [pub? (Modifier/isPublic (int flags))
          cls-pub? (Modifier/isPublic (.getModifiers (load-class class)))]
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
  ;; a member that cannot be linked is left out (the rest of the class stays)
  (concat
   (for [^KmFunction f (.getFunctions c)
         :when (and (visible? (Attributes/getVisibility f)) (own-member? (Attributes/getKind f)))
         :let [d (try (function-decl ctx f) (catch LinkageError _ nil))] :when d]
     d)
   (for [^KmProperty p (.getProperties c)
         :when (and (visible? (Attributes/getVisibility p)) (own-member? (Attributes/getKind p)))
         :let [d (try (property-decl ctx p) (catch LinkageError _ nil))] :when d]
     d)))

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
  "Documented cache: Kotlin internal class name -> {:binary :km :internal} or nil (a loaded class that is not a Kotlin
  class). A class that cannot be loaded is not cached."
  (atom {}))
(register-reset! ::kotlin-class-cache #(reset! kotlin-class-cache {}))

(defn- kotlin-class [internal]
  (if-let [e (find @kotlin-class-cache internal)]
    (val e)
    (let [binary (internal->binary internal)
          c (load-class binary)
          m (some-> c read-meta)
          r (when (instance? KotlinClassMetadata$Class m)
              {:binary binary :internal internal :km (.getKmClass ^KotlinClassMetadata$Class m)})]
      (when c (swap! kotlin-class-cache assoc internal r))
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
(register-reset! ::declared-members-cache #(reset! declared-members-cache {}))

(declare finish-decls)

(defn- declared-members [{:keys [binary internal km]}]
  (or (get @declared-members-cache internal)
      (let [ds (vec (finish-decls binary (container-decls (member-ctx binary internal km) km)))]
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

(defn- load-class!
  "`load-class`, but a LinkageError (the class needs a class that is not there) is thrown, not turned into nil."
  ^Class [^String n]
  (try (Class/forName n false (clojure.lang.RT/baseLoader))
       (catch ClassNotFoundException _ nil)))

(defn- missing-name
  "The class that a LinkageError says is missing (`pbopt.Missing`), else `fallback`."
  [^Throwable e fallback]
  (let [m (.getMessage e)
        n (some-> m (str/split #"[ ]") first)]
    (if (and n (re-matches #"[\w$./]+" n) (not (str/starts-with? m "Could not initialize")))
      (str/replace n "/" ".")
      fallback)))

(defn- unloadable
  "nil if the class `n` (a Class.getName style name) can be loaded, else the name of the class that is missing."
  [^String n]
  (try (Class/forName n false (clojure.lang.RT/baseLoader)) nil
       (catch ClassNotFoundException _ n)
       (catch LinkageError e (missing-name e n))))

(defn- desc-class-names
  "The classes (not primitives) that the JVM descriptor `desc` mentions."
  [desc]
  (when desc
    (let [{:keys [params return]} (if (str/starts-with? desc "(") (desc-types desc) {:params [] :return (desc-type desc)})]
      (->> (conj (vec params) return)
           (map #(str/replace % #"^\[+" ""))
           (map #(if (and (str/starts-with? % "L") (str/ends-with? % ";")) (subs % 1 (dec (count %))) %))
           (remove #{"int" "long" "short" "byte" "double" "float" "boolean" "char" "void" "I" "J" "S" "B" "D" "F" "Z" "C"})))))

(defn- finish-decls
  "The declarations `ds` of the class file `binary`, each JVM member marked: `:partial? true` when its class cannot
  reflect (one of its other members needs a class that is not there: Clojure's own compiler could not look the method
  up, so the call goes through a MethodHandle), and the declaration `:missing \"pbopt.Missing\"` when the JVM
  signature of its member mentions a class that is not there. Calling such a declaration is a kt error that names the
  class (`ckway.resolve/check-supported!`); the other declarations of the class and package stay."
  [binary ds]
  (let [refl (atom {}) miss (atom {})
        reflectable? (fn [cn] (if-let [e (find @refl cn)] (val e)
                                (let [r (if-let [c (load-class cn)]
                                          (try (.getDeclaredMethods c) (.getDeclaredFields c) (.getDeclaredConstructors c) (.getMethods c) true
                                               (catch LinkageError _ false))
                                          true)]
                                  (swap! refl assoc cn r) r)))
        missing (fn [cn] (if-let [e (find @miss cn)] (val e) (let [r (unloadable cn)] (swap! miss assoc cn r) r)))
        mark1 (fn [m] (cond-> m (and (:class m) (not (reflectable? (:class m)))) (assoc :partial? true)))
        mark (fn [m] (when m (cond-> (mark1 m) (:default m) (update :default mark1))))]
    (mapv (fn [d]
            (if (and (#{:function :property} (:kind d)) (or (:jvm d) (:getter d)))
              (let [d (cond-> d (:jvm d) (update :jvm mark) (:getter d) (update :getter mark) (:setter d) (update :setter mark))
                    ms (remove nil? [(:jvm d) (:getter d) (:setter d) (:default (:jvm d))])
                    bad (some missing (distinct (mapcat #(desc-class-names (:desc %)) ms)))]
                (cond-> d bad (assoc :missing bad)))
              d))
          ds)))

(defn- unlinkable-class-decls
  "What a class that cannot be linked (`e`, the LinkageError of loading it) leaves in the index: for a Kotlin class (the
  class file mentions kotlin.Metadata) with a plain name, a placeholder declaration of its var that cannot be called;
  its error names the missing class. Nothing for any other class."
  [binary ^LinkageError e]
  (let [[pkg simple] (let [i (.lastIndexOf ^String binary ".")] [(subs binary 0 (max 0 i)) (subs binary (inc i))])
        k (try (class-file-members binary) (catch Exception _ nil))]
    (when (and (:kotlin? k) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" simple) (not (str/ends-with? simple "Kt")))
      (let [internal (str (str/replace pkg "." "/") (when (seq pkg) "/") simple)
            miss (missing-name e binary)]
        [{:kind :class :name simple :var-name simple :owner binary :receivers [] :params []
          :return (class-type internal []) :type-params [] :flags #{:no-constructor :unlinkable}
          :missing miss :jvm nil :signature (str "class " simple)}]))))

(defn- class-file-decls* [binary]
  (when-let [c (load-class! binary)]
    (let [m (read-meta c)]
      (cond
        (instance? KotlinClassMetadata$Class m) (class-decls binary (.getKmClass ^KotlinClassMetadata$Class m))
        (instance? KotlinClassMetadata$FileFacade m) (package-decls binary (.getKmPackage ^KotlinClassMetadata$FileFacade m))
        (instance? KotlinClassMetadata$MultiFileClassPart m)
        (let [part ^KotlinClassMetadata$MultiFileClassPart m]
          (package-decls (internal->binary (.getFacadeClassName part)) (.getKmPackage part) binary))
        :else []))))

(defn- class-file-decls [binary]
  (try (finish-decls binary (vec (class-file-decls* binary)))
       (catch LinkageError e (unlinkable-class-decls binary e))))

(defn class-members
  "Declarations of the members that the JVM class `binary` declares itself (not inherited ones)."
  [binary]
  (filter #(= binary (:owner %)) (class-file-decls binary)))

(defn primary-properties
  "The properties that the primary constructor of the Kotlin class `c` declares, in constructor order
  (`kt/data`, README rule 11). => nil when `c` has no Kotlin class metadata, else
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
  "Documented cache: package name -> {:sig the class path state it was built from, :idx the index}. A package that has
  no declaration is not cached. An entry is used only while the class path (the roots that can hold the package, with
  the modification time of the jar or of the package directory in it) is the one it was built from."
  (atom {}))
(register-reset! ::package-cache #(reset! package-cache {}))

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

(defn- classpath-signature
  "What of the class path decides the index of `pkg`: its roots, with the modification time of the jar or of the
  package directory in it."
  [pkg]
  (let [dir (str/replace pkg "." "/")]
    (vec (for [^File f (package-roots pkg)]
           [(.getPath f) (if (.isDirectory f) (.lastModified (File. f dir)) (.lastModified f))]))))

(defn package-index
  "Index of the Kotlin package `pkg` (see the namespace docstring). Cached per package as long as the class path
  is the same one; an empty index is never cached."
  [pkg]
  (let [sig (classpath-signature pkg)
        e (get @package-cache pkg)]
    (if (and e (= sig (:sig e)))
      (:idx e)
      (let [idx (build-index pkg)]
        (if (seq idx)
          (swap! package-cache assoc pkg {:sig sig :idx idx})
          (swap! package-cache dissoc pkg))
        idx))))

(defn- class-packages
  "Names of the packages that hold class files on the class path (for the hint of a package that is not there)."
  []
  (let [dirs (fn walk [^File root ^File d depth]
               (when (< depth 8)
                 (let [kids (or (.listFiles d) [])
                       here (when (some #(str/ends-with? (.getName ^File %) ".class") kids)
                              [(str/replace (str (.relativize (.toPath root) (.toPath d))) File/separator ".")])]
                   (concat here (mapcat #(when (.isDirectory ^File %) (walk root % (inc depth))) kids)))))]
    (->> (classpath-files)
         (mapcat (fn [^File f]
                   (if (.isDirectory f)
                     (dirs f f 0)
                     (try (map #(str/replace % "/" ".") (remove str/blank? (keys (jar-packages f)))) (catch Exception _ [])))))
         (remove #(str/starts-with? % "META-INF"))
         distinct)))

(defn- edit-distance [^String a ^String b]
  (let [n (count b)]
    (peek (reduce (fn [prev ^Character ca]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row)) (inc (nth prev (inc j)))
                                             (+ (nth prev j) (if (= ca (.charAt b j)) 0 1)))))
                            [(inc (first prev))] (range n)))
                  (vec (range (inc n))) a))))

(defn similar-packages
  "Up to five packages on the class path whose names are close to `pkg` (a typo) or that lie below it. For the error of
  a `kt/require` that finds no class."
  [pkg]
  (let [all (class-packages)
        below (filter #(str/starts-with? % (str pkg ".")) all)
        near (->> all (remove (set below))
                  (map (fn [n] [(edit-distance pkg n) n]))
                  (filter (fn [[d _]] (<= d (max 2 (quot (count pkg) 4)))))
                  (sort-by identity) (map second))]
    (vec (take 5 (concat (sort below) near)))))

(defn clear-cache!
  "Forget everything the library has cached. Same as `clear-caches!` (kept for the old name)."
  []
  (clear-caches!))
