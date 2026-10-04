# Known limits of kt

Each item was checked against the code or run. `hardening_test.clj` pins most of them with a test.

## 1. Number width at a generic position

Kotlin's `Int` is `java.lang.Integer`. A Clojure integer is a `Long`. At a parameter or result whose Kotlin type is a
type parameter (`T`, JVM `Object`), kt does not know the width and leaves the `Long` alone.

```clojure
(f/boxedT 1)        ; fun <T> boxedT(x: T) = (x as Int).toString()
;; ClassCastException: class java.lang.Long cannot be cast to class java.lang.Integer
;;   at fx.HardeningKt.boxedT(Hardening.kt:66)      <- the Kotlin frame is the first one
(f/boxedT (int 1))  ; the remedy: say the width yourself
```

kt converts when it knows the type: a call with `:<>` on a non-reified generic function (`(f/boxedT 1 :<> Int)`) converts
a `T` parameter and result, a `vararg T`, and function types and fun interfaces that mention `T`
(`(f/applyT 1 inc :<> Int)` gives Kotlin an `Integer` back from `inc`).

kt does NOT convert: the elements of a `List<T>`/`Map` that you pass, a call without `:<>`, a member of a generic
class (`Box<Int>.put(x)`), and the members of a `kt/reify` object of a generic interface (`kt/reify Visitor` returns a
`Long` for `Visitor<Int>`; `kt/reify` has no type-argument syntax). Use `(int x)`, `(long x)`, `(mapv int xs)`.

kt never rewrites the `ClassCastException`: it is the JVM's own, thrown inside the Kotlin function, on the static and
the dynamic path.

## 2. Objects are initialised by `kt/require`

The var of a Kotlin `object` holds the instance (so `k/Config` is the instance), and the instance cannot exist
before the class is initialised. So `kt/require` runs the `init` block of every `object` of the package (and of every
enum class: its entries are vars). Clojure vars have no hook on deref, so initialisation cannot be lazy without
making the var something other than the instance.

If an initialiser throws, `kt/require` still succeeds. The var holds a `kt.rt.FailedObject`. A kt call that gets it as
an argument (static or dynamic path) throws `kt: object `Bad` (fx.init.Bad) failed to initialise: ...` with the
original exception as the cause. The var is still a good type form (`:<> i/Bad`).

```clojure
(i/x i/Bad)   ; ex-info "kt: object `Bad` (fx.init.Bad) failed to initialise: java.lang.IllegalStateException: boom ..."
(.m i/Bad)    ; Clojure interop is not guarded: IllegalArgumentException, no matching field for kt.rt.FailedObject
(i/Bad)       ; compile error: `Bad` is an object, not a function
```

A second `kt/require` of the same package gives the same error and the same cause (kt remembers the first one; the JVM
only keeps its text).

## 3. Type aliases

* A `typealias` is a var. The var of an alias of a class is that class var (constructor, companion receiver,
  `kt/ref X class`, type forms). The members of the class stay in the package of the class, not in the package of the alias.
* An alias of a Java class (`typealias Headers = com.sun.net.httpserver.Headers`) calls the public constructors of the
  Java class, read by reflection. Parameter names are the class file's (`arg0`, ...), types are erased. A Java class
  that is not behind an alias has no var (kt reads Kotlin metadata): use `(ArrayList.)`.
* An alias of a function type or of a generic instantiation (`typealias Params = Map<String, String?>`) is for type
  forms and `doc` only. Calling it is a compile error: "type alias ..., not a class".
* Kotlin's own limit shows through: `typeOf<T>()` does not accept a `suspend` function type, so
  `(f/showType :<> f/SuspHandler)` is refused by the Kotlin compiler.
* The stdlib aliases (`ArrayList`, `HashMap`, `Exception`, ...) are default names in type forms. They are read from the
  metadata of the stdlib on the class path, not from a list.

## 4. Suspend bodies

A Clojure function that Kotlin runs as a `suspend` lambda runs on its own virtual thread (JDK 21+ needed).

* A plain `ThreadLocal` is not visible in the body. A `ThreadContextElement` of the coroutine context is.
  `(.set tl "x") (f/runIt (fn [_] (.get tl)))` gives `nil`.
* `set!` on a var that was bound OUTSIDE the body fails: `Can't set!: *v* from non-binding thread`. Reading it works,
  and a `binding` made inside the body can be `set!`.
* `(catch Exception ...)` also catches the `InterruptedException` that a cancel causes, so the body goes on. A job
  that sleeps in a swallowed interrupt and then 500 ms more completed 717 ms after the cancel, against 117 ms when the
  interrupt is passed on. Catch narrower types, or rethrow `InterruptedException`.
* Without kotlinx.coroutines on the class path a body has no cancellation and no `ThreadContextElement` (from the code,
  `kt.co`). With JDK 21 to 23, `synchronized` in a body pins the carrier thread (JEP 491 fixed this in 24; from the
  `kt.co` docstring, not measured here).

## 5. More than 20 parameters

* A Clojure function for a Kotlin function type with more than 20 parameters: compile error, "function types with more
  than 20 parameters is not supported yet".
* A Kotlin function value with more than 20 parameters works, but it is a `java.lang.reflect.Proxy`: a checked
  exception that it throws arrives as `UndeclaredThrowableException`. Up to 20 parameters it is a real class and the
  exception arrives as itself. (A suspend function value counts the continuation as a parameter.)
* `kt/reify`: a member with more than 20 parameters, `this` included, is refused.
* `kt/ref` of a function with more than 19 parameters is refused.

## 6. `kt/ref`

* A reference to a `suspend` function or to an `inline reified` function is a compile error.
* `(kt/ref Obj member)` for a Kotlin `object` is the bound reference that Kotlin makes (`KProperty0`, `KFunction` with
  no receiver parameter). Any other bound reference (`user::email` on a value or a local, an enum entry) is refused:
  write the type and pass the object when you call it.

* A function reference takes ALL parameters, as in Kotlin: the defaults are not applied. `(kt/ref f/greet)` is
  `(String, String, String) -> String`; `((kt/ref f/greet) "Bob")` is a `kt:` error that says it takes 3 arguments
  (`fixes_test`, F5). To use a default, write a Clojure `fn`.

## 7. `kt/data`

A constructor parameter counts as a property when the class has a public property of the same name and type. So
`class Shadowed(b: Int) { val b: Int = b * 2 }` looks like `class Shadowed(val b: Int)`: `(kt/data (f/Shadowed 2))` is
`{:b 4}`. The metadata has no link between a parameter and a property.

## 8. `kt/reify`

* Interfaces only (an abstract class is refused). There is no type-argument syntax: `T` stays `Object` (see 1).
* A Kotlin property and a Java method of the same name (`interface Runs { val run: Boolean }` and `Runnable.run`):
  write the member under the interface that declares it. Written under a sub-interface of both, `(run [this] ...)` is
  refused as ambiguous, and no type hint can choose.
* Annotations on a member: elements may be strings, numbers, booleans, chars and arrays of them. Enums, classes and nested
  annotations are refused.

* `kt/reify` compiles when members are missing and fails only when Kotlin calls the missing member (a member with a
  default body is inherited, so it is fine). The error is an `AbstractMethodError` that names it:

  ```clojure
  (f/.describe (kt/reify f/Shape2 (.area [this] 2.0)))   ; "shape:2.0"  (describe has a body)
  (f/.scale    (kt/reify f/Shape2 (.area [this] 2.0)) 2) ; AbstractMethodError: kt/reify: the abstract member
  ;;   `fun fx.Shape2.scale(k: Int = ...): fx.Shape2` was called, but the kt/reify form does not write it
  ```

## 9. `:<>` and the Kotlin compiler

* `inline reified` calls are compiled by the Kotlin compiler into a small bridge. The JVM that compiles needs the
  `:kotlinc` alias (`kotlin-compiler-embeddable`). Without it: an error that names the alias, unless the bridge is stored.
* A stored bridge needs no compiler: AOT-compiled code carries it (it is written to `*compile-path*`), and the disk
  cache `.kt-cache/` (or the directory in `-Dkt.cache.dir`; an empty value turns it off) keeps the others. A cache entry
  is keyed on the source, the Kotlin version and the class files it depends on.
* AOT-compiled code still runs `kt/require` at load time: it reads Kotlin metadata, so `kotlin-metadata-jvm` and the Kotlin
  classes must be on the run class path.
* `:<>` needs the static path. A receiver of unknown type with several candidates is a compile error ("add a type
  hint"); a var used as a value cannot take `:<>`.
* Reified properties (`inline val <reified T> T.name`) are not supported.
* Two context parameters of the same type on a reified function are refused: Kotlin binds a context argument by type, so
  both would get the same value (a hand-written `with(x) { with(y) { both<Int>() } }` returns `"y|y"`). A function that is
  not reified is called directly and binds each parameter.
* The `:kotlinc` alias brings `kotlinx-coroutines-core-jvm` 1.8.0 (the compiler fails with INTERNAL_ERROR without it).
  `clojure -Spath -A:kotlinc` shows that jar. If your application uses another version, make it a dependency of
  your own project (as `:coro` does with 1.10.2 in `deps.edn`): it replaces 1.8.0, and `clojure -Spath -A:coro:kotlinc`
  has only the 1.10.2 jar. The compiler runs with it.

## 10. Other declarations

* A constructor of an `inner` class: "inner-class constructors is not supported yet".
* A declaration without a JVM member, and a `fun interface` without exactly one abstract method, when a Clojure
  function has to be adapted to it (from the code).
* An `object` or an enum entry is a value, not a function: `(f/Registry)` is a compile error. `apply` on one is a
  `ClassCastException`.
* A class with no public constructor (`Duration`), an interface, an abstract, sealed or enum class: the error says
  which, and lists the entries of an enum or the companion functions that return the class.

## 11. Kotlin metadata

`kt` reads metadata with `kotlin-metadata-jvm` 2.4.20. A class written by a newer Kotlin than the reader fails the
`kt/require` of its package with "cannot read Kotlin metadata of ... (written by a newer Kotlin than
kotlin-metadata-jvm? update the dependency)" (from the code; not run, no newer Kotlin is installed here).
`@file:JvmPackageName` is not a limit: the Kotlin compiler refuses it in user code ("internal in file", and "not supported
for files with class declarations"), so only the stdlib uses it.

## 12. Clojure data that goes to a Kotlin API

A Clojure persistent map is a `java.util.Map` AND an `Iterable` (of `Map.Entry` objects). A Kotlin library that
checks `Iterable` before `Map` treats it as a collection of pairs. The JSON renderer of the `web` library of
the examples (`examples/kotlin/web/Body.kt`, example 12) does:

```clojure
(w/.render (w/JsonBody) {:a 1 :b 2})                        ; [[":a",1],[":b",2]]   a list of pairs
(w/.render (w/JsonBody) (java.util.HashMap. {"a" 1 "b" 2})) ; {"a":1,"b":2}
```

The keys are keywords there too (`":a"`). The remedy is to hand over a Java map with string keys
(`(java.util.HashMap. m)`) or a Kotlin data class (`kt/data` goes the other way: class to map). `(instance? Iterable m)`
and `(instance? java.util.Map m)` are both true for a map; for a vector only the first is. kt does not change the value:
a map is passed as it is.

## 13. A value in a `def` var has no static type

`(def c (f/Other))` does not tell the compiler what `c` holds: a var is global and can be rebound. So a kt call on
`c` is selected by name and arity. If one declaration fits, the static path casts `c` to its class and a wrong class is
a `kt:` error at run time (`wrong-class`, `fixes_test` F2). If several fit, the dynamic path decides by the run-time
class, with a reflection warning (`*warn-on-reflection*`). A type hint or a local gives a static type:

```clojure
(def c (f/Other))                (f/.count c)   ; dynamic path (Cart2, Kinds, Other all have a `count`)
(def ^fx.Other c (f/Other))      (f/.count c)   ; static
(let [c (f/Other)] (f/.count c))                ; static: the class var tells the type of `c`
```

A correct call on an untyped receiver costs one `instance?` check on top of the call. A wrong receiver says which
call, which Kotlin declaration was selected, the actual class and, if that class has a property or function of the same
name, how to write it: "`weigh` is a property of fx.Cart2: write (f/weigh cart)".

## 14. `fun interface` with a value class in its method

A `fun interface` whose method has a value class in its signature has a mangled JVM name (`adjust-3E1F-40`), which a
Clojure `reify` cannot define. A Clojure function passed there is adapted by a generated class (the class of `kt/reify`,
one per interface, written to the output under AOT). The function gets the boxed value-class object and its result is
unboxed. A `kt/reify` object or a Kotlin object that already implements the interface is passed on as it is.

The result of a Clojure function is checked against the Kotlin return type when the type is known and not generic
(a value class, `String`, a collection...): `nil` or a wrong class is a `kt:` error. The result of a `suspend` function
type or member is checked for a value class only; any other class there is passed on unchecked (it is `Object` on the JVM).
