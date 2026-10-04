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

[0.1.0]: https://github.com/Vadym-Lopatka/ckway/releases/tag/v0.1.0
