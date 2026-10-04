package fx

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@JvmInline value class Uid(val v: Long) {
    constructor(s: String) : this(s.toLong())
    constructor(a: Long, b: Long = 100) : this(a + b)
    fun plus(n: Long): Uid = Uid(v + n)
    fun describe(prefix: String = "uid"): String = "$prefix:$v"
    val doubled: Long get() = v * 2
    val next: Uid get() = Uid(v + 1)
    companion object {
        const val LIMIT = 99L
        val ZERO = Uid(0)
        fun of(x: Long): Uid = Uid(x)
    }
}

@JvmInline value class Name(val s: String) {
    fun shout(): String = s.uppercase()
}

@JvmInline value class Id<T>(val raw: String)

@JvmInline value class Cnt(val n: Int = 3)

fun nextUid(u: Uid): Uid = Uid(u.v + 1)
fun uidOrNull(u: Uid?): Uid? = u?.let { Uid(it.v + 1) }
fun nameOrNull(n: Name?): Name? = n?.let { Name(it.s + "!") }
fun uidList(us: List<Uid>): List<Uid> = us.map { Uid(it.v * 10) }
fun withUidDefault(x: Int, u: Uid = Uid(7)): Long = x + u.v
fun uints(vararg xs: UInt, base: Int = 0): Int = base + xs.sumOf { it.toInt() }
fun mkU(i: Int): UInt = i.toUInt()
fun uVal(x: UInt): Int = x.toInt()
fun Uid.double(): Uid = Uid(v * 2)
val Uid.tripled: Uid get() = Uid(v * 3)
fun mapUid(u: Uid, f: (Uid) -> Uid): Uid = f(u)
fun uidFn(): (Uid) -> Uid = { Uid(it.v + 100) }
fun idOf(i: Id<String>): String = i.raw
fun mkId(s: String): Id<String> = Id(s)
fun cnt(c: Cnt = Cnt()): Int = c.n

fun pause(d: Duration = 1.seconds): Long = d.inWholeMilliseconds
fun twice(d: Duration): Duration = d * 2
fun u(x: UInt): UInt = x + 1u
fun tryIt(fail: Boolean): Result<Int> = if (fail) Result.failure(IllegalStateException("bad")) else Result.success(7)

class Holder(var uid: Uid = Uid(1)) {
    fun bump(by: Uid): Uid = Uid(uid.v + by.v)
    fun bumpD(by: Uid = Uid(5), n: Int = 1): Long = uid.v + by.v + n
    val twin: Uid get() = Uid(uid.v)
}

interface Tracker { fun track(u: Uid): String }
class TrackerImpl : Tracker { override fun track(u: Uid): String = "tracked:${u.v}" }

fun tag(u: Uid): String = "uid"
fun tag(n: Name): String = "name"
