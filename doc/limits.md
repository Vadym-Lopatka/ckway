# Known limits of kt

Each item was checked against the code or run. `hardening_test.clj` pins most of them with a test.

## 1. Number width at a generic position

Kotlin's `Int` is `java.lang.Integer`. A Clojure integer is a `Long`.
At a parameter or result whose Kotlin type is a type parameter (`T`, JVM `Object`) `kt` gives an integer literal that fits
`Int` the width `Int`; any other Clojure integer keeps the `Long`. A literal at a `T` that another argument or the receiver
also has stays a `Long`, because Kotlin infers `T` from the other one and `kt` does not
(`(c/.minus (c/listOf 1 2 3) 2)` removes nothing: write `(int 2)`).

```clojure
(f/boxedT 1)             ; fun <T> boxedT(x: T) = (x as Int).toString()
;; "1"                      the literal fits Int and nothing else shares T: it is an Int
(let [n 1] (f/boxedT n)) ; n is not a literal: it keeps the Long
;; ClassCastException: class java.lang.Long cannot be cast to class java.lang.Integer
;;   at fx.HardeningKt.boxedT(Hardening.kt:66)      <- the Kotlin frame is the first one
(f/boxedT (int 1))       ; the remedy: say the width yourself

(c/.minus (c/listOf 1 2 3) 2)         ; [1 2 3]  the 2 is a Long (T is also the receiver's T); no element is equal to it
(c/.minus (c/listOf 1 2 3) (int 2))   ; [1 3]
(c/.contains (c/listOf 1 2 3) 2)      ; false     the same reason
(f/listOfT 1 2)                       ; fun <T> listOfT(a: T, b: T): the two literals share T, so both are Longs
```

`kt` converts when it knows the type: a call with `:<>` on a non-reified generic function (`(f/boxedT 1 :<> Int)`) converts
a `T` parameter and result, a `vararg T`, and function types and fun interfaces that mention `T`
(`(f/applyT 1 inc :<> Int)` gives Kotlin an `Integer` back from `inc`).

`kt` does NOT convert: a `Long` that is not a literal (a `let` local, a result of `inc`), a literal at a `T` that another
argument or the receiver has, the elements of a `List<T>`/`Map` that you pass, a member of a generic
class (`Box<Int>.put(x)`), and the members of a `kt/reify` object of a generic interface (`kt/reify Visitor` returns a
`Long` for `Visitor<Int>`; `kt/reify` has no type-argument syntax). Use `(int x)`, `(long x)`, `(mapv int xs)`.

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
  kt reads the declarations of the interface itself from its Kotlin metadata, because `ckway.meta` lists an override
  only when its Kotlin parameter types differ from the original's (`kt/reify` calls two private functions of
  `ckway.meta` for this). Overloads that differ only in `Int` and `Int?` (`f(Int?)`, `f(Int)`: JVM `Integer` and `int`) are
  two members: write them with the hints `^Integer` and `^int` (a hint that is exactly the JVM class of the parameter is
  the exact fit; `^Integer` alone for an `Int` still fits when no other overload does).
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
  `ClassCastException`.
* A class with no public constructor (`Duration`), an interface, an abstract, sealed or enum class: the error says
  which, and lists the entries of an enum or the companion functions that return the class.

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

## 15. The disk cache of the Kotlin bridges: location and security model

Where. `-Dckway.cache.dir=<dir>` selects the directory; an empty value turns the cache off. Without the property:
`$XDG_CACHE_HOME/ckway` (if set and absolute), `%LOCALAPPDATA%\ckway` on Windows, else `~/.cache/ckway`. It is never the
working directory: if the resolved default directory is not absolute (the JDK sets `user.home` to `?` for a user with no
passwd entry, common in containers), the cache is off. kt creates the directory with owner-only permissions (`rwx------`)
where the file system has POSIX permissions. An existing directory is used only if, after symbolic links are resolved,
it is a directory that belongs to the current user and neither group nor others can write it. This holds for the default
directory and for the one you name with the property. The owner is compared with the owner of a file that the process
creates (not with `user.name`, which is `?` for such a user). For the default directory a failed check is silent
(`-Dckway.debug=true` says why). For `-Dckway.cache.dir` it is an explicit setting, so kt turns the cache off and prints
ONE line to stderr (once per JVM), for example `ckway: the bridge cache is OFF: the directory /tmp/c of -Dckway.cache.dir
can be written by its group. Run `chmod 700 /tmp/c` ...`.

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

Pruning. Every store prunes: of one bridge name the 8 most recently used entries are kept (two projects, or two branches,
with different versions of one Kotlin library do not evict each other), an entry that was not used for 90 days is deleted
whatever its bridge, and a `.tmp-<uuid>` directory older than one hour (a JVM that was killed while it wrote) is deleted.
"Used" is the modification time of the entry directory; a hit sets it to now when it is older than one hour. That does not
touch the class files or `entry.txt`, so the atomic write and the hash check stay as they were. The numbers are
`keep-per-bridge`, `max-age-ms`, `stale-tmp-ms` and `touch-after-ms` in `ckway.bridge.cache`.

What this protects against: a class file in the cache that is damaged, truncated, replaced by a stale or foreign
class, or edited without the matching hash; a cache that a different project or user left in the working directory
(it is not read); two JVMs that write at the same time.

What it does NOT protect against: someone who can write both the class file and `entry.txt` in the cache directory can
plant code that runs with your privileges, because the hash lives next to the class. That is why the default directory
is per user, owner-only, and refused if others can write it. A `ckway.cache.dir` that others can write (`/tmp`, a shared
build directory) is refused with a warning. Do not share the cache between trust domains. If in doubt, turn the
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
  has passed (system property `ckway.interrupt.grace.ms`, read at each wait, default 5000 ms) or at a second interrupt of
  the thread, so `ExecutorService.shutdownNow` stops such a thread. The callee is not stopped by that (it ignored the
  cancellation); its late result is dropped. Measured with a grace of 400 ms: `InterruptedException` after 406 ms, flag clear.
  The in-body wait has no bound (a cancelled body waits for its callee: structured concurrency).
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
JVM parameter types: they differ in a type argument or in the result type of a lambda, which the JVM erases. `kt` does
not guess. The error lists each candidate with its Java interop call:

```clojure
(c/.sum (c/listOf 1 2 3))
;; kt: (.sum (c/listOf 1 2 3)) is ambiguous. Candidates: ...
;;   fun collections.Iterable<Int>.sum(): Int
;;     ->  (kotlin.collections.CollectionsKt/sumOfInt x)    ; JVM descriptor (Ljava/lang/Iterable;)I
(kotlin.collections.CollectionsKt/sumOfInt (c/listOf 1 2 3))   ; 6
```

An `inline` candidate has no public JVM method (`(c/.sumOf xs f)`: "no public JVM method (it is `inline`): write it in
Clojure"). Write the loop in Clojure.

The members of Kotlin's built-in types (`Int.rangeTo`, `Map.keys`, `Map.getOrDefault`) are not vars. Use the extensions (`until`, `downTo`, `step`:
`(r/.until 1 4)` is an `IntRange`) or Clojure's functions. A var of the same name can be another declaration:
`(r/.rangeTo 1 5)` is the extension for `Comparable` and gives a `ComparableRange`, not an `IntRange`; `c/keys` is
the property of `AbstractMap` only, so `(c/keys {})` or a `HashMap` is an error. `c/.getOrDefault` is not a var.

A top-level function and a property of the same name share one var. A call that fits both is an error that gives the
interop forms of both.

## 19. A class that cannot be linked

A class that mentions a class which is not on the class path (an optional dependency) is skipped, and a call of what
was skipped is a `kt:` error that names the missing class (README, rule 1). A top-level class with a plain name gets a
placeholder var for that error. A nested class and a file facade (`...Kt`) that cannot be linked are dropped without a
placeholder, so there is no var for them (from the code of `ckway.meta/unlinkable-class-decls`; not run).
