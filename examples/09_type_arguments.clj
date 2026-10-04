;; # 9. Type arguments
;;
;; A Kotlin `inline fun <reified T>` needs its type arguments at compile time. In Clojure you write them
;; with `:<>`. This needs the Kotlin compiler on the class path: start with the aliases `:examples:kotlinc`.
;; (A generic function that is not `reified` does not need the compiler.)
;; Aliases: :kotlinc
(ns examples.09-type-arguments
  (:require [examples.util :refer [err]]
            [ckway.core :as kt]))

(kt/require '[shop :as s]
            '[kotlin.reflect :as r])

;; ## One type argument
;;
;; Kotlin: `inline fun <reified T> typeName(): String = typeOf<T>().show()`.
;; After `:<>` comes one type form. A type form is a class symbol.

;; Kotlin: typeName<String>()
(s/typeName :<> String)
;; => "String"

;; Kotlin: typeName<Money>()    -- a class var of a required package
(s/typeName :<> s/Money)
;; => "Money"

;; Kotlin: typeName<java.util.UUID>()    -- a Java class
(s/typeName :<> java.util.UUID)
;; => "UUID"

;; A function that has a value parameter takes `:<>` after it.
;; Kotlin: "42".parseAs<Int>()
(s/.parseAs "42" :<> Int)
;; => 42

;; The type argument decides the Kotlin type of the result: Int is `Integer`, Long is `Long`.
(class (s/.parseAs "42" :<> Int))
;; => java.lang.Integer

(class (s/.parseAs "42" :<> Long))
;; => java.lang.Long

;; Kotlin: "x".parseAs<Int>()
(s/.parseAs "x" :<> Int)
;; => nil

;; Kotlin: "3.5".parseAs<Money>()
(str (s/.parseAs "3.5" :<> s/Money))
;; => "3.50"

;; ## Kotlin default names
;;
;; Without a `kt/require`, a type form knows the names that Kotlin imports by default:
;; `Int`, `Long`, `String`, `Any`, `List`, `Map`, `Pair` and the others. `Object` means `Any`.

;; Kotlin: typeName<Int>()
(s/typeName :<> Int)
;; => "Int"

;; Kotlin: typeName<Any>()
(s/typeName :<> Object)
;; => "Any"

;; ## Nullable types
;;
;; A `?` after the symbol makes the type nullable.

;; Kotlin: typeName<String?>()
(s/typeName :<> String?)
;; => "String?"

;; Kotlin: typeName<Any?>()
(s/typeName :<> Any?)
;; => "Any?"

;; Kotlin: describeAs<String?>(null)
(s/describeAs nil :<> String?)
;; => "String?:null"

;; ## Generic types
;;
;; A generic type is a list: the class, then the type arguments. Lists nest.

;; Kotlin: typeName<List<String>>()
(s/typeName :<> (List String))
;; => "List<String>"

;; Kotlin: typeName<List<String?>>()
(s/typeName :<> (List String?))
;; => "List<String?>"

;; Kotlin: typeName<Map<String, List<Int>>>()
(s/typeName :<> (Map String (List Int)))
;; => "Map<String, List<Int>>"

;; Kotlin: typeName<Pair<Money, List<Status>>>()
(s/typeName :<> (Pair s/Money (List s/Status)))
;; => "Pair<Money, List<Status>>"

;; Kotlin: typeName<List<*>>()
(s/typeName :<> (List *))
;; => "List<*>"

;; Kotlin: describeAs<List<Int>>(listOf(1, 2))
(s/describeAs [1 2] :<> (List Int))
;; => "List<Int>:[1 2]"

;; ## Several type arguments
;;
;; A vector gives several type arguments. A vector of one type is allowed.

;; Kotlin: mapTypes<String, List<Int>>()
(s/mapTypes :<> [String (List Int)])
;; => "String -> List<Int>"

;; Kotlin: typeName<String>()
(s/typeName :<> [String])
;; => "String"

;; ## The plain twin and the reified function
;;
;; `Settings` has two members with the name `get`: a plain `get(key): String?` and an
;; `inline fun <reified T> get(key): T?`. A call without `:<>` never selects the reified function.

(def settings (s/Settings {"port" "8080" "name" "shop"}))

;; Kotlin: settings.get("port")
(s/.get settings "port")
;; => "8080"

;; Kotlin: settings.get<Int>("port")
(s/.get settings "port" :<> Int)
;; => 8080

(class (s/.get settings "port" :<> Int))
;; => java.lang.Integer

;; Kotlin: settings.get<Int>("name")
(s/.get settings "name" :<> Int)
;; => nil

;; A reified function with no twin cannot be called without `:<>`.
(err (s/typeName))
;; => "kt: typeName is `inline reified`: it needs its type arguments, which only the Kotlin compiler can give it. Write them with `:<>`, e.g. `(typeName ... :<> Type)`; a type form is a literal, kt does not infer it."

;; ## A generic function that is not reified
;;
;; The type argument is optional. `:<>` tells `kt` the width of a number and checks the arguments.
;; Kotlin: `fun <T> firstOr(xs: List<T>, fallback: T): T`.

;; Kotlin: firstOr(emptyList(), 1)
(s/firstOr [] 1)
;; => 1

;; Kotlin: firstOr<Int>(emptyList(), 1)    -- the fallback is an `Integer` now
(class (s/firstOr [] 1 :<> Int))
;; => java.lang.Integer

;; Kotlin: firstOr<Int>(listOf(5), 1)
(s/firstOr [5] 1 :<> Int)
;; => 5

;; Kotlin: pairUp<Int, String>(1, "a")
(str (s/pairUp 1 "a" :<> [Int String]))
;; => "(1, a)"

;; ## typeOf and KType parameters
;;
;; Kotlin's `typeOf<T>()` is a reified function of the package `kotlin.reflect`.
;; It gives a `KType` for a function that takes one: `fun typeToString(type: KType): String`.

;; Kotlin: typeToString(typeOf<List<String?>>())
(s/typeToString (r/typeOf :<> (List String?)))
;; => "List<String?>"

;; Kotlin: typeToString(typeOf<Map<String, Any?>>())
(s/typeToString (r/typeOf :<> (Map String Any?)))
;; => "Map<String, Any?>"

;; ## Errors
;;
;; The errors tell what is wrong. A name that is not a type:

(err (s/typeName :<> Foo))
;; => "kt: unknown type name `Foo` in a type form. A type form is a class symbol: a kt class var (`users/User`), a Kotlin default name (`Int`, `List`, `Any`), or a class that Clojure resolves (`java.util.UUID`); `?` after it makes it nullable. A type form is a literal: it is never evaluated."

;; A generic class without type arguments (Kotlin has no raw types):

(err (s/typeName :<> List))
;; => "kt: type form List: `kotlin.collections.List` has 1 type parameter, but the form gives 0 type arguments (Kotlin has no raw types: write e.g. `(List String)`)"

;; Too many type arguments for the class:

(err (s/typeName :<> (List Int Int)))
;; => "kt: type form (List Int Int): `kotlin.collections.List` has 1 type parameter, but the form gives 2 type arguments"

;; A wrong number of type arguments for the function:

(err (s/typeName :<> [String Int]))
;; => "kt: `:<>` gives 2 type arguments to typeName, but no declaration has 2 type parameters. Kotlin declares:"

;; A string is not a type form. A type form is a literal, never an evaluated expression:

(err (s/typeName :<> "String"))
;; => "kt: \"String\" is not a type form: a type form is a class symbol, a class symbol with `?`, or a list `(Class type-form ...)`. A type form is a literal: it is never evaluated."

;; A type that breaks the bound of the type parameter. `numberType` needs `T : Number`.

;; Kotlin: numberType<Int>()
(s/numberType :<> Int)
;; => "Int"

;; Kotlin: numberType<String>()
(err (s/numberType :<> String))
;; => "kt: the Kotlin compiler rejected (s/numberType :<> String)"

;; The argument must fit the type that you gave:

(err (s/describeAs 5 :<> String))
;; => "kt: (s/describeAs 5 :<> String): `x` is String but got Long"
