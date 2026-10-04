;; # 1. The first call
;;
;; Run this file with `examples/run examples/01_first_call.clj`, or send the forms to a REPL one by one.
;; The REPL needs the alias `:examples` (it has the compiled `shop` library; run `examples/build` once).
;; The Kotlin library of all examples is in `examples/kotlin/shop`.
(ns examples.01-first-call
  (:require [clojure.repl :refer [doc]]
            [clojure.string :as str]
            [examples.util :refer [err]]
            [kt.core :as kt]))

;; ## Make a namespace from a Kotlin package
;;
;; `kt/require` reads the Kotlin metadata of a package and makes one var for each name in it.
;; The alias works like a usual Clojure alias.

;; Kotlin: import shop.*
(kt/require '[shop :as s])

;; ## Call a top-level function
;;
;; A Kotlin function is a Clojure function with the same name. The arguments are in the same order.

;; Kotlin: welcome("Ann")
(s/welcome "Ann")
;; => "Welcome, Ann!"

;; Kotlin: welcome("Ann", "Hello")
(s/welcome "Ann" "Hello")
;; => "Hello, Ann!"

;; ## Read the Kotlin signature
;;
;; `doc` shows the Kotlin declaration. A default value is shown as `...`.

(doc s/welcome)
;; prints: fun welcome(name: String, greeting: String = ..., punct: String = ...): String

;; The var has the usual Clojure metadata.
(:arglists (meta #'s/welcome))
;; => ([name greeting punct])

;; ## A var is a function value
;;
;; You can pass the var to `map`, `apply`, `comp` and the other functions of Clojure.

(map s/welcome ["Ann" "Bob"])
;; => ("Welcome, Ann!" "Welcome, Bob!")

(apply s/welcome ["Ann" "Hi" "?"])
;; => "Hi, Ann?"

(fn? s/welcome)
;; => true

;; A var in the position of a value takes positional arguments only.
;; Use `partial` or `fn` when you need a fixed argument.
(map (partial s/welcome "Ann") ["Hi" "Hello"])
;; => ("Hi, Ann!" "Hello, Ann!")

;; ## A Kotlin constant and a top-level property
;;
;; A top-level property is a function without arguments. This is true for `const val` as well.

;; Kotlin: VERSION
(s/VERSION)
;; => "1.0"

;; Kotlin: taxPercent
(s/taxPercent)
;; => 20

;; ## Names are checked when your code compiles
;;
;; A wrong Kotlin name is an error before the code runs.

(err (s/welkome "Ann"))
;; => "No such var: s/welkome"

;; A missing argument is an error that shows the Kotlin declaration.
(err (s/welcome))
;; => "kt: (welcome): missing required parameter `name`. Pass it positionally or as `:name`."

;; An argument of the wrong type is an error as well. Kotlin has no implicit conversion from a number to a String.
(err (s/welcome 42))
;; => "kt: no Kotlin declaration of `welcome` fits (welcome 42)"

;; ## Java still works
;;
;; Plain Clojure Java interop is not changed. A Kotlin top-level function is a static method of a file class.
;; Only `kt` knows about defaults and named arguments.

(shop.FunctionsKt/welcome "Ann" "Hi" "!")
;; => "Hi, Ann!"
