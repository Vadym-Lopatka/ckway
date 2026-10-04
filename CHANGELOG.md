# Changelog

All notable changes to this project are in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The project is alpha: the API may change before 1.0.

## [0.1.0] - 2026-10-04

First release. Clojure calls Kotlin with `ckway.core` (alias `kt`). The 11 rules:

* Namespace: `kt/require` makes one namespace from one Kotlin package, one var for each name.
* Name and call: a function or property is a Clojure function with its receivers first; a function with a receiver has the prefix `.`.
* Class names: a class var constructs and is the receiver of companion members; an `object` or enum entry var holds the instance; nested names keep their dots.
* Arguments: positional, named (a keyword names the parameter of the next argument), Kotlin defaults, and `vararg`.
* Type arguments: `:<>` gives the type arguments, also for `inline reified` functions (compiled with the Kotlin compiler into a cached bridge).
* Functions: a Clojure function goes where Kotlin wants a function type, a `fun interface` or a Java single-method interface; a Kotlin function value is a Clojure function.
* No guess: Kotlin overload rules select the declaration; an unclear call is an error; a value is adapted only to the declared parameter type.
* Write a property: `kt/set!`.
* References: `kt/ref` is Kotlin `X::y`.
* Implement an interface: `kt/reify`.
* Data: `kt/data` gives a read-only map of the primary-constructor properties.

Also: `suspend` functions and suspend lambdas (virtual threads), value classes, a per-user bridge cache with SHA-256 checks, and AOT support.

Also in this release (the fixes of the reviews before it):

* One number rule: `kt` changes a number only when the declared Kotlin parameter type says so (`Int`, `Short`, `Byte`, `Long`, `Float`, `Double`). At `Any`, `Number` or a type parameter a Clojure integer stays a `Long`, a literal or not (before, a literal that fits `Int` became an `Int` there, which made lookups in lists and maps built in Clojure miss).
* The code that `kt` emits uses only qualified names, so a local of your code named `int`, `long`, `name`, `count`... cannot capture one.
* Overload choice: a conversion of a number that `kt` makes itself (an integer for a `Double`) is used only when no overload takes the value as it is; type arguments count in the choice of the most specific overload (`List<T>` is not a subtype of `Collection<Int>`); a type hint that you write (`^CharSequence s`) is the static type of the argument; a collection passed as a whole to a `vararg` chooses between overloads when its elements can be seen, and the error says so when they cannot.
* A member that the JVM hides, in a class that is not public (`@InlineOnly` functions of the standard library), is called through a bridge with `invokeExact`: about 8 times faster than before.
* An integer literal that is out of range for its `Int`, `Short` or `Byte` parameter is a compile-time error with the same words as the run-time check.

* A type that the library itself puts on a local (the parameters of a `fn` literal passed at a Kotlin function type, `this` and the parameters of a `kt/reify` member) is an upper bound, never "a type you wrote": `(p/eachEv xs (fn [e] (p/.pos e)))` compiles again for a subclass member or overload. Your own hint on such a parameter is still the static type.
* The bridge cache keeps its entries in its own subdirectory `bridges-v1` of the directory that is named, and deletes only what it can prove that it made: entry directories with the exact generated name and only class files and `entry.txt` in them, and `.ckway-tmp-<uuid>` temporary directories. Before, a prune could delete any old directory of the cache directory (`-Dckway.cache.dir=target` lost sibling directories and `.tmp-*` of other tools). A delete never follows a symbolic link.
* The scripts `bin/test` and `examples/run` create their cache directories with mode 700 (a group-writable directory under `umask 002` was refused, so the cache was off).
* Overload choice: the specificity rule (type arguments included) decides before the closeness of a parameter type to the class of the value, so a value from a nested call (`(p/gl (c/listOf 1))`), a literal, a local, a var as a value and the dynamic path give the same answer.
* Type hints: a hint that you write decides between the candidates that it fits for sure; when it fits none for sure but the value could (`^clojure.lang.IPersistentMap` at a `Map` parameter, `^java.util.Collection` at a `List`) it is an upper bound and the call is checked at run time; a hint that can never fit stays a compile error. A wrong hint at run time is a `kt:` error that names the call, the parameter, the declaration and the actual class (before: `ClassCastException`).
* `(m/sqrt 4)`: when every candidate would need the library's own conversion of an integer, the "ambiguous" error says so and suggests `4.0` or `(double x)`.
* `kt/reify`: one written member serves every leaf override that takes the same parameter types (`S1.get(): CharSequence` and `S2.get(): Comparable<*>` over `Src.get(): Any`), with the result checked for each; leaves with different parameter types are a compile error that says to write the member under each interface.
* `ckway.meta/own-declarations` is public; `kt/reify` no longer calls private functions of `ckway.meta`. An override that narrows the result type (`override fun get(): String` over `fun get(): Any`) is now the declaration a caller sees, so `(p/.get s)` is a `String` on the static path.
* `-Dckway.interrupt.grace.ms` is clamped (at most 24 hours; negative is 0; not a number is the default): a huge value gave `ArithmeticException` instead of `InterruptedException`.
* The interrupt flag that is set again after a cancelled call is set only on the thread of the body. A suspend call from a thread that inherited the context of the body (`future`, `bound-fn`) is a top-level call in that context with a Job that is a child of the Job of the body (`doc/limits.md`, 16).
* An override that narrows the result type has the default values of the member it overrides (Kotlin marks them on the original only): `(q/.mk (q/Far))` with `interface Src { fun mk(x: Int = 7): Any }` and `class Far : Src { override fun mk(x: Int): String }` in another package works again, runs the `$default` synthetic of the original as kotlinc does (the override's body runs), and `doc` shows `x: Int = ...`.
* A suspend call completes its own Job on every exit. A call from a child thread of a body (`future`, `pmap`) that throws before it suspends no longer leaves the Job of the body waiting for ever; a top-level call that throws no longer leaves its Job active.
* Overload choice: a numeric preference on one parameter no longer overrides a disadvantage on another with an equal reference tier (`mx(CharSequence, Int)` / `mx(String, Long)` with `(p/mx "s" 1)` is ambiguous, as in Kotlin).
* A call on an untyped receiver whose candidates are one member and its narrowing overrides (`(p/.get s)` with `interface Src { fun get(): Any }`, `StrSrc : Src { override fun get(): String }`) is a static virtual call of the most general declaration again, not the dynamic path.
* `kt/reify`: the leaves of one member are matched by JVM name and parameter types, so one form serves a Java and a Kotlin leaf.
* The bridge cache prints one line per JVM when a foreign file or directory sits on an entry name (the bridge is then not cached), and deletes a `.ckway-tmp-<uuid>` directory only when it holds class files and `entry.txt` only.
* The "ambiguous" message for numbers suggests `4.0`, `(double x)`, `(float x)`; `4.0f` is not Clojure.

[0.1.0]: https://github.com/Vadym-Lopatka/ckway/releases/tag/v0.1.0
