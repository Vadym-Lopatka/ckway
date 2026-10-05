# Spike: http4k from Clojure through ckway

This spike builds a small "Catalog API" on [http4k](https://www.http4k.org/) (a Kotlin web library), written in Clojure.
It uses `routes` and `bind`, path and query lenses, two `Filter`s, and a real server (`SunHttp`, the JDK server).

## What it shows

- Routes: `GET /health`, `GET /products?tag=x`, `GET /products/{id}`, `POST /products`, `DELETE /products/{id}`.
- Handlers are plain Clojure functions.
- Filters: request logging, and a catch-all that turns an error into JSON 500 (and a bad lens value into 400).
- A real server on port 0, started and stopped cleanly, more than once.
- Tests: most call the handler in memory (no socket, the http4k way). Some start the real server and use `java.net.http.HttpClient`.
- No global state. `(start! config)` gives a system map. `(stop! system)` stops it.

## Files

| File | What |
|---|---|
| `src/spike/http4k/domain.clj` | Pure Clojure: the product store and `validate`. No `kt`. |
| `src/spike/http4k/api.clj` | The http4k handler: routes, lenses, filters. |
| `src/spike/http4k/core.clj` | `start!`, `stop!`, `-main`. |
| `test/spike/http4k/` | The tests (`api_test`: in memory; `server_test`: real server). |
| `bin/test` | Makes `target/cache` (mode 700) and runs the tests. |

## Run

```sh
bin/test                  # all tests, exit code 0 when they pass
clojure -M:run            # server on port 8080
clojure -M:run 0          # server on a free port; the port is printed
```

Config of `start!` (a map, every key is optional): `:port` (default 0), `:store` (a `ProductStore`; default a new memory store), `:log-fn` (default: print to stderr).

```clojure
(def system (core/start! {:port 0}))
(:port system)
(core/stop! system)
```

Every alias that runs code sets `-Dckway.cache.dir=target/cache`, so your own bridge cache is not touched.
The `:kotlinc` alias is there only for a `:<>` call. This spike has none, so it is not used.

## Kotlin form -> Clojure form

`h` is `org.http4k.core`, `r` is `org.http4k.routing`, `l` is `org.http4k.lens`, `srv` is `org.http4k.server`.

| Kotlin | Clojure |
|---|---|
| `import org.http4k.core.*` | `(kt/require '[org.http4k.core :as h])` |
| `Method.GET` | `h/Method.GET` |
| `Status.OK` | `(h/OK h/Status)` |
| `Request(GET, "/a")` | `(h/Request h/Method.GET "/a")` |
| `Response(OK)` | `(h/Response status)` |
| `response.header("Location", x)` | `(h/.header response "Location" x)` |
| `response.body(text)` | `(h/.body response text)` |
| `request.bodyString()` | `(h/.bodyString request)` |
| `response.status.code` | `(h/code (h/status response))` |
| `"/health" bind GET to { ... }` | `(r/.to (r/.bind "/health" h/Method.GET) (fn [req] ...))` |
| `routes(a, b, c)` | `(r/routes a b c)`; with one argument it is an "ambiguous" error, use `(r/routes :list [a])` |
| `Path.int().of("id")` | `(l/.of (l/.int l/Path) "id")` |
| `Query.optional("tag")` | `(l/.optional l/Query "tag")` |
| `idLens(request)` | `(l/.invoke ^org.http4k.lens.LensExtractor id-lens request)` |
| `Filter { next -> { req -> ... } }` | `(kt/reify h/Filter (.invoke [_ next] (fn [req] ...)))` |
| `Filter.NoOp` | `(h/NoOp h/Filter)` |
| `a.then(b)` (Filter, handler) | `(h/.then filter handler)` |
| `handler(request)` | `(h/.invoke handler request)` (hint the local `^kotlin.jvm.functions.Function1`; a handler is not callable as `(handler request)`) |
| `next(request)` inside `Filter` | `(next request)` (a Clojure fn) |
| `handler.asServer(SunHttp(port))` | `(srv/.asServer handler (srv/SunHttp (int port)))` |
| `server.start()` | `(srv/.start server)` |
| `server.port()` | `(srv/.port server)` |
| `server.stop()` | `(srv/.stop server)` |

Problems and workarounds are in `FINDINGS.md`.
