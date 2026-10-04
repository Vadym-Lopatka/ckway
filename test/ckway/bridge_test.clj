(ns ckway.bridge-test
  "V6: the bridge classes of ckway.bridge: naming, storage, AOT."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ckway.bridge :as bridge])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def box-uid {:kind :static :class "fx.Uid" :name "box-impl" :desc "(J)Lfx/Uid;"})
(def unbox-uid {:kind :virtual :class "fx.Uid" :name "unbox-impl" :desc "()J"})
(def next-uid {:kind :static :class "fx.ValuesKt" :name "nextUid-SMDO5ZU" :desc "(J)J"})
(def pause-default {:kind :static :class "fx.ValuesKt" :name "pause-LRDsOJo$default" :desc "(JILjava/lang/Object;)J"})
(def seconds {:kind :virtual :class "kotlin.time.Duration$Companion" :name "getSeconds-UwyO8pc" :desc "(I)J"})
(def box-duration {:kind :static :class "kotlin.time.Duration" :name "box-impl" :desc "(J)Lkotlin/time/Duration;"})

(deftest naming-is-deterministic
  (testing "the same target gives the same name, in every run (the literal is part of the contract)"
    (is (= (bridge/call-class-name box-uid) (bridge/call-class-name (assoc box-uid :extra 1))))
    (is (= "ckway.bridge.C_fx_Uid_box_impl__116a9f967b" (bridge/call-class-name box-uid))))
  (testing "different kind, class, name or descriptor give different names"
    (is (= 6 (count (distinct (map bridge/call-class-name [box-uid unbox-uid next-uid pause-default seconds box-duration])))))
    (is (not= (bridge/call-class-name box-uid) (bridge/call-class-name (assoc box-uid :desc "(I)Lfx/Uid;"))))
    (is (not= (bridge/call-class-name unbox-uid) (bridge/call-class-name (assoc unbox-uid :kind :static)))))
  (testing "the name is a valid Clojure class symbol that the compiler keeps as it is"
    (doseq [t [box-uid unbox-uid next-uid pause-default seconds box-duration]]
      (is (re-matches #"ckway\.bridge\.C_[A-Za-z0-9_]+__[0-9a-f]{10}" (bridge/call-class-name t))))))

(deftest which-targets-need-a-bridge
  (is (true? (bridge/needed? box-uid)) "a dashed name")
  (is (true? (bridge/needed? next-uid)))
  (is (true? (bridge/needed? pause-default)) "a dashed name before $default")
  (is (true? (bridge/needed? seconds)) "dashed and private (hidden inline function)")
  (is (false? (bridge/needed? {:kind :static :class "fx.BasicsKt" :name "greet" :desc "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"})))
  (is (false? (bridge/needed? {:kind :static :class "fx.BasicsKt" :name "greet$default" :desc "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;"}))
      "$ is kept by the compiler")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"kt: .*no\.such\.Class" (bridge/needed? {:kind :static :class "no.such.Class" :name "x" :desc "()V"}))
      "a member that cannot be found is a kt error, not silently 'no bridge needed'"))

(deftest bridge-class-works-and-is-idempotent
  (let [n1 (bridge/bridge-class box-uid)
        n2 (bridge/bridge-class box-uid)]
    (is (= n1 n2))
    (is (= n1 (bridge/call-class-name box-uid)))
    (testing "the bridge is a public static `call` with the descriptor of the target"
      (let [c (Class/forName n1)
            m (.getMethod c "call" (into-array Class [Long/TYPE]))]
        (is (= fx.Uid (.getReturnType m)))
        (is (= "Uid(v=7)" (str (.invoke m nil (object-array [7])))))))
    (testing "a :virtual bridge takes the receiver first"
      (let [c (Class/forName (bridge/bridge-class unbox-uid))
            m (.getMethod c "call" (into-array Class [fx.Uid]))]
        (is (= 7 (.invoke m nil (object-array [(.invoke (.getMethod (Class/forName n1) "call" (into-array Class [Long/TYPE])) nil (object-array [7]))]))))))
    (testing "a target in a jar (kotlin-stdlib), also a private one (MethodHandle)"
      (let [c (Class/forName (bridge/bridge-class box-duration))
            d (.invoke (.getMethod c "call" (into-array Class [Long/TYPE])) nil (object-array [10000000000]))]
        (is (instance? kotlin.time.Duration d))
        (is (= "5s" (str d))))
      (let [c (Class/forName (bridge/bridge-class seconds))]
        (is (= 10000000000 (.invoke (.getMethod c "call" (into-array Class [(Class/forName "kotlin.time.Duration$Companion") Integer/TYPE]))
                                    nil (object-array [kotlin.time.Duration/Companion (int 5)]))))))
    (testing "installing a name that exists does not define it again (the bytes are not used)"
      (is (identical? (Class/forName n1) (bridge/install! n1 (byte-array 0)))))))

(defn- tmp-dir ^File [] (.toFile (Files/createTempDirectory "ckway-bridge" (make-array FileAttribute 0))))

(deftest storage-under-compile-path
  (let [dir (tmp-dir)
        target {:kind :static :class "fx.Uid" :name "box-impl" :desc "(J)Lfx/Uid;"}
        f (fn [] (binding [*compile-files* true *compile-path* (.getPath dir)] (bridge/bridge-class target)))]
    (testing "with *compile-files* the class file is written (again at each call, same bytes)"
      (let [n (f)
            file (io/file dir "ckway" "bridge" (str (last (str/split n #"\.")) ".class"))]
        (is (.exists file))
        (let [bytes1 (vec (Files/readAllBytes (.toPath file)))]
          (is (= n (f)))
          (is (= bytes1 (vec (Files/readAllBytes (.toPath file))))))))
    (testing "without *compile-files* nothing is written"
      (let [dir2 (tmp-dir)]
        (binding [*compile-path* (.getPath dir2)] (bridge/bridge-class unbox-uid))
        (is (not (.exists (io/file dir2 "ckway"))))))))

;; ---------------------------------------------------------------- AOT in a fresh JVM

(def ^:private demo-source
  "(ns aot.vdemo
  (:require [ckway.core :as kt]))

(kt/require '[fx :as f] '[kotlin.time :as t])

(defn run []
  (println (f/v (f/nextUid (f/Uid 5)))
           (f/pause)
           (t/inWholeMilliseconds (t/seconds t/Duration 5))))

(defn -main [& _] (run))
")

(defn- clj [env cp & args]
  (apply sh "env" (concat env ["clojure" "-Scp" cp] args)))

(deftest aot-compiled-value-class-calls-run-without-the-generator
  (let [root (tmp-dir)
        src (io/file root "src") classes (io/file root "classes")
        _ (.mkdirs (io/file src "aot")) _ (.mkdirs classes)
        _ (spit (io/file src "aot" "vdemo.clj") demo-source)
        base-cp (System/getProperty "java.class.path")
        compile-cp (str/join File/pathSeparator [base-cp (.getPath src) (.getPath classes)])
        run-cp (str/join File/pathSeparator [base-cp (.getPath classes)])
        compile! (fn [times]
                   (clj [] compile-cp "-M" "-e"
                        (str "(binding [*compile-path* \"" (.getPath classes) "\"] "
                             (str/join " " (repeat times "(compile 'aot.vdemo)")) ")")))
        bridge-files #(set (map (fn [^File f] (.getName f)) (.listFiles (io/file classes "ckway" "bridge"))))]
    (testing "compiling the same namespace twice in one JVM works"
      (let [c (compile! 2)]
        (is (zero? (:exit c)) (:err c))))
    (let [files (bridge-files)]
      (testing "the bridge classes are under the compile path"
        (is (every? #(contains? files (str (last (str/split (bridge/call-class-name %) #"\.")) ".class"))
                    [box-uid unbox-uid next-uid pause-default seconds box-duration]))
        (is (<= 8 (count (filter #(str/starts-with? % "C_") files)))))
      (testing "compiling in a second JVM, over the existing classes, works and writes the same files"
        (let [c (compile! 1)]
          (is (zero? (:exit c)) (:err c))
          (is (= files (bridge-files))))))
    (testing "a fresh JVM with only the compiled classes (no source of aot.vdemo) gives the right results"
      (is (not (str/includes? run-cp (.getPath src))))
      (let [log (io/file root "classload.txt")
            r (clj [(str "JAVA_OPTS=-Xlog:class+load:file=" (.getPath log))] run-cp "-M" "-m" "aot.vdemo")
            loaded (slurp log)]
        (is (zero? (:exit r)) (:err r))
        (is (= "6 1000 5000" (str/trim (:out r))))
        (testing "the bridges come from the class files, the generator was not loaded"
          (is (re-find #"ckway\.bridge\.C_fx_Uid_box_impl__116a9f967b source: file:\S*/classes/" loaded))
          (is (not (str/includes? loaded "ckway.bridge.gen"))))))))
