# coflow: clojure.core.async.flow on kotlinx.coroutines

A spike. It runs the API of `clojure.core.async.flow` on Kotlin coroutines (`Channel`, `select`, `CoroutineScope`,
dispatchers). All Kotlin is called through ckway (`ckway.core`, alias `kt`).

The goal: for a user, it works as `clojure.core.async.flow` does. Same functions, same step-fn contract, same keywords,
same report and error maps.

## Licence

* `src/spike/coflow/flow.clj` and `src/spike/coflow/impl.clj` are **derived work from core.async 1.10.874-alpha3**.
  They are under the **Eclipse Public License 1.0** and keep the original copyright header.
  `flow.clj` is the original file with the implementation namespace replaced (docstrings, names and arglists are the
  original's, because the API must be the same). `impl.clj` keeps the structure and the logic of the original `impl.clj`;
  the channels, loops, mults and executors are rewritten.
* `test/orig/ex-flow.clj`, `test/orig/my-flow.clj` and `test/orig/flow_test_original.txt` are copies of files of
  clojure/core.async (EPL 1.0), unchanged.
* The other files (`chan.clj`, `takeover.clj`, `demo.clj`, tests) are new and use the licence of this repository (MIT).
* core.async is a normal dependency (`deps.edn`), not copied.

## Run

```sh
cd spikes/coflow
clojure -M:run        # the demo: a small flow that prints, then stops
bin/test              # all tests (about 4 minutes)
bin/test -n spike.coflow.chan-test       # one namespace
```

`bin/test` makes `target/cache` (mode 700) for the ckway bridge cache and runs the tests. The spike calls no `:<>`
(reified) function, so the Kotlin compiler is not needed. One run is enough.

## Use

```clojure
(require '[spike.coflow.flow :as flow])   ; same names as clojure.core.async.flow
```

The keywords that the flow sends and expects are **the ones of `clojure.core.async.flow`**:
`:clojure.core.async.flow/pause`, `/resume`, `/stop`, `/pid`, `/report`, `/error`, `/in-ports`, `/input-filter`, ...
A step fn must use them. So you have two ways:

1. **Alias for the functions, `:as-alias` for the keywords** (no code outside the `ns` form changes except `flow/` calls
   stay as they are):

   ```clojure
   (:require [spike.coflow.flow :as flow]
             [clojure.core.async.flow :as-alias fk])   ; ::fk/pause = :clojure.core.async.flow/pause
   ```
2. **Takeover: the user code is not changed at all.** `(spike.coflow.takeover/install!)` replaces the roots of three vars
   of `clojure.core.async.flow` (`create-flow`, `process`, `futurize`). Code that requires `clojure.core.async.flow :as flow`
   and writes `::flow/pause` keeps working. `install!` returns a function that restores the old roots. This changes a library
   for the whole JVM: use it in an application, not in a library.

Why not just `[spike.coflow.flow :as flow]` and `::flow/pause`? Because then `::flow/pause` is
`:spike.coflow.flow/pause`, and the flow would have to guess. We keep the original keywords, so a step fn written for the
original works untouched (way 2) and the original's own tools (flow monitor, ...) see the same data.

The channels are ports (`spike.coflow.chan`): a Kotlin `Channel` that implements `ReadPort`, `WritePort` and `Channel` of
`clojure.core.async.impl.protocols`. So `a/<!!`, `a/<!` (in `go`), `a/alts!!`, `a/poll!`, `a/take!`, `a/>!!`, `a/close!`
work on `:report-chan` and `:error-chan`. Helpers for code that does not use core.async: `chan/take!!` (with timeout),
`put!!`, `poll!`, `close!`, `->seq`, `drain`, and `chan/->flow` (a Kotlin `Flow` of the channel).

The SPI is the original's: `clojure.core.async.flow.spi/ProcLauncher` and `Resolver`, and `...impl.graph/Graph`, are
used as they are. A `ProcLauncher` that reads `::flow/control` with `alts!!` works (test `custom-launcher`).

## Design

* One `CoroutineScope(SupervisorJob() + Dispatchers.Default)` per started flow. Every proc loop, every mult, every bridge
  and every pump is a coroutine of it.
* `stop` sends the stop command (the procs run their transition fn), closes report and error, waits for the proc loops
  (grace time 5000 ms, system property `coflow.stop.grace.ms`), then `cancelAndJoin`s the scope. After `stop` the Job of the
  scope is completed: no coroutine, no thread is left.
* A proc loop is a Clojure fn run as a suspend lambda (so on a virtual thread). It waits in a Kotlin
  `select { control.onReceiveCatching ...; casts...; in.onReceiveCatching ... }`. The select is biased: the first clause
  wins, so control has priority, as `alts!! :priority true` in the original. An output is written with
  `select { control.onReceiveCatching ...; out.onSend(msg) ... }`: a control message can overtake a blocked write.
* `:chan-opts` `:buf-or-n`: a number n is `Channel(n)`; `(sliding-buffer n)` is `Channel(n, DROP_OLDEST)`;
  `(dropping-buffer n)` is `Channel(n, DROP_LATEST)`. A transducer (`:xform`) is run by the port before the send (and
  flushed at close), the exception handler puts the same map on the error channel.
* `:workload`: `:mixed` and `:io` run the loop on `Dispatchers.IO`, `:compute` runs each transform as a coroutine on
  `Dispatchers.Default` and waits `compute-timeout-ms` (`select` with `onAwait` and `onTimeout`). A user `Executor`
  (`:io-exec` ...) becomes a dispatcher with `asCoroutineDispatcher`. **A Clojure body runs on its own virtual thread, so a
  dispatcher does not bound its parallelism** (see findings).
* mult: a coroutine per mult. A message goes to every tap; the next one is taken when all taps have it. A tap with room gets
  it by `trySend`, the others by a child coroutine (`async`), as the original's mult does with `put!`.
* Ports that the user makes with core.async (`::flow/in-ports`, `::flow/out-ports`) are read and written through a bridge
  coroutine (the proc loop can only `select` on Kotlin channels).
* `futurize` returns a `CompletableFuture`, completed by a coroutine; `inject` returns such a future.

## Invariants of core.async.flow that the port keeps

Taken from the source of `clojure.core.async.flow` 1.10.874-alpha3, its docstrings and `doc/flow-guide.md`. Each has a test
`invariant-N-...` in `test/spike/coflow/invariants_test.clj`, run against the original and the port (three arms:
`:orig`, `:alias` = `spike.coflow.flow`, `:takeover` = the port behind `clojure.core.async.flow`).

1. (a) A proc's step fn never runs concurrently with itself; its state goes from one call to the next with no loss.
2. (b) Messages on one connection keep their order, none is lost, none is duplicated, also under back-pressure,
   pause/resume and many producers.
3. (c) Control has priority over data input. A paused proc takes no input but answers control commands and `ping`.
4. (d) Transitions come in the legal order only (one per change of status) and `::flow/stop` is delivered exactly once to a
   proc that was started.
5. (e) The init arity is called once per `start`, with the declared params plus `::flow/pid`. `describe` is pure and is
   called once, by `process`.
6. (f) Output to an undeclared or unconnected out id, a `nil` message and a malformed return are handled as the
   original does (an error on the error channel with the old state, or ignored).
7. (g) An exception from a step fn goes to the error channel with the same map; the proc continues with the old state.
8. (h) Back-pressure: a slow consumer blocks the producer, with the same buffer semantics for fixed, sliding and dropping buffers.
9. (i) `inject` semantics, including an unknown pid or io id; `command-proc` (named by the `Graph` protocol, implemented by nothing in this version).
10. (j) After `stop` nothing runs and the channels are closed; a second `stop`, `start` again, `pause` before `start`.
11. (k) Fan-out and fan-in routing: one out to many ins (every connection gets every message), many outs to one in.

What the original leaves open is not asserted: the interleaving of two producers, which procs answer a `ping` within a
very short timeout, the first `ping` after a `pause` (see the test comment).

## Kotlin form -> Clojure form

| Kotlin | Clojure |
|---|---|
| `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | `(co/CoroutineScope (kc/.plus (co/SupervisorJob) (co/Default co/Dispatchers)))` |
| `Dispatchers.IO` | `(co/IO co/Dispatchers)` |
| `scope.launch(dispatcher) { ... }` | `(co/.launch scope :context d :block (fn [s] ...))`, or `(co/.launch scope (fn [s] ...))` |
| `async(dispatcher) { ... }` / `d.await()` | `(co/.async scope :context d :block (fn [_] ...))` / `(co/.await d)` |
| `job.cancel()`, `job.join()`, `job.cancelAndJoin()` | `(co/.cancel job)`, `(co/.join job)`, `(co/.cancelAndJoin job)` |
| `scope.coroutineContext.job`, `scope.isActive` | `(co/job (.getCoroutineContext scope))`, `(co/isActive scope)` |
| `withTimeoutOrNull(ms) { ... }` | `(co/withTimeoutOrNull ms (fn [_] ...))` |
| `Channel<Any>(n)` | `(kch/Channel n)` |
| `Channel<Any>(n, BufferOverflow.DROP_OLDEST)` | `(kch/Channel n :onBufferOverflow kch/BufferOverflow.DROP_OLDEST)` |
| `ch.send(v)` / `ch.close()` | `(kch/.send ch v)` / `(kch/.close ch)` |
| `ch.trySend(v)`, `.isSuccess`, `.isClosed` | `(kch/.trySend ch v)`, `(kch/isSuccess r)`, `(kch/isClosed r)` |
| `ch.tryReceive().getOrNull()` | `(kch/.getOrNull (kch/.tryReceive ch))` |
| `select { c.onReceiveCatching { f(it) } }` | `(sel/select (fn [sb] (sel/.invoke sb (kch/onReceiveCatching c) (fn [r] ...))))` |
| `select { c.onSend(v) { ... } }` | `(sel/.invoke sb (kch/onSend c) v (fn [_] ...))` |
| `select { onTimeout(ms) { ... } }` | `(sel/.onTimeout sb ms (fn [] ...))` |
| `select { d.onAwait { ... } }` | `(sel/.invoke sb (co/onAwait d) (fn [r] ...))` |
| `ch.receiveAsFlow()`, `flow.toList()` | `(kflow/.receiveAsFlow ch)`, `(kflow/.toList f)` |
| `executor.asCoroutineDispatcher()`, `dispatcher.asExecutor()` | `(co/.asCoroutineDispatcher ex)`, `(co/.asExecutor d)` |

## What differs for a user

See the section "Semantic differences" of the report that came with this spike. In short: `stop` waits for the procs
(then cancels); a pending `take` of a user on the report channel is served by a pump coroutine (one extra message may wait in
its hand when a take was given up, until the pump is woken); `::flow/in-ports` are read one message ahead; `:compute`
cancels a timed-out transform; foreign (core.async) ports are bridged; the `datafy` of a channel has a `:take-count` that
counts only the pending core.async takes.
