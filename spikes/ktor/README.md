# Ktor from Clojure with ckway

A "Catalog API" on Ktor server (CIO engine), written in Clojure. No Kotlin code of our own.
The server, the routes, the handlers and the plugins are Clojure functions.
Tests call the real server with `java.net.http.HttpClient`, with `testApplication`, and with the Ktor client.

## What the API does

| Request | Answer |
|---|---|
| `GET /health` | 200 `{"status":"ok"}` |
| `GET /products` (`?tag=x`) | 200, an array |
| `GET /products/{id}` | 200, or 404 `{"error":"not found"}`, or 400 when the id is not a number |
| `POST /products` | 201, the product, a `Location` header; 400 `{"error":...}` when invalid |
| `DELETE /products/{id}` | 204, or 404, or 400 |
| an exception in a handler | 500 `{"error":"internal error"}`, logged, no stack trace to the client |
| an unknown route | 404 `{"error":"not found"}` |
| a known path with a wrong method | 405 `{"error":"method not allowed"}` |

JSON is read and written with `org.clojure/data.json`. There is no ContentNegotiation.

## Files

* `src/spike/ktor/domain.clj` - pure Clojure: the `ProductStore` protocol, `validate`, a memory store.
* `src/spike/ktor/core.clj` - `(start! config)` gives a system map, `(stop! system)` stops it, `-main`.
* `test/spike/ktor/api_test.clj` - every route and every error path, over a real socket.
* `test/spike/ktor/ktor_tools_test.clj` - `testApplication` and the Ktor client (`HttpClient(CIO)`).
* `test/spike/ktor/reified_test.clj` - the `inline reified` forms with `:<>`.
* `test/spike/ktor/interop_test.clj` - pins the ckway forms that needed a fix (`status` overloads by hint, compile-time ambiguity, defn return hint, trailing lambda).
* `FINDINGS.md` - what worked, what did not, and why.

## Run and test

```sh
bin/test                  # all tests, exit code 0 if they pass
clojure -M:run            # the server on port 8080
clojure -M:run 9000       # another port
```

`bin/test` makes `target/cache` (mode 700) for the ckway bridge cache, so your own cache is not touched.
It runs with `:test:kotlinc`. Only `reified_test.clj` needs the compiler. The first run compiles two bridges (about 10 s).
The app itself (`clojure -M:run`) does not need `:kotlinc`.

The config map of `start!` (all keys are optional):

```clojure
{:host "127.0.0.1" :port 0            ; port 0 = a free port; the real port is in (:port system)
 :grace-ms 100 :timeout-ms 2000       ; for stop!
 :start-timeout-ms 10000              ; start! gives up after this
 :call-logging? false                 ; install CallLogging
 :log-error (fn [^Throwable e] ...)   ; called for each exception in a handler
 :store my-store}                     ; any ProductStore; default is a new memory store
```

There is no global state. Each `start!` makes its own store (unless you pass one) and its own server.

## What you must know (suspend handlers)

A Ktor handler is a `suspend` lambda. ckway runs your Clojure function on a virtual thread (JDK 21+). So:

* Write the handler as `(fn [ctx] ...)`. The first argument is the lambda receiver (`RoutingContext`). Get the call with `(rt/call ctx)`.
* Call suspend functions (`receiveText`, `respondText`) as plain functions. They return when ready. The return value of the handler is ignored.
* Do not wrap a suspend call in `(catch Exception ...)`. A cancel (the client went away, the server stops) arrives as an `InterruptedException`, and `catch Exception` swallows it. In `core.clj` the only `catch Exception` is in `parse-json`, which does a pure parse, and it rethrows `InterruptedException` first.
* Let other exceptions go out of the handler. StatusPages turns them into the 500 answer.
* A `ThreadLocal` set outside is not visible in the handler. `set!` of a var that was bound outside fails. (`doc/limits.md`, 4.)
* On JDK 21 to 23, `synchronized` in a handler pins the carrier thread.
* Put a type hint on the lambda parameter (`^RoutingContext ctx`), so the calls are resolved at compile time and there is no reflection warning. The type of a `fn` literal parameter is known to ckway, but not the type of a `defn` parameter, so hint it there. A return hint on a `defn` (`(defn url ^String [..] ..)`) is used.
* `start(wait = false)` throws the exception of a module that fails. But then `resolvedConnectors()` waits for ever. `start!` does not call it blindly: it waits with a time limit.

## Kotlin form -> Clojure form

`eng` = `io.ktor.server.engine`, `cio` = `io.ktor.server.cio`, `app` = `io.ktor.server.application`, `rt` = `io.ktor.server.routing`,
`resp` = `io.ktor.server.response`, `req` = `io.ktor.server.request`, `sp` = `io.ktor.server.plugins.statuspages`,
`cl` = `io.ktor.server.plugins.calllogging`, `http` = `io.ktor.http`.
A member is in the namespace of the package of the class that declares it (`call.response` is `app/response`, `response.headers` is `resp/headers`). Use `doc` to find it.

| Kotlin | Clojure |
|---|---|
| `import io.ktor.server.engine.*` | `(kt/require '[io.ktor.server.engine :as eng])` |
| `embeddedServer(CIO, port = 0, host = "127.0.0.1") { module }` | `(eng/embeddedServer cio/CIO 0 "127.0.0.1" (fn [app] ...))` (the lambda is the trailing argument; hint a non-literal port and host: see below) |
| `server.start(wait = false)` | `(eng/.start server :wait false)` |
| `server.engine.resolvedConnectors().first().port` | `(eng/port (first (eng/.resolvedConnectors (eng/engine server))))` |
| `server.stop(100, 2000)` | `(eng/.stop server 100 2000)` |
| `install(StatusPages) { ... }` | `(app/.install application (sp/StatusPages) (fn [^StatusPagesConfig cfg] ...))` (a property is a function: `(sp/StatusPages)`) |
| `install(CallLogging)` | `(app/.install application (cl/CallLogging))` |
| `exception<Throwable> { call, cause -> ... }` | `(sp/.exception cfg (kt/ref Throwable class) (fn [call cause] ...))` |
| `exception<Throwable> { call, cause -> ... }` (reified) | `(sp/.exception cfg (fn [call cause] ...) :<> Throwable)` (needs `:kotlinc`) |
| `routing { ... }` | `(rt/.routing application (fn [r] ...))` |
| `get("/health") { ... }` | `(rt/.get r "/health" (fn [ctx] ...))` |
| `route("/products") { get { ... } }` | `(rt/.route r "/products" (fn [r2] (rt/.get r2 (fn [ctx] ...))))` |
| `route("/{id}") { get { ... }; delete { ... }; handle { ... } }` | `(rt/.route r2 "/{id}" (fn [r3] (rt/.get r3 f) (rt/.delete r3 f) (rt/.handle r3 f)))` |
| `status(HttpStatusCode.NotFound) { call, _ -> ... }` | `(sp/.status cfg (http/NotFound http/HttpStatusCode) (fn [^ApplicationCall call status] ...))` |
| `call` (in the handler) | `(rt/call ctx)` |
| `call.parameters["id"]` | `(http/.get (app/parameters call) "id")` |
| `call.request.queryParameters["tag"]` | `(http/.get (req/queryParameters (app/request call)) "tag")` |
| `call.receiveText()` | `(req/.receiveText call)` |
| `call.respondText(text, ContentType.Application.Json, HttpStatusCode.Created)` | `(resp/.respondText call text (http/Json http/ContentType.Application) (http/Created http/HttpStatusCode))` |
| `call.respondText("", status = HttpStatusCode.NoContent)` | `(resp/.respondText call "" :status (http/NoContent http/HttpStatusCode))` |
| `call.response.headers.append("Location", url)` | `(resp/.append (resp/headers (app/response call)) "Location" url)` |
| `call.receive<String>()` | `(req/.receive call :<> String)` (needs `:kotlinc`) |
| `call.respond(status, text)` | `(resp/.respond call status text :<> String)` (needs `:kotlinc`) |
| `testApplication { application { ... }; client.get("/health") }` | `(tst/testApplication (fn [b] (tst/.application b module) (creq/.get (tst/client b) "/health")))` |
| `HttpClient(CIO)` | `(cl/HttpClient ccio/CIO)` |
| `client.get(url)`, `response.bodyAsText()` | `(creq/.get client url)`, `(cst/.bodyAsText response)` |
| `client.post(url) { header(..); setBody(text) }` | `(creq/.post client url (fn [b] (creq/.header b "k" "v") (creq/.setBody b text :<> String)))` |
| `response.status.value` | `(http/value (cst/status response))` |
| `client.close()` | `(cl/.close client)` |

### `status(...)` of StatusPages

`status(HttpStatusCode.NotFound) { call, _ -> ... }` has two overloads with the same JVM signature. They differ in the type of
the first lambda parameter (`ApplicationCall` or `StatusContext`). Write the type as a hint on the `fn` parameter:

```clojure
(fn [^StatusPagesConfig cfg]                       ; the hint on cfg is needed (see below)
  (sp/.status cfg (http/NotFound http/HttpStatusCode)
              (fn [^ApplicationCall call status] ...)))        ; the handler gets an ApplicationCall
```

* `^StatusPagesConfig$StatusContext ctx` selects the other overload.
* No hint is a compile error "is ambiguous", whose "Way out" names the hint.
* `cfg` needs `^StatusPagesConfig`. ckway does not infer the type argument of `(sp/StatusPages)`, so `cfg` of `(fn [cfg] ...)` has no type, the
  call goes the dynamic path, the lambda hint is not seen, and the call is "ambiguous" when the module runs (`start` throws it).
* The trailing lambda goes after the `vararg` of codes: `(sp/.status cfg code1 code2 f)`.

### 404 and 405

Unknown path: 404 `{"error":"not found"}`. Known path, wrong method: 405 `{"error":"method not allowed"}`.
Ktor gives 405 only for a constant path (`/health`, `/products`). For a path with a parameter (`PUT /products/1`) it gives 404.
To have one behaviour, `route("/{id}")` has a `handle { 405 }` after `get` and `delete` (a route with a method is preferred over it).
There is no `Allow` header.

### Types that kt needs at `embeddedServer`

`(eng/embeddedServer cio/CIO (long (:port config)) ^String (:host config) f)` has hints on purpose. With `(:port config)` and `(:host config)`
(types unknown to the compiler) kt chooses the overload `embeddedServer(factory, environment, configure, module)` and fails at run time:
`the argument `environment` (...ApplicationEnvironment) is java.lang.Long 0, but the Kotlin declaration that was selected needs ...ApplicationEnvironment`.
With literals, or with `(long ...)` and `^String`, the right overload is chosen.
