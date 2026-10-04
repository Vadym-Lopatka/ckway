(ns examples.util
  "The one helper of the examples: `err` shows an error as a value, so that every example file loads whole.")

(defn err*
  "Evaluates `form` (with `eval`, in the current namespace, so compile-time errors are caught too).
  Returns the FIRST LINE of the message of the error that it throws:
  the outermost exception in the cause chain whose message starts with `kt:` or `kt/` (the error of kt);
  if there is none, the innermost exception (for example a Kotlin exception, or `No such var: ...`).
  If the form does not throw, it returns the text \"NO ERROR\"."
  [form]
  (try (eval form) "NO ERROR"
       (catch Throwable t
         (let [chain (take-while some? (iterate #(.getCause ^Throwable %) t))
               msg #(str (ex-message %))
               pick (or (first (filter #(re-find #"^kt[:/]" (msg %)) chain)) (last chain))]
           (first (clojure.string/split-lines (msg pick)))))))

(defmacro err
  "(err form) => the first line of the error message of `form`, as a string. See `err*`."
  [form]
  `(err* '~form))
