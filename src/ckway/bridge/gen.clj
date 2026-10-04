(ns ckway.bridge.gen
  "Bytecode of a call bridge (see `ckway.bridge`). The only user of `clojure.asm`.

  The bridge has one public static method `call`. For a public target it is a direct
  INVOKESTATIC / INVOKEVIRTUAL / INVOKEINTERFACE. For a non-public target (:access :private) the
  class has a static final MethodHandle, made in the static initializer with
  MethodHandles.privateLookupIn, and `call` is MethodHandle.invokeExact on it."
  (:require [clojure.string :as str])
  (:import [clojure.asm AnnotationVisitor ClassWriter Opcodes Type MethodVisitor]))

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

(defn call-bridge-bytes
  "Class bytes of the bridge `cname` for `target` = {:kind :class :name :desc :access}."
  ^bytes [^String cname {:keys [kind class name desc access]}]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)
        owner (internal class)
        call-desc (bridge-desc kind class desc)
        iface? (.isInterface (load-class class))]
    (.visit cw Opcodes/V1_8 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER) (internal cname) nil "java/lang/Object" nil)
    (when (= :private access)
      (.visitEnd (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC Opcodes/ACC_FINAL) "MH" "Ljava/lang/invoke/MethodHandle;" nil nil))
      (let [mv (.visitMethod cw Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
        (.visitCode mv)
        (.visitLdcInsn mv (Type/getObjectType owner))
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "lookup" "()Ljava/lang/invoke/MethodHandles$Lookup;" false)
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "privateLookupIn"
                          "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;" false)
        (.visitLdcInsn mv (Type/getObjectType owner))
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
        :abstract (abstract-method! cw m)))
    (.visitEnd cw)
    (.toByteArray cw)))
