(ns ckway.types
  "Type forms (DESIGN-2 rule 5): the literal that follows `:<>`, and Kotlin types as source text.

  A type form is
    a class symbol         json/JsonBody  Int  java.util.UUID
    the same with `?`      String?  users/User?  Any?
    a list                 (List (Map String Any?))   `*` is the star projection
  or, as the value of `:<>`, a vector of type forms. A type form is never evaluated.

  A class symbol is resolved in this order (like Kotlin: explicit before default imports):
    1. a kt class (or object) var of a required Kotlin namespace (`json/JsonBody`);
    2. a Kotlin default-import type name (`Int`, `List`, `Pair`, `Sequence`, `Array`, `IntArray`...). The
       names are read from the stdlib that is on the class path: the built-ins (`*.kotlin_builtins`
       of kotlin, kotlin.collections, kotlin.ranges, kotlin.annotation, read with the kotlin-metadata
       library) and the public top-level classes of kotlin, kotlin.collections, kotlin.sequences,
       kotlin.ranges, kotlin.text. Only the JVM class of a mapped built-in (`List` is
       java.util.List) is a table (`mapped-jvm`: it is the language's own mapping);
    3. a class that Clojure resolves in the namespace (imports, fully qualified names).

  A resolved type is a ckway.meta <type> (:class internal name, :nullable?, :args, :value-class...), so
  it can replace a type parameter in a declaration (`subst`). Functions:
    (resolve-class ns sym)    => <type>               a class symbol without type arguments (`List::class`)
    (resolve-forms ns forms)  => [<type> ...]       compile errors for unknown names, wrong number of arguments
    (source type)             => \"kotlin.collections.List<kotlin.String?>\"   Kotlin source text, fully qualified
    (jvm-class type)          => Class or nil       for static typing (primitives boxed; Array<T> is T[])
    (subst tmap type)         => type               tmap {\"T\" <type>}"
  (:require [clojure.string :as str]
            [ckway.meta :as meta])
  (:import [kotlin.metadata Attributes Visibility KmClass]
           [kotlin.metadata.internal.common KotlinCommonMetadata]))

(set! *warn-on-reflection* true)

(defn- fail [msg] (throw (ex-info msg {:kt/error true})))

;; ---------------------------------------------------------------- Kotlin default-import names

(def ^:private mapped-jvm
  "The JVM class of each Kotlin built-in that is not a class of its own (the Kotlin language's
  mapping). A built-in that is not here must be a class of that name (kotlin.Unit, kotlin.Function,
  kotlin.ranges.IntRange): the test `ckway.types-test/every-default-name-has-a-jvm-class` checks it."
  (merge
   (into {} (for [n ["Any" "String" "CharSequence" "Throwable" "Cloneable" "Comparable" "Enum" "Number"]]
              [(str "kotlin/" n) (str "java.lang." (if (= n "Any") "Object" n))]))
   {"kotlin/Annotation" "java.lang.annotation.Annotation" "kotlin/Nothing" "java.lang.Void"
    "kotlin/Int" "java.lang.Integer" "kotlin/Long" "java.lang.Long" "kotlin/Short" "java.lang.Short"
    "kotlin/Byte" "java.lang.Byte" "kotlin/Double" "java.lang.Double" "kotlin/Float" "java.lang.Float"
    "kotlin/Char" "java.lang.Character" "kotlin/Boolean" "java.lang.Boolean"
    "kotlin/Array" "[Ljava.lang.Object;"
    "kotlin/IntArray" "[I" "kotlin/LongArray" "[J" "kotlin/ShortArray" "[S" "kotlin/ByteArray" "[B"
    "kotlin/DoubleArray" "[D" "kotlin/FloatArray" "[F" "kotlin/CharArray" "[C" "kotlin/BooleanArray" "[Z"
    "kotlin/collections/Iterator" "java.util.Iterator" "kotlin/collections/MutableIterator" "java.util.Iterator"
    "kotlin/collections/Iterable" "java.lang.Iterable" "kotlin/collections/MutableIterable" "java.lang.Iterable"
    "kotlin/collections/Collection" "java.util.Collection" "kotlin/collections/MutableCollection" "java.util.Collection"
    "kotlin/collections/List" "java.util.List" "kotlin/collections/MutableList" "java.util.List"
    "kotlin/collections/Set" "java.util.Set" "kotlin/collections/MutableSet" "java.util.Set"
    "kotlin/collections/Map" "java.util.Map" "kotlin/collections/MutableMap" "java.util.Map"
    "kotlin/collections/ListIterator" "java.util.ListIterator" "kotlin/collections/MutableListIterator" "java.util.ListIterator"
    "kotlin/collections/Map.Entry" "java.util.Map$Entry" "kotlin/collections/MutableMap.MutableEntry" "java.util.Map$Entry"}))

(defn- load-class ^Class [^String n]
  (try (Class/forName n false (clojure.lang.RT/baseLoader))
       (catch ClassNotFoundException _ nil)
       (catch LinkageError _ nil)))

(defn- builtin-classes
  "The public classes that the `.kotlin_builtins` resource of the package `pkg-path` (\"kotlin/collections\")
  declares: [{:internal :name :tparams}]"
  [^String pkg-path]
  (let [res (str pkg-path "/" (last (str/split pkg-path #"/")) ".kotlin_builtins")]
    (if-let [in (.getResourceAsStream (clojure.lang.RT/baseLoader) res)]
      (with-open [in in]
        (let [m (try (KotlinCommonMetadata/read ^java.io.InputStream in)
                     (catch Throwable e
                       (throw (ex-info (str "kt: cannot read " res " (the Kotlin built-ins of the stdlib on the class path)")
                                       {:kt/error true} e))))]
          (vec (for [^KmClass k (.getClasses (.getKmModuleFragment ^KotlinCommonMetadata m))
                     :let [n (.getName k)]
                     :when (and (= Visibility/PUBLIC (Attributes/getVisibility k)) (not (str/ends-with? n ".Companion")))]
                 {:internal n :name (subs n (inc (.lastIndexOf n "/"))) :tparams (count (.getTypeParameters k))}))))
      [])))

(defn- stdlib-entry [internal tparams]
  {:internal internal :tparams tparams :jvm (or (mapped-jvm internal) (meta/internal->binary internal))})

(declare build-default-names)

(def default-names
  "Kotlin default-import type names: {\"List\" {:internal \"kotlin/collections/List\" :tparams 1 :jvm \"java.util.List\"}}.
  Read from the stdlib on the class path when first needed (see the namespace docstring). The type aliases of the
  default-import packages that have no class file (`ArrayList`, `HashMap`, `Exception`...) are entries
  {:alias {:params [...] :type <type>}}, read from the Kotlin metadata of the package (`meta/public-aliases`).
  A class of the same name wins over an alias. Documented cache (delay); `ckway.meta/clear-caches!` replaces it."
  (delay (build-default-names)))

(defn- build-default-names []
    (merge
     (into {} (for [pkg ["kotlin.text" "kotlin.sequences" "kotlin.ranges" "kotlin.collections" "kotlin"]
                    [simple a] (meta/public-aliases pkg)]
                [simple {:alias a}]))
     (into {} (for [pkg ["kotlin.text" "kotlin.sequences" "kotlin.ranges" "kotlin.collections" "kotlin"]
                    [simple {:keys [internal binary tparams]}] (meta/public-classes pkg)]
                [simple {:internal internal :tparams tparams :jvm binary}]))
     (into {} (for [pkg ["kotlin/annotation" "kotlin/ranges" "kotlin/collections" "kotlin"]
                    {:keys [internal name tparams]} (builtin-classes pkg)]
                [name (stdlib-entry internal tparams)]))))

(meta/register-reset! ::default-names #(alter-var-root #'default-names (fn [_] (delay (build-default-names)))))

;; ---------------------------------------------------------------- resolving

(defn- class-entry
  "Entry of a JVM class that Clojure resolved (not a Kotlin default name, not a kt var)."
  [^Class c]
  (let [n (.getName c)
        i (inc (.lastIndexOf n "."))
        internal (str (str/replace (subs n 0 i) "." "/") (str/replace (subs n i) "$" "."))]
    {:internal internal :tparams (count (.getTypeParameters c)) :jvm n}))

(defn- kt-var-entry [v]
  (let [m (meta v)
        d (first (filter #(#{:class :object} (:kind %)) (:kt/decls m)))]
    (cond
      (:kt/alias m) {:alias (:kt/alias m)}
      (and d (or (:kt/class m) (:kt/object m)))
      {:internal (:class (:return d)) :tparams (count (:type-params d)) :jvm (:owner d)})))

(defn- lookup-var [ns sym]
  (let [r (try (ns-resolve ns sym) (catch Exception _ nil))]
    (when (var? r) r)))

(defn- lookup-class [ns sym]
  (let [r (try (ns-resolve ns sym) (catch Exception _ nil))]
    (when (class? r) r)))

(defn- unknown-name [shown]
  (fail (str "kt: unknown type name `" shown "` in a type form. A type form is a class symbol: a kt class var "
             "(`users/User`), a Kotlin default name (`Int`, `List`, `Any`), or a class that Clojure resolves "
             "(`java.util.UUID`); `?` after it makes it nullable. A type form is a literal: it is never evaluated.")))

(defn- entry-of [ns sym]
  (let [shown (str sym)]
    (if (namespace sym)
      (if-let [v (lookup-var ns sym)]
        (or (kt-var-entry v)
            (fail (str "kt: `" shown "` in a type form is not a Kotlin class: it is a var that kt did not make from a class or object")))
        (or (some-> (lookup-class ns sym) class-entry) (unknown-name shown)))
      (or (some-> (lookup-var ns sym) kt-var-entry)
          (get @default-names shown)
          (some-> (lookup-class ns sym) class-entry)
          (unknown-name shown)))))

(declare subst)

(defn- tparam-count [{:keys [alias tparams]}]
  (if alias (count (:params alias)) tparams))

(defn- name-of [{:keys [alias internal]}]
  (if alias (str "type alias with the type " (meta/type-text (:type alias))) (str "`" (str/replace internal "/" ".") "`")))

(defn- ->type
  "The <type> of a resolved entry. A type alias is its expanded type, its own type parameters replaced by `args`."
  [{:keys [internal alias]} nullable? args]
  (if alias
    (cond-> (subst (zipmap (:params alias) args) (:type alias))
      nullable? (assoc :nullable? true))
    (let [info (meta/class-info internal)]
      {:class internal :nullable? (boolean nullable?) :args (vec args)
       :value-class? (boolean (:value-class? info)) :value-class (:value-class info)
       :fun-interface? false :fn-type nil :alias nil :type-param nil})))

(defn- form-text [form]
  (let [s (pr-str form)] (if (> (count s) 60) (str (subs s 0 57) "...") s)))

(declare resolve-form)

(defn- resolve-class-form [ns head args shown]
  (when-not (symbol? head)
    (fail (str "kt: " shown " is not a type form: a type form is a class symbol, or a list whose first element is a class symbol. "
               "A type form is a literal: it is never evaluated.")))
  (let [s (str head)
        nullable? (and (> (count s) 1) (str/ends-with? s "?"))
        sym (if nullable? (symbol (namespace head) (subs (name head) 0 (dec (count (name head))))) head)
        entry (entry-of ns sym)
        n (tparam-count entry)]
    (when (not= n (count args))
      (fail (str "kt: type form " shown ": " (name-of entry) " has "
                 (if (zero? n) "no type parameters" (str n " type parameter" (when (not= 1 n) "s")))
                 ", but the form gives " (count args) " type argument" (when (not= 1 (count args)) "s")
                 (when (and (pos? n) (zero? (count args))) " (Kotlin has no raw types: write e.g. `(List String)`)"))))
    (->type entry nullable? (map #(resolve-form ns %) args))))

(defn resolve-form
  "<type> for the type form `form`, read in namespace `ns`."
  [ns form]
  (cond
    (= '* form) {:star? true}
    (symbol? form) (resolve-class-form ns form [] (form-text form))
    (and (seq? form) (seq form)) (resolve-class-form ns (first form) (rest form) (form-text form))
    :else (fail (str "kt: " (form-text form) " is not a type form: a type form is a class symbol, a class symbol with `?`, "
                     "or a list `(Class type-form ...)`. A type form is a literal: it is never evaluated."))))

(defn resolve-class
  "<type> for the class symbol `sym` read in `ns`, with no type arguments (a generic class gets star
  projections): the type of `List::class` and `Box::value`, where Kotlin writes no type arguments. A `?` is not
  allowed here (`resolve-form` makes nullable types)."
  [ns sym]
  (let [entry (entry-of ns sym)]
    (->type entry false (repeat (tparam-count entry) {:star? true}))))

(defn arg-forms
  "The type forms that the value of `:<>` gives: a vector is a list of type forms, anything else is one."
  [v]
  (if (vector? v) v [v]))

(defn resolve-forms
  "[<type> ...] for the value `v` of `:<>`."
  [ns v]
  (when (and (vector? v) (some vector? v))
    (fail (str "kt: " (form-text v) " is not a list of type forms: a vector inside a vector is not a type form. "
               "Write a generic type as a list: `(List String)`.")))
  (mapv #(resolve-form ns %) (arg-forms v)))

;; ---------------------------------------------------------------- source text and JVM class

(declare source)

(def ^:dynamic *type-param-names*
  "True: `source` writes a type parameter that was not substituted by its name (`T`), for Kotlin source in which the
  type parameter is declared (a generated generic function). Default: its erasure, `kotlin.Any?`."
  false)

(defn- fn-type-source [{:keys [arity args return suspend? receiver?]}]
  (let [[recv ps] (if receiver? [(first args) (rest args)] [nil args])]
    (str (when suspend? "suspend ") (when recv (str (source recv) ".")) "(" (str/join ", " (map source ps)) ") -> " (source return))))

(defn source
  "Kotlin source text of a <type>, with fully qualified class names. A type parameter that is not
  substituted is `Any?` (the erasure)."
  [{:keys [class type-param nullable? args fn-type star?] :as t}]
  (cond
    star? "*"
    fn-type (if nullable? (str "(" (fn-type-source fn-type) ")?") (fn-type-source fn-type))
    type-param (if *type-param-names* (str type-param (when nullable? "?")) "kotlin.Any?")
    :else (str (str/replace class "/" ".")
               (when (seq args) (str "<" (str/join ", " (map source args)) ">"))
               (when nullable? "?"))))

(defn binary-name
  "JVM binary name of the Kotlin internal class name: \"fx/Outer.Inner\" -> \"fx.Outer$Inner\"."
  [internal]
  (meta/internal->binary internal))

(defn jvm-class
  "The JVM class that a value of <type> `t` has, when it is known and not Object (a primitive is
  boxed; `Array<T>` is `T[]`), else nil."
  ^Class [{:keys [class type-param args star? fn-type] :as t}]
  (when-not (or star? type-param fn-type (nil? class))
    (let [mapped (mapped-jvm class)
          c (cond
              (= "kotlin/Array" class)
              (let [e (or (some-> (first args) jvm-class) Object)] (.getClass (java.lang.reflect.Array/newInstance ^Class e 0)))
              mapped (load-class mapped)
              :else (load-class (meta/internal->binary class)))]
      (when (and c (not= Object c)) c))))

(defn subst
  "`t` with the type parameters in `tmap` ({\"T\" <type>}) replaced. `T?` over a nullable argument stays one `?`."
  [tmap {:keys [type-param args fn-type nullable?] :as t}]
  (cond
    (nil? t) nil
    (:star? t) t
    type-param (if-let [r (get tmap type-param)]
                 (cond-> r nullable? (assoc :nullable? true))
                 t)
    :else (cond-> t
            (seq args) (update :args (partial mapv #(subst tmap %)))
            fn-type (update :fn-type #(-> %
                                          (update :args (partial mapv (fn [a] (subst tmap a))))
                                          (update :return (fn [r] (subst tmap r))))))))
