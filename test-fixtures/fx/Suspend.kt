package fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.time.Duration

suspend fun step(i: Int): Int { delay(10); return i + 1 }
suspend fun fastStep(i: Int): Int { delay(1); return i + 1 }
suspend fun boom(): Int { delay(10); throw IllegalStateException("boom") }
suspend fun boomIo(): Int { delay(1); throw java.io.IOException("io") }
suspend fun now(): Int = 7
suspend fun yieldStep(i: Int): Int { yield(); return i + 1 }
suspend fun unitStep() { delay(1) }
suspend fun nullStep(i: Int): String? { delay(1); return if (i > 0) "n$i" else null }
suspend fun greetLater(name: String = "you", n: Int = 1): String { delay(1); return "hi $name x$n" }

suspend fun runIt(block: suspend (Int) -> Any?): Any? = block(1)
suspend fun runOpt(block: (suspend (Int) -> Int)? = null): Int = block?.invoke(5) ?: -1
suspend fun runUnit(block: suspend (Int) -> Unit): String { block(3); return "ran" }
suspend fun runInt(block: suspend (Int) -> Int): Int = block(4)
fun launchIt(scope: CoroutineScope, block: suspend (Int) -> Any?): Job = scope.launch { block(1) }
fun launchUndispatched(scope: CoroutineScope, block: suspend (Int) -> Any?): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) { block(1) }

val Tag = ThreadLocal<String?>()
class TagContext(val tag: String) : ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TagContext>
    override fun updateThreadContext(context: CoroutineContext): String? { val o = Tag.get(); Tag.set(tag); return o }
    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) { Tag.set(oldState) }
}
fun currentTag(): String? = Tag.get()
suspend fun runTagged(tag: String, block: suspend (Int) -> Any?): Any? =
    withContext(Dispatchers.Default + TagContext(tag)) { block(1) }
suspend fun ctxTag(): String? = coroutineContext[TagContext]?.tag
suspend fun currentJob(): Job? = coroutineContext[Job]
suspend fun hasDispatcher(): Boolean = coroutineContext[kotlin.coroutines.ContinuationInterceptor] != null
suspend fun isActiveNow(): Boolean = coroutineContext[Job]?.isActive ?: true

class Svc(val prefix: String = "svc") {
    suspend fun load(k: String, n: Int = 1): String { delay(1); return "$prefix:$k:$n" }
    suspend fun nothing() { delay(1) }
    companion object {
        suspend fun build(n: Int, p: String = "made"): Svc { delay(1); return Svc("$p$n") }
    }
}
interface Loader { suspend fun loadBase(k: String): String { delay(1); return "base:$k" } }
class LoaderImpl : Loader
suspend fun String.shoutLater(n: Int = 2): String { delay(1); return uppercase() + "!".repeat(n) }
suspend fun waitFor(d: Duration, tag: String = "w"): String { delay(d); return "$tag:${d.inWholeMilliseconds}" }

@JvmInline value class Ticket(val n: Long)
suspend fun mkTicket(n: Long): Ticket { delay(1); return Ticket(n) }
suspend fun ticketNext(t: Ticket): Ticket { delay(1); return Ticket(t.n + 1) }
suspend fun ticketOrNull(t: Ticket?): Ticket? { delay(1); return t?.let { Ticket(it.n + 1) } }

suspend fun withRecv(block: suspend StringBuilder.() -> Unit): String { val sb = StringBuilder(); sb.block(); return sb.toString() }
fun interface SBefore { suspend fun before(x: Int): Int }
suspend fun useBefore(b: SBefore, x: Int): Int = b.before(x)
fun interface SUnit { suspend fun go(x: Int) }
suspend fun useUnit(b: SUnit, x: Int): String { b.go(x); return "went" }
fun suspFn(): suspend (Int) -> Int = { x -> delay(1); x + 100 }
suspend fun decorate(d: suspend (Int, suspend (Int) -> Int) -> Int): Int = d(1) { x -> delay(1); x * 10 }
fun numbers(): Flow<Int> = flow { for (i in 1..3) { delay(1); emit(i) } }

class Keeper {
    var saved: (suspend (Int) -> Any?)? = null
    fun keep(b: suspend (Int) -> Any?) { saved = b }
    suspend fun runSaved(): Any? = saved!!(1)
}

// ---- helpers for tests
fun manyIt(n: Int, block: suspend (Int) -> Any?): Long = runBlocking(Dispatchers.Default) {
    val t = System.nanoTime(); (1..n).map { async { block(it) } }.awaitAll(); (System.nanoTime() - t) / 1_000_000 }
fun yieldIt(block: suspend (Int) -> Any?): Long = runBlocking {
    val t = System.nanoTime(); block(1); System.nanoTime() - t }
fun ktYieldLoop(n: Int): Long = runBlocking {
    val t = System.nanoTime(); var x = 0; repeat(n) { yield(); x++ }; (System.nanoTime() - t) }
fun threadName(): String = Thread.currentThread().toString()
fun isVirtualNow(): Boolean = Thread.currentThread().isVirtual
fun mainThreadsDefault(block: suspend (Int) -> Any?): Any? = runBlocking(Dispatchers.Default) { block(1) }
fun failsOf(block: suspend (Int) -> Any?): String = try { runBlocking { block(1) }.toString() } catch (e: Throwable) { "THROWN " + e }
fun runB(block: suspend (Int) -> Any?): Any? = runBlocking { runIt(block) }
fun runTaggedB(tag: String, block: suspend (Int) -> Any?): Any? = runBlocking { runTagged(tag, block) }
fun defaultScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)
fun unconfinedScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
fun cancelScope(s: CoroutineScope) { s.cancel() }

fun markerOfSusp(f: suspend (Int) -> Any?): Boolean =
    f.javaClass.methods.find { it.name == "invoke" && it.parameterCount == 2 }?.annotations?.any { it is Marker } ?: false
fun markerOfSBefore(t: SBefore): Boolean =
    t.javaClass.methods.find { it.name == "before" }?.annotations?.any { it is Marker } ?: false
fun same(b: suspend (Int) -> Int): suspend (Int) -> Int = b
fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
fun tagCtx(tag: String): CoroutineContext = Dispatchers.Default + TagContext(tag)
suspend fun hop(i: Int): Int = withContext(Dispatchers.Default) { i + 1 }
fun ktHopLoop(n: Int): Long = runBlocking {
    val t = System.nanoTime(); var x = 0; repeat(n) { x = hop(x) }; (System.nanoTime() - t) }
suspend fun runZero(block: suspend () -> Int): Int = block() + 1
suspend fun runThree(block: suspend (Int, String, Long) -> String): String = block(1, "b", 3L)
