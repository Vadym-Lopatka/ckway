(ns spike.coflow.api-test
  "API parity with clojure.core.async.flow, the SPI, datafy, the licence header, and the ban on core.async machinery."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.datafy :as d]
            [clojure.core.async.flow :as orig]
            [clojure.core.async.flow.spi :as spi]
            [spike.coflow.flow :as port]
            [spike.coflow.takeover :as takeover]))

(deftest api-parity-ns-publics
  (let [o (ns-publics 'clojure.core.async.flow)
        p (ns-publics 'spike.coflow.flow)]
    (testing "same names"
      (is (= (set (keys o)) (set (keys p))))
      (is (= '#{create-flow start stop pause resume ping pause-proc resume-proc ping-proc inject process
                map->step lift*->step lift1->step futurize}
             (set (keys p)))))
    (doseq [k (sort (keys o))]
      (testing (str k)
        (let [mo (meta (o k)) mp (meta (p k))]
          (is (= (:arglists mo) (:arglists mp)) "arglists")
          (is (= (:doc mo) (:doc mp)) "docstring")
          (is (= (boolean (:macro mo)) (boolean (:macro mp))) "macro?")
          (is (= (fn? @(o k)) (fn? @(p k))) "fn?"))))
    (testing "ns docstring"
      (is (= (:doc (meta (the-ns 'clojure.core.async.flow))) (:doc (meta (the-ns 'spike.coflow.flow)))))
      (is (= (:author (meta (the-ns 'clojure.core.async.flow))) (:author (meta (the-ns 'spike.coflow.flow))))))))

(deftest spi-is-the-original-spi
  ;; the port implements the protocols of clojure.core.async.flow.spi and ...impl.graph themselves
  (let [g (port/create-flow {:procs {:a {:proc (port/process (port/lift1->step inc))}} :conns []})
        pr (port/process (port/lift1->step inc))]
    (is (satisfies? spi/ProcLauncher pr))
    (is (satisfies? clojure.core.async.flow.impl.graph/Graph g))
    (is (= (spi/describe pr) ((port/lift1->step inc))) "describe is the description of the step fn")
    (testing "a proc of the original runs in a flow of the port (and the other way round)"
      (let [op (orig/process (orig/lift1->step inc))
            g2 (port/create-flow {:procs {:a {:proc op}} :conns []})
            {:keys [report-chan]} (port/start g2)]
        (is (some? report-chan))
        (port/stop g2)))))

(deftest datafy-shape-same-keys
  (let [step (orig/map->step {:describe (fn [] {:ins {:in "i"}}) :transform (fn [s _ _] [s nil])})
        mk (fn [m] (m {:procs {:a {:proc (orig/process step)}} :conns []}))
        go (mk orig/create-flow)
        gp (mk port/create-flow)]
    (is (= (set (keys (d/datafy go))) (set (keys (d/datafy gp)))))
    (is (= (:chans (d/datafy go)) (:chans (d/datafy gp))))
    (is (= (:conns (d/datafy go)) (:conns (d/datafy gp))))
    (is (= (:execs (d/datafy go)) (:execs (d/datafy gp))))
    (is (= (set (keys (d/datafy (port/process step)))) (set (keys (d/datafy (orig/process step))))))))

(deftest takeover-restores-the-original-roots
  (let [before [(var-get #'orig/create-flow) (var-get #'orig/process) (var-get #'orig/futurize)]
        restore (takeover/install!)]
    (is (not= before [(var-get #'orig/create-flow) (var-get #'orig/process) (var-get #'orig/futurize)]))
    (restore)
    (is (= before [(var-get #'orig/create-flow) (var-get #'orig/process) (var-get #'orig/futurize)]))))

;; ---------------------------------------------------------------------------------------------------------------
;; src/ uses nothing of core.async but the protocols (boundary type) and the flow SPI namespaces

(defn- src-files []
  (->> (file-seq (io/file "src")) (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(defn- strip
  "source without comments and string literals (a docstring may talk about `<!!`)"
  [^String s]
  (-> s
      (str/replace #"\"(?:[^\"\\]++|\\.)*+\"" "\"\"")
      (str/replace #";[^\n]*" "")))

(def allowed-namespaces
  #{"clojure.core.async.impl.protocols"            ;; ReadPort, WritePort, Channel, Handler (the boundary type)
    "clojure.core.async.flow"                      ;; :as-alias, for the keywords; and the original's names in docstrings
    "clojure.core.async.flow.spi"                  ;; protocols ProcLauncher, Resolver
    "clojure.core.async.flow.impl.graph"           ;; protocol Graph
    "clojure.core.async.flow.impl.graph.Graph"})   ;; its interface, in `reify`

(deftest src-uses-no-core-async-machinery
  (let [files (src-files)]
    (is (seq files))
    (doseq [f files]
      (let [code (strip (slurp f))
            mentioned (set (re-seq #"clojure\.core\.async[A-Za-z0-9.\-]*" code))]
        (testing (.getName ^java.io.File f)
          (is (every? allowed-namespaces mentioned) (str "mentions " (remove allowed-namespaces mentioned)))
          ;; the names that a `:refer :all`/alias use of core.async would show
          (is (not (re-find #"\((?:a|async)/(?:chan|go|thread|alts!!?|<!!?|>!!?|timeout|mult|pipe|go-loop|io-thread)\b" code)))
          (is (not (re-find #"clojure\.core\.async\.impl\.(?:dispatch|channels|buffers|timers|ioc|mutex|exec)" code))))))))

(deftest src-has-the-epl-header-on-derived-files
  (doseq [n ["impl.clj" "flow.clj"]]
    (let [s (slurp (io/file "src/spike/coflow" n))]
      (is (str/includes? s "Eclipse Public License 1.0") n)
      (is (str/includes? s "Copyright (c) Rich Hickey and contributors") n)
      (is (str/includes? s "Derived work") n))))
