package fx

import kotlinx.coroutines.*
import kotlin.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ReviewB: fixtures of ckway.review-b-test.

// ---- B5: cancellation order
object RbLog { val log = CopyOnWriteArrayList<String>() }

suspend fun rbCleanupCallee() {
    try { delay(10_000) } finally {
        withContext(NonCancellable) { delay(200) }
        RbLog.log.add("inner-cleanup-done")
    }
}

fun rbTimeoutOrder(block: suspend () -> Unit): List<String> = runBlocking {
    RbLog.log.clear()
    try { withTimeout(100) { block() } } catch (e: TimeoutCancellationException) { }
    RbLog.log.add("outer-resumed")
    RbLog.log.toList()
}

fun rbPureKotlinOrder(): List<String> = rbTimeoutOrder { rbCleanupCallee() }

// ---- B6: top-level suspend call
object RbTicker { @Volatile @JvmField var ticks = 0; @Volatile @JvmField var cancelled = false }

suspend fun rbTickForever() {
    try { while (true) { delay(20); RbTicker.ticks++ } }
    catch (e: CancellationException) { RbTicker.cancelled = true; throw e }
}
suspend fun rbNeedJob(): Job = coroutineContext.job
suspend fun rbSlowCancel() {
    try { delay(10_000) } finally { withContext(NonCancellable) { delay(300) }; RbTicker.cancelled = true }
}

// ---- B7: hooks that throw
class RbThrowingElement(val onUpdate: Boolean) : ThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<RbThrowingElement>
    override val key: CoroutineContext.Key<*> get() = Key
    override fun updateThreadContext(context: CoroutineContext) {
        if (onUpdate && Thread.currentThread().isVirtual) throw IllegalStateException("update-boom")
    }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
        if (!onUpdate && Thread.currentThread().isVirtual) throw IllegalStateException("restore-boom")
    }
}

fun rbWithThrowingElement(onUpdate: Boolean, block: suspend (Int) -> Any?): String = runBlocking {
    try { withContext(RbThrowingElement(onUpdate)) { block(1).toString() } }
    catch (e: Throwable) { "THROWN " + e.message }
}

class RbInterceptor(val throwing: Boolean) : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    val intercepted = AtomicInteger(); val released = AtomicInteger()
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        if (Thread.currentThread().isVirtual) {
            intercepted.incrementAndGet()
            if (throwing) throw IllegalStateException("intercept-boom")
        }
        return continuation
    }
    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        if (Thread.currentThread().isVirtual) released.incrementAndGet()
    }
}

fun rbInterceptor(throwing: Boolean): RbInterceptor = RbInterceptor(throwing)

/** Runs `block` as a coroutine with the interceptor and a Job in its context; the final result as text (or "hang"). */
fun rbRunWith(ip: RbInterceptor, block: suspend (Int) -> Any?): String {
    val latch = CountDownLatch(1)
    var out = "hang"
    suspend { block(1) }.startCoroutine(Continuation(ip + Job()) { r -> out = r.toString(); latch.countDown() })
    return if (latch.await(5, TimeUnit.SECONDS)) out else "hang"
}

/** A Job whose handler registrations are counted: invokeOnCompletion and dispose. */
object RbHandlers { val registered = AtomicInteger(); val disposed = AtomicInteger() }
class RbCountingInterceptorJob(val d: CompletableJob) : CompletableJob by d {
    @Suppress("UNCHECKED_CAST")
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
        if (key == Job.Key) this as E else d.get(key)
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(initial, this)
    @OptIn(InternalCoroutinesApi::class)
    override fun invokeOnCompletion(onCancelling: Boolean, invokeImmediately: Boolean, handler: CompletionHandler): DisposableHandle {
        RbHandlers.registered.incrementAndGet()
        val h = d.invokeOnCompletion(onCancelling, invokeImmediately, handler)
        return DisposableHandle { RbHandlers.disposed.incrementAndGet(); h.dispose() }
    }
}
fun rbRunBodiesOnOneJob(n: Int, block: suspend (Int) -> Any?): List<Int> {
    RbHandlers.registered.set(0); RbHandlers.disposed.set(0)
    val job = RbCountingInterceptorJob(Job())
    val ip = RbInterceptor(false)
    for (i in 1..n) {
        val latch = CountDownLatch(1)
        suspend { block(i) }.startCoroutine(Continuation(ip + job) { latch.countDown() })
        latch.await(5, TimeUnit.SECONDS)
    }
    return listOf(RbHandlers.registered.get(), RbHandlers.disposed.get(), ip.intercepted.get(), ip.released.get())
}

// ---- B10: generic super-interface
interface RbVis<T> { fun rbVisit(x: T): T }
interface RbStrVis : RbVis<String> { override fun rbVisit(x: String): String }
interface RbIntVis : RbVis<Int> { override fun rbVisit(x: Int): Int }
fun rbUseVis(v: RbVis<String>, s: String): String = v.rbVisit(s)
fun rbUseIntVis(v: RbVis<Int>, n: Int): Int = v.rbVisit(n)

interface RbOver { fun rbPut(x: Any?): String; fun rbPut(x: String): String }

// ---- B12: two unrelated default methods
interface RbDefA { fun rbSay(): String = "A" }
interface RbDefB { fun rbSay(): String = "B" }
fun rbHello(a: RbDefA): String = a.rbSay()

// ---- B9/B11: a plain interface
interface RbPlain { fun plain(x: Int): Int }
fun rbCallPlain(p: RbPlain, x: Int): Int = p.plain(x)

// ---- B15: reified calls that no other test uses
inline fun <reified T> rbTypeOf(): String = T::class.java.simpleName + "!"
inline fun <reified T> rbConcurrent(): String = "c:" + T::class.java.simpleName
