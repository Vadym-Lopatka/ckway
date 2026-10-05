# Findings: http4k through ckway

## Verdict

ckway fits http4k well. The Catalog API (routes, path and query lenses, three filters, a real SunHttp server) is written in Clojure with `kt` forms and a little plain interop. No Kotlin glue was needed. All 12 tests pass (116 assertions), with no reflection warnings.
The friction is in four places: the `Request(...)`/`Response(...)` factories, a call of an `HttpHandler` value, `Filter { }` (an inline function), and `routes(x)` with one argument. Each has a short workaround.
Rows 1 and 3 to 7 were reproduced a second time by the orchestrator, in a fresh JVM.

## Findings

`h` = `org.http4k.core`, `r` = `org.http4k.routing`, `l` = `org.http4k.lens`, `srv` = `org.http4k.server`.

| # | Kotlin form | Clojure form | Result | ckway rule or limit | Note |
|---|---|---|---|---|---|
| 1 | `Request(GET, "/a")`, `Response(OK)` | `(h/.invoke h/Request h/Method.GET "/a")` | workaround | rule 3 | `(h/Request ...)` is a `kt:` error: the class is an interface. The factory is the companion `operator fun invoke`. |
| 2 | `Status.OK`, `Method.GET` | `(h/OK h/Status)`, `h/Method.GET` | works | rule 3 | `Status.OK` is a companion property, so the class var is the receiver. `Method` is an enum. |
| 3 | `handler(request)` (`typealias HttpHandler = (Request) -> Response`) | `(.invoke ^Function1 handler req)` | workaround | rule 6 | A routing handler is a Kotlin class that implements `Function1`. It is not a Clojure fn, and `(h/.invoke handler req)` is a `kt:` error. |
| 4 | `routes(a)` | `(apply r/routes [a b ...])` | workaround | rule 7 | With ONE argument `(r/routes a)` calls the extension property `routes` and returns a `List`. No error. |
| 5 | `Filter { next -> { req -> ... } }` | `(kt/reify h/Filter (.invoke [_ next] ...))` | workaround | rule 10 | `Filter(fn)` is an `inline` function with no JVM method. `kt/reify` of the interface works. |
| 6 | a `Filter` member that returns a handler | `(reify kotlin.jvm.functions.Function1 (invoke [_ req] ...))` | workaround | rules 6 and 10 | A Clojure fn that a `kt/reify` member returns is not converted to `Function1`: `ClassCastException` later, inside http4k. |
| 7 | `Filter.NoOp` | `(h/NoOp org.http4k.core.Filter/Companion)` | workaround | rule 3 | An extension property on a `Companion` does not accept the class var `h/Filter`. Not used in the final code. |
| 8 | `"/health" bind GET to { ... }` | `(r/.to (r/.bind path method) handler)` | works | rules 2 and 6 | Both infix functions are plain calls. A string literal as receiver gives a reflection warning; a `^String` parameter does not. |
| 9 | `Path.int().of("id")`, `Query.optional("tag")`, `lens(request)` | `(l/.of (l/.int l/Path) "id")`, `(l/.optional l/Query "tag")`, `(l/.invoke ^LensExtractor lens req)` | works | rules 2, 3 and 7 | A bad number throws `LensFailure`; the error filter makes it a 400. The hint on the lens avoids reflection. |
| 10 | `handler.asServer(SunHttp(0)).start()`, `server.port()`, `server.stop()` | `(srv/.start (srv/.asServer handler (srv/SunHttp (int port))))` | works | rules 1 and 4 | Start on port 0, stop and restart are clean. |
| 11 | `req.path`, `bodyString()`, `header(...)`, `body(...)`, `request.method` | `r/.path`, `h/.bodyString`, `h/.header`, `h/.body`, `h/method` | works | rule 2 | Many functions of a package share a name (`.invoke`, `.then`, `.header`). Hints on locals pick the right one. |
| 12 | metadata read | `kt/require` of core, routing, lens, server | works | Requirements | http4k 6.61.0.0 classes have metadata 2.4.0. |

## Details

### 1. `Request(...)` and `Response(...)`

```clojure
(h/Request h/Method.GET "/a")
;; kt: `Request` has no public constructor: it is an interface. Implement it with kt/reify, or call a function that returns one.
```

Workaround: `(h/.invoke h/Request h/Method.GET "/a")`, `(h/.invoke h/Response (h/OK h/Status))`.
ckway change: when the companion of a class has `operator fun invoke`, let `(Request ...)` call it, as Kotlin does. At least name the companion `invoke` in the error.

### 3. Call of an `HttpHandler` value

```clojure
(def hh (r/.to (r/.bind "/a" h/Method.GET) (fn [rq] (h/.invoke h/Response (h/OK h/Status)))))
(h/.invoke hh (h/.invoke h/Request h/Method.GET "/a"))
;; kt: no Kotlin declaration of `.invoke` fits (.invoke #object[org.http4k.routing.RoutingHttpHandler ...] #object[org.http4k.core.MemoryRequest ...])
```

Rule 6 says a Kotlin function value that Clojure gets is a Clojure function. That holds for a lambda, not for a class that implements `Function1`.
Workaround: `(.invoke ^kotlin.jvm.functions.Function1 hh req)` (`api/call`).
ckway change: `(.invoke f x)` on an object that implements `FunctionN` calls `invoke`.

### 4. `routes` with one argument

```clojure
(r/routes hh)         ;; => java.util.Collections$SingletonList (a List, not a RoutingHttpHandler)
(r/routes hh hh)      ;; => org.http4k.routing.RoutingHttpHandler
(r/routes :list [hh])
;; kt: (routes :list [hh]) is ambiguous. Candidates:
;;     fun routes(vararg list: Pair<Method, (Request) -> Response>): RoutingHttpHandler
;;     fun routes(vararg list: RoutingHttpHandler): RoutingHttpHandler
```

The one-argument call selects `val RoutingHttpHandler.routes: List<...>` (an extension property) before the top-level vararg function. In Kotlin `routes(x)` is always the function. This is the most serious finding: a wrong value and no error.
Workaround: `(apply r/routes [a b c d e])`, and never one route. The written form with five untyped arguments also works, with a reflection warning.
ckway change: in `(f x)` a property with receiver `x` must not win over a top-level function of the same name, or the call must be an "ambiguous" error. Also: select a vararg overload when every element of the collection is an instance of one candidate's element class.

### 5. `Filter { ... }`

```clojure
(h/.invoke h/Filter (fn [nxt] nxt))
;; kt: no Kotlin declaration of `.invoke` fits (.invoke h/Filter (fn [nxt] nxt))
```

`Filter(fn)` is `inline fun ... crossinline`. It has no JVM method.
Workaround: `kt/reify` of `Filter`.
ckway change: none needed. The docs could say: an inline function that takes a lambda is not callable; use `kt/reify` of the interface.

### 6. A function that a `kt/reify` member returns

```clojure
(def f (kt/reify h/Filter (.invoke [this nxt] (fn [rq] (.invoke ^kotlin.jvm.functions.Function1 nxt rq)))))
(.invoke ^kotlin.jvm.functions.Function1 (h/.then ^org.http4k.core.Filter f hh) rq)
;; java.lang.ClassCastException: class ...$fn__6144 cannot be cast to class kotlin.jvm.functions.Function1
```

Rule 6 converts a fn that is an ARGUMENT of a Kotlin call. It does not convert the RETURN value of a `kt/reify` member. The error comes later, inside http4k.
Workaround: `(reify kotlin.jvm.functions.Function1 (invoke [_ req] ...))` (`api/->handler`).
ckway change: convert the returned fn to the declared function type of the member.

### 7. `Filter.NoOp`

```clojure
(h/NoOp h/Filter)
;; kt: no Kotlin declaration of `NoOp` fits (NoOp h/Filter)
;;     val org.http4k.core.Filter.Companion.NoOp: org.http4k.core.Filter
;;       -> `receiver` is Companion but got AFn
```

Workaround: `(h/NoOp org.http4k.core.Filter/Companion)`.
ckway change: accept the class var as the receiver of an extension property on a `Companion`.

### 8. A string literal as receiver

`(r/.bind "/health" h/Method.GET)` works, with `Reflection warning: call to .bind can't be resolved statically (argument or receiver types unknown); using the dynamic path.`
Workaround: a helper with `^String path` and `^Method method` parameters (`api/route`).
ckway change: a string literal receiver is a `String` on the static path.

### 9. A lens without a hint

`(l/.invoke lens rq)` works, with a reflection warning. With `^org.http4k.lens.LensExtractor` it takes the static path.

### Difference from the Catalog spec

A known path with a wrong method gives 405 `{"error":"method not allowed"}` (the choice of http4k). Only an unknown path gives 404.

## Versions

- http4k: `org.http4k/http4k-core` 6.61.0.0 (the newest on Maven Central on 2026-10-05). Server backend: `org.http4k.server.SunHttp`, part of http4k-core.
- Kotlin metadata of the http4k classes: 2.4.0. Read with kotlin-metadata-jvm 2.4.20.
- ckway: this repository by `:local/root "../.."` (base commit 7a303cf).
- JDK: OpenJDK 25.0.4. Clojure 1.12.1. data.json 2.5.1.
- The alias `:kotlinc` is in `deps.edn` but is not used: the spike has no `:<>` call.
