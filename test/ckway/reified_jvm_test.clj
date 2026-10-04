(ns ckway.reified-jvm-test
  "Step 5, T3: the compiler dependency and the storage of Kotlin bridges, each in its own JVM:
  no compiler on the class path, the disk cache, a changed target, AOT."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir ^File [] (.toFile (Files/createTempDirectory "kt-reified" (make-array FileAttribute 0))))

(def ^:private base-cp (System/getProperty "java.class.path"))

(defn- cp-entries [] (str/split base-cp (re-pattern File/pathSeparator)))

(defn- canon [^String p] (.getCanonicalPath (io/file p)))

(defn- join-cp [entries] (str/join File/pathSeparator entries))

(defn- without-compiler [entries]
  (remove #(str/includes? % "kotlin-compiler-embeddable") entries))

(defn- jvm
  "Run Clojure code `code` in a new JVM with class path `cp` and system properties `props`."
  [cp props code]
  (apply sh "clojure" (concat (for [[k v] props] (str "-J-D" k "=" v)) ["-Scp" cp "-M" "-e" code])))

(def ^:private stats-code
  "(println \"STATS\" (pr-str (select-keys @ckway.bridge/kotlin-stats [:compiled :cache-hits :compile-ms :cache-ms])))")

(defn- stats [out]
  (edn/read-string (second (re-find #"STATS (\{.*\})" out))))

(defn- report [label {:keys [compile-ms cache-ms] :as s}]
  (println (str "KT BRIDGE " label ": " (pr-str (select-keys s [:compiled :cache-hits]))
                (when (seq compile-ms) (str " compile-ms " (mapv #(Math/round (double %)) compile-ms)))
                (when (seq cache-ms) (str " cache-ms " (mapv #(format "%.1f" (double %)) cache-ms))))))

(def ^:private two-calls
  "(require '[ckway.core :as kt] 'ckway.bridge) (kt/require '[fx :as f])
   (println \"OUT\" (eval '(f/typeName :<> String)) (eval '(f/describe 1 :<> Int)))")

(deftest no-compiler-and-no-stored-bridge
  (let [cache (tmp-dir)
        r (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" (.getPath cache)}
               "(require '[ckway.core :as kt]) (kt/require '[fx :as f])
                (println \"MSG\" (pr-str (try (eval '(f/typeName :<> String)) (catch Throwable t (ex-message (loop [t t] (if-let [c (.getCause t)] (recur c) t)))))))")
        msg (edn/read-string (second (re-find #"MSG (\".*\")" (:out r))))]
    (is (zero? (:exit r)) (:err r))
    (testing "the error says exactly which dependency and which alias to add"
      (is (str/includes? msg "is an `inline reified` call"))
      (is (str/includes? msg "(f/typeName :<> String)"))
      (is (str/includes? msg "org.jetbrains.kotlin/kotlin-compiler-embeddable 2.4.20"))
      (is (str/includes? msg "`:kotlinc` alias"))
      (is (str/includes? msg ":kotlinc {:extra-deps {org.jetbrains.kotlin/kotlin-compiler-embeddable {:mvn/version \"2.4.20\""))
      (is (str/includes? msg "no stored bridge for this call exists")))
    (testing "a call that needs no bridge works without the compiler"
      (let [r (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" (.getPath cache)}
                   "(require '[ckway.core :as kt]) (kt/require '[fx :as f])
                    (println \"OUT\" (eval '(f/echo 5 :<> Long)) (eval '(f/.firstOf (f/Box2 [1]))))")]
        (is (str/includes? (:out r) "OUT 5 1") (str (:out r) (:err r)))))))

(deftest disk-cache
  (let [cache (tmp-dir)
        props {"ckway.cache.dir" (.getPath cache)}
        a (jvm base-cp props (str two-calls stats-code))
        sa (stats (:out a))]
    (is (zero? (:exit a)) (:err a))
    (is (str/includes? (:out a) "OUT String v:Int:1"))
    (testing "the first JVM compiles both bridges and fills the cache"
      (is (= 2 (:compiled sa)))
      (is (= 0 (:cache-hits sa)))
      ;; an entry is a directory <bridge>-<key> with the class files and entry.txt (hashes), see ckway.bridge.cache
      (let [entries (filter #(.isDirectory ^File %) (.listFiles cache))]
        (is (= 2 (count entries)))
        (is (= 2 (count (filter #(.isFile (io/file ^File % "entry.txt")) entries))))
        (is (= 2 (count (mapcat (fn [^File e] (filter #(str/ends-with? (.getName ^File %) ".class") (.listFiles e))) entries))))))
    (report "JVM 1 (empty cache; the first is the cold compiler, the second warm)" sa)
    (testing "the second JVM compiles nothing: it needs no compiler"
      (let [b (jvm (join-cp (without-compiler (cp-entries))) props (str two-calls stats-code))
            sb (stats (:out b))]
        (is (zero? (:exit b)) (:err b))
        (is (str/includes? (:out b) "OUT String v:Int:1"))
        (is (= 0 (:compiled sb)))
        (is (= 2 (:cache-hits sb)))
        (report "JVM 2 (cache hit, no compiler)" sb)))
    (testing "the cache can be turned off by an empty directory property: then a missing compiler is an error"
      (let [c (jvm (join-cp (without-compiler (cp-entries))) {"ckway.cache.dir" ""}
                   "(require '[ckway.core :as kt]) (kt/require '[fx :as f])
                    (println \"OUT\" (try (eval '(f/typeName :<> String)) (catch Throwable t :error)))")]
        (is (str/includes? (:out c) "OUT :error") (str (:out c) (:err c)))))))

(deftest changed-target-is-not-reused
  (testing "same source text, but the inline function in the library changed: the stored bridge is stale"
    (let [root (tmp-dir) lib (io/file root "lib") cache (tmp-dir)
          src (io/file root "Lib.kt")
          build! (fn [body]
                   (spit src (str "package ck\ninline fun <reified T> libName(): String = \"" body "\"\n"))
                   (.mkdirs lib)
                   (let [r (sh "kotlinc" "-module-name" "ck" "-nowarn" (.getPath src) "-d" (.getPath lib))]
                     (is (zero? (:exit r)) (str (:out r) (:err r)))))
          cp (join-cp (concat (cp-entries) [(.getPath lib)]))
          props {"ckway.cache.dir" (.getPath cache)}
          run (fn []
                (let [r (jvm cp props (str "(require '[ckway.core :as kt] 'ckway.bridge) (kt/require '[ck :as c])
                                            (println \"OUT\" (eval '(c/libName :<> String)))" stats-code))]
                  (is (zero? (:exit r)) (:err r))
                  [(second (re-find #"OUT (\S+)" (:out r))) (stats (:out r))]))]
      (build! "v1")
      (let [[v s] (run)]
        (is (= "v1" v))
        (is (= 1 (:compiled s))))
      (let [[v s] (run)]
        (is (= "v1" v))
        (is (= {:compiled 0 :cache-hits 1} (select-keys s [:compiled :cache-hits])) "unchanged: the cache is used"))
      (build! "v2")
      (let [[v s] (run)]
        (is (= "v2" v) "the changed class file is seen")
        (is (= {:compiled 1 :cache-hits 0} (select-keys s [:compiled :cache-hits])) "...so the bridge was compiled again")))))

(def ^:private aot-source
  "(ns aot.reified
  (:require [ckway.core :as kt]))

(kt/require '[fx :as f])

(defn run []
  (println \"AOT\" (f/typeName :<> String) (f/.parseAs \"42\" :<> Int)))
")

(defn- copy-tree [^File from ^File to skip?]
  (doseq [^File f (file-seq from) :when (.isFile f)
          :let [rel (str (.relativize (.toPath from) (.toPath f)))]
          :when (not (skip? rel))]
    (io/make-parents (io/file to rel))
    (io/copy f (io/file to rel))))

(deftest aot-needs-neither-compiler-nor-generator
  (let [root (tmp-dir)
        src (io/file root "src") classes (io/file root "classes") cache (tmp-dir)
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "reified.clj") aot-source)
        compile-cp (join-cp (concat (cp-entries) [(.getPath src) (.getPath classes)]))
        c (jvm compile-cp {"ckway.cache.dir" (.getPath cache)}
               (str "(binding [*compile-path* \"" (.getPath classes) "\"] (compile 'aot.reified))" stats-code))]
    (is (zero? (:exit c)) (:err c))
    (testing "the compile JVM wrote the two bridge classes next to the compiled namespace"
      (is (.exists (io/file classes "aot" "reified__init.class")))
      (is (= 2 (count (filter #(re-matches #"K_fx_ReifiedKt_.*\.class|K_fx_UnsupportedKt_.*\.class" %)
                              (map #(.getName ^File %) (.listFiles (io/file classes "ckway" "bridge"))))))
          (str (map #(.getName ^File %) (.listFiles (io/file classes "ckway" "bridge"))))))
    (testing "a fresh JVM: no compiler, no source of the namespace, no generator source, no cache"
      (let [lib-src (io/file root "kt-src")
            _ (copy-tree (io/file "src") lib-src #{"ckway/bridge/ksrc.clj" "ckway/bridge/kotlinc.clj"})
            run-entries (for [e (without-compiler (cp-entries))]
                          (if (= (canon e) (canon "src")) (.getPath lib-src) e))
            run-cp (join-cp (concat run-entries [(.getPath classes)]))
            r (jvm run-cp {"ckway.cache.dir" ""}
                   "(require 'aot.reified) (aot.reified/run)
                    (println \"LOADED\" (some? (find-ns 'ckway.bridge.ksrc)) (some? (find-ns 'ckway.bridge.kotlinc)) (some? (find-ns 'ckway.bridge.gen)))")]
        (is (not (str/includes? run-cp (.getPath src))) "the source of the namespace is not on the class path")
        (is (not (.exists (io/file lib-src "ckway" "bridge" "ksrc.clj"))))
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "AOT String 42") (:out r))
        (is (str/includes? (:out r) "LOADED false false false") "neither the source generator nor the compiler namespace was loaded")))))

(deftest compile-class-path-includes-the-dynamic-class-loader
  (testing "a directory added to Clojure's DynamicClassLoader at run time (not on java.class.path) is on the compile class path"
    (let [root (tmp-dir) lib (io/file root "lib") cache (tmp-dir) src (io/file root "Dyn.kt")]
      (spit src "package dyn\ninline fun <reified T> dynName(): String = \"dyn:\" + T::class.java.simpleName\n")
      (.mkdirs lib)
      (let [k (sh "kotlinc" "-module-name" "dyn" "-nowarn" (.getPath src) "-d" (.getPath lib))]
        (is (zero? (:exit k)) (str (:out k) (:err k))))
      (let [r (jvm base-cp {"ckway.cache.dir" (.getPath cache)}
                   (str "(let [dcl (clojure.lang.DynamicClassLoader. (.getContextClassLoader (Thread/currentThread)))]
                           (.setContextClassLoader (Thread/currentThread) dcl)
                           (.addURL dcl (.toURL (.toURI (java.io.File. \"" (.getPath lib) "\")))))
                         (require '[ckway.core :as kt]) (kt/require '[dyn :as d])
                         (println \"OUT\" (eval '(d/dynName :<> String)))"))]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (str/includes? (:out r) "OUT dyn:String") (str (:out r) (:err r)))))))
