(ns ckway.set
  "`(kt/set! read-form value)`: README rule 8, the Kotlin `a.p = v`.

  `read-form` is exactly the form that reads the property, `(f/level g)`, `(db/applicationName conn)`,
  `(f/topVar)`. Its variable is a kt var; of its declarations only the properties count (a function of
  the same name is no property). The property is selected as a read selects it (`ckway.resolve/choose`:
  by the receivers), so the same receiver gives the same declaration. Then the assignment is a call of
  a pseudo function `setter-decl` - the receivers of the property, one parameter `value` of the type of
  the property, JVM member = the setter (or the field of a `@JvmField`/`lateinit` property that has no setter)
  - that goes through `bind`, the applicability check, `plan` and `emit` like any call. So the value
  follows the typed-position rules of an argument: number width, function adapter, value class (a
  mangled setter goes through a bridge), `nil` only for a nullable type.

  Static path: when the declaration is known at compile time the expansion is the direct JVM call (no
  reflection, no `ckway.rt/call-dyn`), in a `let` that evaluates the receivers and the value once, in the
  written order, and returns the value (like `set!`).

  Unknown receiver type with several property declarations of that name: the same as a call
  (`ckway.resolve/expand`): the dynamic path `set-dyn`, with a reflection warning. It selects at run
  time with the classes of the values and keeps the prepared assignment in the call cache of the var
  (`ckway.rt/call-dyn`).

  Compile errors (with the Kotlin declaration in the message): the first argument is not a kt property
  read; the property is a `val`; its setter is not public (`private set`, `internal set`)."
  (:require [clojure.string :as str]
            [ckway.meta :as meta]
            [ckway.resolve :as r]
            [ckway.rt :as rt]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- the pseudo function

(def ^:private unit-type
  {:class "kotlin/Unit" :nullable? false :args [] :value-class? false :fun-interface? false
   :alias nil :type-param nil :fn-type nil})

(defn assignable
  "How the property `decl` is assigned: {:how :setter|:field :jvm <member>} or {:error text}.
  A `val` has no setter; a `var` whose setter is `private`/`internal` (`private set`) cannot be called
  from outside; a `@JvmField var` is a public field."
  [decl]
  (let [vis (:setter-visibility decl)
        jvm (:jvm decl)
        what (:signature decl)]
    (cond
      (not (contains? (:flags decl) :mutable))
      {:error (str "`" what "` is read-only: a `val` has no setter")}

      (and vis (not= :public vis))
      {:error (str "the setter of `" what "` is " (name vis) " (`" (name vis) " set`), so kt cannot call it")}

      (:setter decl) {:how :setter :jvm (:setter decl)}

      (and (:field jvm) (not (contains? (:flags decl) :const))) {:how :field :jvm jvm}

      :else {:error (str "`" what "` has no public setter and no public field")})))

(defn setter-decl
  "The declaration of a pseudo function that assigns the property `decl`, assigned as `how` (see
  `assignable`): same receivers, one parameter `value` of the property type, JVM member = the setter
  or the field."
  [decl {:keys [how jvm]}]
  (let [value-jt (case how
                   :setter (peek (:params (meta/desc-types (:desc jvm))))
                   :field (first (:params (meta/desc-types (str "(" (:desc jvm) ")V")))))]
    (assoc decl
           :kind :function :getter nil :setter nil
           :params [{:name "value" :type (:return decl) :default? false :vararg? false :jvm-type value-jt}]
           :return unit-type
           :jvm jvm
           :flags (disj (:flags decl) :mutable :const))))

;; ---------------------------------------------------------------- selection

(defn- uform [target value]
  (str "(kt/set! " (r/short-pr target) " " (r/short-pr value) ")"))

(defn- not-a-read [target value why]
  (r/fail (str "kt: " (uform target value) ": the first argument must be a kt property read like "
               "`(alias/name receiver)` or `(alias/topLevelVar)`, the form that reads the property; " why)))

(defn- target-var [env target value]
  (let [head (when (seq? target) (first target))
        v (when (and (symbol? head) (not (and (nil? (namespace head)) (contains? env head))))
            (try (ns-resolve *ns* head) (catch Exception _ nil)))]
    (when-not (and (var? v) (:kt/decls (meta v)))
      (not-a-read target value (str (if (seq? target) (str "`" (r/short-pr head) "`") (str "`" (r/short-pr target) "`"))
                                    " is not a var that kt made from a Kotlin package")))
    v))

(defn- property-decls [target value decls]
  (let [ps (filter #(= :property (:kind %)) decls)]
    (when (empty? ps)
      (not-a-read target value (str "`" (r/short-pr (first target)) "` has no Kotlin property. Kotlin declares:\n"
                                    (str/join "\n" (map #(str "    " (:signature %)) decls)))))
    ps))

(defn- assignment!
  "The `assignable` result of `decl`, or the compile error for `target`/`value`."
  [target value decl]
  (let [a (assignable decl)]
    (when (:error a)
      (r/fail (str "kt: " (uform target value) ": " (:error a) "\n  Kotlin declares: " (:signature decl))
              {:kt/candidates [(:signature decl)]}))
    a))

(defn- value-checks!
  "Bind the value as the parameter of `sdecl` and check it like an argument. => items."
  [target value sdecl parsed]
  (let [{:keys [items error]} (r/bind sdecl parsed)
        _ (when error (r/fail (str "kt: " (uform target value) ": " error)))
        checks (r/check-items (r/slots sdecl) items)]
    (when-let [bad (some #(when (= :no (first %)) (second %)) checks)]
      (r/fail (str "kt: " (uform target value) ": " bad "\n  Kotlin declares: " (:signature sdecl))))
    (r/check-supported! sdecl checks)
    items))

;; ---------------------------------------------------------------- run time

(defn- prepare-set
  [^clojure.lang.Var v target-form pos value lit]
  (let [var-name (str (.sym v))
        pdecls (property-decls target-form value (:kt/decls (meta v)))
        n (count pos)
        parsed {:positional (vec (map-indexed (fn [i x] {:arg x :info (r/value-info x) :idx i}) pos)) :named []}
        {:keys [decl]} (r/choose var-name pdecls parsed false)
        sdecl (setter-decl decl (assignment! target-form value decl))
        vinfo (cond-> (r/value-info value) (and lit (some? value)) (assoc :lit lit))
        items (value-checks! target-form value sdecl (update parsed :positional conj {:arg value :info vinfo :idx n}))]
    (rt/prepare (r/plan sdecl items))))

(defn set-dyn
  "The dynamic path of `kt/set!`: assign `value` to the property that the kt var `v` reads from the
  receivers `pos`. `lit` (:int|:long) says that `value` was an integer literal. Returns `value`."
  ([v pos value] (set-dyn v pos value nil))
  ([^clojure.lang.Var v pos value lit]
   (let [m (meta v)
         pos (vec pos)
         all (conj pos value)
         call (rt/cached (:kt/cache m) (rt/call-key ::set all {} (when lit {(count pos) lit}))
                         #(prepare-set v (apply list (.sym v) (repeat (count pos) '_)) pos value lit))]
     (call (rt/argv all {}))
     value)))

;; ---------------------------------------------------------------- compile time

(defn- simple-form? [f] (or (symbol? f) (not (coll? f))))

(defn- var-symbol [^clojure.lang.Var v]
  (symbol (str (ns-name (.ns v))) (str (.sym v))))

(defn expand
  "Expansion of `(kt/set! target value)`. `form` is the whole macro call."
  [env form target value]
  (let [v (target-var env target value)
        var-name (str (.sym ^clojure.lang.Var v))
        pdecls (property-decls target value (:kt/decls (meta v)))
        forms (rest target)
        _ (when (some keyword? forms)
            (r/fail (str "kt: " (uform target value) ": a property read takes only its receivers, no named arguments")))
        parsed (r/parse-args var-name pdecls forms (fn [f] {:arg f :info (r/form-info env f)}))
        sel (r/choose var-name pdecls parsed true)
        vinfo (r/form-info env value)]
    (if (:dynamic? sel)
      (do (r/warn-dynamic form var-name)
          `(ckway.set/set-dyn (var ~(var-symbol v)) [~@forms] ~value ~@(when-let [l (:lit vinfo)] [l])))
      (let [decl (:decl sel)
            sdecl (setter-decl decl (assignment! target value decl))
            n (count forms)
            items (value-checks! target value sdecl
                                 (update parsed :positional conj {:arg value :info vinfo :idx n}))
            items (r/type-literals sdecl items)
            ;; the receivers and the value are evaluated once, in the written order
            binds (map (fn [it] (if (simple-form? (:arg it)) [nil it] (let [g (gensym "r")] [[g (:arg it)] (assoc it :arg g)])))
                       (butlast items))
            vsym (gensym "v")
            vitem (assoc (last items) :arg vsym)
            call (r/emit (r/plan sdecl (vec (concat (map second binds) [vitem]))))]
        `(let [~@(mapcat identity (keep first binds)) ~vsym ~(:arg (last items))]
           ~call
           ~vsym)))))
