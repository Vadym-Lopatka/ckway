(ns ckway.bridge.kotlinc
  "Compile one Kotlin source text in this JVM with the Kotlin compiler.

  The compiler (`org.jetbrains.kotlin/kotlin-compiler-embeddable`, alias `:kotlinc`) is NOT a
  dependency of ckway. It is used by name through reflection, so this namespace loads without it and
  `compile-source` says what to add when it is missing.

  The class path of the compilation is the class path of this JVM: java.class.path and the
  URLClassLoaders (what Clojure's DynamicClassLoader added), see `ckway.meta/classpath-files`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ckway.meta :as meta])
  (:import [java.io ByteArrayOutputStream File PrintStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def compiler-class "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

(defn kotlin-version
  "The version of the Kotlin libraries that kt runs with (the stdlib on the class path)."
  []
  (str kotlin.KotlinVersion/CURRENT))

(defn- load-compiler-class ^Class []
  (try (Class/forName ^String compiler-class true (clojure.lang.RT/baseLoader))
       (catch ClassNotFoundException _ nil)
       (catch LinkageError _ nil)))

(defn available? [] (some? (load-compiler-class)))

(defn missing-message
  "The error text for a reified call that needs the compiler. `what` says which call."
  [what]
  (str "kt: " what " is an `inline reified` call. kt compiles a small Kotlin bridge for it, which needs the Kotlin "
       "compiler on the class path, and no stored bridge for this call exists (no AOT class, no entry in the "
       "disk cache).\n  Add org.jetbrains.kotlin/kotlin-compiler-embeddable " (kotlin-version) " with the `:kotlinc` alias of deps.edn:\n"
       "    :kotlinc {:extra-deps {org.jetbrains.kotlin/kotlin-compiler-embeddable {:mvn/version \"" (kotlin-version)
       "\" :exclusions [org.jetbrains.kotlin/kotlin-reflect]}}}\n"
       "  and start with it (clojure -M:kotlinc ...). Only the JVM that compiles needs it: AOT-compile the namespace, "
       "or keep the disk cache, and other JVMs run without it."))

(defn- jvm-target
  "The Kotlin -jvm-target for the bridge. Kotlin refuses to inline bytecode of a newer target into an
  older one, so use this JVM's version (override: -Dckway.jvm-target=21)."
  []
  (or (System/getProperty "ckway.jvm-target") (str (.feature (Runtime/version)))))

(defn- classpath-string []
  (str/join File/pathSeparator (map #(.getAbsolutePath ^File %) (meta/classpath-files))))

(defn- delete-tree [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree c)))
  (.delete f))

(defn- read-classes [^File dir]
  (let [root (.toPath dir)]
    (into {} (for [^File f (file-seq dir) :when (and (.isFile f) (str/ends-with? (.getName f) ".class"))
                   :let [rel (str (.relativize root (.toPath f)))]]
               [(str/replace (subs rel 0 (- (count rel) 6)) File/separator ".") (Files/readAllBytes (.toPath f))]))))

(defn compile-source
  "Compile the Kotlin text `source` (file name `file-name`). => {:classes {binary-name bytes} :ms n}.
  Throws ex-info {:kt/compile-failed [message ...] :kt/output text} when the Kotlin compiler reports errors,
  and ex-info with the `missing-message` of `what` when the compiler is not on the class path."
  [what file-name ^String source]
  (let [^Class cls (or (load-compiler-class) (throw (ex-info (missing-message what) {:kt/error true :kt/no-compiler true})))
        t0 (System/nanoTime)
        tmp (.toFile (Files/createTempDirectory "ckway-bridge" (make-array FileAttribute 0)))
        out (io/file tmp "out") src (io/file tmp file-name)]
    (try
      (.mkdirs out)
      (spit src source)
      (let [compiler (.newInstance (.getConstructor cls (make-array Class 0)) (make-array Object 0))
            ^java.lang.reflect.Method exec (.getMethod cls "exec" (into-array Class [PrintStream (class (make-array String 0))]))
            buf (ByteArrayOutputStream.)
            ps (PrintStream. buf true "UTF-8")
            args (into-array String ["-no-stdlib" "-no-reflect" "-nowarn"
                                     "-jvm-target" (jvm-target)
                                     "-cp" (classpath-string) "-d" (.getPath out) (.getPath src)])
            code (str (.invoke exec compiler (object-array [ps args])))
            text (.toString buf "UTF-8")]
        (when-not (= "OK" code)
          (throw (ex-info (str "kt: the Kotlin compiler failed on a bridge (" code ")")
                          {:kt/error true :kt/compile-failed
                           (vec (keep #(second (re-matches #".*?\.kt:\d+:\d+: error: (.*)" %)) (str/split-lines text)))
                           :kt/output text})))
        {:classes (read-classes out) :ms (/ (- (System/nanoTime) t0) 1e6)})
      (finally (delete-tree tmp)))))
