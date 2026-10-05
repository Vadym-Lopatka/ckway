# Findings: core.async.flow on kotlinx.coroutines through ckway

This file shows the state on ckway at `fix/spike-findings` d446567 (now on `main`). `README.md` has the invariants, the test arms and the forms table.

## Verdict

ckway and kotlinx.coroutines fit this port well. The whole machinery of `clojure.core.async.flow` (process loops, control, routing, back-pressure, pause and resume, ping, inject, compute workloads) runs on Kotlin `Channel`s, `select`, one `CoroutineScope` for each flow, and dispatchers, written in Clojure with plain `kt` forms. No Kotlin glue is needed, and no `:<>` call, so no Kotlin compiler.
For a user the port works as the original: the same flow definition, the same step functions, the same keywords and message shapes. The same user code runs against the original (the oracle) and the port in differential scenarios and in tests of 11 invariants; the results are equal.
Price: throughput is about 2 to 2.5 times lower than the original (200 000 messages through 3 procs: about 0.9 to 1.0 s against 0.4 s).
`bin/test` was run again in review, idle and under CPU load: 81 tests (about 4390 assertions) with the oracle, and 36 tests in the drop-in JVM, 0 failures in both.

## Three ways to use it

1. Change one `require`: `[spike.coflow.flow :as flow]`, with the original namespace as `:as-alias` for the keywords.
2. `(spike.coflow.takeover/install!)`: puts the port behind the vars of `clojure.core.async.flow`. User code is not changed. It changes the whole JVM.
3. The directory `dropin/` first on the class path: `clojure.core.async.flow` IS the port. Even the `require` stays.

core.async is a dependency, for its protocols only (`clojure.core.async.impl.protocols`, the flow `spi`): the channels that a user sees work with `<!!`, `<!`, `alts!!`, `poll!`, `close!`. `src/` uses no `chan`, `go`, `thread` or `alts`; a test checks it.

## Findings

`kch` = `kotlinx.coroutines.channels`, `co` = `kotlinx.coroutines`, `sel` = `kotlinx.coroutines.selects`, `kflow` = `kotlinx.coroutines.flow`.

| # | Kotlin form | Clojure form | Result | Note |
|---|---|---|---|---|
| 1 | `select { c.onReceiveCatching { } }` | `(sel/select (fn [sb] (sel/.invoke sb (kch/onReceiveCatching c) (fn [r] ...))))` | works | `invoke` is a member extension with two receivers; `sb` is first. The select is biased, so control wins. |
| 2 | `c.onSend(v) { }` | `(sel/.invoke sb (kch/onSend c) v (fn [_] ...))` | works | The value is between the clause and the block. `(kch/onSend c v)` is an error. |
| 3 | `onTimeout(ms) { }`, `d.onAwait { }` | `(sel/.onTimeout sb ms (fn [] ...))`, `(co/onAwait d)` | works | |
| 4 | `ch.receiveCatching()` | `(kch/.getOrNull (kch/.receiveCatching c))` | works | Was a workaround: a `suspend` function that returned a value class came back unwrapped. Fixed in ckway (D2). |
| 5 | `ch.trySend(v)`, `ch.tryReceive()` | `(kch/.trySend c v)`, `(kch/.tryReceive c)`, `(kch/isSuccess r)`, `(kch/isClosed r)`, `(kch/.getOrNull r)` | works | `isSuccess` and `isClosed` are properties: no dot. |
| 6 | `Channel(n, DROP_OLDEST)` | `(kch/Channel n :onBufferOverflow kch/BufferOverflow.DROP_OLDEST)` | works | A sliding buffer is `DROP_OLDEST`, a dropping buffer is `DROP_LATEST`, a fixed n is `Channel(n)`, 0 is rendezvous. |
| 7 | `Channel.CONFLATED` | `(kch/CONFLATED kch/Channel)` | works | A companion property. |
| 8 | `launch(ctx) { }`, `async`, `cancel`, `join`, `cancelAndJoin`, `withTimeoutOrNull` | `(co/.launch scope f)`, `(co/.launch scope :context d :block f)`, `(co/.async ...)`, `(co/.cancelAndJoin j)`, `(co/withTimeoutOrNull ms f)` | works | The trailing lambda is positional (rule 4). A receiver or dispatcher with no type needs a hint (`^Job`, `^CoroutineDispatcher`). |
| 9 | `executor.asCoroutineDispatcher()`, `dispatcher.asExecutor()` | `(co/.asCoroutineDispatcher ex)`, `(co/.asExecutor d)` | works | `:io-exec` and the like become dispatchers. |
| 10 | `Dispatchers.IO`, `Dispatchers.Default` | `(co/IO co/Dispatchers)`, `(co/Default co/Dispatchers)` | works | |
| 11 | `ch.receiveAsFlow().toList()` | `(kflow/.toList (kflow/.receiveAsFlow ch))` | works | The report stream as a Kotlin `Flow`. |
| 12 | `ch.send(keyword)` | `(let [v :hello] (kch/.send c v))` | workaround | A keyword literal always names a parameter (rule 4). Pass it through a local. Issue #14. |
| 13 | `Job.cancel()` on a loop that waits in `select` | `(co/.cancel job)` | works | The loop ends at once with a `CancellationException`. |
| 14 | a step fn with `(catch Exception ...)` around a blocking wait | | works, with care | A cancel is seen as `InterruptedException` in the Clojure body (`doc/limits.md` 4 and 16). The loop reports a `Throwable` only while the scope is active, else it throws it again. |
| 15 | a Clojure body as a `suspend` lambda | | works, with a limit | The body runs on its own virtual thread. A dispatcher does not bound it. Issue #17. |
| 16 | many idle procs | | works | 1000 idle procs hold no platform thread each; the thread count is bounded by the pools. |

## What the review rounds found in the port itself

These were bugs of the spike, not of ckway. Each has a test that failed first.

- `stop` waited for the procs; the original returns at once. Now a reaper coroutine cleans up.
- A user's core.async channel as an in-port was read one message ahead, also while the proc was paused. Now a proc with user ports waits through ONE commit protocol (core.async's `Handler` with a shared flag), so nothing is taken that is not consumed. Loss is 0 with stops in a flood; the first design lost 60 of 600.
- The wrapper of a channel (`KPort`) held a value outside the Kotlin channel (a "stash"). That gave a double delivery, a reorder under load, and capacity + 1. Now a value leaves the Kotlin channel only when a taker commits, under one lock. Capacity is exact.
- A polling put pump; a `:compute` timeout that interrupted the transform; a `Future` class that was not a `FutureTask`. All now as the original.

## Differences that are left

| Difference | Reason |
|---|---|
| The report and error channels are `KPort`, not `ManyToManyChannel`. `datafy` of a channel has the same keys, but its counts are best effort. | A Kotlin `Channel` behind core.async's protocols. |
| `futurize` and `inject` return a subclass of `FutureTask`. | The task runs on a coroutine and cancels it. |
| A step fn runs on a virtual thread in every workload. `:compute` does not bound parallelism. | ckway runs a Clojure suspend lambda on its own virtual thread. |
| A rendezvous port (capacity 0) polls every 1 ms while a core.async taker waits. | Kotlin gives no signal for a suspended `send`. The flow's own report and error channels are sliding and do not poll. |
| A forced cancel (the opt-in grace time, or `Job.cancel`) can drop a message that a user's channel had committed. `stop` never does. | A cancel is not in the original. |
| Raw Kotlin reads (`->kotlin`, `->flow`) and core.async reads on the same port must not be mixed. | Pending puts need the wake of the port's own receive. |
| `takeover` and `dropin` change `clojure.core.async.flow` for the whole JVM. | They replace vars, or the namespace on the class path. |
| A flow that has a proc from another `ProcLauncher` leaves its parked pump coroutines alive after `stop`. | The port cannot know when that launcher's thread ends. |
| Throughput is about 2 to 2.5 times lower. | One coroutine and one virtual thread for each proc, and locks at the core.async boundary. |

The oracle is core.async 1.10.874-alpha3 from Maven Central.

## License

`src/spike/coflow/flow.clj` and `impl.clj` are derived from core.async (Copyright Rich Hickey and contributors) and keep its EPL 1.0 header. The rest of this repository is MIT.

## Versions

- core.async 1.10.874-alpha3 (oracle and dependency).
- kotlinx-coroutines-core-jvm 1.11.0.
- ckway: this repository by `:local/root "../.."`.
- JDK 25.0.4, Clojure 1.12.1.
