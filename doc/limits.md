# Known limits of kt

Each item was checked against the code or run. `hardening_test.clj` pins most of them with a test.

## 1. Number width at a generic position

Kotlin's `Int` is `java.lang.Integer`. A Clojure integer is a `Long`.
`kt` changes a number only when the DECLARED Kotlin type of the parameter says so (`Int`, `Short`, `Byte`, `Long`,
`Float`, `Double`). At `Any`, `Any?`, `Number`, a type parameter `T` or a `vararg` of those, every Clojure value goes on
unchanged: a Clojure integer is a `Long`, a literal or not. The static path, the dynamic path and a var used as a value
do the same. (Only the choice between overloads that differ in a number type looks at a literal: `1` picks `Int` before
`Long`, as in Kotlin.)

So a collection that you build in Clojure works with Clojure integers:

```clojure
(map class (c/listOf 1 2))            ; (java.lang.Long java.lang.Long)
(c/.contains (c/listOf 1 2 3) 2)      ; true
(c/.minus (c/listOf 1 2 3) 2)         ; [1 3]
(let [f c/listOf] (map class (f 1 2))) ; (java.lang.Long java.lang.Long)
```

Data that Kotlin code made with `Int` holds `Integer`s, and a `Long` is not equal to an `Integer`. A lookup in such data with
a Clojure integer finds nothing: say the width with `(int x)`, or give the type with `:<>` (the receiver needs a
known type, so that the call is selected at compile time):

```clojure
(def by-length (c/.associateBy (c/listOf "a" "bb") (fn [s] (count s))))   ; Kotlin Map<Int, String>: the keys are Integers
(c/.get by-length 1)                  ; nil       the 1 is a Long
(c/.get by-length (int 1))            ; "a"
(c/.get by-length 1 :<> [Int String]) ; "a"       `:<>` converts the T parameter
(contains? by-length 1)               ; false
(def kotlin-ints (c/.map (t/.split "1,2,3" ",") (fn [s] (t/.toInt s))))   ; Kotlin List<Int>
(c/.contains kotlin-ints 2)           ; false
(c/.contains kotlin-ints (int 2))     ; true
```

A Kotlin function that casts a `T` to `Int` gets the `Long` and fails in Kotlin code:

```clojure
(f/boxedT 1)             ; fun <T> boxedT(x: T) = (x as Int).toString()
;; ClassCastException: class java.lang.Long cannot be cast to class java.lang.Integer
;;   at fx.HardeningKt.boxedT(Hardening.kt:66)      <- the Kotlin frame is the first one
(f/boxedT (int 1))       ; "1"   the remedy: say the width yourself
(f/boxedT 1 :<> Int)     ; "1"
```

`kt` converts when it knows the type: a call with `:<>` on a non-reified generic function (`(f/boxedT 1 :<> Int)`) converts
a `T` parameter and result, a `vararg T`, and function types and fun interfaces that mention `T`
(`(f/applyT 1 inc :<> Int)` gives Kotlin an `Integer` back from `inc`).

`kt` does NOT convert: an integer at `Any`, `Number` or a `T` without `:<>`, the elements of a `List<T>`/`Map` that you
pass, a member of a generic class (`Box<Int>.put(x)`), and the members of a `kt/reify` object of a generic interface
(`kt/reify Visitor` returns a `Long` for `Visitor<Int>`; `kt/reify` has no type-argument syntax). Use `(int x)`,
`(long x)`, `(mapv int xs)`.

`kt` never rewrites the `ClassCastException`: it is the JVM's own, thrown inside the Kotlin function, on the static and
the dynamic path.

## 2. Objects are initialised by `kt/require`

The var of a Kotlin `object` holds the instance (so `k/Config` is the instance), and the instance cannot exist
before the class is initialised. So `kt/require` runs the `init` block of every `object` of the package (and of every
enum class: its entries are vars). Clojure vars have no hook on deref, so initialisation cannot be lazy without
making the var something other than the instance.

If an initialiser throws, `kt/require` still succeeds. The var holds a `ckway.rt.FailedObject`. A kt call that gets it as
an argument (static or dynamic path) throws `kt: object `Bad` (fx.init.Bad) failed to initialise: ...` with the
original exception as the cause. The var is still a good type form (`:<> i/Bad`).

```clojure
(i/x i/Bad)   ; ex-info "kt: object `Bad` (fx.init.Bad) failed to initialise: java.lang.IllegalStateException: boom ..."
(.m i/Bad)    ; Clojure interop is not guarded: IllegalArgumentException, no matching field for ckway.rt.FailedObject
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
  `ckway.co`), and a top-level suspend call has no `Job`; an interrupt of a thread that waits for such a call ends the
  wait with `InterruptedException` while the Kotlin call goes on (see 16). With JDK 21 to 23, `synchronized` in a body pins the carrier thread (JEP 491 fixed this in 24; from the
  `ckway.co` docstring, not measured here).
* A suspend call that ignores the cancellation of the body (`withContext(NonCancellable) { delay(300) }`) returns its
  value to the body, as in Kotlin. kt then sets the interrupt flag of the body's thread again, so the next blocking
  operation (`Thread/sleep`, a lock) fails at once with the `CancellationException` of the cancel, and the next suspend
  call sees the cancelled `Job`. In a `withTimeout(100)`: Kotlin's own body that suspends again after such a callee ends
  at 308 ms; the Clojure body with `(Thread/sleep 3000)` after it ends at 303 ms (`round3b_test`, M2). Before, the
  interrupt was swallowed by the wait and the sleep ran its 3000 ms.

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
  An interface that specialises a generic one (`interface StrVis : Vis<String> { override fun visit(x: String): String }`)
  is fine: write `(.visit [this x] ...)` once. The class also has the JVM bridge method `visit(Object)Object` (as
  kotlinc makes), so Kotlin code that holds the object as `Vis<String>` works. The erased twin is not offered
  as a second candidate, so no type hint is needed.
  The same holds for an override that narrows only the result (`interface GStr : GSrc<String> { override fun get(): String }`,
  `override val name: String` over `val name: Any`), for a suspend member, for a value class in the signature
  (`override fun id(): Uid` over `fun id(): Any`: the bridge unboxes and boxes), over any number of levels and when two
  super-interfaces declare the same member (`R3Both : R3Left, R3Right`). Each Kotlin member is ONE writable member, the
  most specific one; the class gets that JVM method and a bridge method for every JVM signature it overrides.
  kt reads the declarations of the interface itself from its Kotlin metadata (`ckway.meta/own-declarations`, public),
  because `ckway.meta/class-members` lists an override of the same parameter types only when its result type differs
  from the original's: `override fun get(): String` over `fun get(): Any` is the member that a caller sees (its result is a
  `String`; before, the original hid it). Overloads that differ only in `Int` and `Int?` (`f(Int?)`, `f(Int)`: JVM `Integer` and `int`) are
  two members: write them with the hints `^Integer` and `^int` (a hint that is exactly the JVM class of the parameter is
  the exact fit; `^Integer` alone for an `Int` still fits when no other overload does).
* One member that two interfaces narrow in different ways (`interface Src { fun get(): Any }`, `S1 : Src { override fun get():
  CharSequence }`, `S2 : Src { override fun get(): Comparable<*> }`): `(kt/reify S1 S2 (.get [this] "ab"))` implements the JVM
  method of each leaf (`S1.get()`, `S2.get()`) and of the original (`Src.get()`, which calls the first leaf): one
  written form serves every leaf override that takes the same parameter types. Its result is checked against the result type
  of each leaf when that leaf is called (a `kt:` error "expected CharSequence ... got ..."). A member that you write under
  each interface is used as written. If the leaves take different parameter types (`G1 : G<String>`, `G2 : G<Int>`) one body
  cannot serve both: a compile error that says to write the member under each interface.
  The leaves are matched by JVM name and JVM parameter types, not by the written name, so a Java leaf (`CharSequence get();`,
  written `get`) and a Kotlin leaf (`.get`) of one member are served by one form, also when the Java interface does not extend
  the Kotlin one (an abstract method of the same JVM name and parameters that differs only in the result type).
* The value that a member returns is adapted to the declared return type as an argument is adapted to a parameter: a
  function type (also `suspend` and with a receiver), a `fun interface`, a Java single-method interface (a Java member too;
  there `nil` is a value), number width, a value class, `Unit`. An object that already has the type passes as it is. A wrong
  value is a `kt:` error that names the member and the Kotlin type, at the point of return. A generic result (`T`, `Any`)
  is not adapted: nothing says what it must be, so a Clojure function returned there stays a Clojure function.
  An interface that extends a function type (`fun interface Filter : (Handler) -> Handler`) has the member `.invoke`
  with the types of that supertype (before, the member was the generic `invoke(P1): R` of `Function1`, and nothing
  was adapted); its parameter of a function type is a Clojure function that is also the original Kotlin `Function1`.
* Two unrelated interfaces with a default body for the same member are a compile error that names the member
  (the JVM would fail with `IncompatibleClassChangeError` at the call): write the member yourself.
* The class of a form is reused as long as it implements the current interface classes. When an interface is redefined
  (REPL `definterface`, a reloaded class) the next evaluation of the form defines a new class (`..._g1`, `_g2`...);
  objects made earlier keep implementing the old interface.
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
  compiler (`kotlin-compiler-embeddable`) on its class path. Without it: an error that gives the dependency to add to the
  `deps.edn` of your project (library, version of the Kotlin stdlib that kt runs with, and the exclusion of
  `kotlin-reflect`) and says that the compiler is needed only to compile a `:<>` call, not at run time, unless the bridge is stored.
  (The `:kotlinc` alias of this repository is only for its own tests; a project that uses ckway has no such alias.)
* A stored bridge needs no compiler: AOT-compiled code carries it (it is written to `*compile-path*`), and the
  per-user disk cache keeps the others (see 15). A cache entry is keyed on the source, the Kotlin versions and the
  class files it depends on.
* The bridge is compiled for the class-file version of the Kotlin declaration (its owner class and, for a multi-file
  facade, the part classes), at least `1.8`, never above the running JVM. So a bridge to a library built for
  Java 11 is a Java 11 class and runs on a JVM 11 (and on 21...), where a JDK 25 build would have written major
  version 69. `-Dckway.jvm-target=<n>` overrides it (`1.8`, `11`, `17`...). The bytecode bridges (`clojure.asm`) and
  the `kt/reify` classes are always version 52 (Java 8), like Clojure's own classes.
* AOT-compiled code still runs `kt/require` at load time: it reads Kotlin metadata, so `kotlin-metadata-jvm` and the Kotlin
  classes must be on the run class path.
* `:<>` needs the static path. A receiver of unknown type with several candidates is a compile error ("add a type
  hint"); a var used as a value cannot take `:<>`.
* Reified properties (`inline val <reified T> T.name`) are not supported.
* Two context parameters of the same type on a reified function are refused: Kotlin binds a context argument by type, so
  both would get the same value (a hand-written `with(x) { with(y) { both<Int>() } }` returns `"y|y"`). A function that is
  not reified is called directly and binds each parameter.
* `kotlin-compiler-embeddable` brings `kotlinx-coroutines-core-jvm` 1.8.0 (the compiler fails with INTERNAL_ERROR without it).
  In this repository `clojure -Spath -A:kotlinc` shows that jar. If your application uses another version, make it a
  dependency of your own project, at the top level (the README shows 1.10.2): it replaces 1.8.0, and `clojure -Spath -A:coro:kotlinc`
  has only the 1.10.2 jar. The compiler runs with it (the consumer project of the README check ran so).

## 10. Other declarations

* A constructor of an `inner` class: "inner-class constructors is not supported yet".
* A declaration without a JVM member, and a `fun interface` without exactly one abstract method, when a Clojure
  function has to be adapted to it (from the code).
* An `object` or an enum entry is a value, not a function: `(f/Registry)` is a compile error. `apply` on one is a
  `ClassCastException`. An `object` with an `operator fun invoke` (its own or inherited, or an extension of the same
  package) is callable as `(f/Registry 1)`; `(f/.invoke f/Registry 1)` works too. `apply` on the var is still an error.
* The call of a class var falls back to the `operator fun invoke` of the companion object when no constructor fits
  (rule 3). It reads the members of the companion of that class and the extensions on its `Companion` that the package
  of the class var declares. An `invoke` that the companion inherits from a supertype, and an extension `invoke` that
  another package declares, are not seen: write `(alias/.invoke alias/Name ...)`. A type alias of a class in another
  package does not see the companion `invoke` of its target either.
* A class that implements a Kotlin function type has the member `invoke` (rule 6) with the parameter and result types of
  that supertype. A type parameter of the class (`class Box<T> : (T) -> T`) is `Any?` there, a value class in the type
  arguments is the object itself in a parameter and `Any?` in the result (the JVM holds it boxed in a generic position),
  and a function type of more than 22 parameters gives no `invoke`. Kotlin picks the member by the static type of the
  receiver; `kt` sees only the class of the value, so the declaration of each class applies to the objects of that
  class. The object of a class that the package does not know (`RoutingHttpHandler` is in `org.http4k.routing`, the call
  is `(core/.invoke handler req)`; a Clojure `reify` of `Function1`; a Kotlin function value) gets the generic
  `invoke(p1: P1): R` of `Function1` (and `Function2`...), which the package has as soon as one of its classes implements
  a function type: nothing checks the types of the arguments (a position of a type parameter, see 1: a Clojure integer for an
  `Int` is a `Long` there, say `(int 1)`), and a result of a function type is not a Clojure function. A package with no class
  that implements a function type, and no `operator fun invoke`, has no `.invoke` var.
* A class with no public constructor (`Duration`), an interface, an abstract, sealed or enum class: the error says
  which, and lists the entries of an enum or the companion functions that return the class.
* A declaration that Kotlin source cannot call is no var: one that is not `public` (`internal`, also with
  `@PublishedApi`; `protected`; `private`), and one that Kotlin refuses because it is deprecated:
  `@Deprecated(level = DeprecationLevel.ERROR)` (a call is a compile error in Kotlin; the stdlib's
  `MutableList.sort(comparison)`, `String.toUpperCase()`, `appendln`) and `DeprecationLevel.HIDDEN`, also through
  `@DeprecatedSinceKotlin(errorSince = ..., hiddenSince = ...)` when the Kotlin of the stdlib on the class path has
  reached that version (the stdlib's old `maxBy` that returns `T?`; the one-parameter `Channel(capacity)` of
  kotlinx.coroutines is hidden by its level). A class with such a deprecation is no var either, with its members.
  The Kotlin metadata does not mark these declarations; `kt` reads the annotations from the class file. For a class
  that cannot reflect (19) that check is not made. `@JvmSynthetic`, `@SinceKotlin`, `@RequiresOptIn` and
  `@Deprecated` with the level WARNING ARE vars (Kotlin can call them). One difference from Kotlin is left: Kotlin
  still RESOLVES a call to an ERROR-level declaration and then refuses it, so `err(a: Int = 1)` (ERROR) hides
  `err(a: Int = 1, b: Int = 2)` for `err()` in Kotlin; `kt` does not see the first and calls the second.

## 11. Kotlin metadata

`kt` reads metadata with `kotlin-metadata-jvm` 2.4.20 (`readStrict`). Classes compiled by Kotlin 2.2.0, 2.4.0, 2.4.20,
2.4.20 with `-language-version 2.0`, and 2.2.0 with `-language-version 1.9` (metadata version 1.9.0) were read and called.
A class written by a newer Kotlin than the reader fails the `kt/require` of its package with "cannot read Kotlin
metadata of ... (written by a newer Kotlin than kotlin-metadata-jvm? update the dependency)"; the cause says
`Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0`. We saw this with a 2.2.0
reader on classes of Kotlin 2.4.0 (no Kotlin newer than 2.4.20 is installed here). The fix is a newer
`org.jetbrains.kotlin/kotlin-metadata-jvm` in your own `deps.edn`.
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

A set or a map is not a PREDICATE. `kt` adapts it to a function type when that is the only way to pass it (18), but it
does not apply Clojure's truthiness to the result: Kotlin wants a `Boolean`. `(kc/.filter xs #{1 2})` is "kt: expected a
Boolean where Kotlin expects a Boolean, got java.lang.Long" (the set returns the element), `(kc/.any xs #{2})` is "kt:
nil where Kotlin expects a non-null Boolean". Write `(fn [x] (contains? s x))`.

The choice between overloads sees the same two faces. `(kc/.toMap {1 2})` (`kc` is `kotlin.collections`) is ambiguous:
`Iterable<Pair<K, V>>.toMap()` and `Map<K, V>.toMap()` both take a Clojure map, and neither type is a subtype of the
other. Say which one you mean with a hint, `(kc/.toMap ^java.util.Map m)`, or pass a `java.util.HashMap`, which is no
`Iterable`.

## 13. A value in a `def` var has no static type

`(def c (f/Other))` does not tell the compiler what `c` holds: a var is global and can be rebound. So a kt call on
`c` is selected by name and arity. If one declaration fits, the static path casts `c` to its class and a wrong class is
a `kt:` error at run time (`wrong-class`, `fixes_test`). If several fit, the dynamic path decides by the run-time
class, with a reflection warning (`*warn-on-reflection*`). A type hint or a local gives a static type:

```clojure
(def c (f/Other))                (f/.count c)   ; dynamic path (Cart2, Kinds, Other all have a `count`)
(def ^fx.Other c (f/Other))      (f/.count c)   ; static
(let [c (f/Other)] (f/.count c))                ; static: the class var tells the type of `c`
```

A correct call on an untyped receiver costs one `instance?` check on top of the call. A wrong receiver says which
call, which Kotlin declaration was selected, the actual class and, if that class has a property or function of the same
name, how to write it: "`weigh` is a property of fx.Cart2: write (f/weigh cart)".

A type hint that you write is the static type of the argument, as the declared type of a variable is in Kotlin: with a member
`mu(x: String)` and an extension `B.mu(x: CharSequence)`, `(let [^CharSequence s "s"] (p/.mu b s))` calls the extension, and
`(let [s "s"] (p/.mu b s))` the member. There are three cases, each shown by an evaluated example (`examples/14_kotlin_stdlib.clj`):

```clojure
;; 1. The hint fits some candidates for sure: it decides between them (kind has overloads for Int, Long and String).
(let [^String x (identity "a")] (s/kind x))
;; => "String"

;; 2. It fits no candidate for sure, but the value could still fit (the hint is an interface, or a non-final class that a
;;    parameter type extends): it is only an upper bound, the call is checked at run time.
(let [^clojure.lang.IPersistentVector v [1 2 3]] (c/.first v))
;; => 1

;; 3. It can never fit (a final class that is unrelated to every candidate): a compile error.
(err (let [^Long n (identity 1)] (c/.first n)))
;; => "kt: no Kotlin declaration of `.first` fits (.first n)"
```

(In case 2 a value of the wrong class is a `kt:` error at run time, with the candidates.) A hint that the static path
trusts and that is wrong at run time is a `kt:` error too, never the JVM's `ClassCastException`: it names the call, the
parameter, the Kotlin declaration and the actual class. A correct call costs one `instance?` check.
A type that only the Clojure compiler inferred (a `loop` variable that is a `Number`, the element of a `doseq`) is an upper
bound: the class of the value decides at run time. `^Object` says nothing. One exception: `^Number` for an argument that
goes to an integer parameter (`Int`, `Long`...) is not a width, so the class of the value decides, as for an inferred type.

An upper bound also lets a MEMBER of the run-time class win over an extension on the static type: with `open class E1`,
`class E2 : E1() { fun show() }` and `fun E1.show()`, Kotlin's `val e: E1 = E2(); e.show()` is the extension, and
`(p/.show (p/e1))` (`fun e1(): E1`, the object is an `E2`) is the member; `(p/.show ^E1 (p/e1))` is the extension.

A type that the LIBRARY puts on a local is never a hint that you wrote, so it is an upper bound too: the parameters of a
`fn` literal that you pass at a Kotlin function type (the library tags them with the Kotlin parameter types so that nested
`kt` calls stay direct), and `this` and the parameters of a `kt/reify` member. With `interface Ev`, `class Click : Ev { fun pos() }`
and `fun eachEv(xs: List<Ev>, f: (Ev) -> String)`, `(p/eachEv xs (fn [e] (p/.pos e)))` works: `e` is an `Ev` for the
library, and the call on it is checked at run time, as it is for a local that Clojure inferred. Your own hint on such a
parameter, `(fn [^Click e] (p/handle e))`, is the static type, as everywhere.

Among candidates that all take the value as it is, the choice is the most specific one by Kotlin's rule (type arguments
included); how close a parameter type is to the class of the value does not choose. So a value that comes out of a nested
call (`(p/gl (c/listOf 1))`), a literal (`(p/gl [1])`), a local, a var used as a value and the dynamic path give the same
answer. Two candidates that are unrelated in Kotlin's rule (`vc(xs: MutableList<Int>)` and `vc(xs: Collection<String>)`)
are ambiguous for the result of `listOf` too, as for a vector.

## 14. `fun interface` with a value class in its method

A `fun interface` whose method has a value class in its signature has a mangled JVM name (`adjust-3E1F-40`), which a
Clojure `reify` cannot define. A Clojure function passed there is adapted by a generated class (the class of `kt/reify`,
one per interface, written to the output under AOT). The function gets the boxed value-class object and its result is
unboxed. A `kt/reify` object or a Kotlin object that already implements the interface is passed on as it is.

The result of a Clojure function is checked against the Kotlin return type when the type is known and not generic
(a value class, `String`, a collection...): `nil` or a wrong class is a `kt:` error. The result of a `suspend` function
type or member is checked for a value class only; any other class there is passed on unchecked (it is `Object` on the JVM).

## 15. The disk cache of the Kotlin bridges: location and security model

Where. `-Dckway.cache.dir=<dir>` selects the directory; an empty value turns the cache off. Without the property:
`$XDG_CACHE_HOME/ckway` (if set and absolute), `%LOCALAPPDATA%\ckway` on Windows, else `~/.cache/ckway`. It is never the
working directory: if the resolved default directory is not absolute (the JDK sets `user.home` to `?` for a user with no
passwd entry, common in containers), the cache is off.

The directory that is named (`<dir>`) is only the PARENT of the cache. kt works in its own subdirectory `<dir>/bridges-v1`,
which it creates itself, and it never reads, writes or deletes anything else in `<dir>`: you can name a directory that holds
other things (`target`, `~/.cache`, your home directory). kt creates what is missing with owner-only permissions
(`rwx------`) where the file system has POSIX permissions.

What the check requires. When they exist, BOTH `<dir>` and `<dir>/bridges-v1` must be, after symbolic links are resolved,
a directory that belongs to the current user and that neither group nor others can write; `<dir>/bridges-v1` must also not
be a symbolic link itself. Otherwise the cache is off. So a directory that you create must have mode 700 (or 755, 750...;
`mkdir -m 700`): a plain `mkdir -p` under `umask 002` makes it group-writable, and kt refuses it (`bin/test` and
`examples/run` create theirs with mode 700, or let kt create it). The owner is compared with the owner of a file that the
process creates (not with `user.name`, which is `?` for such a user). The directories ABOVE `<dir>` are not checked
(see below). For the default directory a failed check is silent (`-Dckway.debug=true` says why). For `-Dckway.cache.dir` it
is an explicit setting, so kt turns the cache off and prints ONE line to stderr (once per JVM), for example `ckway: the
bridge cache is OFF: the directory /tmp/c of -Dckway.cache.dir can be written by its group. Run `chmod 700 /tmp/c` ...`.

What an entry is. A directory `<bridge class>-<key>` with the class files and `entry.txt` (written last, the directory
is renamed into place, so a reader sees all of an entry or none, and two JVMs that write the same entry cannot mix it).
`entry.txt` has a SHA-256 of every class file. The key is a hash of the bridge source, the Kotlin stdlib version, the JVM
target, the SHA-256 of every class the call depends on (including the part classes of a multi-file facade, where the
inline bodies are) and the version of the Kotlin compiler that made the entry. A class file is defined only after
its SHA-256 matches. An entry that fails (hash, a class that cannot be defined or linked, a missing inner class, a
damaged `entry.txt`) is deleted and the bridge is compiled again. A directory of an entry with a missing or unparsable
`entry.txt` is damaged in the same way: a lookup deletes it, and a store replaces it (before, it stayed and the bridge was
compiled at every start). A cache directory that cannot be created or written
means "no cache": no error, the bridge is compiled every run. `-Dckway.debug=true` prints one line for each such case.
A JVM without the compiler on its class path cannot name the compiler version; it finds the entry by the rest of the key.

Pruning. Every store prunes, in `<dir>/bridges-v1` only: of one bridge name the 8 most recently used entries are kept (two
projects, or two branches, with different versions of one Kotlin library do not evict each other), an entry that was not
used for 90 days is deleted whatever its bridge, and a `.ckway-tmp-<uuid>` directory older than one hour (a JVM that was
killed while it wrote) is deleted. "Used" is the modification time of the entry directory; a hit sets it to now when it is
older than one hour. That does not touch the class files or `entry.txt`, so the atomic write and the hash check stay as they
were. The numbers are `keep-per-bridge`, `max-age-ms`, `stale-tmp-ms` and `touch-after-ms` in `ckway.bridge.cache`.

kt deletes only what it can prove that it created. A directory is deleted (by a prune, a failed verification or a repair)
only if (a) it lies directly inside `<dir>/bridges-v1` (the resolved parent is that directory), (b) it is a real directory,
not a symbolic link, (c) its name is exactly the name that kt generates, `ckway.bridge.<class>-<32 hex digits>` for an
entry or `.ckway-tmp-<uuid>` for a temporary directory, and (d) for an entry or a temporary directory, everything in it is a plain file named
`*.class` or `entry.txt`. A plain file, any other directory (also `.tmp-*` of another tool, or a `.ckway-tmp-` name that
is not a uuid), a directory with an entry name that holds anything else, and a symbolic link are never touched; a
delete never follows a symbolic link (a link inside an entry is removed as a link, its target stays). Entries that an
earlier layout wrote directly in `<dir>` are not read and not deleted.

When such a thing sits on the exact name of an entry that kt wants to write (a file, a link, or a directory with other
files), kt does not touch it and does not cache that bridge: every run compiles it again. kt prints ONE line to stderr
per JVM that names the path. A `.ckway-tmp-<uuid>` directory older than one hour that holds anything but class files and
`entry.txt` is not deleted either; kt prints one line per JVM that names it, and you delete it yourself.

What this protects against: a class file in the cache that is damaged, truncated, replaced by a stale or foreign
class, or edited without the matching hash; a cache that a different project or user left in the working directory
(it is not read); two JVMs that write at the same time.

What it does NOT protect against: someone who can write both the class file and `entry.txt` in the cache directory can
plant code that runs with your privileges, because the hash lives next to the class. That is why the default directory
is per user, owner-only, and refused if others can write it. A `ckway.cache.dir` that others can write (`/tmp`, a shared
build directory) is refused with a warning. The argument is this: a reader trusts `<dir>/bridges-v1` only while it and
`<dir>` belong to you and nobody else can write them; to swap the subdirectory someone must write `<dir>`, and a directory
that someone else made fails the owner check at the next use. What is not checked: the directories above `<dir>`. Someone
who can rename an ancestor of `<dir>` (an ancestor that others can write and that has no sticky bit) can replace the
whole tree; the replacement belongs to that someone, and fails the owner check at the next use, but a JVM that checked
just before the swap and reads just after it has a (very small) window. Name a directory under a parent that only you
can write. Do not share the cache between trust domains. If in doubt, turn the
cache off (`-Dckway.cache.dir=`) or AOT-compile the namespaces (the class files are then part of your build).

## 16. Waiting for a Kotlin suspend call: cancellation and interrupts

(`ckway.co`; the callee needs kotlinx.coroutines to be cancelled.)

* Inside a body (a Clojure function that Kotlin runs as a coroutine), a suspend call waits until the callee resumes it.
  When the Job of the body is cancelled, the callee gets the cancellation through the Job in its context and the body
  goes on only when the callee has finished (a `finally { withContext(NonCancellable) { ... } }` of the callee completes
  first), as in Kotlin. A callee that ignores cancellation keeps the body waiting. Code that is blocked in non-coroutine
  code (`Thread/sleep`, a lock) is still stopped by the interrupt that the cancellation sends.
  Any other interrupt of a body (not caused by its Job) ends the wait with `InterruptedException` and the callee keeps
  running: kt cannot cancel a call whose Job is the body's own.
  If the callee returns a value although the Job is cancelling (it ignored the cancellation), the body gets the value and
  the interrupt flag is set again (see 4).
* A suspend call from outside any body gets a Job of its own (`coroutineContext.job` works). If the waiting thread is
  interrupted, that Job is cancelled and the thread keeps waiting for the callee to finish its cancellation; then it gets
  `InterruptedException`, also when the callee returned a value. The interrupt flag is clear when the exception arrives
  (as for any `InterruptedException`), and the callee's late result is dropped. This is what `kotlinx.coroutines.runBlocking`
  does (1.10.2 and 1.11.0; `round3b_test` runs it): with a cancellable callee that has a slow `finally`, `runBlocking`
  threw `InterruptedException` after 412 ms, flag clear, after the cleanup (`cleaned=true`). With a callee that never
  resumes (`suspendCoroutine { }`) `runBlocking` stayed blocked for ever, in `TIMED_WAITING`, also after a second
  interrupt. kt differs there on purpose: the wait is bounded. It ends with `InterruptedException` when the grace time
  has passed (system property `ckway.interrupt.grace.ms`, read at each wait, default 5000 ms; a number that is not a number is 5000, a negative one is 0, so the exception comes as soon as the callee has been told to cancel, and one above 24 hours is 24 hours) or at a second interrupt of
  the thread, so `ExecutorService.shutdownNow` stops such a thread. The callee is not stopped by that (it ignored the
  cancellation); its late result is dropped. Measured with a grace of 400 ms: `InterruptedException` after 406 ms, flag clear.
  The in-body wait has no bound (a cancelled body waits for its callee: structured concurrency).
* The Job of a call (the one of a top-level call, or of a call from a child thread) is completed on every exit of the call:
  a normal return without suspension, a synchronous throw, a resume with a value or a failure, an interrupt, the grace
  time. One macro, `ckway.co/wait-for`, owns this for the static emission and the function-value path, and `ckway.co/call-suspend`
  for the dynamic path. A child thread of a body that makes a call that throws before it suspends (`@(future (p/boomNow))`)
  therefore does not keep the Job of the body active.
* The interrupt flag that is set again (above) is set only on the thread of the body itself, the one that the cancellation
  of the Job interrupts. A thread that only inherited the context of the body (`future`, `bound-fn`, a pool task started in
  the body) has no such thread: its flag is never set because of the cancellation. A suspend call that it makes is a call
  from outside a body, in the context of the body (its dispatcher and other elements): it gets a Job of its own, which is a
  child of the Job of the body. So (a) an interrupt of that thread cancels that Job, waits for the callee (at most the grace
  time) and ends with `InterruptedException` with the flag clear, as for any top-level call; (b) when the Job of the body is
  cancelled, the child Job is cancelled with it and the callee gets the cancellation (structured concurrency); the call
  ends as the callee ends it (a `CancellationException`, or the value of a callee that ignored the cancellation, with no
  flag). `ckway.co/*body-thread*` is the binding that tells the two threads apart.
* Without kotlinx.coroutines: no Job, no cancellation. An interrupt ends the wait with `InterruptedException` at once
  and the Kotlin call goes on.
* `catch Exception` in a body catches the `InterruptedException` of a cancel (see 4).
* A coroutine body resumes its continuation exactly once: on a value, an exception, an `Error`, a `ThreadContextElement` that
  throws in `updateThreadContext` or `restoreThreadContext` (the coroutine fails with that exception), or an interceptor
  that throws. `releaseInterceptedContinuation` is called after the resume. The interceptor gets a guard around the
  continuation that lets only the first resume through. If `interceptContinuation` throws, the continuation is resumed
  directly with that exception. If `resumeWith` of the intercepted continuation throws after the continuation ran (an
  unconfined interceptor whose completion fails), the exception goes to the uncaught exception handler and the
  continuation is NOT resumed again (`round3b_test`, M9: one completion, not two); if it throws before (a dispatcher that
  refuses the task), the continuation is resumed with that exception.

## 17. Compiler noise

The embedded Kotlin compiler would print JVM warnings to the stderr of your process on JDK 24 and later
(`sun.misc.Unsafe::invokeCleaner has been called by ...FastJarFileSystemKt`). kt compiles with
`-Xuse-fast-jar-file-system=false` and captures the compiler's own messages (they only appear in a `kt:` error). With that,
a compiling run printed nothing to stderr on JDK 25 / Kotlin 2.4.20 (`review_b_test`, B14). What remains is outside kt's
control: the JVM's own start-up messages for your flags, and a warning that a future compiler or JDK may print; the JVM option
`--sun-misc-unsafe-memory-access=allow` silences the Unsafe one for good, if a compiler version brings it back.

## 18. Erased overloads

`(c/.sum xs)`, `(c/.sumOf xs f)`, `(c/.maxOrNull xs)` and `(c/.flatMap xs f)` are several Kotlin declarations with the same
JVM parameter types: they differ in a type argument, in the parameter types of a lambda or in its result type, which the JVM
erases. `kt` does not guess. The error says which of these it is, and lists each candidate with its Java interop call:

```clojure
(c/.sum (c/listOf 1 2 3))
;; kt: (.sum (c/listOf 1 2 3)) is ambiguous. Candidates: ...
;;   fun collections.Iterable<Int>.sum(): Int
;;     ->  (kotlin.collections.CollectionsKt/sumOfInt x)    ; JVM descriptor (Ljava/lang/Iterable;)I
(kotlin.collections.CollectionsKt/sumOfInt (c/listOf 1 2 3))   ; 6
```

When the candidates differ in the parameter types of a lambda, the way out is a type hint on the parameters of the `fn`
literal (the error shows it first). Ktor has `status(vararg status: HttpStatusCode, handler: suspend (ApplicationCall,
HttpStatusCode) -> Unit)` and the same with `suspend (StatusContext, HttpStatusCode) -> Unit` (`@JvmName("statusWithContext")`):

```clojure
(sp/.status cfg code (fn [^io.ktor.server.application.ApplicationCall call status] ...))   ; the first one
(sp/.status cfg code (fn [^io.ktor.server.routing.StatusContext ctx status] ...))          ; the second
```

A hint fits the parameter type of a candidate when it is that class or a class that extends it (the lambda is then given a
value that it takes). A parameter without a hint, and a parameter type that the JVM does not tell (`Any`, a type
parameter), fit every candidate. If more than one candidate still fits, or none, the call stays ambiguous: a hint only
chooses where a call is ambiguous, it never turns a call into an error. Only a `fn` or `fn*` literal that you write
in the call has hints (not `#(...)`, not a function value), and only the static path looks at them: through a
receiver of unknown class the call is selected at run time, where the lambda has no hints. When the receiver has a type for
the compiler (a hint, a local, a parameter of a `fn` at a function type) the ambiguity is a compile error, not a run-time one:
all candidates are members of the same class, so no subclass can change the answer.

Type arguments count in the choice only where they can be seen. When two candidates are different Kotlin types, the type
arguments must fit as Kotlin's declaration-site variance says (`List<out E>`, `Collection<out E>`, `Map<K, out V>`;
`MutableList<E>` is invariant): `fun <T> gl(xs: List<T>)` and `fun gl(xs: Collection<Int>)` are unrelated (neither type is a
subtype of the other), so the non-generic one wins, as in Kotlin. A pair that this cannot decide is treated as
unrelated: then a function without type parameters is taken before a generic one (Kotlin does the same), or the
ambiguity error decides, never a guess. A
pair that takes the same Kotlin class and differs only in the type argument (`Map<String, Any>` and `Map<K, V>`) is
always an error: the value is a Clojure map, and the JVM erased what it holds.

A candidate wins only if it is at least as good as every other one on EVERY parameter. The closeness of a number to a
number parameter (an `Int` literal prefers `Int`) never hides a disadvantage on another parameter: with `mx(a: CharSequence,
b: Int)` and `mx(a: String, b: Long)`, `(p/mx "s" 1)` is ambiguous (kotlinc says so for `mx("s", 1)`). A var used as a value
has no literal: `(let [f p/mx] (f "s" 1))` passes a `Long`, which only the `Long` overload takes, as `mx("s", 1L)` in Kotlin.

The whole choice is Kotlin's (`ckway.resolve/most-specific`; `test/ckway/round6_test.clj` and `round7_test.clj` compare
155 calls with what `kotlinc` itself chooses, and 1052 calls of the overloaded extension functions of `kotlin.collections`,
`kotlin.sequences` and `kotlin.text`):

* Two candidates are compared by the argument that each parameter receives, wherever a named argument puts it. With
  `oo(a: Int, b: Long)` and `oo(b: Int, a: Short)`, `(p/oo :a 1 :b 2)` is ambiguous, as in Kotlin.
* For an integer literal Kotlin's order of the integer types is `Int` before `Long`, `Short` and `Byte`, and `Short` before
  `Byte`. `Long` against `Short` or `Byte` is ambiguous: `(p/n 1)` with `n(a: Short)` and `n(a: Long)` is an error, as
  `n(1)` is in Kotlin. A conversion of a number that `kt` makes itself has `kt`'s own order (a `BigInt` is a `Long` before
  it is an `Int`); Kotlin has no such call.
* `Any` and `String?` are unrelated (neither is a subtype of the other): `(p/r "x")` with `r(a: Any)` and `r(a: String?)`
  is ambiguous, as in Kotlin.
* The shape decides only between candidates whose parameter types are equally specific: the one without a `vararg`,
  then the one that uses FEWER defaults (`d(a: Int, b: Int = 0)` before `d(a: Int, b: Int = 0, c: Int = 0)` for `(p/d 1)`).
  With `s5(a: Long, b: String = "d")` and `s5(a: Int, b: CharSequence = "d", c: Int = 0)`, `(p/s5 1 "x")` is ambiguous (each
  is better on one parameter), though only the second leaves a default out.
* A Clojure function at a `fun interface` parameter is compared as the function type of its method (Kotlin: SAM
  conversion), by the number of parameters. Between a function type and a Kotlin `fun interface` that are equally
  specific, the function type is taken (`a3(f: (Int) -> Int)` before `a3(f: FI)`). A candidate that takes the
  function at a JAVA functional interface (`Comparator`, `IntUnaryOperator`) is used only when every candidate does,
  whatever the other parameters are: `a5(a: Any, f: FI)` is taken before `a5(a: String, f: IntUnaryOperator)`, as kotlinc does.
* Two generic candidates: the type parameters of the less specific one are inferred from the parameter types of the
  other, one binding for all parameters, within the declared bounds (`ckway.resolve`, `bind-var!`). So
  `MutableList<T>.removeAll(predicate)` is more specific than `MutableIterable<T>.removeAll(predicate)`, and
  `u5(a: MutableList<T>, b: MutableList<T>)` than `u5(a: MutableCollection<A>, b: MutableCollection<B>)`. What this cannot
  decide (a bound such as `T : Comparable<T>`) makes the two unrelated, never a guess.
* A value is passed as what it IS before it is passed as a function. A vector, a map, a set, a keyword and a var can all
  be called (`clojure.lang.IFn`), and `kt` adapts them to a function type when that is the only way
  (`(kc/.map xs {1 :a})`, `(let [k :name] (kc/.map xs k))`). That adapter is a conversion, like `kt`'s own conversion
  of a number: a candidate that needs it is used only when no candidate takes the values as they are. `(kc/.removeAll ml
  [1 2])` is `removeAll(elements: Collection<T>)`, not `removeAll(predicate)`. A Clojure function (`fn`) is a function:
  no conversion. (The result of the adapted value is checked as any function's: see 12 for a set as a predicate.)

An override and the member that it overrides are one member, whatever the JVM does with them: `override fun amt(): W`
(a value class: JVM name `amt-<hash>`) over `fun amt(): Any`, `override fun put(x: String)` in a `Box<String>` over
`put(x: T)`. `ckway.meta` records the Kotlin override relation on the declaration (`:overrides`); for a receiver whose
class overrides a member, the overridden declaration is no candidate. The call is the override's JVM method, with its
narrowed result type.

A member that a class has through two unrelated supertypes (`interface Q1 { fun run2(): String }`, `interface Q2 { fun
run2(): String }`, `class QC : Q1, Q2`) is one member: `(p/.run2 (p/QC))` is one virtual call. Members are the same when
they are both functions (or both properties) of one Kotlin name with the same written parameter types; `pm(x:
Int)` and `pm(x: Long)` stay two overloads, and so do members whose parameter types name a type parameter of their
class (`A<T>.f(x: T)`, `B<T>.f(x: T)`). A receiver that you hint as ONE of the interfaces can still leave out an argument
whose default the other interface declares (`interface W1 { fun wd(x: Int = 1) }`, `interface W2 { fun wd(x: Int) }`,
`class WC : W1, W2`: `(p/.wd ^W2 x)` is `"wd1"`); Kotlin refuses that for a `W2`. The hint is an upper bound there, and
the object decides.

Two candidates are ordered only on what can be seen. The JVM erases what a collection HOLDS, so a candidate whose
parameter asks something of it is never put before one that asks less, nor the other way round: the call is ambiguous,
and the error says so and lists the Java interop call of each candidate. That is the case for a type parameter with a
declared bound at a type-argument position (`fun <T : Number> u9(a: MutableList<T>)` and `fun <T> u9(a:
MutableCollection<T>)`: Kotlin takes the first for a list of numbers and the second for a list of strings, a
`java.util.ArrayList` does not show which; `<T : Comparable<T>>`, `<T : Any>` against `Collection<T?>` are the same) and
for one type variable at two parameters of which one is invariant (`lk(a: MutableList<T>, b: MutableList<T>)` against
`lk(a: MutableCollection<A>, b: MutableCollection<B>)`). The way out for a generic function is `:<>`: the type arguments
are then known, `(p/u9 xs :<> Int)` is the bounded one, and with `:<> String` it is no candidate (`String` is no
`Number`). Candidates that ask the SAME, or nothing, are ordered by their classes as before
(`MutableList<T>.removeAll(predicate)` before `MutableIterable<T>.removeAll(predicate)`). The type arguments of a value
are never known otherwise: not from a nested `kt` call either.
NOT covered: a CONCRETE type argument. `gl(xs: List<T>)` and `gl(xs: Collection<Int>)`: the non-generic one is taken
for every list (`(p/gl ["a"])` too, where Kotlin takes `List<T>`); so is `two(a: Collection<String>, b: Collection<Int>)`
before `two(a: List<T>, b: List<T>)`.

A Clojure persistent collection (vector, list, map, set, lazy seq) is READ-ONLY. It implements the `java.util`
interfaces, which are Kotlin's `Mutable*` types, but it cannot be changed. Taking it at a `MutableList`, `MutableMap`...
parameter is a conversion, like the function adapter: a candidate that takes it as a read-only type is used first
(`iv(x: MutableList<Int>)` / `iv(x: Iterable<Int>)`: `(p/iv [1])` is the `Iterable` one, as Kotlin's `iv(listOf(1))`;
`(kc/.asReversed [1 2 3])` is `List.asReversed`), when its parameter holds the same thing (`MutableList<Int>` gives way to
`Iterable<Int>`, not to `Collection<String>`: that stays ambiguous). When only a `Mutable*` candidate exists, the call
is made, and a change then throws `java.lang.UnsupportedOperationException` (`(kc/.removeAll [1 2 3] [1])`). A
`java.util.ArrayList` or `(kc/mutableListOf ...)` takes the `Mutable*` overload. The result of a `kt` call that Kotlin
declares as a read-only `List`, `Set`, `Map`, `Collection` or `Iterable` is read-only too where the call is written in
the argument (`(p/iv (p/readOnly))`); through a local, on the dynamic path and for a var used as a value only the class
of the object is known (`java.util.Arrays$ArrayList` for `listOf(1, 2)`), and that is a mutable list for the JVM.

A collection passed as a whole to a `vararg` (`:xs coll`) chooses between vararg overloads only when its elements can be
seen: a primitive or typed array, a vector literal of values of known classes, or, at run time, the elements themselves.
The run time looks at the elements of any list, vector, seq or set that has some: a candidate fits only if every element
fits its element type, and exactly one candidate must fit (`(r/routes :list [h])`, with `routes(vararg Pair<..>)` and
`routes(vararg RoutingHttpHandler)`). The selection is kept per class of the elements, so another call with other
elements chooses again. An empty collection, or elements that fit several candidates, is ambiguous: the error says so,
and the way out is to pass the elements positionally or a typed array (`(int-array xs)`, `(into-array String xs)`).

An `inline` candidate has no public JVM method (`(c/.sumOf xs f)`: "no public JVM method (it is `inline`): write it in
Clojure"). Write the loop in Clojure.

A member of a Java class is not a var either, so where Kotlin would call the Java member, a Kotlin extension of the
same name can be chosen: `(tx/.append sb 1)` (`tx` is `kotlin.text`) is the extension `StringBuilder.append(value: Short)`,
Kotlin's `sb.append(1)` is Java's `append(int)`; the results are equal.
Where the Kotlin extension of that name is no var (Kotlin refuses a call to it), only the member is left, and it is
not a var: `(tx/.subSequence "abc" 0 1)` is an error. Use Java interop for the member: `(.subSequence "abc" 0 1)`.

The members of Kotlin's built-in types (`Int.rangeTo`, `Map.keys`, `Map.getOrDefault`) are not vars. Use the extensions (`until`, `downTo`, `step`:
`(r/.until 1 4)` is an `IntRange`) or Clojure's functions. A var of the same name can be another declaration:
`(r/.rangeTo 1 5)` is the extension for `Comparable` and gives a `ComparableRange`, not an `IntRange`; `c/keys` is
the property of `AbstractMap` only, so `(c/keys {})` or a `HashMap` is an error. `c/.getOrDefault` is not a var.

A top-level function and a property of the same name share one var (`routes(vararg h)` and `val H.routes`; a member
property and a top-level function too). A call that fits both is an error, never the property or the member by a rule:
`routes(h)` and `h.routes` are different texts in Kotlin, and one form here. The error names the ways out: the function by a
named argument (`(r/routes :list [h])`, a property has no such parameter), the property by a reference
(`((kt/ref X routes) h)`, rule 9). A property without a receiver has no reference that tells it from the function: the
error then gives the Java interop forms of both. A call that fits only one of them is unchanged. When the types of the
arguments are not known to the compiler, the run time decides, and so does the error.

## 19. A class that cannot be linked

A class that mentions a class which is not on the class path (an optional dependency) is skipped, and a call of what
was skipped is a `kt:` error that names the missing class (README, rule 1). A top-level class with a plain name gets a
placeholder var for that error. A nested class and a file facade (`...Kt`) that cannot be linked are dropped without a
placeholder, so there is no var for them (from the code of `ckway.meta/unlinkable-class-decls`; not run).
