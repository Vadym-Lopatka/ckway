# coflow: clojure.core.async.flow on kotlinx.coroutines

A spike. It runs the API of `clojure.core.async.flow` on Kotlin coroutines (`Channel`, `select`, `CoroutineScope`,
dispatchers). All Kotlin is called through ckway (`ckway.core`, alias `kt`).

The goal: for a user, it works as `clojure.core.async.flow` does. Same functions, same step-fn contract, same keywords,
same report and error maps, same invariants.

## Licence

* `src/spike/coflow/flow.clj` and `src/spike/coflow/impl.clj` are **derived work from core.async 1.10.874-alpha3**.
  They are under the **Eclipse Public License 1.0** and keep the original copyright header.
  `flow.clj` is the original file with the implementation namespace replaced (docstrings, names and arglists are the
  original's, because the API must be the same). `impl.clj` keeps the structure and the logic of the original `impl.clj`;
  the channels, loops, mults and executors are rewritten.
* `test/orig/ex-flow.clj`, `test/orig/my-flow.clj` and `test/orig/flow_test_original.txt` are copies of files of
  clojure/core.async (EPL 1.0), unchanged.
* The other files (`chan.clj`, `ext.clj`, `takeover.clj`, `demo.clj`, `dropin/`, tests) are new and use the licence of this
  repository (MIT). `dropin/clojure/core/async/flow.clj` copies no code of the original.
* core.async is a normal dependency (`deps.edn`), not copied.

## Run

```sh
cd spikes/coflow
clojure -M:run        # the demo: a small flow that prints, then stops
bin/test              # all tests, two JVMs (about 6 minutes)
bin/test -n spike.coflow.chan-test       # one namespace (first JVM only)
```

`bin/test` makes `target/cache` (mode 700) for the ckway bridge cache. JVM 1 runs everything with the original
`clojure.core.async.flow` as the oracle. JVM 2 puts `dropin` in front of the class path (alias `:dropin`) and runs the
tests with concrete expected values once, with no oracle (see "Three ways to use it"). The spike calls no `:<>`
(reified) function, so the Kotlin compiler is not needed.

## Three ways to use it

The keywords that the flow sends and expects are **the ones of `clojure.core.async.flow`**:
`:clojure.core.async.flow/pause`, `/resume`, `/stop`, `/pid`, `/report`, `/error`, `/in-ports`, `/input-filter`, ...

1. **Alias.** `(:require [spike.coflow.flow :as flow] [clojure.core.async.flow :as-alias fk])`, and write `::fk/pause`.
2. **Takeover.** `(spike.coflow.takeover/install!)` replaces the roots of `create-flow`, `process` and `futurize` of
   `clojure.core.async.flow`. Code that requires `clojure.core.async.flow :as flow` and writes `::flow/pause` is not
   changed. `install!` returns a fn that restores the old roots. It changes a library for the whole JVM.
3. **Drop-in.** Put the directory `dropin` before the core.async jar on the class path (`clojure -A:dropin ...`).
   `dropin/clojure/core/async/flow.clj` shadows the original file: it re-publishes every public var of `spike.coflow.flow`
   (with doc and arglists) under the namespace `clojure.core.async.flow`. The user's `require` is unchanged too. The SPI
   namespaces (`clojure.core.async.flow.spi`, `...impl.graph`) are not shadowed: they are the original's, and the port
   uses them. Nothing else needs shadowing. Tested in a separate JVM (`dropin_test.clj` and the invariants tests).

The channels are ports (`spike.coflow.chan`): a Kotlin `Channel` that implements `ReadPort`, `WritePort` and `Channel` of
`clojure.core.async.impl.protocols`. `a/<!!`, `a/<!` (in `go`), `a/alts!!`, `a/poll!`, `a/take!`, `a/>!!`, `a/close!` work
on `:report-chan` and `:error-chan`. Helpers for code that does not use core.async: `chan/take!!` (with timeout), `put!!`,
`poll!`, `close!`, `->seq`, `drain`, `chan/->flow` (a Kotlin `Flow`).

Extras that are **not** part of the API (`spike.coflow.ext`): `(await-stopped g timeout-ms)` joins the cleanup of a stopped
flow; `(running-scope g)` gives its `CoroutineScope`.

## Design

* One `CoroutineScope(SupervisorJob() + Dispatchers.Default)` per started flow. Every proc loop, mult and pump is a coroutine of it.
* **`stop` returns at once with `true`, as the original does.** It sends the stop command, closes the report, error and
  (internal) control channels, and starts a **reaper**: one coroutine outside the flow's scope (`GlobalScope`, it ends by
  itself). The reaper waits until every coroutine of the scope is done (procs run their stop transition, injects and
  transforms finish) for at most the grace time (property `coflow.stop.grace.ms`, 5000), then `cancelAndJoin`s the scope.
  So no coroutine outlives a stop for longer than the grace time. `start` after `stop` makes a new scope, and works while
  the reaper of the old run still waits. A proc that calls `stop` on its own flow from a step fn does not wait for itself.
* A proc loop is a Clojure fn run as a suspend lambda (so on a virtual thread). It waits in a Kotlin
  `select { control.onReceiveCatching ...; casts...; in.onReceiveCatching ... }`, biased: control first, as `alts!! :priority true`.
  Before it suspends, it tries `tryReceive` on each port in the same order (no suspension on a busy flow).
* An output is written with `select { control.onReceiveCatching ...; out.onSend(msg) ... }`.
* `:chan-opts` `:buf-or-n`: a number n is `Channel(n)`; `(sliding-buffer n)` is `Channel(n, DROP_OLDEST)`;
  `(dropping-buffer n)` is `Channel(n, DROP_LATEST)`. A transducer (`:xform`) is run by the port before the send.
* `:workload`: `:mixed` and `:io` run the loop on `Dispatchers.IO`; `:compute` runs each transform as a coroutine on
  `Dispatchers.Default` wrapped in a `FutureTask`, and the loop does `Future.get(timeout)` as the original does (a transform
  that times out is left running, not interrupted). A Clojure body always has its own virtual thread, so a dispatcher
  does not bound its parallelism.
* mult: a coroutine per mult; the next message is taken when all taps have the last one.
* **A user's own channel (core.async) in `::flow/in-ports` / `::flow/out-ports` is read and written by the loop itself, with
  no bridge and no extra buffer.** In: the proc only *polls* it (`take!` with a non-blocking handler) while it looks for
  input, in the order of the read set; only if nothing is ready does it register a blocking take (a handler with an
  active flag, like `alts!`), together with its Kotlin `select`, and cancels the registration when the select returns. A
  paused proc, a proc that waits in a transform, an input that the filter excludes, and a flow that was never started
  take nothing. Out: control first, then a non-blocking put, then a registered put handler that is cancelled if control
  wins (if the put committed in the same moment, the loop knows and does not write the message again).
* `futurize` and `inject` return a `java.util.concurrent.FutureTask` (the class of the original's futures; a `proxy`
  subclass, so `(class f)` is a different class, but `instance?`, `future-cancel`, `deref` with timeout, `.get` and the
  exception wrapping behave the same). The task is run by a coroutine.

### The one race (in-ports)

A Kotlin `select` and a core.async handler cannot commit together. In the instant when a control message wins the select
and the user's channel commits a take of the registered handler, the message has left the user's channel. The proc then
keeps it (`held`) and reads it next, at the position of that input in the read set. It is lost only if the flow is stopped
(or its scope cancelled) before that read. So at most **one message per in-port per proc** can be lost, and only in a
stop that falls into that window. The original loses none (`alts!!` is atomic). Tests: `item-2-in-ports-race-bound`
(25 rounds of pause/resume floods with a stop in the middle, 3 arms: 0 messages lost in every round we ran, so the race
was not observed; the assertion is "at most 1"), and `item-2-in-ports-nothing-lost-while-the-flow-runs` (3000
messages, random pause/resume, no stop: all arrive once, in order).

### The report channel and the hand-held value

A take of a core.async user on a port is served by a pump coroutine. The pump receives from the Kotlin channel only while
a take waits. If that take is given up (an `alts!!` took another branch) in the moment the value arrives, the pump keeps the
value (the *stash*), and the next take gets it first (so it is never lost or reordered). The stash counts as one slot of the
capacity: when buffer plus stash is full, a sliding port drops the stash (the oldest) and a dropping port drops the new
value. A fixed-buffer port that a user builds with `chan/port` can hold one value more than its capacity in this case
(the flow's own channels that a user can read, report and error, are sliding).

### Where the quirks of the original come from

Two behaviours that you may take for a bug are the original's:

* **The first `ping` after `pause` can report the old status.** In the original `impl.clj`, `proc`, the `run` loop makes
  `pong` at the start of each turn with the `status` of that turn, and `handle-command` is `(partial handle-command pid pong)`.
  `send-outputs` receives that same `handle-command`, so a `pause` that arrives while the proc writes an output is handled
  there, and a `ping` handled in the same write is answered with the status that the turn started with.
* **Data can win over a control command that was sent just after another one.** Control commands go
  `control-chan -> (async/mult control-chan) -> control-tap` of each proc (`Graph/start`, `control-mult` and `start-proc` in
  the original `impl.clj`). `alts!! ... :priority true` gives control priority only over what has already arrived at the tap.
  The port has the same hops (`control` port, a mult coroutine, a tap per proc).

## Invariants of core.async.flow that the port keeps

Each has a test `invariant-N-...` in `test/spike/coflow/invariants_test.clj`, run against the original and the port (arms
`:orig`, `:alias` = `spike.coflow.flow`, `:takeover`; in the drop-in JVM all arms are the port). The *source* column says where in
core.async 1.10.874-alpha3 the rule is written.

| # | Invariant | Where the original says or does it |
|---|---|---|
| 1 | A proc's step fn never runs concurrently with itself; the state goes from call to call with no loss | `process` doc: "state' will be the state supplied to subsequent calls". Code: one `loop [status state count read-ins]` per proc in `impl/proc` `run`, `recur` with `nstate` |
| 2 | Messages on a connection keep their order, none lost, none duplicated, also under back-pressure, pause/resume, many producers | Not a sentence of flow: it comes from `core.async/chan` (FIFO, put blocks when full) and from one sender loop per proc, with `send-outputs` writing `msgs` in order |
| 3 | Control has priority over data; a paused proc takes no input but answers control and ping | `spi/ProcLauncher` doc: "Whenever it is reading or writing to any channel a process must use alts!! and include a read of the ::flow/control channel, giving it priority." and "In the :paused status operation is suspended and no output is produced." and "::flow/ping - emit a ping message ... containing at least its pid and status". Code: `(async/alts!! read-chans :priority true)` with `[control casts & ins]`, `(async/<!! control)` when paused |
| 4 | Transitions only in legal order, one per change of status; `stop` once to a started proc | `process` doc, arity 2: "The transition arity will be called when the process makes a state transition, transition being one of ::flow/resume, ::flow/pause or ::flow/stop". Code: `handle-transition` `(if (not= status nstatus) (transition ...) state)`; `:exit` ends the loop |
| 5 | Init once per `start` with the declared params (+ `::flow/pid`); describe is pure | `process` doc: "The init arity will be called once by the process to establish any initial state ... The key ::flow/pid will be added" and "describe may be called by users ... It will also be called by the impl in order to discover what channels are needed". Code: `(step)` in `proc`, `(step args)` in `start` |
| 6 | Output to an undeclared or unconnected id, nil messages, malformed returns: as the original | `process` doc: "A step need not output at all ..., however an output _message_ may never be nil (per core.async channels)". Code: `send-outputs`: `(or (outs output) (spi/get-write-chan resolver output))`; `write-chan` throws `"can't resolve channel with io-id"`; the nil is an `alts!!` assertion. (`spi/Resolver` doc says "or nil (in which case the output should be dropped)"; the code throws. We copy the code.) |
| 7 | A step-fn exception goes to the error channel with the same map; the proc continues with the old state | `start` doc: ":error-chan ... Any (and only) exceptions thrown anywhere on any thread inside a flow will appear in maps sent here. There will at least be a ::flow/ex entry"; `spi` doc: "if a process encounters an error it must report it on the ::flow/error channel ... and attempt to continue". Code: the two `catch Throwable` in `run` return `[status state count read-ins]` |
| 8 | A slow consumer blocks the producer; same buffer semantics for fixed, sliding, dropping | `create-flow` doc: ":chan-opts ... buf-or-n and xform have their meanings per core.async/chan; the default ... is {:buf-or-n 10}" and "signals ... async/sliding-buffer of size 100". Code: `(async/chan (async/sliding-buffer 100))` for report, error, casts |
| 9 | `inject` and `command-proc`, unknown pid or io id | `inject` doc: "asynchronously puts the messages on the channel corresponding to the input or output of the process, returning a future that will complete when done". `Graph/command-proc` doc: "synchronously sends a process-specific command" (declared, not implemented in this version) |
| 10 | After `stop` nothing runs; channels closed; second stop, start again, pause before start | `stop` doc: "shuts down the flow, stopping all procsesses and closing the error and report channels. The flow can be started again". Code: `running-chans` throws `"flow not running"`; `stop` is `(when-let [... @chans] ...)` |
| 11 | Fan-out and fan-in routing | `create-flow` doc: "Inputs and outputs support multiple connections. When an output is connected multiple times every connection will get every message, as per a core.async/mult. Note that non-multed outputs do not have corresponding channels". Code: `needs-mult?` |

What the original leaves open is not asserted: the interleaving of two producers, which procs answer a `ping` within a
very short timeout, the first `ping` after a `pause`.

## Kotlin form -> Clojure form

| Kotlin | Clojure |
|---|---|
| `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | `(co/CoroutineScope (kc/.plus (co/SupervisorJob) (co/Default co/Dispatchers)))` |
| `Dispatchers.IO` | `(co/IO co/Dispatchers)` |
| `scope.launch(dispatcher) { ... }` | `(co/.launch scope :context d :block (fn [s] ...))`, or `(co/.launch scope (fn [s] ...))` |
| `GlobalScope.launch(Dispatchers.Default) { ... }` | `(co/.launch co/GlobalScope :context (co/Default co/Dispatchers) :block (fn [_] ...))` |
| `job.cancel()`, `job.join()`, `job.cancelAndJoin()`, `job.children` | `(co/.cancel job)`, `(co/.join job)`, `(co/.cancelAndJoin job)`, `(co/children job)` |
| `scope.coroutineContext.job`, `scope.isActive` | `(co/job (.getCoroutineContext scope))`, `(co/isActive scope)` |
| `withTimeoutOrNull(ms) { ... }` | `(co/withTimeoutOrNull ms (fn [_] ...))` |
| `Channel<Any>(n)` | `(kch/Channel n)` |
| `Channel<Any>(n, BufferOverflow.DROP_OLDEST)` | `(kch/Channel n :onBufferOverflow kch/BufferOverflow.DROP_OLDEST)` |
| `Channel.UNLIMITED`, `Channel.CONFLATED` | `(kch/UNLIMITED kch/Channel)`, `(kch/CONFLATED kch/Channel)` |
| `ch.send(v)` / `ch.close()` | `(kch/.send ch v)` / `(kch/.close ch)` |
| `ch.trySend(v)`, `.isSuccess`, `.isClosed` | `(kch/.trySend ch v)`, `(kch/isSuccess r)`, `(kch/isClosed r)` |
| `ch.tryReceive().getOrNull()` | `(kch/.getOrNull (kch/.tryReceive ch))` |
| `select { c.onReceiveCatching { f(it) } }` | `(sel/select (fn [sb] (sel/.invoke sb (kch/onReceiveCatching c) (fn [r] ...))))` |
| `select { c.onReceive { ... } }` | `(sel/.invoke sb (kch/onReceive c) (fn [v] ...))` |
| `select { c.onSend(v) { ... } }` | `(sel/.invoke sb (kch/onSend c) v (fn [_] ...))` |
| `select { onTimeout(ms) { ... } }` | `(sel/.onTimeout sb ms (fn [] ...))` |
| `ch.receiveAsFlow()`, `flow.toList()` | `(kflow/.receiveAsFlow ch)`, `(kflow/.toList f)` |
| `executor.asCoroutineDispatcher()`, `dispatcher.asExecutor()` | `(co/.asCoroutineDispatcher ex)`, `(co/.asExecutor d)` |

## What still differs for a user

See the final report that came with this spike ("Semantic differences"); in short: the channel class, the `FutureTask`
subclass, the `datafy` counts of channels, one possible lost message per in-port in a stop that hits the race window,
the grace time (a transform or inject that runs for more than the grace time after `stop` is cancelled), and that a
Clojure step fn always runs on a virtual thread.
