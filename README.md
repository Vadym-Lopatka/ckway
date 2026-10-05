# ckway

ckway is the Clojure to Kotlin way. The library namespace is `ckway.core`. This document uses the alias `kt` for it.

Status: version 0.1.0, alpha. The API may change.

## What it is

`kt` lets Clojure call Kotlin code as if the code were Clojure.
A Kotlin package becomes a Clojure namespace, and each Kotlin function, property and class becomes a var.
The forms follow the Kotlin text, so you can copy a Kotlin line and change it by rule.

## Install

ckway is a git dependency. Add it to the `deps.edn` of your own project:

```edn
{:deps
 {io.github.vadym-lopatka/ckway {:git/url "https://github.com/Vadym-Lopatka/ckway"
                                 :git/tag "v0.1.0"
                                 :git/sha "4f215cc"}

  ;; Only if your Kotlin code uses kotlinx.coroutines (a `suspend` function that calls `delay`, a `Flow`, ...).
  org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm {:mvn/version "1.10.2"}}

 :aliases
 {;; Only to COMPILE a `:<>` call to an `inline reified` Kotlin function (rule 5).
  :kotlinc {:extra-deps {org.jetbrains.kotlin/kotlin-compiler-embeddable
                         {:mvn/version "2.4.20"
                          :exclusions [org.jetbrains.kotlin/kotlin-reflect]}}}}}
```

The library brings `org.jetbrains.kotlin/kotlin-stdlib` and `org.jetbrains.kotlin/kotlin-metadata-jvm` 2.4.20.
Your own build compiles your Kotlin code. The compiled classes (a directory or a jar) must be on the class path of the JVM that runs Clojure.

* **kotlinx-coroutines.** Add it when your Kotlin code uses it. Without it, `suspend` functions that use only `kotlin-stdlib` still work, but a Clojure function that runs as a suspend lambda cannot be cancelled ([`doc/limits.md`](doc/limits.md), 4 and 16).
  If you add the compiler (below), also keep this dependency at the top level: it replaces the old 1.8.0 that the compiler brings.
* **The Kotlin compiler (`:kotlinc`).** A call with `:<>` to an `inline reified` function makes `kt` write a short Kotlin source and compile it in the same JVM. Start that JVM with the alias: `clojure -M:kotlinc ...`.
  The compiler is not needed at run time when the bridge was compiled before: the per-user bridge cache keeps it, and AOT-compiled code carries it.
  So you can compile once with `:kotlinc` (for example `clojure -M:kotlinc -e "(compile 'my.app)"`) and run without it.
  A call without `:<>`, and a `reified` function that you do not call with `:<>`, never need the compiler.

In your namespace, require `ckway.core` as `kt`. Then call `kt/require` for each Kotlin package that you use (rule 1).

`kt` reads the Kotlin metadata of the compiled classes. It does not read the Kotlin source.

## Requirements

* **JDK 21 or newer.** A Clojure function that Kotlin runs as a `suspend` lambda runs on a virtual thread (JDK 21). Without that, `kt` stops with an error that says so. The code uses no other JDK 21 feature that we know of, but we ran the tests on JDK 21.0.8 and 25.0.4 only. We did not try an older JDK.
* **Clojure 1.12 or newer.** `kt` names a function that has a receiver with a leading dot (`s/.count`). Clojure 1.11 reads such a form as a Java method call and ignores the alias; the errors on 1.11.4 are `No matching method add found taking 1 args for class shop.Cart`. On Clojure 1.11.4, `ckway.core` loads and `kt/require` works, and a call of a function with no receiver works, but every call with a leading dot fails. Of the example files, only 01 and 10 pass on 1.11.4; the others fail. All of them pass on 1.12.0 and 1.12.1. So 1.11 is not supported.
* **Kotlin.** `kt` reads `@Metadata` with `kotlin-metadata-jvm` 2.4.20 in strict mode. It reads the classes of any Kotlin whose metadata version the reader knows. We read classes that were compiled by Kotlin 2.2.0, 2.4.0 and 2.4.20, by 2.4.20 with `-language-version 2.0`, and by 2.2.0 with `-language-version 1.9` (metadata version 1.9.0). A class that a newer Kotlin wrote fails the `kt/require` of its package with `kt: cannot read Kotlin metadata of ... (written by a newer Kotlin than kotlin-metadata-jvm? update the dependency)`. The reader accepts metadata up to one minor version above its own (the error of a 2.2.0 reader says `maximum supported version is 2.3.0`), so 2.4.20 should read the classes of Kotlin up to 2.5. We could not try it: no newer compiler exists here. When the error appears, put a newer `org.jetbrains.kotlin/kotlin-metadata-jvm` in your own `deps.edn`.
* **The `kotlinc` command is not a requirement of your project.** Only the tests and the examples of this repository run it ([Development](#development)). You use your own build tool to compile your Kotlin code.

## The rules

There are 11 rules and one note.
The snippets use the `shop` library of the examples. `tea`, `cake`, `cart` and `products` are defined in the example file that is named for each rule.

### 1. Namespace

`(kt/require '[package :as alias])` makes one namespace from one Kotlin package; each name the package declares, or that a class of the package inherits, is one var.

```clojure
;; Kotlin: import shop.*
(kt/require '[shop :as s])
```

Example 01.
`kt/require` of a package with no Kotlin class is an error.
A class that mentions a class that is not on the class path (an optional dependency) is skipped: the rest of the package works, and a call of what was skipped is a `kt:` error that names the missing class.

### 2. Name and call

A function or property is called as a Clojure function with its receivers first; the var name is the Kotlin name, with the prefix `.` only for a function that has a receiver.

```clojure
;; Kotlin: cart.count
(s/count cart)
;; => 3

;; Kotlin: cart.count("drink")
(s/.count cart "drink")
;; => 2

;; Kotlin: welcome("Ann")
(s/welcome "Ann")
;; => "Welcome, Ann!"
```

Examples 03 and 01. A property and a method can have the same name. The prefix `.` tells them apart.

### 3. Class names

A class var means what the bare class name means in Kotlin (call it to construct, give it as the receiver of companion members); an `object` var or enum-entry var holds the instance; a nested name keeps its dots.

```clojure
;; Kotlin: Cart("ann")
(str (s/Cart "ann"))
;; => "Cart(ann, 0 items)"

;; Kotlin: Money.of(3, 50)
(str (s/.of s/Money 3 50))
;; => "3.50"

;; Kotlin: Status.NEW
(str s/Status.NEW)
;; => "NEW"

;; Kotlin: Catalog.size
(s/size s/Catalog)
;; => 4
```

Examples 02 and 03.

### 4. Arguments

Positional arguments bind in sequence; a keyword literal names the parameter of the next argument and all arguments after it are named; a parameter with no argument takes its Kotlin default; a `vararg` takes the remaining positional arguments, or one collection when named.

```clojure
;; Kotlin: cart.add(coffee, quantity = 2)
(s/.add cart coffee :quantity 2)
;; => 2

;; Kotlin: joinLabel("a", "b", sep = "-")
(s/joinLabel "a" "b" :sep "-")
;; => "a-b"

;; Kotlin: joinLabel(*arrayOf("a", "b"), sep = "/")
(s/joinLabel :parts ["a" "b"] :sep "/")
;; => "a/b"
```

Example 02.
A var that you pass as a value (`(apply s/joinLabel xs)`, `(map s/welcome names)`) takes positional arguments only; a keyword in `xs` is an ordinary value. Named arguments need the written form.
In Kotlin you write a trailing lambda after the parentheses. `kt` has no such syntax: when you skip a defaulted parameter before a lambda, name the lambda (`(s/cart :build (fn [c] ...))`, example 06).

### 5. Type arguments

`:<>` gives the type arguments as one type form or a vector of them; a type form is a class symbol, the symbol with `?` for nullable, or `(Class type-form ...)` for a generic type; a call without `:<>` never selects a `reified` function.

```clojure
;; Kotlin: "42".parseAs<Int>()
(s/.parseAs "42" :<> Int)
;; => 42

;; Kotlin: typeName<Map<String, List<Int>>>()
(s/typeName :<> (Map String (List Int)))
;; => "Map<String, List<Int>>"

;; Kotlin: typeName<String?>()
(s/typeName :<> String?)
;; => "String?"
```

Example 09. This needs the Kotlin compiler in the JVM, or a stored bridge (see Install). The examples use the alias `:kotlinc` for it.
The default Kotlin names (`Int`, `String`, `Any`, `List`, `Map`, ...) are known without a `kt/require`. `Object` means `Any`.

### 6. Functions

A Clojure function goes where Kotlin wants a function type or a `fun interface`; a lambda receiver is the first parameter; a Kotlin function value that Clojure gets is a Clojure function.

```clojure
;; Kotlin: applyDiscount(Money(1000)) { it - Money(100) }
(str (s/applyDiscount (s/Money 1000) (fn [m] (s/.minus m (s/Money 100)))))
;; => "9.00"

;; Kotlin: buildText { append("Hello"); append(" world") }
(s/buildText (fn [sb] (.append sb "Hello") (.append sb " world")))
;; => "Hello world"

;; Kotlin: val fmt = priceFormatter("EUR "); fmt(Money(350))
(def fmt (s/priceFormatter "EUR "))
(fmt (s/Money 350))
;; => "EUR 3.50"
```

Example 06.
A Clojure function also goes where a Java interface with exactly one abstract method is expected (`Function`, `Predicate`, `Runnable`, `Comparator`, ...), as Kotlin converts a lambda there (example 14).

### 7. No guess

`kt` selects the declaration with the Kotlin overload rules and stops with an error if not exactly one fits; it adapts a value only to the declared Kotlin type of its parameter (number width, function type, value class) and changes no other data; `Unit` is `nil`.

```clojure
;; Kotlin: kind(1)    -- overloads for Int, Long and String
(s/kind 1)
;; => "Int"

;; Kotlin: kind(5_000_000_000)
(s/kind 5000000000)
;; => "Long"

;; Kotlin: welcome(42)    -- does not compile in Kotlin
(err (s/welcome 42))
;; => "kt: no Kotlin declaration of `welcome` fits (welcome 42)"
```

Examples 02 and 01. `err` is a helper of the examples (`examples/util.clj`): `(err form)` evaluates the form and returns the first line of the `kt:` error message as a string, so that an error can be shown as a value. A raw number is not a value class (`(s/.plus price 5)` is an error, example 07).

More about the choice and the numbers (example 14 shows them with the Kotlin standard library):

* An applicable member always wins over an extension, as in Kotlin.
* Among several applicable declarations `kt` picks the most specific one by Kotlin's rule (each parameter type a subtype of the other's, type arguments included: `List<T>` is not a subtype of `Collection<Int>`; `T` before `T?`; on a tie the one without a vararg, then the one that uses fewer defaults; when no candidate is the most specific, a function without type parameters before a generic one). If none is clearly most specific, the call is an error.
* `kt` changes a number only when the declared Kotlin type of the parameter says so (`Int`, `Short`, `Byte`, `Long`, `Float`, `Double`). At `Any`, `Any?`, `Number`, a type parameter or a vararg of those, every Clojure value is passed unchanged: a Clojure integer stays a `Long`, so `(c/listOf 1 2)` (`c` is `kotlin.collections`) holds `Long`s and `(c/.contains (c/listOf 1 2) 1)` is `true`. Only the choice between overloads that differ in a number type looks at a literal (`1` is an `Int` before it is a `Long`, as in Kotlin), and a conversion that `kt` makes itself (an integer for a `Double`) is used only when no overload takes the value as it is.
  Data that Kotlin code made with `Int` holds `Integer`s: say `(int 1)`, or give the type with `:<>` (`doc/limits.md`, 1).
* A type hint that you write decides between the candidates that it fits for sure, as the declared type of a variable does in Kotlin (`^String x` at `kind`). When it fits no candidate for sure but the value could still fit (the hint is an interface, or a non-final class that a parameter type extends: `^clojure.lang.IPersistentVector v` for a `List` receiver), it is only an upper bound and the call is checked at run time. A hint that can never fit (`^Long n` for a `List` receiver: `Long` is final) is a compile error. A hint that is wrong at run time is a `kt:` error, not a `ClassCastException`. A type that only the Clojure compiler inferred, and a type that `kt` itself puts on a local (the parameters of a `fn` literal at a Kotlin function type, the parameters of a `kt/reify` member), is an upper bound; `^Object` or no hint means unknown. Example 14 shows each case.
* Only a Clojure character is a Kotlin `Char`.
* A number outside the range of an `Int`, `Short` or `Byte` parameter is a `kt:` error that names the parameter.

### 8. Write a property

`(kt/set! read-form value)` writes a `var` property.

```clojure
;; Kotlin: cart.note = "gift"
(kt/set! (s/note cart) "gift")
;; => "gift"
```

Example 10. It works for members, top-level properties, extension properties and value class properties. A `val` is an error.

### 9. References

`(kt/ref X y)` is Kotlin `X::y`; `y` is `class`, a property name, or a function name with its `.`.

```clojure
;; Kotlin: products.map(Product::name)
(map (kt/ref s/Product name) [tea cake])
;; => ("Tea" "Cake")

;; Kotlin: className(Product::class)
(s/className (kt/ref s/Product class))
;; => "Product"
```

Example 10.

### 10. Implement an interface

`kt/reify` implements Kotlin interfaces with the shape of `reify` and rule-2 names.

```clojure
;; Kotlin: object : Rule { override fun applies(product: Product) = product.hasTag("food") }
(def food-rule
  (kt/reify s/Rule
    (.applies [this product] (s/.hasTag product "food"))))

(map s/name (filter #(s/.applies food-rule %) products))
;; => ("Cake")
```

Example 11. A property getter is `(name [this] ...)` and the setter is `(name [this value] ...)`. A member that you do not write keeps its Kotlin default.

### 11. Data

`(kt/data x)` gives a read-only map of the primary-constructor properties of `x`.

```clojure
;; Kotlin: mapOf("id" to tea.id, "name" to tea.name, "tags" to tea.tags)
(select-keys (kt/data tea) [:id :name :tags])
;; => {:id 1, :name "Tea", :tags ["drink" "hot"]}
```

Example 04.

### Note: suspend

A `suspend` function is a usual var; the call gives its result when it is ready; a Clojure function passed as a suspend lambda runs on a virtual thread.

```clojure
;; Kotlin: slowSum(1, 2)    -- a suspend function with delay(5)
(s/slowSum 1 2)
;; => 3

;; Kotlin: runBlocking { slowSum(1, 2) }
(co/runBlocking :block (fn [scope] (s/slowSum 1 2)))
;; => 3
```

Example 08.

## Simple examples

Each one comes from an example file. The file runs it and checks the result.

A first call (example 01):

```clojure
;; Kotlin: welcome("Ann", "Hello")
(s/welcome "Ann" "Hello")
;; => "Hello, Ann!"
```

Named arguments and defaults (example 02):

```clojure
;; Kotlin: Product(name = "Cake", price = Money(500), id = 3)
(str (s/Product :name "Cake" :price (s/Money 500) :id 3))
;; => "Product(id=3, name=Cake, price=5.00, tags=[])"
```

An extension function on a JDK type (example 05):

```clojure
;; Kotlin: "Hello Big World".slug(separator = "_")
(s/.slug "Hello Big World" :separator "_")
;; => "hello_big_world"
```

A data class as a map (example 04):

```clojure
(let [{:keys [id name]} (kt/data tea)]
  [id name])
;; => [1 "Tea"]
```

A value class (example 07):

```clojure
;; Kotlin: Money(350) + Money(150)
(str (s/.plus price (s/Money 150)))
;; => "5.00"
```

The builder DSL (example 06):

```clojure
;; Kotlin: cart("ann") { add(tea); add(cake, 2) }
(def ann-cart
  (s/cart "ann" (fn [c]
                  (s/.add c tea)
                  (s/.add c cake 2))))

(str ann-cart)
;; => "Cart(ann, 3 items)"
```

A flow of values (example 08):

```clojure
;; Kotlin: productFlow().collect { names += it.name }
(let [names (atom [])]
  (flow/.collect (s/productFlow) (fn [p] (swap! names conj (s/name p))))
  @names)
;; => ["Tea" "Coffee" "Cake" "Water"]
```

A good error (example 02):

```clojure
(err (s/welcome "Ann" :greetings "Hi"))
;; => "kt: (welcome \"Ann\" :greetings \"Hi\"): unknown parameter name `greetings`. Parameters: name, greeting, punct."
```

The Kotlin standard library is a Kotlin library too (example 14; `c` is `kotlin.collections`):

```clojure
;; Kotlin: listOf(1, 2, 3).map { it * 10 }
(c/.map (c/listOf 1 2 3) (fn [x] (* x 10)))
;; => [10 20 30]
```

## Examples

The Kotlin libraries of the examples are `examples/kotlin/shop` and `examples/kotlin/web` (a small web server, example 12).
Each file is a tutorial. Read it from top to bottom. The files go from simple to complex.

| File | What it shows |
|---|---|
| [`01_first_call.clj`](examples/01_first_call.clj) | `kt/require`, a top-level function, `doc`, a var as a function value |
| [`02_classes_and_arguments.clj`](examples/02_classes_and_arguments.clj) | Constructors, named and default arguments, `vararg`, number rules, `nil`, error messages |
| [`03_properties_objects_companions.clj`](examples/03_properties_objects_companions.clj) | Properties, `object`, companion members, enum entries, nested classes |
| [`04_data_classes.clj`](examples/04_data_classes.clj) | `copy`, `=`, `kt/data`, vars in `map`, `filter`, `sort-by`, `group-by` |
| [`05_extensions.clj`](examples/05_extensions.clj) | Extension functions and properties, other packages, member wins, context parameters |
| [`06_functions.clj`](examples/06_functions.clj) | Clojure functions as lambdas, lambda with receiver, builder DSL, `fun interface`, Kotlin function values |
| [`07_value_classes.clj`](examples/07_value_classes.clj) | `Money`, operators, `Duration`, `Result`, a raw number is not a value class |
| [`08_suspend.clj`](examples/08_suspend.clj) | `suspend` functions, suspend lambdas, `runBlocking`, `async`, cancellation, `Flow`, 1000 bodies |
| [`09_type_arguments.clj`](examples/09_type_arguments.clj) | `:<>`, nullable and generic types, the plain twin, `typeOf`, type errors (needs `:kotlinc`) |
| [`10_set_ref.clj`](examples/10_set_ref.clj) | `kt/set!` and `kt/ref` |
| [`11_reify.clj`](examples/11_reify.clj) | `kt/reify`: properties, defaults, suspend member, overload hints, two interfaces |
| [`12_web_server.clj`](examples/12_web_server.clj) | A web server written in Kotlin (`web`), started, called with an HTTP client and stopped (needs `:kotlinc`) |
| [`13_everything_together.clj`](examples/13_everything_together.clj) | One small program that uses most features (needs `:kotlinc`) |
| [`14_kotlin_stdlib.clj`](examples/14_kotlin_stdlib.clj) | The Kotlin standard library as a Kotlin library: collections, text, `require`, `runCatching`, integer literals, a Java functional interface, erased overloads |

### Check all examples

Run the commands in the root directory of this repository (they need the tools of [Development](#development)).

```sh
examples/run
```

The command builds the Kotlin library, then checks each file in two JVMs.
The first JVM reads the file form by form, evaluates each form, and compares the result with the comment under the form.
The second JVM runs a plain `load-file` of the file in a fresh state.
To check one file, give its name: `examples/run examples/03_properties_objects_companions.clj`.
The exit code is 0 only if everything passed.

The comments under a form are the checks:

* `;; => value` is the printed result of the form.
* `;; prints: text` means the form prints the text.

An example shows an error as a value: `(err form)` from `examples/util.clj` returns the first line of the error message.
So no form of an example file throws, and each file loads whole.

### Start a REPL

Build the Kotlin library once. Then start a REPL with the aliases that the file needs.
A line `;; Aliases:` near the top of a file names the extra aliases. Without that line, `:examples` is enough.

```sh
examples/build
clojure -M:examples -r                  # examples 01 to 08, 10, 11 and 14
clojure -M:examples:kotlinc -r          # examples 09, 12 and 13
```

Load a whole file. `load-file` works for every example file:

```text
user=> (load-file "examples/03_properties_objects_companions.clj")
```

Or send the forms of a file one by one from your editor. To send a whole file as input, run for example
`clojure -M:examples -r < examples/01_first_call.clj`.

## How it works

1. `kt/require` reads the Kotlin metadata (`@Metadata`) of the classes of a package at run time and makes one var for each name.
2. Each var carries its declarations as metadata and is a function with an `:inline` expansion.
3. When your code compiles, the expansion sees the argument forms. It selects the declaration with the Kotlin overload rules and writes a direct JVM call (the static path). There is no reflection.
4. If the receiver type is not known (for example a value from a `def`), the call takes the dynamic path. The same rules select the declaration at run time, and a cache keeps the result.
5. A Kotlin member that the JVM hides (a value class parameter, a default value, an inline function, a method of a multi-file facade part) is called through a small generated bridge class. The bridge holds the MethodHandle of the member in a `static final` field and calls it with `invokeExact`, so the call is not slower than a direct one by more than the work of the member (about 9 ns for `(tx/.uppercase "abc")`, which is a hidden `inline` function). Only a constructor of a class that is not public, and a class that cannot be linked completely, use a MethodHandle that is looked up at run time.
6. For an `inline reified` function, `kt` writes a short Kotlin source, compiles it with the Kotlin compiler in the JVM, and caches the class. The bridge cache is a per-user directory (`$XDG_CACHE_HOME/ckway`, else `~/.cache/ckway`; `-Dckway.cache.dir` overrides it, and an empty value turns it off); `kt` works only in its own subdirectory `bridges-v1` of it and deletes only what it made there. Each cached class is checked against a SHA-256 hash before it is loaded (details: [`doc/limits.md`](doc/limits.md), 15).
7. A Clojure function that goes where Kotlin wants a function type or a `fun interface` is wrapped in an adapter class. A `suspend` call waits for its result, and a suspend lambda runs on a virtual thread with the coroutine context.
8. Errors come from the same place: they show the Kotlin declaration and say which argument does not fit.

## Limits

The full list is in [`doc/limits.md`](doc/limits.md). The five most important:

1. At a generic position (`T`, `Any`, `Number`) a Clojure integer is a `Long`. Data that Kotlin code made with `Int` holds `Integer`s, so a lookup in it needs `(int 2)`, or the type with `:<>`.
2. `kt/require` runs the `init` block of every `object` and every enum class of the package, so a failing initializer shows up when you call something that uses the object.
3. A Clojure function that runs as a suspend lambda has its own virtual thread. A `ThreadLocal` is not visible in it, and `(catch Exception ...)` also catches the interrupt of a cancel.
4. `:<>` needs the static path and, for `reified` functions, the Kotlin compiler in the JVM (alias `:kotlinc`). Reified properties are not supported.
5. `kt/reify` takes interfaces only. `kt/ref` refuses bound references (except to an `object`), and references to `suspend` and `reified` functions.

## Development

Run the commands in the root directory of this repository.

```sh
bin/test        # builds the Kotlin test fixtures, then runs all tests
examples/run    # builds the Kotlin libraries of the examples, then checks every example file
```

Both commands exit with 0 only if everything passed.
They need:

* `kotlinc` 2.4.x on the `PATH` (the tests and the examples compile Kotlin code with it),
* the Clojure CLI (`clojure`),
* JDK 21 or newer,
* network access on the first run: Maven Central for the dependencies, and GitHub for the test runner (a git dependency).

The tests and the examples never touch your own bridge cache: the scripts point `XDG_CACHE_HOME` to a directory under `target/`.
Aliases in `deps.edn` (for development only; a consumer does not see them): `:test`, `:coro` (kotlinx-coroutines from Maven Central), `:coro-next` (the newest kotlinx-coroutines), `:kotlinc` (`kotlin-compiler-embeddable`), `:examples` (kotlinx-coroutines and the Kotlin libraries `shop` and `web` of the examples).

## License

MIT. See [`LICENSE`](LICENSE).
