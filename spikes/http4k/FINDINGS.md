# Findings: http4k through ckway

This file shows the state after the ckway fixes of the branch `fix/spike-findings` (commit d446567). The section "History" says what each row was before.

## Verdict

ckway fits http4k well. The Catalog API (routes, path and query lenses, three filters, a real SunHttp server) is written in Clojure with `kt` forms that follow the Kotlin text. No Kotlin glue is needed.
One form still needs a workaround: `Filter { }` is an `inline` function, so the filter is a `kt/reify` of the interface.
All 21 tests pass (139 assertions), with no reflection warnings. The orchestrator ran them again on d446567.

## Findings

`h` = `org.http4k.core`, `r` = `org.http4k.routing`, `l` = `org.http4k.lens`, `srv` = `org.http4k.server`.

| # | Kotlin form | Clojure form | Result | Note |
|---|---|---|---|---|
| 1 | `Request(GET, "/a")`, `Response(OK)` | `(h/Request h/Method.GET "/a")`, `(h/Response status)` | works | The companion `operator fun invoke`. `(h/.invoke h/Request ...)` also works. |
| 2 | `Status.OK`, `Method.GET` | `(h/OK h/Status)`, `h/Method.GET` | works | `Status.OK` is a companion property. `Method` is an enum. |
| 3 | `handler(request)` | `(h/.invoke handler req)` | works | A local needs the hint `^kotlin.jvm.functions.Function1` for the static path; with no hint there is a reflection warning. `(handler req)` does not work: a `RoutingHttpHandler` is not a Clojure fn. |
| 4 | `routes(a, b, ...)` | `(r/routes a b ...)` | works | `(r/routes :list [a b])` selects by the elements. `(r/routes a)` with one argument is an "ambiguous" error with two ways out. |
| 5 | `Filter { next -> { req -> ... } }` | `(kt/reify h/Filter (.invoke [_ next] (fn [req] ... (next req))))` | workaround | `Filter(fn)` is an `inline` function with no JVM method. Not a ckway defect. |
| 6 | a `Filter` member that returns a handler | a plain `(fn [req] ...)` | works | The returned fn is adapted to the declared function type. |
| 7 | `Filter.NoOp` | `(h/NoOp h/Filter)` | works | Pinned in a test; the app does not use it. |
| 8 | `"/health" bind GET to { ... }` | `(r/.to (r/.bind "/health" h/Method.GET) handler)` | works | Static path, no warning, no helper. |
| 9 | `Path.int().of("id")`, `Query.optional("tag")`, `lens(request)` | `(l/.of (l/.int l/Path) "id")`, `(l/.optional l/Query "tag")`, `(l/.invoke ^org.http4k.lens.LensExtractor lens req)` | works | The hint must be `LensExtractor`. A bad number throws `LensFailure`; the error filter makes it a 400. |
| 10 | `handler.asServer(SunHttp(0)).start()`, `server.port()`, `server.stop()` | `(srv/.start (srv/.asServer handler (srv/SunHttp (int port))))` | works | `(int port)` only avoids a reflection warning for an untyped `port`. |
| 11 | `req.path`, `bodyString()`, `header(...)`, `body(...)`, `request.method` | `r/.path`, `h/.bodyString`, `h/.header`, `h/.body`, `h/method` | works | Many functions of a package share a name. Hints on locals pick the right one. |
| 12 | metadata read | `kt/require` of core, routing, lens, server | works | http4k 6.61.0.0 classes have metadata 2.4.0. |

`test/spike/http4k/interop_test.clj` pins rows 1 to 4 and 6 to 9, so a regression in ckway shows here.

## Details

### 3. Call of an `HttpHandler` value

`(h/.invoke handler req)` works. Rule 6 makes a Kotlin lambda a Clojure fn, but a `RoutingHttpHandler` is a class that implements `(Request) -> Response`, so it stays an object:

```clojure
(hh req)
;; ClassCastException: class org.http4k.routing.RoutingHttpHandler cannot be cast to class clojure.lang.IFn
```

Inside a `kt/reify` member, a parameter with a function type (`next`) IS a Clojure fn: `(next req)`.

### 4. `routes` with one argument

`routes(h)` (a function) and `h.routes` (an extension property) are both `(r/routes h)`. The call is now an error and not the property value:

```
kt: (routes #object[org.http4k.routing.RoutingHttpHandler ...]) is ambiguous. Candidates:
    val ...RoutingHandler<R, F, Self>.routes: collections.List<...>
    fun routes(vararg list: ...RoutingHttpHandler): ...
  Why: a function and a property are both named `routes`, so they share one var, and this call fits both. ...
  Way out: the function: name a parameter, `(r/routes :list ...)`
           the property: `((kt/ref r/RoutingHandler routes) x)`
```

### 5. `Filter { ... }`

`Filter(fn)` is `inline fun ... crossinline`. It has no JVM method, so no library can call it. `kt/reify` of the interface is the way.

### Open notes for ckway (small)

- A hint with a SUBCLASS of the class that declares `invoke` (`^org.http4k.lens.BiDiPathLens lens`) gives the reflection warning "can't be resolved statically". The hint `^LensExtractor` is static. A subclass hint should be static too.
- A local that holds the result of a `defn` with a return tag (`(let [a (ok-handler)] (r/routes a b))`) can still warn at `routes`. A hint on the local is static. A direct nested call is static.
- A nil from a variable with NO hint at `(h/Request h/Method.GET v)` takes the dynamic path. The error is then "fits no constructor and no `operator fun invoke` of the companion object", which is less clear than the static one: "nil where Kotlin expects a non-null String (the argument `uri` (String))".

### Difference from the Catalog spec

A known path with a wrong method gives 405 `{"error":"method not allowed"}` (the choice of http4k). An unknown path gives 404. The Ktor spike does the same now.

## History

The first run of the spike (ckway 7a303cf) needed these workarounds. The fixes removed them.

| # | Before | Fixed by |
|---|---|---|
| 1 | `(h/Request ...)` was the error "has no public constructor: it is an interface" | B1: companion `invoke` as a constructor form |
| 3 | `(h/.invoke handler req)` was "no Kotlin declaration of `.invoke` fits"; plain Java `.invoke` was used | B2: `invoke` of a class that implements a function type |
| 4 | `(r/routes a)` returned the property value (a `List`) with no error; `(r/routes :list [a])` was "ambiguous" | A1: a property does not win over a function; A3: a vararg collection selects by its elements |
| 6 | a fn that a `kt/reify` member returned was not converted: `ClassCastException` inside http4k | B4: the result of a `kt/reify` member is adapted |
| 7 | `(h/NoOp h/Filter)` was "`receiver` is Companion but got AFn" | B3: the class var as the receiver of a Companion extension |
| 8 | `(r/.bind "/health" h/Method.GET)` gave a reflection warning | C2: an enum entry var has a static type |
| 9 | worked first; then "ambiguous" after B2 (a regression that this spike found) | D1: a declared `invoke` and the function-type `invoke` are one member |

## Versions

- http4k: `org.http4k/http4k-core` 6.61.0.0 (the newest on Maven Central on 2026-10-05). Server backend: `org.http4k.server.SunHttp`, part of http4k-core.
- Kotlin metadata of the http4k classes: 2.4.0. Read with kotlin-metadata-jvm 2.4.20.
- ckway: this repository by `:local/root "../.."`, at `fix/spike-findings` d446567.
- JDK: OpenJDK 25.0.4. Clojure 1.12.1. data.json 2.5.1.
- The alias `:kotlinc` is in `deps.edn` but is not used: the spike has no `:<>` call.
