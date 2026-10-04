(ns ckway.bridge.gen
  "Bytecode of a call bridge (see `ckway.bridge`). The only user of `clojure.asm`.

  The bridge has one public static method `call`. For a public target it is a direct
  INVOKESTATIC / INVOKEVIRTUAL / INVOKEINTERFACE. For a non-public target (:access :private) the
  class has a static final MethodHandle, made in the static initializer with
  MethodHandles.privateLookupIn, and `call` is MethodHandle.invokeExact on it."
  (:require [clojure.string :as str])
  (:import [clojure.asm AnnotationVisitor ClassWriter Opcodes Type MethodVisitor]
           [java.lang.reflect Modifier]))

(set! *warn-on-reflection* true)

(defn- internal ^String [^String n] (str/replace n "." "/"))

(defn- load-class ^Class [^String n]
  (Class/forName n false (clojure.lang.RT/baseLoader)))

(defn- bridge-desc
  "Descriptor of `call`: the descriptor of the target; a :virtual target gets its receiver first."
  ^String [kind owner ^String desc]
  (if (= :static kind)
    desc
    (Type/getMethodDescriptor (Type/getReturnType desc)
                              (into-array Type (cons (Type/getObjectType (internal owner)) (Type/getArgumentTypes desc))))))

(defn- load-args [^MethodVisitor mv ^String call-desc]
  (reduce (fn [slot ^Type t]
            (.visitVarInsn mv (.getOpcode t Opcodes/ILOAD) (int slot))
            (+ slot (.getSize t)))
          0 (Type/getArgumentTypes call-desc)))

(defn- push-class!
  "Push the Class `owner` (internal name). A class that is not public cannot be an LDC constant of another
  package (IllegalAccessError), so it is looked up by name with the loader of the bridge."
  [^MethodVisitor mv ^String owner public?]
  (if public?
    (.visitLdcInsn mv (Type/getObjectType owner))
    (do (.visitLdcInsn mv (str/replace owner "/" "."))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/Class" "forName" "(Ljava/lang/String;)Ljava/lang/Class;" false))))

(defn call-bridge-bytes
  "Class bytes of the bridge `cname` for `target` = {:kind :class :name :desc :access}."
  ^bytes [^String cname {:keys [kind class name desc access]}]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)
        owner (internal class)
        call-desc (bridge-desc kind class desc)
        target-class (load-class class)
        iface? (.isInterface target-class)
        public? (Modifier/isPublic (.getModifiers target-class))]
    (.visit cw Opcodes/V1_8 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER) (internal cname) nil "java/lang/Object" nil)
    (when (= :private access)
      (.visitEnd (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC Opcodes/ACC_FINAL) "MH" "Ljava/lang/invoke/MethodHandle;" nil nil))
      (let [mv (.visitMethod cw Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
        (.visitCode mv)
        (push-class! mv owner public?)
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "lookup" "()Ljava/lang/invoke/MethodHandles$Lookup;" false)
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "privateLookupIn"
                          "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;" false)
        (push-class! mv owner public?)
        (.visitLdcInsn mv ^String name)
        (.visitLdcInsn mv (Type/getMethodType ^String desc))
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/MethodHandles$Lookup"
                          (if (= :static kind) "findStatic" "findVirtual")
                          "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;" false)
        (.visitFieldInsn mv Opcodes/PUTSTATIC (internal cname) "MH" "Ljava/lang/invoke/MethodHandle;")
        (.visitInsn mv Opcodes/RETURN)
        (.visitMaxs mv 0 0)
        (.visitEnd mv)))
    (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) "call" call-desc nil nil)]
      (.visitCode mv)
      (if (= :private access)
        (do (.visitFieldInsn mv Opcodes/GETSTATIC (internal cname) "MH" "Ljava/lang/invoke/MethodHandle;")
            (load-args mv call-desc)
            (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/MethodHandle" "invokeExact" call-desc false))
        (do (load-args mv call-desc)
            (case kind
              :static (.visitMethodInsn mv Opcodes/INVOKESTATIC owner name desc iface?)
              :virtual (.visitMethodInsn mv (if iface? Opcodes/INVOKEINTERFACE Opcodes/INVOKEVIRTUAL) owner name desc iface?))))
      (.visitInsn mv (.getOpcode (Type/getReturnType call-desc) Opcodes/IRETURN))
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (.visitEnd cw)
    (.toByteArray cw)))

;; ---------------------------------------------------------------- reify classes (kt/reify)

(def ^:private unboxing
  "JVM type descriptor of a primitive -> [wrapper class, unboxing method, boxing descriptor]."
  {"Z" ["java/lang/Boolean" "booleanValue" "Z"]
   "C" ["java/lang/Character" "charValue" "C"]
   "B" ["java/lang/Number" "byteValue" "B"]
   "S" ["java/lang/Number" "shortValue" "S"]
   "I" ["java/lang/Number" "intValue" "I"]
   "J" ["java/lang/Number" "longValue" "J"]
   "F" ["java/lang/Number" "floatValue" "F"]
   "D" ["java/lang/Number" "doubleValue" "D"]})

(def ^:private wrappers
  {"Z" "java/lang/Boolean" "C" "java/lang/Character" "B" "java/lang/Byte" "S" "java/lang/Short"
   "I" "java/lang/Integer" "J" "java/lang/Long" "F" "java/lang/Float" "D" "java/lang/Double"})

(def ^:private object-desc "Ljava/lang/Object;")
(def ^:private ifn "clojure/lang/IFn")

(defn- box! [^MethodVisitor mv ^Type t]
  (let [d (.getDescriptor t)]
    (when-let [w (wrappers d)]
      (.visitMethodInsn mv Opcodes/INVOKESTATIC w "valueOf" (str "(" d ")L" w ";") false))))

(defn- return-converted!
  "The Object on the stack becomes the JVM return type `ret`, and is returned."
  [^MethodVisitor mv ^Type ret]
  (let [d (.getDescriptor ret)]
    (cond
      (= "V" d) (do (.visitInsn mv Opcodes/POP) (.visitInsn mv Opcodes/RETURN))
      (unboxing d) (let [[owner m prim] (unboxing d)]
                     (.visitTypeInsn mv Opcodes/CHECKCAST owner)
                     (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL owner m (str "()" prim) false)
                     (.visitInsn mv (.getOpcode ret Opcodes/IRETURN)))
      :else (do (when (not= object-desc d) (.visitTypeInsn mv Opcodes/CHECKCAST (.getInternalName ret)))
                (.visitInsn mv Opcodes/ARETURN)))))

(defn- visit-element! [^AnnotationVisitor av ^String n v]
  (if (vector? v)
    (let [arr (.visitArray av n)]
      (doseq [x v] (.visit ^AnnotationVisitor arr nil x))
      (.visitEnd arr))
    (.visit av n v)))

(defn- annotate! [^MethodVisitor mv annotations]
  (doseq [{:keys [class elements]} annotations]
    (let [av (.visitAnnotation mv (str "L" (internal class) ";") true)]
      (doseq [[n v] elements] (visit-element! av n v))
      (.visitEnd av))))

(defn- fn-method!
  "A method that calls element `idx` of the array `f` with `this` and the arguments (boxed) and converts the result."
  [^ClassWriter cw ^String self {:keys [name desc annotations idx]}]
  (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC ^String name ^String desc nil nil)
        args (Type/getArgumentTypes ^String desc)
        n (inc (count args))]
    (annotate! mv annotations)
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitFieldInsn mv Opcodes/GETFIELD self "f" "[Ljava/lang/Object;")
    (.visitLdcInsn mv (int idx))
    (.visitInsn mv Opcodes/AALOAD)
    (.visitTypeInsn mv Opcodes/CHECKCAST ifn)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (reduce (fn [slot ^Type t]
              (.visitVarInsn mv (.getOpcode t Opcodes/ILOAD) (int slot))
              (box! mv t)
              (+ slot (.getSize t)))
            1 args)
    (.visitMethodInsn mv Opcodes/INVOKEINTERFACE ifn "invoke"
                      (str "(" (apply str (repeat n object-desc)) ")" object-desc) true)
    (return-converted! mv (Type/getReturnType ^String desc))
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- delegate-method!
  "A method whose body is the static method of `I$DefaultImpls` (Kotlin without JVM default methods)."
  [^ClassWriter cw {:keys [name desc impls impls-desc]}]
  (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC ^String name ^String desc nil nil)
        ret (Type/getReturnType ^String desc)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (reduce (fn [slot ^Type t]
              (.visitVarInsn mv (.getOpcode t Opcodes/ILOAD) (int slot))
              (+ slot (.getSize t)))
            1 (Type/getArgumentTypes ^String desc))
    (.visitMethodInsn mv Opcodes/INVOKESTATIC (internal impls) ^String name ^String impls-desc false)
    (.visitInsn mv (.getOpcode ret Opcodes/IRETURN))
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;; a JVM bridge method, as kotlinc/javac make for an override with a more specific type
(defn- convert!
  "The value on the stack, of JVM type `from`, becomes JVM type `to` (cast, unbox or box)."
  [^MethodVisitor mv ^Type from ^Type to]
  (let [fd (.getDescriptor from) td (.getDescriptor to)]
    (cond
      (= fd td) nil
      (and (unboxing td) (not (unboxing fd)))
      (let [[owner m prim] (unboxing td)]
        (.visitTypeInsn mv Opcodes/CHECKCAST owner)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL owner m (str "()" prim) false))
      (and (unboxing fd) (not (unboxing td))) (box! mv from)
      (unboxing td) (throw (ex-info "kt: cannot bridge two different primitive types" {:from fd :to td}))
      (= object-desc td) nil
      :else (.visitTypeInsn mv Opcodes/CHECKCAST (.getInternalName to)))))

(defn- bridge-method!
  "`name desc` casts its arguments and calls `target-name target-desc` of this class (the more specific override).
  `arg-vcs` (one entry for each argument: a binary class name or nil) and `ret-vc`: where the override takes or
  returns the underlying value of a value class, the argument is unboxed (`unbox-impl`) and the result boxed
  (`box-impl`)."
  [^ClassWriter cw ^String self {:keys [name desc target-name target-desc arg-vcs ret-vc]}]
  (let [mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_BRIDGE Opcodes/ACC_SYNTHETIC) ^String name ^String desc nil nil)
        from (Type/getArgumentTypes ^String desc)
        to (Type/getArgumentTypes ^String target-desc)
        target-name (or target-name name)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (reduce (fn [slot [i ^Type f ^Type t]]
              (.visitVarInsn mv (.getOpcode f Opcodes/ILOAD) (int slot))
              (if-let [vc (get arg-vcs i)]
                (do (.visitTypeInsn mv Opcodes/CHECKCAST (internal vc))
                    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL (internal vc) "unbox-impl" (str "()" (.getDescriptor t)) false))
                (convert! mv f t))
              (+ slot (.getSize f)))
            1 (map vector (range) from to))
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL self ^String target-name ^String target-desc false)
    (let [rf (Type/getReturnType ^String target-desc) rt (Type/getReturnType ^String desc)]
      (cond
        (= "V" (.getDescriptor rt)) nil
        ret-vc (do (.visitMethodInsn mv Opcodes/INVOKESTATIC (internal ret-vc) "box-impl"
                                     (str "(" (.getDescriptor rf) ")L" (internal ret-vc) ";") false)
                   (convert! mv (Type/getObjectType (internal ret-vc)) rt))
        :else (convert! mv rf rt))
      (.visitInsn mv (.getOpcode rt Opcodes/IRETURN)))
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- abstract-method!
  "A method that throws AbstractMethodError with `message` (the member was not written)."
  [^ClassWriter cw {:keys [name desc message]}]
  (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC ^String name ^String desc nil nil)]
    (.visitCode mv)
    (.visitTypeInsn mv Opcodes/NEW "java/lang/AbstractMethodError")
    (.visitInsn mv Opcodes/DUP)
    (.visitLdcInsn mv ^String message)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/AbstractMethodError" "<init>" "(Ljava/lang/String;)V" false)
    (.visitInsn mv Opcodes/ATHROW)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn reify-class-bytes
  "Class bytes of a `kt/reify` class `cname` (see `ckway.reify`). `spec` = {:ifaces [binary names]
  :methods [method ...]}; a method is {:name :desc :impl ...} with
    :impl :fn        also :idx (index into the array of Clojure functions) and :annotations
    :impl :delegate  also :impls (binary name of `I$DefaultImpls`) and :impls-desc
    :impl :abstract  also :message
    :impl :bridge    also :target-name (default: `name`), :target-desc, and for value classes :arg-vcs and
                     :ret-vc: the method `target-name target-desc` of this class (an override with more specific
                     types, as kotlinc and javac make a bridge method)
  The class implements the interfaces and clojure.lang.IObj (like a Clojure `reify`). Its constructor
  takes the Object[] of functions (called with `this` and the boxed JVM arguments, each returns the
  boxed JVM result) and the metadata map. Names with `-` (mangled Kotlin names) are no problem here."
  ^bytes [^String cname {:keys [ifaces methods]}]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)
        self (internal cname)
        ctor-desc "([Ljava/lang/Object;Lclojure/lang/IPersistentMap;)V"]
    (.visit cw Opcodes/V1_8 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER) self nil "java/lang/Object"
            (into-array String (concat (map internal ifaces) ["clojure/lang/IObj"])))
    (.visitEnd (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) "f" "[Ljava/lang/Object;" nil nil))
    (.visitEnd (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) "m" "Lclojure/lang/IPersistentMap;" nil nil))
    (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC "<init>" ctor-desc nil nil)]
      (.visitCode mv)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitVarInsn mv Opcodes/ALOAD 1)
      (.visitFieldInsn mv Opcodes/PUTFIELD self "f" "[Ljava/lang/Object;")
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitVarInsn mv Opcodes/ALOAD 2)
      (.visitFieldInsn mv Opcodes/PUTFIELD self "m" "Lclojure/lang/IPersistentMap;")
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC "meta" "()Lclojure/lang/IPersistentMap;" nil nil)]
      (.visitCode mv)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitFieldInsn mv Opcodes/GETFIELD self "m" "Lclojure/lang/IPersistentMap;")
      (.visitInsn mv Opcodes/ARETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (let [mv (.visitMethod cw Opcodes/ACC_PUBLIC "withMeta" "(Lclojure/lang/IPersistentMap;)Lclojure/lang/IObj;" nil nil)]
      (.visitCode mv)
      (.visitTypeInsn mv Opcodes/NEW self)
      (.visitInsn mv Opcodes/DUP)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitFieldInsn mv Opcodes/GETFIELD self "f" "[Ljava/lang/Object;")
      (.visitVarInsn mv Opcodes/ALOAD 1)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL self "<init>" ctor-desc false)
      (.visitInsn mv Opcodes/ARETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (doseq [m methods]
      (case (:impl m)
        :fn (fn-method! cw self m)
        :delegate (delegate-method! cw m)
        :bridge (bridge-method! cw self m)
        :abstract (abstract-method! cw m)))
    (.visitEnd cw)
    (.toByteArray cw)))
