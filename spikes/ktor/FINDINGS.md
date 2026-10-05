# Findings: Ktor through ckway

This file shows the state after the ckway fixes of the branch `fix/spike-findings` (commit d446567). The section "History" says what each row was before.

## Verdict

ckway fits Ktor well. The Catalog API (server, routing DSL, suspend handlers, StatusPages with `exception` and `status`, CallLogging, request and response calls), `testApplication`, the Ktor client and the `inline reified` calls all work from Clojure. No Kotlin glue is needed.
No Ktor form is blocked now. Two workarounds stay: a server in a `def` has no static type, and Ktor itself waits for ever in `resolvedConnectors()` after a failed module.
All 21 tests pass (127 assertions), with no reflection warnings. The orchestrator ran them again on d446567.

## Findings

`eng` = `io.ktor.server.engine`, `cio` = `io.ktor.server.cio`, `app` = `io.ktor.server.application`, `rt` = `io.ktor.server.routing`, `resp` = `io.ktor.server.response`, `req` = `io.ktor.server.request`, `sp` = `io.ktor.server.plugins.statuspages`, `cl` = `io.ktor.server.plugins.calllogging`, `http` = `io.ktor.http`. In the tests: `tst` = `io.ktor.server.testing`, `creq` = `io.ktor.client.request`, `cst` = `io.ktor.client.statement`, `ccio` = `io.ktor.client.engine.cio`.

| # | Kotlin form | Clojure form | Result | Note |
|---|---|---|---|---|
| 1 | `embeddedServer(CIO, port = 0, host = h) { module }` | `(eng/embeddedServer cio/CIO (long p) ^String h (fn [app] ...))` | works | The trailing lambda goes to `module`; `watchPaths` keeps its default. Untyped `(:port m)` `(:host m)` also work, on the dynamic path with a reflection warning. |
| 2 | `install(StatusPages) { ... }` | `(app/.install application (sp/StatusPages) (fn [cfg] ...))` | works | `StatusPages` is a property: `(sp/StatusPages)` needs the parentheses. `cfg` gets its type from the plugin; no hint. |
| 3 | `exception<Throwable> { call, cause -> }` | `(sp/.exception cfg (kt/ref Throwable class) f)`, `(sp/.exception cfg f :<> Throwable)` | works | The `:<>` form needs `:kotlinc`. The app uses the `kt/ref` form. |
| 4 | `status(HttpStatusCode.NotFound) { call, code -> }` | `(sp/.status cfg (http/NotFound http/HttpStatusCode) (fn [^ApplicationCall call status] ...))` | works | The hint selects the overload; `^StatusPagesConfig$StatusContext` selects the other one. No hint is a compile-time "ambiguous" error that names the hint. |
| 5 | `routing { get; route { get; post; delete } }` | `rt/.routing`, `rt/.get`, `rt/.route`, `rt/.post`, `rt/.delete` | works | |
| 6 | a handler `suspend RoutingContext.() -> Unit` | `(fn [ctx] ...)`, `(rt/call ctx)` | works | Runs on a virtual thread. A `defn` parameter needs the hint `^RoutingContext`; a `fn` literal at the call does not. |
| 7 | `call.parameters["id"]` | `(http/.get (app/parameters call) "id")` | works | `get` is an extension in `io.ktor.http`. |
| 8 | `call.request.queryParameters["tag"]` | `(http/.get (req/queryParameters (app/request call)) "tag")` | works | A missing parameter is nil. |
| 9 | `call.receiveText()` | `(req/.receiveText call)` | works | |
| 10 | `call.respondText(t, ContentType.Application.Json, HttpStatusCode.Created)` | `(resp/.respondText call t (http/Json http/ContentType.Application) (http/Created http/HttpStatusCode))` | works | `:status s` can be named. |
| 11 | `call.response.headers.append(k, v)` | `(resp/.append (resp/headers (app/response call)) k v)` | works | The namespace is the package of the class that declares the member. Find it with `doc`. |
| 12 | `install(CallLogging)` | `(app/.install application (cl/CallLogging))` | works | |
| 13 | `server.start(wait = false)` | `(eng/.start server :wait false)` | works | Throws the exception of a failing module. |
| 14 | `engine.resolvedConnectors().first().port` | `(eng/port (first (eng/.resolvedConnectors (eng/engine server))))` | works | Never returns after a failed module (Ktor), see 22. |
| 15 | `server.stop(100, 2000)` | `(eng/.stop server 100 2000)` | works | |
| 16 | `testApplication { application { m }; client.get("/health") }` | `(tst/testApplication (fn [b] (tst/.application b m) (creq/.get (tst/client b) "/health")))` | works | Needs `ktor-server-test-host-jvm`. No hint on `b`. |
| 17 | `HttpClient(CIO)`, `client.get(url)`, `response.bodyAsText()` | `(cl/HttpClient ccio/CIO)`, `(creq/.get client (url "/x"))`, `(cst/.bodyAsText r)` | works | `get` has String, Url and URL overloads. The return hint `^String` of the `defn` `url` selects. |
| 18 | `client.post(url) { header(..); setBody(text) }` | `(creq/.post client url (fn [b] (creq/.header b k v) (creq/.setBody b text :<> String)))` | works | `setBody` is `inline reified`. |
| 19 | `call.receive<String>()`, `call.respond(text)`, `call.respond(status, text)` | `(req/.receive call :<> String)`, `(resp/.respond call s :<> String)`, `(resp/.respond call status s :<> String)` | works | Needs `:kotlinc`. `:<>` must be last, also after a lambda. |
| 20 | type hints | hints on `defn` parameters only | works | A `fn` literal at a Kotlin function type has typed parameters (`Application`, `ApplicationTestBuilder`, `cfg`). A `defn` return hint is used. |
| 21 | a server in a `def` | `(let [server ...] ...)` | workaround | A var value has no static type (limits 13): `.start` on it gives a reflection warning and still works. |
| 22 | a module that throws | `start!` waits with a time limit and stops the server | workaround | Ktor, not ckway. See Details. |
| 23 | a wrong method on a known path | `route("/{id}") { get; delete; handle { 405 } }` and `status(MethodNotAllowed)` | works | Ktor alone gives 405 on a constant path and 404 on a `{id}` path. The extra `handle` makes it 405 for both. No `Allow` header. |

`test/spike/ktor/interop_test.clj` pins rows 1, 2, 4, 17 and 20, so a regression in ckway shows here.

## Details

### 4. `status(...)`

The two overloads have the same JVM parameter types. They differ in the first parameter of the lambda (`ApplicationCall` or `StatusContext`). A type hint on that parameter selects:

```clojure
(sp/.status cfg (http/NotFound http/HttpStatusCode)
            (fn [^io.ktor.server.application.ApplicationCall call status] ...))
```

With no hint, the compiler stops:

```
kt: (.status cfg (http/NotFound http/HttpStatusCode) (fn [call status] nil)) is ambiguous. Candidates:
    fun ...StatusPagesConfig.status(vararg status: HttpStatusCode, handler: suspend (ApplicationCall, HttpStatusCode) -> Unit): Unit
    fun ...StatusPagesConfig.status(vararg status: HttpStatusCode, handler: suspend (StatusPagesConfig.StatusContext, HttpStatusCode) -> Unit): Unit
  Why: these declarations take the same JVM parameter types. They differ only in the parameter types of a lambda, and the JVM erases them, so a Clojure value cannot choose between them. kt does not guess.
  Way out: write the lambda as a `fn` with a type hint on the parameters that tell the candidates apart, e.g. `(fn [^io.ktor.server.application.ApplicationCall x y] ...)` ...
```

The hints work on the static path only. `cfg` is on the static path because its type comes from `(sp/StatusPages)`. If the plugin is held in a local, `cfg` has no type: then write `(fn [^StatusPagesConfig cfg] ...)` (`doc/limits.md`, 18).

### 19. `:<>`

- All `:<>` calls work. The first run compiles the bridges: `reified_test` takes about 10 s with an empty `target/cache`.
- `:<>` must be last. `(sp/.exception cfg :<> Throwable f)` gives `kt: ...: positional argument (fn ...) follows a named argument. Put all positional arguments before the first keyword.`
- The app runs and answers requests without `:kotlinc`.

### 22. A failing module and `resolvedConnectors()`

```clojure
(let [srv (eng/embeddedServer cio/CIO :port 0 :module (fn [a] (throw (ex-info "plain" {}))))]
  (try (eng/.start srv :wait false) (catch Throwable e (ex-message e)))        ; => "plain"
  (deref (future (eng/.resolvedConnectors (eng/engine srv))) 2000 :hangs))      ; => :hangs
```

The same when the exception is in the config lambda of a plugin. In a REPL the evaluation hangs.
`start!` calls `resolvedConnectors` in a `future` with the limit `:start-timeout-ms`. On a failure it stops the server and throws an `ex-info`. A port that is in use also ends in the time limit: Ktor CIO prints a `BindException` to stderr.

### Suspend and the limits

No handler has a suspend call inside `catch Exception`. The one `catch Exception` is in a pure JSON parse, and it throws an `InterruptedException` again first. All other exceptions go out to StatusPages, which gives the 500.

## History

The first run of the spike (ckway 7a303cf) needed these workarounds. The fixes removed them.

| # | Before | Fixed by |
|---|---|---|
| 1 | the lambda had to be named (`:module f`); later, untyped `(:port m)` `(:host m)` selected the wrong overload and failed at run time ("`environment` ... is java.lang.Long 0") | A2: trailing lambda; D5: unknown types go to the dynamic path |
| 2 | `cfg` had no type; the inner `status` call was ambiguous at run time unless `cfg` had a hint | D6: a type argument that another argument fixes types the lambda parameter |
| 4 | blocked: "ambiguous", also with a hint; the error came when the module ran, so `start` threw. The app used a catch-all route (404 for a wrong method) | A2: trailing lambda after a vararg; A4: hints on `fn` parameters select; the error is at compile time |
| 17, 20 | a `defn` return hint was not used: `^String (url "/x")` at each call | C3: the return hint of a function |
| 20 | `^Application`, `^ApplicationTestBuilder` hints on `fn` literals | D6 |

## Versions

- Ktor 3.6.0, the newest on Maven Central (`maven-metadata.xml`, `lastUpdated` 2026-09-16). Engine: CIO.
- Kotlin metadata of the Ktor classes: 2.3.0.
- kotlinx-coroutines-core-jvm 1.11.0 as a top-level dependency: Ktor 3.6.0 asks for it. The README of ckway names 1.10.2.
- ckway: this repository by `:local/root "../.."`, at `fix/spike-findings` d446567.
- JDK 25.0.4, Clojure 1.12.1, Kotlin compiler for `:kotlinc` 2.4.20, data.json 2.5.1, slf4j-simple 2.0.17.

## Not done

- Netty (CIO only).
- A cancel when the client disconnects.
- `bin/test` runs with `:kotlinc` only. A test run with stored bridges and no compiler was not made; the app was started without `:kotlinc` and answered.
