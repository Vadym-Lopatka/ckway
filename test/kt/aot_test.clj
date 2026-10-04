(ns kt.aot-test
  "AOT: a namespace that uses kt/require compiles, and the compiled code runs in a fresh JVM
  without its source."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private demo-source
  "(ns aot.demo
  (:require [kt.core :as kt]))

(kt/require '[fx :as f])

(defn run []
  (println (f/greet \"Bob\" :punct \"?\")
           (f/.shout \"ab\")
           (f/wordCount \"a b c\")
           (apply f/greet [\"Z\"])
           (f/step 1)
           (f/runIt (fn [x] (f/step x)))
           (f/useBefore (fn [x] (f/step x)) 4)))
")

(def ^:private demo2-source
  "(ns aot.demo2
  (:require [kt.core :as kt]))

(kt/require '[fx :as f])

(defn ref-of-person [] (kt/ref f/Person firstName))

(defn run []
  (let [g (f/Gadget)
        p (f/Person 7 \"Ann\" \"a@b\")]
    (kt/set! (f/level g) 5)
    (kt/set! (f/uid g) (f/Uid 9))
    ((fn [x] (kt/set! (f/label x) \"dyn\")) g)
    (println (f/level g) (f/label g) (str (f/uid g)))
    (println (pr-str (update (kt/data p) :uid str)))
    (println (f/propName (ref-of-person))
             (f/readProp (ref-of-person) p)
             (f/className (kt/ref f/Person class))
             (f/callRef (kt/ref f/Person .greet) p)
             (f/make (kt/ref f/Person2))
             (identical? (ref-of-person) (ref-of-person))
             (.get ^kotlin.reflect.KProperty0 (kt/ref f/topVal))
             (class (kt/ref f/Gadget level)))))
")

(def ^:private demo3-source
  "(ns aot.demo3
  (:require [kt.core :as kt]))

(kt/require '[fx :as f] '[kotlinx.coroutines :as co])

(defn make-holder []
  (let [lvl (atom 0)]
    (kt/reify f/Holder2
      (level [this] @lvl)
      (level [this v] (reset! lvl v))
      (uid [this] (f/Uid 3))
      (.next [this u] (f/.plus u 10))
      (.label [this n] n))))

(defn make-job [log]
  (kt/reify f/Job2
    (^{fx.Marker true} .run [this] (co/delay 5) (swap! log conj :ran))
    (allowParallelRun [this] true)))

(defn run []
  (let [log (atom [])]
    (println (f/useHolder (make-holder)))
    (println (f/runJob (make-job log)) @log (f/jobFlags (make-job log)))
    (println (vec (f/annotationsOf (make-job log) \"run\")))
    (println (contains? (loaded-libs) 'kt.bridge.gen) (contains? (loaded-libs) 'kt.bridge.kotlinc))))
")

(defn- tmp-dir ^File [] (.toFile (Files/createTempDirectory "kt-aot" (make-array FileAttribute 0))))

(defn- clj [cp & args]
  (apply sh "clojure" "-Scp" cp "-M" "-e" args))

(deftest compile-then-run-without-source
  (let [root (tmp-dir)
        src (io/file root "src") classes (io/file root "classes")
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "demo.clj") demo-source)
        base-cp (System/getProperty "java.class.path")
        compile-cp (str/join File/pathSeparator [base-cp (.getPath src) (.getPath classes)])
        run-cp (str/join File/pathSeparator [base-cp (.getPath classes)])
        c (clj compile-cp (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.demo))"))]
    (is (zero? (:exit c)) (:err c))
    (is (.exists (io/file classes "aot" "demo__init.class")))
    (is (not (str/includes? run-cp (.getPath src))) "the source is not on the run classpath")
    (let [r (clj run-cp "(require 'aot.demo) (aot.demo/run)")]
      (is (zero? (:exit r)) (:err r))
      (is (= "Hello, Bob? AB! 3 Hello, Z! 2 2 5" (str/trim (:out r)))))))

(deftest compile-and-run-set-ref-data-without-source
  (let [root (tmp-dir)
        src (io/file root "src") classes (io/file root "classes")
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "demo2.clj") demo2-source)
        base-cp (System/getProperty "java.class.path")
        compile-cp (str/join File/pathSeparator [base-cp (.getPath src) (.getPath classes)])
        run-cp (str/join File/pathSeparator [base-cp (.getPath classes)])
        c (clj compile-cp (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.demo2))"))]
    (is (zero? (:exit c)) (:err c))
    (is (.exists (io/file classes "aot" "demo2__init.class")))
    (let [r (clj run-cp "(require 'aot.demo2) (aot.demo2/run)")
          lines (str/split-lines (str/trim (:out r)))]
      (is (zero? (:exit r)) (:err r))
      (is (= "5 dyn Uid(v=9)" (first lines)))
      (is (= "{:id 7, :firstName \"Ann\", :email \"a@b\", :uid \"Uid(v=1)\"}" (second lines)))
      (is (re-matches #"firstName Ann fx\.Person Yo Ann #object\[fx\.Person2 0x[0-9a-f]+ Person2\(id=1, name=x\)\] true 41 kt\.ref\.proxy\$kotlin\.jvm\.internal\.MutablePropertyReference1Impl\$IFn\$[0-9a-f]+"
                      (nth lines 2))
          (nth lines 2)))))

(deftest compile-and-run-reify-without-source-and-generator
  (let [root (tmp-dir)
        src (io/file root "src") classes (io/file root "classes")
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "demo3.clj") demo3-source)
        base-cp (System/getProperty "java.class.path")
        compile-cp (str/join File/pathSeparator [base-cp (.getPath src) (.getPath classes)])
        run-cp (str/join File/pathSeparator [base-cp (.getPath classes)])
        c (clj compile-cp (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.demo3))"))]
    (is (zero? (:exit c)) (:err c))
    (is (.exists (io/file classes "aot" "demo3__init.class")))
    (let [generated (filter #(re-find #"^R_" (.getName ^File %)) (.listFiles (io/file classes "kt" "bridge")))]
      (is (= 2 (count generated)) "one class for each shape of kt/reify: Holder2 (mangled) and Job2 (suspend)"))
    (let [r (clj run-cp "(require 'aot.demo3) (aot.demo3/run)")
          lines (str/split-lines (str/trim (:out r)))]
      (is (zero? (:exit r)) (:err r))
      (is (= "5/13/a/null" (nth lines 0)))
      (is (= "job [:ran] job/true" (nth lines 1)))
      (is (= "[fx.Marker]" (nth lines 2)))
      (is (= "false false" (nth lines 3)) "the generator and the Kotlin compiler were not loaded"))))
