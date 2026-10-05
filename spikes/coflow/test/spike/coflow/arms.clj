(ns spike.coflow.arms
  "The arms of a differential test. The same user code (scenarios.clj) is compiled into one namespace per arm.

  :orig      flow -> clojure.core.async.flow                       (the oracle)
  :alias     flow -> spike.coflow.flow                             (the port, used by its own namespace)
  :takeover  flow -> clojure.core.async.flow, port installed behind it (spike.coflow.takeover)

  In all arms `fk` is clojure.core.async.flow, so `::fk/pause` is the same keyword everywhere."
  (:require [clojure.java.io :as io]
            [clojure.core.async.flow]
            [spike.coflow.flow]
            [spike.coflow.takeover :as takeover]))

(def arms [:orig :alias :takeover])

(def ^:private flow-ns {:orig 'clojure.core.async.flow
                        :alias 'spike.coflow.flow
                        :takeover 'clojure.core.async.flow})

(defn- arm-ns-sym [arm] (symbol (str "spike.coflow.scen." (name arm))))

(defn arm-ns
  "The namespace of an arm; compiles scenarios.clj into it on the first call."
  [arm]
  (let [sym (arm-ns-sym arm)]
    (or (find-ns sym)
        (locking arm-ns
          (or (find-ns sym)
              (let [n (create-ns sym)]
                (binding [*ns* n]
                  (refer-clojure)
                  (alias 'flow (flow-ns arm))
                  (alias 'fk 'clojure.core.async.flow)
                  (doseq [f ["scenarios.clj" "scenarios_inv.clj" "scenarios_ext.clj" "scenarios_exact.clj"]]
                    (with-open [r (io/reader (io/resource (str "spike/coflow/" f)))]
                      (clojure.lang.Compiler/load r (str "spike/coflow/" f) f))))
                n))))))

(defn call
  "Runs a function of the user code (a symbol of scenarios.clj) in an arm."
  [arm fsym & args]
  (let [n (arm-ns arm)
        f (var-get (ns-resolve n fsym))]
    (if (= arm :takeover)
      (takeover/with-port (apply f args))
      (apply f args))))

(defn scenario
  "Runs the named scenario of scenarios.clj in an arm."
  [arm k]
  (let [n (arm-ns arm)
        f (get (var-get (ns-resolve n 'scenarios)) k)]
    (if (= arm :takeover)
      (takeover/with-port (f))
      (f))))

(defn scenario-names []
  (sort (keys (var-get (ns-resolve (arm-ns :orig) 'scenarios)))))
