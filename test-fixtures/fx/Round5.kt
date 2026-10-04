package fx.r5

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

// Fixtures of ckway.round5-test (fourth review).

// ---- Y2: a suspend call that throws before it suspends must complete its own Job
suspend fun boomNow(): Int = throw IllegalStateException("now")
suspend fun okNow(): Int = 5
suspend fun boomArg(n: Int): Int = if (n == 2) throw IllegalStateException("arg$n") else n
fun blockingRun(block: suspend () -> Any?): Any? = runBlocking { block() }

@Volatile var lastJob: Job? = null
suspend fun boomJob(): Int { lastJob = coroutineContext[Job]; throw IllegalStateException("job") }
suspend fun okJob(): Int { lastJob = coroutineContext[Job]; return 1 }
fun lastJobCompleted(): Boolean = lastJob!!.isCompleted
fun lastJobActive(): Boolean = lastJob!!.isActive

// ---- Y1: a narrowed override has the default values of the member it overrides
interface DSrc { fun mk(x: Int = 7): Any }
class DSame : DSrc { override fun mk(x: Int): String = "same$x" }
open class DBase { open fun get(x: Int = 3, y: Int = x + 1): Any = "b$x,$y" }
open class DMid : DBase() { override fun get(x: Int, y: Int): CharSequence = "m$x,$y" }
class DLeaf : DMid() { override fun get(x: Int, y: Int): String = "l$x,$y" }
abstract class DThis { val tag = "T"; open fun m(a: String = tag + "!"): Any = a }
class DThisSub : DThis() { override fun m(a: String): String = "sub:$a" }
interface DSusp { suspend fun s(n: Int = 2): Any }
class DSuspImpl : DSusp { override suspend fun s(n: Int): String = "s$n" }
@JvmInline value class VId(val v: Int)
interface DVc { fun vc(id: VId = VId(9)): Any }
class DVcImpl : DVc { override fun vc(id: VId): String = "vc${id.v}" }
interface DIface2 : DSrc { override fun mk(x: Int): CharSequence }
class DIface2Impl : DIface2 { override fun mk(x: Int): String = "i2-$x" }
fun kDSame() = DSame().mk()
fun kDLeaf() = DLeaf().get()
fun kDLeafY() = DLeaf().get(y = 5)
fun kDMid() = DMid().get(1)
fun kDThis() = DThisSub().m()
suspend fun kDSusp() = DSuspImpl().s()
fun kDVc() = DVcImpl().vc()
fun kDI2() = DIface2Impl().mk()

// ---- Y3: a candidate wins only if it is at least as specific on EVERY parameter
fun mx(a: CharSequence, b: Int) = "CharSequence,Int"
fun mx(a: String, b: Long) = "String,Long"
fun kMxL() = mx("s", 1L)   // String,Long: only that one takes a Long
// `mx("s", 1)` is rejected by kotlinc: "overload resolution ambiguity" (String is better on the first parameter, Int on the second)
fun my(a: CharSequence, b: Long) = "CharSequence,Long"
fun my(a: String, b: Int) = "String,Int"
fun kMy() = my("s", 1)     // String,Int: better on both
fun mn(a: Int, b: Long) = "Int,Long"
fun mn(a: Long, b: Int) = "Long,Int"
// `mn(1, 1)` is ambiguous in Kotlin too

// ---- Y4: a narrowed override and an untyped receiver
interface TSrc { fun fetch(): Any; val label: Any }
interface TStr : TSrc { override fun fetch(): String; override val label: String }
class TStrImpl : TStr { override fun fetch(): String = "t-str"; override val label: String = "t-tag" }
class TSrcImpl : TSrc { override fun fetch(): Any = 42; override val label: Any = 7 }
fun tStr(): TStr = TStrImpl()

// ---- Y2: a suspend function VALUE (called through its Clojure wrapper) that throws before it suspends
fun boomFn(): suspend () -> Int = { lastJob = coroutineContext[Job]; throw IllegalStateException("fn") }
