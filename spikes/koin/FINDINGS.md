# Findings: Koin through ckway

This file shows the state after the ckway fixes of the branch `fix/spike-findings` (commit d446567). The section "History" says what each row was before.

## Verdict

ckway fits Koin well. Every form on the list works without Kotlin glue: modules, `single`/`factory`, `get()`, qualifiers, parameters, properties, scopes, `onClose`, `close`, `startKoin`, override. The forms follow the Kotlin text: `(dsl/module (fn [m] ...))`, `(m/.single m f :<> T)`.
One thing still needs care. A Clojure protocol cannot be the `T` of a reified call unless its namespace is AOT-compiled, so the spike registers a protocol through a `KClass` value (`di/single-of`).
`di/single-of` calls a few members that Koin means for itself. Koin's own `inline` `single` compiles to the same calls in the bytecode of every user, so the risk is small.
All 41 tests pass twice, with the compiler and with stored bridges only (159 assertions each), with no reflection warnings. The orchestrator ran them again on d446567.

## Findings

`dsl` = `org.koin.dsl`, `m` = `org.koin.core.module`, `k` = `org.koin.core`, `sc` = `org.koin.core.scope`, `p` = `org.koin.core.parameter`, `q` = `org.koin.core.qualifier`, `ctx` = `org.koin.core.context`, `kjvm` = `kotlin.jvm`.

| # | Kotlin form | Clojure form | Result | Note |
|---|---|---|---|---|
| 1 | `module { }`, `single<T> { }`, `factory<T> { }`, `scoped<T> { }`, `koinApplication { }` | `(dsl/module f)`, `(m/.single m f :<> T)`, `(m/.single m (q/named "a") f :<> T)`, `(m/.factory m f :<> T)`, `(dsl/.scoped s f :<> T)`, `(dsl/koinApplication f)` | works | The trailing lambda is positional. `:<>` must be last. A lambda whose parameter has a default stays named: `(k/.get koin :parameters f :<> T)`. |
| 2 | `single<T> { }`, `factory<T> { }`, T is a JDK, Clojure or Koin class | `(m/.single m f :<> String)` | works | Needs `:kotlinc` once, then the stored bridge. |
| 3 | `get<T>()`, `getOrNull<T>()`, `inject<T>()`, `injectOrNull<T>()`, `getAll<T>()`, `declare<T>()` | `(k/.get koin :<> T)` and so on | works | `inject` gives a `kotlin.Lazy`; read it with `(kot/value lazy)`. |
| 4 | `single<ProductStore> { }` (a Clojure protocol as T) | `... :<> spike.koin.domain.ProductStore` | blocked without AOT; works with AOT | Without AOT: a clear `kt:` error before the compiler runs. It names the class and two ways out. |
| 5 | `single(ProductStore::class) { }` (not in Koin's public API) | `(di/single-of m store-class f)` | workaround | Uses `BeanDefinition`, `SingleInstanceFactory`, `Module.indexPrimaryType`, and the root scope name by Java interop. |
| 6 | `single<Any> { } bind ProductStore::class` | `(-> (m/.single m f :<> Object) (dsl/.bind store-class))` | works | Public API only, but all such definitions share the key `Any`. |
| 7 | `ProductStore::class` | `(kt/ref ProductStore class)`, `(kjvm/kotlin (:on-interface P))` | works | |
| 8 | `koin.get(T::class, named("a")) { parametersOf(x) }` | `(k/.get koin t-class (q/named "a") (fn [] (p/parametersOf x)))` | works | No compiler. A `Class` is not a `KClass`: a clear `kt:` error. |
| 9 | `named("memory")` | `(q/named (name x))` | works | Overloaded (String, Enum). No hint when the argument is the result of a `defn` with a `^String` return hint, or an enum entry var. |
| 10 | `parametersOf(x)`, `params.get<T>()`, `params[0]` | `(p/parametersOf x)`, `(p/.get ps :<> T)`, `(p/.get ps 0)` | works | One vector is ONE parameter. |
| 11 | `properties(mapOf(...))`, `getProperty(k)`, `getProperty(k, d)`, `setProperty`, `deleteProperty` | `(k/.properties app {"a" "b"})`, `(k/.getProperty koin "a")` and so on | works | A nil default is a `kt:` error that names `defaultValue`. |
| 12 | `scope(named("s")) { scoped<T> { } }`, `createScope`, `scope.get<T>()`, `scope.close()` | `(m/.scope m q (fn [s] (dsl/.scoped s f :<> T)))` | works | No hints on the `fn` parameters. |
| 13 | `onClose { }` | `(dsl/.onClose def f)` | works | Koin also calls it with `null` for a single that was never made. |
| 14 | `startKoin { }`, `stopKoin()` | `(ctx/startKoin (fn [a] ...))`, `(ctx/stopKoin)` | works | A second start throws `KoinApplicationAlreadyStartedException`. |
| 15 | `app.close()`, then `get` | `(k/.close app)` | works | `ClosedScopeException: Scope '_root_' is closed`; `getOrNull` gives nil. |
| 16 | override with a test module; `allowOverride(false)` | an extra module after the app modules | works | The last module wins. `false` throws `DefinitionOverrideException` only across modules. |
| 17 | `ArrayList<String>` as T | `:<> (java.util.ArrayList String)` | works | A raw `java.util.ArrayList` is a `kt:` error. |
| 18 | calls on `KoinApplication`, `Module`, `Scope` inside a `fn` | `(fn [a] ...)` at a Kotlin function type | works | The parameter has a type. A hint is needed only for a value with no type at compile time: `^java.util.List` on an `into` result, `^KoinApplication` on a value taken from a map. |
| 19 | a run with no compiler | `clojure -M:test`, `clojure -M:run` | works | After one run with `:kotlinc`. |

`test/spike/koin/interop_test.clj` pins rows 1, 4, 9, 11 and 18, so a regression in ckway shows here.

## Details

### 1. The lambda

The trailing lambda rule (README rule 4) binds the last positional argument to the last parameter when that parameter takes a function and has no default. Two things a user must know:

- `:<>` must be last. `(m/.single m :<> String (fn [_ _] "x"))` gives `kt: ...: positional argument (fn [_ _] "x") follows a named argument. Put all positional arguments before the first keyword.`
- A lambda parameter that HAS a default is not reached. `fun <T> Koin.get(qualifier: Qualifier? = null, parameters: ParametersDefinition? = null)`:

```
kt: no Kotlin declaration of `.get` fits (.get koin (fn [] (p/parametersOf "Ann" (int 3))) :<> StringBuilder)
  -> `qualifier` is Qualifier but got AFunction
  -> `clazz` is KClass but got AFunction
```

  Write `(k/.get koin :parameters f :<> StringBuilder)`.

### 4. A protocol as reified T

`(m/.single m (fn [_ _] store) :<> spike.koin.domain.ProductStore)` gives, before the Kotlin compiler runs:

```
kt: ...: the class `spike.koin.domain.ProductStore` in `:<>` was made at run time (a `defprotocol`, `definterface`, `deftype`, `defrecord`, a `gen-class` that is not compiled yet, or an earlier `kt/reify`). ...
  Ways out: 1. AOT-compile the namespace that defines it and put the classes directory on the class path.
            2. Use an overload that takes a `KClass`, if the library has one: `(kt/ref ProductStore class)`.
```

Cause: `defprotocol` makes the interface in a `DynamicClassLoader`. It is not a file on the class path that the bridge compiler reads.

With AOT it works. In a fresh JVM, `(binding [*compile-path* "target/classes"] (compile 'spike.koin.domain) (compile 'spike.koin.catalog))` writes `spike/koin/domain/ProductStore.class`. With `target/classes` on the class path (alias `:aot`) and `:kotlinc`:

- `(m/.single m (fn [_ _] store) :<> spike.koin.domain.ProductStore)` compiles.
- `(k/.get koin :<> spike.koin.domain.ProductStore)` and `getOrNull` return the same store (`identical?`).
- The interface comes from the `AppClassLoader`. A `reify` that is made after `(require 'spike.koin.domain :reload)` is still an instance of it.
- Not tested: two JVMs, one with and one without the AOT classes.

`clojure -M:test:aot:kotlinc -n spike.koin.forms-test`: 17 tests, 52 assertions, 0 failures. The design of the spike does not depend on AOT.
A possible ckway change: give the bridge compiler stub class files for classes that were made at run time. Not done.

### 5. `di/single-of`

Koin has no public `single(KClass)` that is not reified. `single-of` does what the `inline` `single` does inside:

```clojure
(let [bean (df/BeanDefinition root-scope kclass qualifier definition df/Kind.Singleton)
      factory (inst/SingleInstanceFactory bean)]
  (m/.indexPrimaryType mdl factory)
  (df/KoinDefinition mdl factory))
```

It works with and without a name, with `onClose`, override and `getOrNull`.
The root scope name is `ScopeRegistry.rootScopeQualifier`, which is `@PublishedApi internal`. ckway gives it no var (`No such var`), and that is correct. It is public in the bytecode, so the spike reads it with Java interop: `(.getRootScopeQualifier org.koin.core.registry.ScopeRegistry/Companion)`.
Risk: these members are for Koin itself. But the body of `single` is copied into the bytecode of every Koin user, so Koin must keep them.
ckway change: none. A public `single(KClass)` in Koin would remove the helper.

### 6. `Any` and `bind`

All such definitions share the primary key `Any`. With `allowOverride(false)` and the definitions in two modules, the second throws `DefinitionOverrideException: Already existing definition for [Singleton: 'java.lang.Object' ...]`. With override allowed (the default), or inside one module, the last one owns `Any` and there is no error. It is good for one definition and fragile for many.

### 11. nil

A nil is a `kt:` error where the Kotlin type is not nullable, also when the nil comes from a variable:

```
(k/.getProperty koin "no.such" nilv)
kt: ... nil where Kotlin expects a non-null value of the type parameter `T : Any` (the argument `defaultValue` (T))
  Kotlin: fun <T : Any> org.koin.core.Koin.getProperty(key: String, defaultValue: T): T

(k/.getProperty koin nilv)
kt: (k/.getProperty kn nilv): nil where Kotlin expects a non-null String (the argument `key` (String))

(k/.getProperty ^org.koin.core.Koin nilv "x")
kt: ... nil where Kotlin expects a non-null org.koin.core.Koin (the receiver)
```

`getProperty(key)` with one argument gives nil for a missing key.

### 16. Override is checked across modules only

Two definitions with the same key inside ONE module do not throw. This is Koin, not ckway.

## History

The first run of the spike (ckway 7a303cf) needed these workarounds. The fixes removed them.

| # | Before | Fixed by |
|---|---|---|
| 1, 12, 14 | the lambda had to be named: `(dsl/module :moduleDeclaration f)`, `(m/.single m :definition f :<> T)`; the error said "Pass it positionally", which did not work | A2: trailing lambda, and the corrected error text |
| 4 | the raw compiler text `unresolved reference 'domain'.` | C4: a `kt:` error before the compiler runs |
| 9 | `^String` at each `named` call | C3: the return hint of a function; C2: an enum entry var has a type |
| 11 | a nil from a variable went to Kotlin: `NullPointerException: Parameter specified as non-null is null` | C1: nil against the bound of a type parameter; D3: nil at any non-null parameter or receiver |
| 18 | hints on the `fn` parameters (`^Module m`, `^KoinApplication app`, `^ScopeDSL s`) | not needed; removed in the update |

## Versions

- Koin: `io.insert-koin/koin-core-jvm` 4.2.2 (the newest stable on Maven Central; later entries are alpha, beta or RC). Use the `-jvm` artifact: tools.deps cannot read the Gradle metadata of `koin-core`. Transitive: `co.touchlab/stately-*` 2.1.0.
- Kotlin metadata of the Koin classes: 2.3.0.
- Kotlin compiler for `:<>`: `kotlin-compiler-embeddable` 2.4.20.
- ckway: this repository by `:local/root "../.."`, at `fix/spike-findings` d446567.
- JDK: OpenJDK 25.0.4. Clojure 1.12.1.

## Not done

- A run with a cold cache and no compiler, except the one test that checks the error message of a `:<>` call with no stored bridge.
- A timing of a cold and a warm bridge compile.
