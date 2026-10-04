(ns kt.bridge.ksrc
  "Kotlin source of a reified-call bridge (DESIGN-2 rule 5). Pure text, no compiler.

  `inline fun <reified T> ...` cannot be called from the JVM: the call must be compiled by Kotlin with the
  type argument. The bridge is a tiny Kotlin file with ONE function `call` that does exactly this call
  (this declaration, these type arguments, these supplied parameters):

    @file:JvmName(\"K_fx_Box2_firstOf__3f2a...\")
    package kt.bridge
    fun call(kt_this: fx.Box2): kotlin.String? = kt_this.`firstOf`<kotlin.String>()

  Rules of the text:
    - Parameters: context parameters `kt_c0`.., the dispatch receiver `kt_this`, the extension receiver
      `kt_ext`, then the SUPPLIED parameters under their own names. An omitted parameter is not
      mentioned, so Kotlin uses its default. The call writes every argument by name.
    - A type with a type parameter has the type argument substituted. A class-level type parameter
      (`class GBox<T> { inline fun <reified R> conv(v: T): R? }`) is declared on the generated function with its
      bounds: `fun <T> call(kt_this: fx.GBox<T>, v: T)`. The JVM erases it, so the bridge is one method for
      every instance of the class.
    - A value-class type is passed as `Any?` and cast inside, and a value-class result is declared `Any?`:
      the JVM name of a function with a value class in its signature is mangled, and Clojure holds the
      boxed object anyway (DESIGN-2 rule 7).
    - A top-level or extension function is imported under the alias `ktFn`, an outer class of a companion
      under `KtOwner`, so no name of the user's parameters can hide them.
    - `suspend` declarations give a `suspend fun call`; the caller waits through kt.co.

  `(build decl targs supplied)` => spec {:params :ret :suspend? :source-fn :identity :stamp-classes}.
  `(source spec cname)` => the file text."
  (:require [clojure.string :as str]
            [kt.types :as types]))

(set! *warn-on-reflection* true)

(def version
  "Part of every Kotlin bridge identity. Raise it when the generated text changes."
  2)

(defn- bt [s] (str "`" s "`"))

(defn- kotlin-name [binary] (str/replace binary "$" "."))

(defn- pkg-of [^String binary]
  (let [i (.lastIndexOf binary ".")] (if (neg? i) "" (subs binary 0 i))))

(defn- vc? [t] (boolean (:value-class t)))

(defn- param-spec
  "Spec of a bridge parameter: {:name :slot :type :vararg? :elem :decl-name}."
  [name slot t & [extra]]
  (merge {:name name :slot slot :type t} extra))

(defn- type-src [p]
  (cond (:vararg? p) (types/source (:elem p))
        (vc? (:type p)) "kotlin.Any?"
        :else (types/source (:type p))))

(defn- generics
  "[header where-clause] for the class-level type parameters `tps` ({:name :bounds}) of a member: \"<T, U>\" and
  \" where T : kotlin.Number\" (nil when there are none / no bounds)."
  [tps]
  (when (seq tps)
    [(str "<" (str/join ", " (map :name tps)) "> ")
     (let [bs (for [{:keys [name bounds]} tps b bounds] (str name " : " (types/source b)))]
       (when (seq bs) (str " where " (str/join ", " bs))))]))

(defn- use-expr
  "The expression that gives parameter `p` its Kotlin type inside the bridge."
  [p]
  (if (and (vc? (:type p)) (not (:vararg? p)))
    (str "(" (:name p) " as " (types/source (:type p)) ")")
    (bt (:name p))))

(defn- tmap-of [decl targs]
  (zipmap (map :name (:type-params decl)) targs))

(declare build*)

(defn build
  "Spec of the bridge for `decl` called with the resolved type arguments `targs` and the
  parameters `supplied` (a set of indexes into (:params decl)). The type texts are made here, with the
  class-level type parameters written by name."
  [decl targs supplied]
  (binding [types/*type-param-names* true]
    (let [spec (build* decl targs supplied)]
      (-> spec
          (update :params (fn [ps] (mapv #(assoc % :src (type-src %)) ps)))
          (assoc :ret-src (when (vc? (:ret spec)) "kotlin.Any?")
                 :generics (generics (:class-type-params decl)))))))

(defn- build*
  [decl targs supplied]
  (let [tmap (tmap-of decl targs)
        sub #(types/subst tmap %)
        recvs (:receivers decl)
        indexed (map-indexed vector recvs)
        ctxs (filter #(= :context (:role (second %))) indexed)
        [di disp] (first (filter #(= :dispatch (:role (second %))) indexed))
        [ei ext] (first (filter #(= :extension (:role (second %))) indexed))
        companion (:companion-of disp)
        rparams (concat
                 (map-indexed (fn [k [i r]] (param-spec (str "kt_c" k) i (sub (:type r)) {:label (or (:name r) "context")})) ctxs)
                 (when disp
                   [(if companion
                      (param-spec "kt_comp" di {:class "kotlin/Any" :nullable? true :args []} {:ignored? true :label "this"})
                      (param-spec "kt_this" di (sub (:type disp)) {:label "this"}))])
                 (when ext [(param-spec "kt_ext" ei (sub (:type ext)) {:label "receiver"})]))
        nr (count recvs)
        pparams (for [[i p] (map-indexed vector (:params decl)) :when (contains? supplied i)]
                  (let [t (sub (:type p))]
                    (cond-> (param-spec (:name p) (+ nr i) t {:decl-name (:name p) :label (:name p)})
                      (:vararg? p) (assoc :vararg? true :elem (sub (:vararg-elem p))))))
        params (vec (concat rparams pparams))
        by-slot (into {} (map (fn [p] [(:slot p) p]) params))
        tyargs (str "<" (str/join ", " (map types/source targs)) ">")
        args (str/join ", " (for [p pparams] (str (bt (:decl-name p)) " = " (when (:vararg? p) "*") (use-expr p))))
        name (bt (:name decl))
        owner (:owner decl)
        top? (nil? disp)
        member-call (fn [recv] (str recv "." (if top? "ktFn" name) tyargs "(" args ")"))
        core (cond
               (and top? ext) (member-call (use-expr (by-slot ei)))
               top? (str "ktFn" tyargs "(" args ")")
               (and companion ext) (str "with(KtOwner." (:companion-field disp) ") { " (member-call (use-expr (by-slot ei))) " }")
               companion (member-call (str "KtOwner." (:companion-field disp)))
               ext (str "with(" (use-expr (by-slot di)) ") { " (member-call (use-expr (by-slot ei))) " }")
               :else (member-call (use-expr (by-slot di))))
        body (reduce (fn [e [i _]] (str "with(" (use-expr (by-slot i)) ") { " e " }")) core (reverse ctxs))
        ret (sub (:return decl))
        imports (cond-> []
                  top? (conj [(str (when-not (str/blank? (pkg-of owner)) (str (pkg-of owner) ".")) (bt (:name decl))) "ktFn"])
                  companion (conj [(kotlin-name companion) "KtOwner"]))
        suspend? (boolean (:suspend (:flags decl)))
        supplied-names (mapv :decl-name pparams)
        stamp (cond-> [owner] companion (conj companion))]
    {:params params
     :ret ret
     :suspend? suspend?
     :body body
     :imports imports
     :stamp-classes (vec (distinct (concat stamp
                                           (for [t targs
                                                 c (tree-seq (fn [x] (seq (:args x))) :args t)
                                                 :when (and (:class c) (not (str/starts-with? (:class c) "kotlin/")))]
                                             (types/binary-name (:class c))))))
     :readable (str owner "_" (:name decl))
     :identity (str version "|K|" owner "|" (:name decl) "|" (get-in decl [:jvm :desc]) "|" (:signature decl)
                    "|" (str/join "," (map types/source targs)) "|" (str/join "," supplied-names))}))

(defn source
  "The Kotlin file for `spec`, in the file class `cname` (a binary name in package kt.bridge)."
  [{:keys [params ret-src suspend? body imports generics]} ^String cname]
  (let [simple (subs cname (inc (.lastIndexOf cname ".")))]
    (str "@file:JvmName(\"" simple "\")\n"
         "@file:Suppress(\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"NOTHING_TO_INLINE\")\n"
         "package kt.bridge\n\n"
         (apply str (for [[path alias] imports] (str "import " path " as " alias "\n")))
         "\n"
         (when suspend? "suspend ") "fun " (first generics) "call("
         (str/join ", " (for [p params] (str (when (:vararg? p) "vararg ") (bt (:name p)) ": " (:src p))))
         ")" (when ret-src (str ": " ret-src)) (second generics) " = " body "\n")))
