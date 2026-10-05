# Findings: Ktor through ckway

## Verdict

ckway fits Ktor well. The Catalog API (server, routing DSL, suspend handlers, StatusPages, CallLogging, request and response calls), `testApplication`, the Ktor client and the `inline reified` calls all work from Clojure. No Kotlin glue was needed.
One Ktor form is blocked as a `kt` form: `StatusPagesConfig.status { call, code -> }`. Its two overloads have the same JVM parameter types. `kt` says "ambiguous" and makes no wrong call. Plain Java interop reaches it; the app uses a catch-all route.
The other friction is small: to find which namespace holds a member, type hints against reflection warnings, and the start-up behaviour of Ktor itself (after a failed module, `resolvedConnectors()` waits for ever).
The orchestrator ran `bin/test` again (15 tests, 99 assertions). The rows below were not reproduced a second time one by one.

## Findings

`eng` = `io.ktor.server.engine`, `cio` = `io.ktor.server.cio`, `app` = `io.ktor.server.application`, `rt` = `io.ktor.server.routing`, `resp` = `io.ktor.server.response`, `req` = `io.ktor.server.request`, `sp` = `io.ktor.server.plugins.statuspages`, `cl` = `io.ktor.server.plugins.calllogging`, `http` = `io.ktor.http`. In the tests: `tst` = `io.ktor.server.testing`, `creq` = `io.ktor.client.request`, `cst` = `io.ktor.client.statement`, `ccio` = `io.ktor.client.engine.cio`.

| # | Kotlin form | Clojure form | Result | ckway rule or limit | Note |
|---|---|---|---|---|---|
| 1 | `embeddedServer(CIO, port = 0, host = h) { module }` | `(eng/embeddedServer cio/CIO :port 0 :host h :module (fn [app] ...))` | works | rules 4 and 6 | The module is a plain Clojure fn. |
| 2 | `install(StatusPages) { ... }` | `(app/.install application (sp/StatusPages) (fn [cfg] ...))` | works | rule 2 | `StatusPages` is a property: `(sp/StatusPages)` needs the parentheses. Without them: `kt: ... plugin is Plugin but got core$var_root$f__...`. |
| 3 | `exception<Throwable> { call, cause -> }` | `(sp/.exception cfg (kt/ref Throwable class) f)`, `(sp/.exception cfg f :<> Throwable)` | works (both) | rules 9 and 5 | The `:<>` form needs `:kotlinc`. The app uses the `kt/ref` form. |
| 4 | `status(HttpStatusCode.NotFound) { call, code -> }` | `(sp/.status cfg code :handler f)` | blocked as a `kt` form; works with Java interop | rule 7, erased overloads | "ambiguous" when the module runs, so `start` throws. Never a wrong call. See Details. |
| 5 | `routing { get; route { get; post; delete } }` | `rt/.routing`, `rt/.get`, `rt/.route`, `rt/.post`, `rt/.delete` | works | rule 6 | The overloads with and without a path, and the reified `post<R>`, resolve correctly. |
| 6 | a handler `suspend RoutingContext.() -> Unit` | `(fn [^RoutingContext ctx] ...)`, `(rt/call ctx)` | works | suspend note | Runs on a virtual thread. The return value of the handler is ignored. |
| 7 | `call.parameters["id"]` | `(http/.get (app/parameters call) "id")` | works | rule 2 | `get` is an extension in `io.ktor.http`. |
| 8 | `call.request.queryParameters["tag"]` | `(http/.get (req/queryParameters (app/request call)) "tag")` | works | rule 2 | |
| 9 | `call.receiveText()` | `(req/.receiveText call)` | works | suspend note | `suspend inline`, not reified: a usual call. |
| 10 | `call.respondText(t, ContentType.Application.Json, HttpStatusCode.Created)` | `(resp/.respondText call t (http/Json http/ContentType.Application) (http/Created http/HttpStatusCode))` | works | rule 3 | `:status s` can be named. |
| 11 | `call.response.headers.append("Location", u)` | `(resp/.append (resp/headers (app/response call)) "Location" u)` | works | rule 1 | The namespace is the package of the class that declares the member. `app/headers` and `rt/.append` do not exist. |
| 12 | `install(CallLogging)` | `(app/.install application (cl/CallLogging))` | works | rule 2 | |
| 13 | `server.start(wait = false)` | `(eng/.start server :wait false)` | works | rule 4 | Throws the exception of a failing module. |
| 14 | `engine.resolvedConnectors().first().port` | `(eng/port (first (eng/.resolvedConnectors (eng/engine server))))` | works | suspend note | Never returns after a failed module (Ktor), see 22. |
| 15 | `server.stop(100, 2000)` | `(eng/.stop server 100 2000)` | works | rule 4 | |
| 16 | `testApplication { application { m }; client.get("/health") }` | `(tst/testApplication (fn [b] (tst/.application b m) (creq/.get (tst/client b) "/health")))` | works | suspend note, rule 6 | Needs `ktor-server-test-host-jvm`. |
| 17 | `HttpClient(CIO)`, `client.get(url)`, `response.bodyAsText()` | `(cl/HttpClient ccio/CIO)`, `(creq/.get client url)`, `(cst/.bodyAsText r)` | works | suspend note | `get` has String, Url and URL overloads: hint `^String`. |
| 18 | `client.post(url) { header(..); setBody(text) }` | `(creq/.post client url (fn [b] (creq/.header b k v) (creq/.setBody b text :<> String)))` | works | rule 5 | `setBody` is `inline reified`. |
| 19 | `call.receive<String>()`, `call.respond(text)`, `call.respond(status, text)` | `(req/.receive call :<> String)`, `(resp/.respond call s :<> String)`, `(resp/.respond call status s :<> String)` | works | rule 5 | Needs `:kotlinc`. `:<>` must be last, also after a lambda. |
| 20 | reflection warnings | `^RoutingContext ctx`, `^Application application`, `^String (f ...)` | workaround | rule 7 (hints) | `kt` does not use the return hint of a `defn`. |
| 21 | a server in a `def` | `(let [server ...] ...)` | workaround | dynamic path | `.start` and `.stop` on a var value give a reflection warning. They still work. |
| 22 | a module that throws | `start!` polls with a time limit and stops the server | workaround | none (Ktor) | See Details. |

## Details

### 4. `status(...)`

```clojure
(sp/.status cfg (http/NotFound http/HttpStatusCode)
            :handler (fn [^io.ktor.server.application.ApplicationCall call st] ...))
```

Without `:handler` the lambda is after a `vararg`, so it must be named:

```
kt: no Kotlin declaration of `.status` fits (.status cfg ... (fn ...))
    fun ...StatusPagesConfig.status(vararg status: HttpStatusCode, handler: suspend (ApplicationCall, HttpStatusCode) -> Unit): Unit
      -> missing required parameter `handler`. Pass it positionally or as `:handler`.
```

With `:handler`:

```
kt: (.status #object[...StatusPagesConfig...] #object[...HttpStatusCode ...] :handler #object[...]) is ambiguous. Candidates:
    fun ...status(vararg status: HttpStatusCode, handler: suspend (ApplicationCall, HttpStatusCode) -> Unit): Unit
    fun ...status(vararg status: HttpStatusCode, handler: suspend (StatusPagesConfig.StatusContext, HttpStatusCode) -> Unit): Unit
  Why: these declarations take the same JVM parameter types. They differ only in a type argument (`Iterable<Int>` or `Iterable<Long>`) or in the result type of a lambda ...
  Way out: ... (.status x y z) / (.statusWithContext x y z)   ; JVM descriptor ([Lio/ktor/http/HttpStatusCode;Lkotlin/jvm/functions/Function3;)V
```

What happens:

- The form does not become a static call. The error comes at run time, when the module runs, so `server.start` throws it. With a typed `cfg` and with a `cfg` from a `def` the error is the same.
- A `^ApplicationCall` hint on the parameter of the `fn` does not select an overload.
- No overload is called. `kt` never makes a wrong call here.
- The "Why" text says "result type of a lambda". Here the PARAMETER type of the lambda differs.

The way out of the error text works, with plain Java interop:

```clojure
(.status ^StatusPagesConfig cfg
         (into-array HttpStatusCode [(http/NotFound http/HttpStatusCode)])
         (reify kotlin.jvm.functions.Function3
           (invoke [_ call status k]
             (resp/.respondText ^ApplicationCall call "{\"error\":\"not found\"}"
                                (http/Json http/ContentType.Application) ^HttpStatusCode status)
             kotlin.Unit/INSTANCE)))
```

Result: `/health` gives 200 and an unknown path gives 404 `application/json {"error":"not found"}`. `.statusWithContext` with the same `reify` gets a `StatusPagesConfig$StatusContext`: that is the second overload.
The app does not use it. A suspend lambda that is written by hand must return `kotlin.Unit/INSTANCE`, its `respondText` blocks a Ktor thread, and it cannot be cancelled.
Workaround in `core.clj`: a last route `route("{...}") { handle { ... } }` that answers 404 JSON. So a known path with a wrong method gives 404, not 405.
ckway change: let the type hint on a parameter of a `fn` literal select between overloads that differ only in a function type. Report this ambiguity at compile time when the receiver type is known. Correct the "Why" text for this case.

### 19. `:<>`

- All `:<>` calls work. The first run compiles the bridges: `reified_test` takes about 10 s with an empty `target/cache`.
- `:<>` must be last. `(sp/.exception cfg :<> Throwable f)` gives `kt: ...: positional argument (fn ...) follows a named argument. Put all positional arguments before the first keyword.`
- The app runs and answers requests without `:kotlinc`.

### 20. Reflection warnings

- A `fn` literal that is passed where Kotlin wants a function type gets typed parameters from ckway. `(fn [r] (rt/.get r ...))` gives no warning.
- A parameter of a `defn` is not typed. `(defn- health [ctx] (rt/call ctx))` gives `Reflection warning ... call to call can't be resolved statically (argument or receiver types unknown)`. Fix: `[^RoutingContext ctx]`. The same for `Application` and `EmbeddedServer`.
- The return hint of a `defn` is not used. `(defn u ^String [s] ...)` and then `(creq/.get client (u "/x"))` warns; `(creq/.get client ^String (u "/x"))` does not.
- ckway change: read the `:tag` of the arglists of a var.

### 22. A failing module and `resolvedConnectors()`

This is Ktor, not ckway.

```clojure
(let [srv (eng/embeddedServer cio/CIO :port 0 :module (fn [a] (throw (ex-info "plain" {}))))]
  (try (eng/.start srv :wait false) (catch Throwable e (ex-message e)))        ; => "plain"
  (deref (future (eng/.resolvedConnectors (eng/engine srv))) 2000 :hangs))      ; => :hangs
```

The same when the exception is in the config lambda of a plugin (also the "ambiguous" error of finding 4). In a REPL the evaluation hangs.
`start!` calls `resolvedConnectors` in a `future` with the limit `:start-timeout-ms`. On a failure it stops the server and throws an `ex-info`. A port that is in use also ends in the time limit: Ktor CIO prints a `BindException` to stderr.
An earlier note of an "empty 404 after a `status` call that compiled" was a REPL mistake: a failed `def` kept the old server value.

### Suspend and the limits

No handler has a suspend call inside `catch Exception`. The one `catch Exception` is in a pure JSON parse, and it throws an `InterruptedException` again first. All other exceptions go out to StatusPages, which gives the 500.

## Versions

- Ktor 3.6.0, the newest on Maven Central (`maven-metadata.xml`, `lastUpdated` 2026-09-16). Engine: CIO.
- Kotlin metadata of the Ktor classes: 2.3.0.
- kotlinx-coroutines-core-jvm 1.11.0 as a top-level dependency: Ktor 3.6.0 asks for it. The README of ckway names 1.10.2.
- ckway: this repository by `:local/root "../.."` (base commit 7a303cf).
- JDK 25.0.4, Clojure 1.12.1, Kotlin compiler for `:kotlinc` 2.4.20, data.json 2.5.1, slf4j-simple 2.0.17.

## Not done

- Netty (CIO only).
- A cancel when the client disconnects.
- `bin/test` runs with `:kotlinc` only. A test run with stored bridges and no compiler was not made; the app was started without `:kotlinc` and answered.
