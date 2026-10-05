# Koin from Clojure, with ckway

This spike wires the "Catalog" application with [Koin](https://insert-koin.io) (Kotlin dependency injection), from Clojure, through `ckway.core` (alias `kt`).
Koin has no HTTP. The Catalog operations (list with a tag filter, get, add with validation, delete) are plain Clojure functions of a system.

Koin version: `io.insert-koin/koin-core-jvm` 4.2.2 (newest stable on Maven Central; its Kotlin metadata is 2.3.0).
Use the `-jvm` artifact: tools.deps does not read the Gradle metadata of `koin-core`.

## Layout

| File | What it is |
|---|---|
| `src/spike/koin/domain.clj` | Pure Clojure: the `ProductStore` protocol, `validate`, `memory-store`. |
| `src/spike/koin/catalog.clj` | Pure Clojure: the `CatalogService` protocol and its implementation. No Koin. |
| `src/spike/koin/di.clj` | Three small helpers over Koin: `protocol-class`, `single-of`, `factory-of`, `on-close`. |
| `src/spike/koin/modules.clj` | The Koin modules: config, clock, stores, service, a request scope. |
| `src/spike/koin/core.clj` | `start!`, `stop!`, the Catalog operations, `-main`. |
| `test/spike/koin/core_test.clj` | The application: operations, config, lifecycle, override. |
| `test/spike/koin/forms_test.clj` | Each Koin form, called directly. |
| `test/spike/koin/interop_test.clj` | Pins the ckway fixes (trailing lambda, protocol error, nil for `T : Any`, hints). |
| `test/spike/koin/global_test.clj` | `startKoin` / `stopKoin` (the only global state, stopped in `finally`). |

## What it shows

* `module { }`, `single { }`, `factory { }` with Clojure functions as bodies, and `get()` inside a definition.
* An isolated container: `koinApplication { modules(...) }`. No global state. `start!` / `stop!` can run again and again.
* Reified calls (`single<T> { }`, `get<T>()`, `inject<T>()`, `getOrNull<T>()`) with `:<>`.
* The twins with a `KClass` (`koin.get(clazz, qualifier, parameters)`).
* `named(...)`, `parametersOf(...)`, `properties(mapOf(...))`, `getProperty`, a `scope { }`, `onClose`, `close()`.
* A test that overrides one definition with a test module.
* How to register a Clojure `reify` of a Clojure protocol. See `FINDINGS.md`, row 4.

## Run

```sh
cd spikes/koin
clojure -M:run            # the demo. Needs a stored bridge: run bin/test once, or add :kotlinc
clojure -M:run:kotlinc    # the demo, with the Kotlin compiler
bin/test                  # all tests, twice (see below). Exit code 0 means all passed
```

`bin/test` makes a fresh `target/cache` (mode 700), then:

1. runs the tests with `:test:kotlinc`. The `:<>` calls compile their bridges into `target/cache`.
2. runs the tests again with `:test` only. No compiler. The stored bridges are enough.

Every alias sets `-Dckway.cache.dir=target/cache`, so the bridge cache of the user is never touched.

## Kotlin form -> Clojure form

`dsl` = `org.koin.dsl`, `k` = `org.koin.core`, `m` = `org.koin.core.module`, `sc` = `org.koin.core.scope`,
`p` = `org.koin.core.parameter`, `q` = `org.koin.core.qualifier`, `ctx` = `org.koin.core.context`.

| Kotlin | Clojure |
|---|---|
| `module { ... }` | `(dsl/module (fn [m] ...))` |
| `single<T> { "x" }` | `(m/.single m (fn [_ _] "x") :<> T)` |
| `single<T>(named("a")) { }` | `(m/.single m (q/named "a") f :<> T)` |
| `factory<T> { }` | `(m/.factory m f :<> T)` |
| `single<ProductStore> { }` (a protocol) | a `kt:` error unless the namespace is AOT-compiled (see "With AOT"). Use `(di/single-of m store-class f)` |
| `single { } bind ProductStore::class` | `(-> (m/.single m f :<> Object) (dsl/.bind store-class))` |
| `ProductStore::class` | `(kt/ref ProductStore class)` or `(kjvm/kotlin (:on-interface ProductStore))` (`kjvm` = `kotlin.jvm`) |
| `get<T>()` in a definition | `(sc/.get scope :<> T)` |
| `get(ProductStore::class, named("a"))` in a definition | `(sc/.get scope store-class (q/named "a"))` |
| `definition onClose { }` | `(dsl/.onClose definition f)` |
| `scope(named("s")) { scoped<T> { } }` | `(m/.scope m (q/named "s") (fn [s] (dsl/.scoped s f :<> T)))` |
| `koinApplication { modules(a, b) }` | `(dsl/koinApplication (fn [app] (k/.modules app [a b])))` (a vector built in code needs `^java.util.List`) |
| `properties(mapOf("a" to "b"))` | `(k/.properties app {"a" "b"})` |
| `app.koin` | `(k/koin app)` |
| `koin.get<T>()` | `(k/.get koin :<> T)` |
| `koin.get<T>(named("a"))` | `(k/.get koin (q/named "a") :<> T)` |
| `koin.get(T::class)` | `(k/.get koin t-class)` |
| `koin.get(T::class, named("a")) { parametersOf(x) }` | `(k/.get koin t-class (q/named "a") (fn [] (p/parametersOf x)))` |
| `koin.get<T> { parametersOf(x) }` | `(k/.get koin :parameters (fn [] (p/parametersOf x)) :<> T)` |
| `koin.getOrNull<T>()` | `(k/.getOrNull koin :<> T)` |
| `val l by koin.inject<T>()` | `(kot/value (k/.inject koin :<> T))` (`kot` = `kotlin`) |
| `params.get<T>()` / `params[0]` | `(p/.get ps :<> T)` / `(p/.get ps 0)` |
| `koin.getProperty("k")` / `getProperty("k", d)` | `(k/.getProperty koin "k")` / `(k/.getProperty koin "k" d)` |
| `koin.setProperty("k", v)` | `(k/.setProperty koin "k" v)` |
| `koin.createScope("id", named("s"))` | `(k/.createScope koin "id" (q/named "s"))` |
| `scope.close()` | `(sc/.close scope)` |
| `koin.declare(x, named("n"))` | `(k/.declare koin x (q/named "n") :<> T)` |
| `koin.getAll<T>()` | `(k/.getAll koin :<> T)` |
| `app.close()` / `koin.close()` | `(k/.close app)` / `(k/.close koin)` |
| `allowOverride(false)` | `(k/.allowOverride app false)` |
| `startKoin { ... }` / `stopKoin()` | `(ctx/startKoin (fn [a] ...))` / `(ctx/stopKoin)` |
| `GlobalContext.get()` | `(ctx/.get ctx/GlobalContext)` |

## Naming the lambda

The trailing lambda has a Clojure form now: when the last parameter takes a function and has no default, the last positional argument goes to it.
So `(dsl/module f)`, `(m/.single m f :<> T)`, `(dsl/.scoped s f :<> T)` and `(dsl/koinApplication f)` need no names. Write `:<>` last, after all positional arguments.
A name is still needed where the lambda parameter has a default: `(k/.get koin :parameters f :<> T)`.
The lambda parameters of a `fn` at a Kotlin function type have a static type, so they need no hint.
A hint is still needed for a value that only the run time knows (the result of `into`, a map lookup): `^java.util.List`, `^org.koin.core.KoinApplication`, `^String`.
A return hint on a `defn` is used: `(defn- store-qualifier ^org.koin.core.qualifier.Qualifier [x] ...)`.

## Why `single-of` is acceptable

Koin's `single` is `inline`. Its body is copied into the bytecode of every user of Koin. The body calls
`BeanDefinition`, `SingleInstanceFactory`, `Module.indexPrimaryType` and `ScopeRegistry.rootScopeQualifier`.
`single-of` makes the same calls, so they are de facto stable ABI. `rootScopeQualifier` is `@PublishedApi internal`:
`kt` gives it no var, so `di.clj` calls its public JVM getter with plain Java interop.

## Which reads better: reified or the `KClass` twin?

* The reified call, `(k/.get koin :<> T)`, is the same as the Kotlin text. It needs a class that the Kotlin compiler can see, and a compiler or a stored bridge. A generic class needs type arguments: `(java.util.ArrayList String)`.
* The `KClass` call, `(k/.get koin t-class)`, never needs the compiler, and it works for a class that exists only at run time (a protocol). It needs a class value first.
* The spike uses the reified call for JDK, Clojure and Koin classes, and the `KClass` call for the protocols of the application.

## Without the compiler

`clojure -M:test` (no `:kotlinc`) passes after the bridges are in `target/cache`. A `:<>` call that has no stored bridge says: `kt compiles a small Kotlin bridge for it, which needs the Kotlin compiler on the class path`.
For a real deployment, run once with `:kotlinc`, or AOT-compile the namespaces (see the README of ckway).

## With AOT (an experiment, not used by the design)

Without AOT, a protocol as `:<>` type is a clear `kt:` error before the compiler runs. It names the class and gives two ways out: AOT, or a `KClass` overload.
If the interface of the protocol is a class file, the reified call works for it: `single<ProductStore> { }` and `koin.get<ProductStore>()` with `:<> spike.koin.domain.ProductStore`.
Make the class file, then run with the alias `:aot`:

```sh
clojure -M:aot -e "(binding [*compile-path* \"target/classes\"] (compile 'spike.koin.domain) (compile 'spike.koin.catalog))"
clojure -M:test:aot:kotlinc -n spike.koin.forms-test    # `with-aot-the-protocol-is-a-reified-type` runs
```

Without `target/classes` on the class path, that test does nothing. `bin/test` does not use AOT.
