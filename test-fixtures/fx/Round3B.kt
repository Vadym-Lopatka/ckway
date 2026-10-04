package fx

import kotlinx.coroutines.*
import kotlin.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Fixtures of ckway.round3b-test: kt/reify of interfaces that narrow a type, cancellation after a
// non-cancellable callee, interrupts of a top-level wait, one resume of a continuation.

// ---- M1: reify of interfaces that narrow a type
interface R3GSrc<T> { fun get(): T }
interface R3GStr : R3GSrc<String> { override fun get(): String }
fun r3UseG(s: R3GSrc<String>): String = s.get()
fun r3UseGStr(s: R3GStr): String = s.get()

interface R3Src { fun get(): Any; val name: Any }
interface R3StrSrc : R3Src { override fun get(): String; override val name: String }
fun r3UseSrc(s: R3Src): String = "" + s.get() + "/" + s.name
fun r3UseStrSrc(s: R3StrSrc): String = s.get() + "/" + s.name

// three levels
interface R3L1 { fun g(): Any }
interface R3L2 : R3L1 { override fun g(): CharSequence }
interface R3L3 : R3L2 { override fun g(): String }
fun r3UseL1(s: R3L1): String = "" + s.g()
fun r3UseL2(s: R3L2): String = "" + s.g()
fun r3UseL3(s: R3L3): String = s.g()

// generic parameter AND covariant return at several levels
interface R3P<T> { fun put(x: T): Any }
interface R3PStr : R3P<String> { override fun put(x: String): CharSequence }
interface R3PStr2 : R3PStr { override fun put(x: String): String }
fun r3UseP(s: R3P<String>, x: String): String = "" + s.put(x)
fun r3UseP2(s: R3PStr2, x: String): String = s.put(x)

// two super-interfaces declare the same member
interface R3Left { fun h(): CharSequence; val tag: Any }
interface R3Right { fun h(): Any; val tag: CharSequence }
interface R3Both : R3Left, R3Right { override fun h(): String; override val tag: String }
fun r3UseLeft(s: R3Left): String = "" + s.h() + s.tag
fun r3UseRight(s: R3Right): String = "" + s.h() + s.tag
fun r3UseBoth(s: R3Both): String = s.h() + s.tag
interface R3Same { fun h(): CharSequence }
interface R3SameB : R3Left, R3Same { override fun h(): CharSequence }

// suspend members
interface R3SSrc { suspend fun s(): Any; suspend fun t(x: Int): Any }
interface R3SStr : R3SSrc { override suspend fun s(): String; override suspend fun t(x: Int): String }
fun r3UseSSrc(s: R3SSrc): String = runBlocking { "" + s.s() + s.t(2) }
interface R3SG<T> { suspend fun sg(x: T): T }
interface R3SGStr : R3SG<String> { override suspend fun sg(x: String): String }
fun r3UseSG(s: R3SG<String>, x: String): String = runBlocking { s.sg(x) }

// value classes
@JvmInline value class R3Uid(val v: Int)
interface R3VA { fun id(): Any; fun take(x: Any): Any }
interface R3VB : R3VA { override fun id(): R3Uid; override fun take(x: Any): R3Uid }
interface R3VG<T> { fun put(x: T): T }
interface R3VGU : R3VG<R3Uid> { override fun put(x: R3Uid): R3Uid }
fun r3UseVA(s: R3VA): String = "" + s.id() + "|" + s.take(1)
fun r3UseVB(s: R3VB): Int = s.id().v + s.take(1).v
fun r3UseVG(s: R3VG<R3Uid>): Int = s.put(R3Uid(5)).v
fun r3UseVGU(s: R3VGU): Int = s.put(R3Uid(6)).v

// default body in the sub-interface
interface R3DStr : R3GSrc<String> { override fun get(): String = "dflt" }
fun r3UseD(s: R3GSrc<String>): String = s.get()

// vars
interface R3Var { var level: Int; var label: String }
interface R3VarSub : R3Var { override var level: Int }
fun r3UseVar(s: R3VarSub): Int { s.level = 4; return s.level }

// a generic var overridden with a String, and a generic interface between the two
interface R3PS<T> { var p: T }
interface R3PStrS : R3PS<String> { override var p: String }
fun r3UsePS(s: R3PS<String>): String { s.p = "w"; return s.p }
interface R3Mid<U> : R3GSrc<U>
interface R3MidStr : R3Mid<String> { override fun get(): String }

// ---- M5: overloads Int? and Int
interface R3Ov { fun f(x: Int?): String; fun f(x: Int): String }
fun r3UseOv(a: R3Ov): String = a.f(null) + "," + a.f(3)

// ---- M2: a body whose callee ignores cancellation
suspend fun r3Uncancellable(ms: Long): Int = withContext(NonCancellable) { delay(ms); 5 }
suspend fun r3Delay(ms: Long) { delay(ms) }
private fun r3Now() = System.nanoTime() / 1_000_000

/** `withTimeout(ms) { block() }` in a coroutine: "timeout@<elapsed ms>", or "completed@<elapsed ms>". */
fun r3TimeoutBlock(ms: Long, block: suspend () -> Unit): String = runBlocking {
    val t0 = r3Now()
    try { withTimeout(ms) { block() }; "completed@" + (r3Now() - t0) }
    catch (e: TimeoutCancellationException) { "timeout@" + (r3Now() - t0) }
}
/** Kotlin's own: the callee ignores the cancellation, then the body blocks. */
fun r3KotlinBlockingReference(): String = r3TimeoutBlock(100) { r3Uncancellable(300); Thread.sleep(1000) }
fun r3KotlinSuspendingReference(): String = r3TimeoutBlock(100) { r3Uncancellable(300); delay(3000) }
/** What the body sees after the callee that ignored the cancellation returned. */
fun r3SeenAfterUncancellable(): String = runBlocking {
    var seen = "?"
    try { withTimeout(100) { val x = r3Uncancellable(300); seen = "value=$x active=$isActive"; delay(3000) } } catch (e: TimeoutCancellationException) { }
    seen
}

// ---- M3: a top-level wait that is interrupted
object R3State { @Volatile @JvmField var cleaned = false }
suspend fun r3Never(): Int = suspendCoroutine { }
suspend fun r3SlowFinally() {
    try { delay(10_000) } finally { withContext(NonCancellable) { delay(300) }; R3State.cleaned = true }
}
/** What `runBlocking` does when its thread is interrupted 100 ms after the start. kind: "slow" or "never". */
fun r3RunBlockingProbe(kind: String, waitMs: Long): String {
    R3State.cleaned = false
    var out = "still blocked"
    val t0 = r3Now()
    val th = Thread {
        out = try { runBlocking { if (kind == "slow") r3SlowFinally() else r3Never() }; "returned" }
        catch (e: Throwable) { e.javaClass.simpleName + " after " + (r3Now() - t0) + " ms, flag=" + Thread.currentThread().isInterrupted + ", cleaned=" + R3State.cleaned }
    }
    th.isDaemon = true
    th.start(); Thread.sleep(100); th.interrupt(); th.join(waitMs)
    return if (th.isAlive) "still blocked $waitMs ms after the interrupt (thread state ${th.state})" else out
}

// ---- M9: an interceptor that runs the continuation inline, then throws
class R3ThrowAfterRun : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = object : Continuation<T> {
        override val context get() = continuation.context
        override fun resumeWith(result: Result<T>) {
            continuation.resumeWith(result)
            if (Thread.currentThread().isVirtual) throw IllegalStateException("after-run")
        }
    }
}
fun r3ThrowAfterRun(): ContinuationInterceptor = R3ThrowAfterRun()

/** Starts `block` with the interceptor; counts how often the completion is called. => "<count>:<result>" */
fun r3CountCompletions(ip: ContinuationInterceptor, block: suspend (Int) -> Any?): String {
    val n = AtomicInteger(); var out = "none"
    val first = CountDownLatch(1)
    suspend { block(1) }.startCoroutine(Continuation(ip + Job()) { r -> n.incrementAndGet(); out = r.toString(); first.countDown() })
    first.await(5, TimeUnit.SECONDS)
    Thread.sleep(300)
    return n.get().toString() + ":" + out
}
