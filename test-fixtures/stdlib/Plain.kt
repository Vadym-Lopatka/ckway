package fxs

import java.util.concurrent.CountDownLatch
import kotlin.coroutines.*

suspend fun plainAsync(x: Int): Int = suspendCoroutine { c -> Thread { Thread.sleep(10); c.resume(x + 1) }.start() }
suspend fun plainSync(x: Int): Int = x + 1
suspend fun plainFail(): Int = suspendCoroutine { c -> Thread { c.resumeWithException(IllegalStateException("plainboom")) }.start() }
suspend fun plainUnit() = suspendCoroutine<Unit> { c -> Thread { c.resume(Unit) }.start() }

fun runPlain(block: suspend (Int) -> Int): Int {
    var out: Result<Int>? = null
    val latch = CountDownLatch(1)
    val body: suspend () -> Int = { block(1) }
    body.startCoroutine(Continuation(EmptyCoroutineContext) { out = it; latch.countDown() })
    latch.await()
    return out!!.getOrThrow()
}
fun interface PBefore { suspend fun before(x: Int): Int }
fun runPlainFi(b: PBefore): Int = runPlain { b.before(it) }
suspend fun usePBefore(b: PBefore, x: Int): Int = b.before(x)
fun plainFn(): suspend (Int) -> Int = { x -> plainAsync(x) }
fun threadName(): String = Thread.currentThread().toString()

// a suspend function that returns a value class: the result is the object (D2)
@JvmInline value class PlainVc(val a: Any?)
suspend fun plainVcAsync(x: Any?): PlainVc = suspendCoroutine { c -> Thread { Thread.sleep(10); c.resume(PlainVc(x)) }.start() }
suspend fun plainVcSync(x: Any?): PlainVc = PlainVc(x)
fun plainVcValue(v: PlainVc): Any? = v.a
