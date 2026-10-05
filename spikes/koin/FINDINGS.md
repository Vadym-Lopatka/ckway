# Findings: Koin through ckway

## Verdict

ckway fits Koin well. Every form on the list works without Kotlin glue: modules, `single`/`factory`, `get()`, qualifiers, parameters, properties, scopes, `onClose`, `close`, `startKoin`, override.
Two things need care. The lambda of `module { }` and `single { }` must be named, because a Kotlin trailing lambda has no Clojure form. And a Clojure protocol cannot be the `T` of a reified call unless its namespace is AOT-compiled, so the spike registers a protocol through a `KClass` value (`di/single-of`).
`di/single-of` calls a few members that Koin means for itself. Koin's own `inline` `single` compiles to the same calls in the bytecode of every user, so the risk is small.
The orchestrator ran `bin/test` again (34 tests, with and without the compiler). The rows below are covered by `test/spike/koin/forms_test.clj`; they were not reproduced a second time one by one.

## Findings

`dsl` = `org.koin.dsl`, `m` = `org.koin.core.module`, `k` = `org.koin.core`, `sc` = `org.koin.core.scope`, `p` = `org.koin.core.parameter`, `q` = `org.koin.core.qualifier`, `ctx` = `org.koin.core.context`, `kjvm` = `kotlin.jvm`.

| # | Kotlin form | Clojure form | Result | ckway rule or limit | Note |
|---|---|---|---|---|---|
| 1 | `module { }`, `single<T> { }`, `factory<T> { }` (a lambda after defaulted parameters) | `(dsl/module :moduleDeclaration f)`, `(m/.single m :definition f :<> T)` | workaround | rule 4 | Name the lambda. `koinApplication` also takes it by name in the spike. |
| 2 | `single<T> { }`, `factory<T> { }`, T is a JDK, Clojure or Koin class | `(m/.single m :definition f :<> String)` | works | rule 5 | Needs `:kotlinc` once, then the stored bridge. |
| 3 | `get<T>()`, `getOrNull<T>()`, `inject<T>()`, `injectOrNull<T>()`, `getAll<T>()`, `declare<T>()` | `(k/.get koin :<> T)` and so on | works | rule 5 | `inject` gives a `kotlin.Lazy`; read it with `(kot/value lazy)`. |
| 4 | `single<ProductStore> { }` (a Clojure protocol as T) | `... :<> spike.koin.domain.ProductStore` | blocked without AOT; works with AOT | rule 5 | The compiler cannot see an interface that was made at run time. |
| 5 | `single(ProductStore::class) { }` (not in Koin's public API) | `(di/single-of m store-class f)` | workaround | rule 1 | Uses `BeanDefinition`, `SingleInstanceFactory`, `Module.indexPrimaryType`, and the root scope name by Java interop. |
| 6 | `single<Any> { } bind ProductStore::class` | `(-> (m/.single m :definition f :<> Object) (dsl/.bind store-class))` | works | rule 5 | Public API only, but all such definitions share the key `Any`. |
| 7 | `ProductStore::class` | `(kt/ref ProductStore class)`, `(kjvm/kotlin (:on-interface P))` | works | rule 9 | |
| 8 | `koin.get(T::class, named("a")) { parametersOf(x) }` | `(k/.get koin t-class (q/named "a") (fn [] (p/parametersOf x)))` | works | rule 6 | No compiler. A `Class` is not a `KClass`: a clear `kt:` error. |
| 9 | `named("memory")` | `(q/named "memory")` | works | rule 7 | Overloaded (String, Enum): hint `^String`. |
| 10 | `parametersOf(x)`, `params.get<T>()`, `params[0]` | `(p/parametersOf x)`, `(p/.get ps :<> T)`, `(p/.get ps 0)` | works | rules 4 and 5 | One vector is ONE parameter. |
| 11 | `properties(mapOf(...))`, `getProperty(k)`, `getProperty(k, d)`, `setProperty`, `deleteProperty` | `(k/.properties app {"a" "b"})` and so on | works | | `getProperty(k, nil)` is a Kotlin NPE, see Details. |
| 12 | `scope(named("s")) { scoped<T> { } }`, `createScope`, `scope.get<T>()`, `scope.close()` | `(m/.scope m q (fn [^ScopeDSL s] (dsl/.scoped s :definition f :<> T)))` | works | rules 4 and 5 | `scoped` also needs the named `:definition`. |
| 13 | `onClose { }` | `(dsl/.onClose def f)` | works | rule 6 | Koin also calls it with `null` for a single that was never made. |
| 14 | `startKoin { }`, `stopKoin()` | `(ctx/startKoin (fn [^KoinApplication a] ...))`, `(ctx/stopKoin)` | works | | A second start throws `KoinApplicationAlreadyStartedException`. |
| 15 | `app.close()`, then `get` | `(k/.close app)` | works | | `ClosedScopeException: Scope '_root_' is closed`; `getOrNull` gives nil. |
| 16 | override with a test module; `allowOverride(false)` | an extra module after the app modules | works | | The last module wins. `false` throws `DefinitionOverrideException` only across modules. |
| 17 | `ArrayList<String>` as T | `:<> (java.util.ArrayList String)` | works | rule 5 | A raw `java.util.ArrayList` is a `kt:` error. |
| 18 | calls on `KoinApplication`, `Module`, `Scope` inside a `fn` | `(fn [^org.koin.core.KoinApplication app] ...)` | works with hints | rule 7 | Without a hint: a reflection warning; the dynamic path still works. |
| 19 | a run with no compiler | `clojure -M:test`, `clojure -M:run` | works | stored bridge | After one run with `:kotlinc`. |

## Details

### 1. Name the lambda

`(dsl/module (fn [^org.koin.core.module.Module m] nil))` gives:

```
kt: no Kotlin declaration of `module` fits (module (fn [a] nil))
    fun module(createdAtStart: Boolean = ..., moduleDeclaration: (org.koin.core.module.Module) -> Unit): org.koin.core.module.Module
      -> missing required parameter `moduleDeclaration`. Pass it positionally or as `:moduleDeclaration`.
```

The same for `(m/.single m (fn [_ _] 1) :<> String)`: missing required parameter `definition`.
Workaround: `:moduleDeclaration f`, `:definition f`. This is the README note of rule 4.
ckway change: when the last argument is a `fn` form and the last parameter has a function type, bind it there and let the skipped parameters take their defaults (the Kotlin trailing lambda). The words "Pass it positionally" in the error are also misleading here: that works only when every skipped parameter is given (`(dsl/module false f)`).

### 4. A protocol as reified T

`(m/.single m :definition (fn [_ _] store) :<> spike.koin.domain.ProductStore)` gives:

```
kt: the Kotlin compiler rejected (m/.single m :definition (fn [_ _] ...) :<> spike.koin.domain.ProductStore)
  Kotlin signature: inline fun <reified T> org.koin.core.module.Module.single(qualifier: ... = ..., createdAtStart: Boolean = ..., definition: (Scope, ParametersHolder) -> T): KoinDefinition<T>
  type arguments: spike.koin.domain.ProductStore
  Kotlin says:
    unresolved reference 'domain'.
```

Cause: `defprotocol` makes the interface in a `DynamicClassLoader`. It is not a file on the class path that the bridge compiler reads.

With AOT it works. In a fresh JVM, `(binding [*compile-path* "target/classes"] (compile 'spike.koin.domain) (compile 'spike.koin.catalog))` writes `spike/koin/domain/ProductStore.class`. With `target/classes` on the class path (alias `:aot`) and `:kotlinc`:

- `(m/.single m :definition (fn [_ _] store) :<> spike.koin.domain.ProductStore)` compiles.
- `(k/.get koin :<> spike.koin.domain.ProductStore)` and `getOrNull` return the same store (`identical?`).
- The interface comes from the `AppClassLoader`. A `reify` that is made after `(require 'spike.koin.domain :reload)` is still an instance of it.
- Not tested: two JVMs, one with and one without the AOT classes.

`clojure -M:test:aot:kotlinc -n spike.koin.forms-test`: 17 tests, 52 assertions, 0 failures. The design of the spike does not depend on AOT.
ckway change: let the bridge compiler see classes that were made at run time, or let `:<>` take a `KClass` value for a `reified` function whose body only needs `T::class`.

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

### 11. nil for a parameter of type `T : Any`

`(k/.getProperty koin "no.such" nil)`, with the nil from a variable, gives:

```
NullPointerException at org.koin.core.Koin/getProperty
Parameter specified as non-null is null: method org.koin.core.Koin.getProperty, parameter defaultValue
```

A nil literal for a plainly non-null parameter is a `kt:` error (`` `nil` passed to non-nullable `values` `` for `properties`). Here the parameter type is the type parameter `T` with the bound `Any`, and `kt` lets nil through to the null check of Kotlin.
Workaround: `getProperty(key)` with one argument gives nil for a missing key.
ckway change: check nil against the upper bound of a type parameter (`T : Any` is not nullable).

### 16. Override is checked across modules only

Two definitions with the same key inside ONE module do not throw. This is Koin, not ckway.

### 18. Hints

Without a hint: `call to .modules can't be resolved statically`. The spike uses `^org.koin.core.KoinApplication`, `^org.koin.core.module.Module`, `^java.util.List`, `^String`, and `^kotlin.reflect.KClass` on the `def` of a class value. This is rule 7, not a defect.

## Versions

- Koin: `io.insert-koin/koin-core-jvm` 4.2.2 (the newest stable on Maven Central; later entries are alpha, beta or RC). Use the `-jvm` artifact: tools.deps cannot read the Gradle metadata of `koin-core`. Transitive: `co.touchlab/stately-*` 2.1.0.
- Kotlin metadata of the Koin classes: 2.3.0.
- Kotlin compiler for `:<>`: `kotlin-compiler-embeddable` 2.4.20.
- ckway: this repository by `:local/root "../.."` (base commit 7a303cf).
- JDK: OpenJDK 25.0.4. Clojure 1.12.1.

## Not done

- A run with a cold cache and no compiler, except the one test that checks the error message of a `:<>` call with no stored bridge.
- A timing of a cold and a warm bridge compile.
