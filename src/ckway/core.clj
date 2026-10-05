(ns ckway.core
  "Call Kotlin code from Clojure. Public API, exactly: `require`, `set!`, `ref`, `data`, `reify`.

    (kt/require '[pkg :as alias])      README rule 1
    (kt/set! (alias/p x) value)        rule 8, see `ckway.set`
    (kt/ref X y) (kt/ref y)            rule 9, see `ckway.ref`
    (kt/data x)                        rule 11, see `ckway.data`
    (kt/reify jobs/Job (.run [this] ...))   rule 10, see `ckway.reify`

  Generated namespaces are named `ckway.pkg.<kotlin package>` (the root package is
  `ckway.pkg.<root>`). The prefix cannot clash with a Clojure namespace of the same name as
  the package. The user only sees the alias given to `require`."
  (:refer-clojure :exclude [require set! ref reify])
  (:require [clojure.string :as str]
            [ckway.data :as data*]
            [ckway.meta :as meta]
            [ckway.ref :as ref*]
            [ckway.reify :as reify*]
            [ckway.resolve :as resolve]
            [ckway.rt :as rt]
            [ckway.set :as set*]))

(set! *warn-on-reflection* true)

(defn- ns-sym [pkg] (symbol (str "ckway.pkg." (if (str/blank? pkg) "<root>" pkg))))

(defn- kind-of [decls]
  (cond (some #(#{:object :enum-entry} (:kind %)) decls) :value
        :else :fn))

(defn- recv-sym [r]
  (symbol (case (:role r) :dispatch "this" :extension "receiver" (or (:name r) "context"))))

(defn- arglist [decl]
  (let [ps (:params decl)]
    (vec (concat (map recv-sym (:receivers decl))
                 (mapcat (fn [p] (if (:vararg? p) ['& (symbol (:name p))] [(symbol (:name p))])) ps)))))

(defn- doc-text [pkg decls]
  (str "Kotlin" (when (seq pkg) (str " package " pkg)) ":\n"
       (str/join "\n" (map #(str "  " (:signature %)) decls))))

(defn- static-field-value [cname fname]
  (.get (.getField ^Class (resolve/jvm-class cname) ^String fname) nil))

(defn- companion-invokes
  "The `operator fun invoke` declarations that Kotlin calls for `Name(...)` when the class `Name` has no constructor that
  fits: the members of its companion object, and the extensions on its Companion that the package declares. The
  declarations of the same package only. Only for a class var."
  [all-invokes decls]
  (when-let [owner (:owner (first (filter #(and (= :class (:kind %)) (not (:alias (:flags %)))) decls)))]
    (vec (for [d all-invokes
               :let [rs (:receivers d)]
               :when (and (= :function (:kind d)) (contains? (:flags d) :operator) (= 1 (count rs))
                          (= owner (:companion-of (first rs))))]
           d))))

(defn- var-meta
  "The metadata of a var. A type alias (`:kind :alias` among the declarations) is a name that the package declares:
  the var means the target. Its calls are the constructors of the target class (the declarations that the index
  adds after the alias), `:kt/alias` ({:params :type}) is what a type form expands it to (`ckway.types`)."
  [pkg nsym var-name all-decls all-invokes]
  (let [alias (first (filter #(= :alias (:kind %)) all-decls))
        decls (vec (remove #(= :alias (:kind %)) all-decls))
        cls (first (filter #(and (= :class (:kind %)) (not (:alias (:flags %)))) decls))
        obj (first (filter #(= :object (:kind %)) decls))
        invokes (companion-invokes all-invokes decls)]
    (cond-> {:doc (doc-text pkg all-decls)
             :arglists (list* (distinct (map arglist decls)))
             :kt/decls decls :kt/package pkg :kt/cache (rt/new-cache)}
      alias (assoc :kt/alias (:alias alias))
      cls (assoc :kt/class (:owner cls))
      obj (assoc :kt/object (:owner obj))
      (seq invokes) (assoc :kt/invokes invokes)
      (= :fn (kind-of decls))
      (assoc :inline (fn [& args] `(ckway.resolve/kt-call (var ~(symbol (str nsym) var-name)) ~@args)))
      ;; an object or an enum entry is a value, not a function: calling it is a compile error that says so
      ;; an object that has an `operator fun invoke` (its own, inherited, or an extension of the package) is called as in
      ;; Kotlin: `Foo(1)` is `Foo.invoke(1)`
      (and obj (resolve/object-invoke? obj all-invokes))
      (assoc :inline (fn [& args]
                       `(ckway.resolve/kt-call (var ~(symbol (str nsym) ".invoke")) ~(resolve/var-form (ns-resolve nsym (symbol var-name))) ~@args)))
      (and (not (and obj (resolve/object-invoke? obj all-invokes))) (= :value (kind-of decls)))
      (assoc :inline (fn [& args]
                       (resolve/fail (str "kt: `" var-name "` is "
                                          (if (= :object (:kind (first decls))) "an object" "an enum entry")
                                          ", not a function: it cannot be called. The var is the value itself"
                                          (when (= :object (:kind (first decls)))
                                            "; call a member with the object as its first argument, e.g. (alias/.member alias/Name ...)")
                                          ".")))))))

(defn- var-root [^clojure.lang.Var v decls]
  (let [d (first decls)]
    (case (kind-of decls)
      ;; initialises the class of an object (the var holds the instance): a failing initialiser is no failure of
      ;; the require, the var holds the error (ckway.rt/FailedObject) and a call that uses it throws
      :value (try (static-field-value (:class (:jvm d)) (or (:instance-field (:jvm d)) (:field (:jvm d))))
                  (catch LinkageError e
                    (rt/failed-object (if (= :object (:kind d)) "object" "enum entry") (:class (:jvm d)) e)))
      :fn (let [f (fn [& args] (rt/call-var v args))]
            (if-let [c (:kt/class (meta v))] (with-meta f {:kt/class c}) f)))))

(defn- with-factories
  "The :no-constructor declaration of a class var gets :factories: the names of the companion members of the same
  package that return the class (`Duration.seconds`...), so that the error for a call can say what Kotlin offers."
  [idx decls]
  (mapv (fn [d]
          (if (and (:no-constructor (:flags d)) (not (:alias (:flags d))) (= :class (:kind d)))
            (let [names (->> (vals idx) (apply concat)
                             (filter #(and (= :dispatch (:role (first (:receivers %))))
                                           (= (:owner d) (:companion-of (first (:receivers %))))
                                           (= (:class (:return d)) (:class (:return %)))))
                             ;; the ones that take an argument first (`seconds`, `parse`), then constants (`ZERO`)
                             (sort-by (fn [x] [(if (or (seq (:params x)) (some #(= :extension (:role %)) (:receivers x))) 0 1)
                                               (:var-name x)]))
                             (map #(str/replace (:var-name %) #"^\." "")) distinct vec)]
              (cond-> d (seq names) (assoc :factories names)))
            d))
        decls))

(defn- no-package!
  "The error of a package with no declaration: a typo, a jar that is not on the class path (yet), or Java classes only."
  [pkg]
  (let [near (meta/similar-packages pkg)]
    (throw (ex-info (str "kt/require: no Kotlin class found for package `" (if (str/blank? pkg) "<root>" pkg) "` on the class path. "
                         "Is the name right, and is the jar or directory with its classes on the class path "
                         "(a REPL can add one with add-lib)? A package with Java classes only has no vars."
                         (when (seq near) (str "\n  Packages with a close name: " (str/join ", " near))))
                    {:kt/error true :kt/package pkg :kt/similar near}))))

(defn- intern-package!
  "Create or refresh the namespace for `pkg`, with one var per name."
  [pkg]
  (let [idx (meta/package-index pkg)
        _ (when (empty? idx) (no-package! pkg))
        nsym (ns-sym pkg)
        the-ns (create-ns nsym)]
    (doseq [[s _] (ns-interns the-ns) :when (not (contains? idx (name s)))] (ns-unmap the-ns s))
    (doseq [[var-name all-decls] idx]
      ;; a name that the namespace already refers to as a class (java.lang.Exception, for the alias
      ;; `kotlin.Exception`) is replaced by the var, without Clojure's warning
      (when (class? (get (ns-map the-ns) (symbol var-name))) (ns-unmap the-ns (symbol var-name)))
      (let [^clojure.lang.Var v (intern the-ns (with-meta (symbol var-name) (var-meta pkg nsym var-name (with-factories idx all-decls) (get idx ".invoke"))))]
        (.bindRoot v (var-root v (:kt/decls (meta v))))
        (when (instance? ckway.rt.FailedObject (.getRawRoot v)) (alter-meta! v assoc :kt/failed true))))
    nsym))

(defn- parse-spec [spec]
  (let [[pkg & opts] spec
        {:keys [as] :as m} (when (vector? spec) (apply hash-map opts))]
    (when-not (and (vector? spec) (symbol? pkg) (symbol? as) (= #{:as} (set (keys m))))
      (throw (ex-info (str "kt/require expects [package :as alias], got " (pr-str spec)) {:spec spec})))
    [(str pkg) as]))

(defn require
  "Make a namespace per Kotlin package and alias it in the current namespace.
  (kt/require '[shop :as s] '[shop.pricing :as pr]). Use the symbol <root> for the root package."
  [& specs]
  (doseq [spec specs]
    (let [[pkg as] (parse-spec spec)
          pkg (if (= "<root>" pkg) "" pkg)]
      (alias as (intern-package! pkg)))))

(defmacro set!
  "Write a `var` property: the Kotlin `a.p = v` is (kt/set! (alias/p a) v). The first argument is exactly the
  form that reads the property. The value follows the rules of a call argument (number width, function,
  value class). Returns the value, like `set!`. See `ckway.set`."
  [read-form value]
  (set*/expand &env &form read-form value))

(defmacro ref
  "A Kotlin callable reference: (kt/ref X y) is `X::y`, (kt/ref y) is `::y`. `y` is `class`, a property
  name, or a function name with its `.` prefix. See `ckway.ref`."
  [& args]
  (ref*/expand &env args))

(defn data
  "A read-only map of the primary-constructor properties of `x`, with keyword keys, in constructor order.
  nil gives nil. See `ckway.data`."
  [x]
  (data*/data x))

(defmacro reify
  "Implement Kotlin (or Java) interfaces from Clojure; the shape of `reify`: interfaces, each followed by its
  members. `this` is the first parameter of every member. A Kotlin function is written `(.run [this] ...)`,
  a Kotlin property `(allowParallelRun [this] true)` (a `var` setter: `(level [this v] ...)`); a member of a
  Java interface has its Java name. Only interfaces. See `ckway.reify`."
  [& specs]
  (reify*/expand specs))
