# Changelog

All notable changes to this project are in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The project is alpha: the API may change before 1.0.

## [Unreleased]

* One number rule: `kt` changes a number only when the declared Kotlin parameter type says so (`Int`, `Short`, `Byte`, `Long`, `Float`, `Double`). At `Any`, `Number` or a type parameter a Clojure integer stays a `Long`, a literal or not (before, a literal that fits `Int` became an `Int` there, which made lookups in lists and maps built in Clojure miss).
* The code that `kt` emits uses only qualified names, so a local of your code named `int`, `long`, `name`, `count`... cannot capture one.
* Overload choice: a conversion of a number that `kt` makes itself (an integer for a `Double`) is used only when no overload takes the value as it is; type arguments count in the choice of the most specific overload (`List<T>` is not a subtype of `Collection<Int>`); a type hint that you write (`^CharSequence s`) is the static type of the argument; a collection passed as a whole to a `vararg` chooses between overloads when its elements can be seen, and the error says so when they cannot.
* A member that the JVM hides, in a class that is not public (`@InlineOnly` functions of the standard library), is called through a bridge with `invokeExact`: about 8 times faster than before.
* An integer literal that is out of range for its `Int`, `Short` or `Byte` parameter is a compile-time error with the same words as the run-time check.

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

[0.1.0]: https://github.com/Vadym-Lopatka/ckway/releases/tag/v0.1.0
