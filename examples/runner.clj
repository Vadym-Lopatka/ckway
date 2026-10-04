(ns examples.runner
  "Runs the example files. Two modes, each for one file, each in its own JVM (examples/run starts them):

    run FILE   reads FILE form by form in a clean namespace, evaluates each form, checks the markers
               that follow it, prints `FILE: N forms, M checks, K failed` and each failure.
    load FILE  `load-file` of the untouched FILE in this (fresh) JVM.

  Markers: a comment line right after a form.
    ;; => <value>     (pr-str of the result, compared as text)
    ;; prints: <text> (the form prints the text to stdout)
  A form without a marker is only evaluated: it must not throw.
  An error is shown as a value with `examples.util/err`, so every file loads whole."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader StringWriter]))

(defn- marker-lines
  "The marker comments right after line `end-line` (1-based): [[:=> text] [:prints text]]."
  [lines end-line]
  (->> (drop end-line lines)
       (map #(re-matches #"\s*;; (=>|prints:)\s?(.*)" %))
       (take-while some?)
       (map (fn [[_ k text]] [({"=>" :=> "prints:" :prints} k) (str/trim text)]))))

(defn- end-line
  "The line (1-based) where the form that `rdr` has just read ends. After a symbol or a number the reader has
  also read the newline behind it: the column is 0 then, and the form ended on the line before."
  [^LineNumberingPushbackReader rdr]
  (if (zero? (.getColumnNumber rdr)) (dec (.getLineNumber rdr)) (.getLineNumber rdr)))

(defn- messages [^Throwable t]
  (str/join "\n" (map #(str (ex-message %)) (take-while some? (iterate #(.getCause ^Throwable %) t)))))

(defn- evaluate
  "Evaluates `form` in `ns`. => {:value v :out text} or {:error t :out text}."
  [ns form]
  (let [out (StringWriter.)]
    (binding [*ns* ns *out* out]
      (try {:value (eval form) :out (str out)}
           (catch Throwable t {:error t :out (str out)})))))

(defn- check
  "The failures of one form: a vector of texts. One text for each marker that does not hold."
  [{:keys [value error out]} markers]
  (let [fails (atom [])]
    (doseq [[kind expected] markers]
      (case kind
        :=> (cond error (swap! fails conj (str "expected: " expected "\n    actual:   threw " (str/replace (messages error) "\n" " | ")))
                  (not= expected (binding [*print-length* nil *print-level* nil *print-namespace-maps* false] (pr-str value)))
                  (swap! fails conj (str "expected: " expected "\n    actual:   " (pr-str value))))
        :prints (when-not (str/includes? out expected)
                  (swap! fails conj (str "expected: prints " expected "\n    actual:   " (pr-str out))))))
    (when (and error (not (some #(= :=> (first %)) markers)))
      (swap! fails conj (str "expected: no error\n    actual:   threw " (str/replace (messages error) "\n" " | "))))
    @fails))

(defn run-file
  "=> {:forms n :checks n :failures [[line form-source text] ...]}"
  [file]
  (let [text (slurp file)
        lines (str/split-lines text)
        ns (create-ns (gensym "examples.runner.scratch"))
        _ (binding [*ns* ns] (refer-clojure))
        result (atom {:forms 0 :checks 0 :failures []})
        cur-ns (atom ns)
        on-form (fn [form end]
                  (when (and (seq? form) (= 'ns (first form)))
                    (remove-ns (second form)))
                  (let [markers (marker-lines lines end)
                        r (evaluate @cur-ns form)]
                    (when (and (seq? form) (= 'ns (first form))) (reset! cur-ns (the-ns (second form))))
                    (let [fails (check r markers)]
                      (swap! result #(-> %
                                         (update :forms inc)
                                         (update :checks + (count markers) (if (and (:error r) (empty? markers)) 1 0))
                                         (update :failures into (map (fn [f] [end form f]) fails)))))))]
    ;; the forms are read in the namespace that the `ns` form made
    (binding [*ns* ns]
      (let [rdr (LineNumberingPushbackReader. (StringReader. text))]
        (loop []
          (let [form (binding [*ns* @cur-ns] (read {:eof ::eof :read-cond :allow} rdr))]
            (when-not (= ::eof form)
              (on-form form (end-line rdr))
              (recur))))))
    @result))

(defn- one-line [form] (let [s (binding [*print-length* 12 *print-level* 6] (pr-str form))] (if (> (count s) 160) (str (subs s 0 160) " ...") s)))

(defn run-main [file]
  (let [{:keys [forms checks failures]} (run-file file)]
    (println (format "%s: %d forms, %d checks, %d failed" (.getName (io/file file)) forms checks (count failures)))
    (doseq [[line form text] failures]
      (println (format "  FAIL line %d: %s\n    %s" line (one-line form) text)))
    (zero? (count failures))))

;; ---------------------------------------------------------------- load mode

(defn load-main [file]
  (try
    (binding [*out* (StringWriter.)] (load-file file))
    (println (str (.getName (io/file file)) ": load-file ok"))
    true
    (catch Throwable t
      (println (str (.getName (io/file file)) ": load-file FAILED: " (str/replace (messages t) "\n" " | ")))
      false)))

(defn -main [mode file]
  (let [ok? (case mode "run" (run-main file) "load" (load-main file))]
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
