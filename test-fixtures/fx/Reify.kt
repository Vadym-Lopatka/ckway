package fx

import kotlinx.coroutines.*

// Interfaces that Clojure implements with kt/reify (step 7), and Kotlin code that uses them.

interface Job2 {
    suspend fun run()
    val name: String get() = "job"
    val allowParallelRun: Boolean get() = false
}

interface Shape2 {
    fun area(): Double
    fun scale(k: Int = 2): Shape2
    fun describe(prefix: String = "shape"): String = "$prefix:${area()}"
}

interface Holder2 {
    var level: Int
    val uid: Uid
    fun next(u: Uid): Uid
    fun label(n: Name?): Name?
}

interface Over {
    fun put(x: Int): String
    fun put(x: String): String
}

interface Visitor<T> {
    fun visit(x: T): T
    fun done()
}

interface WithExt {
    fun StringBuilder.emit(s: String)
}

interface Cb2 {
    fun onEvent(f: (Int) -> Int): Int
    fun handler(): (Int) -> Int
}

interface Sup { fun base(): String }
interface Sub : Sup { fun more(): String }

// a mix: a Kotlin interface that extends a Java interface
interface Both : Runnable { fun extra(): String }

// an abstract class: kt/reify takes interfaces only
abstract class AbstractThing { abstract fun go(): String }

// many kinds of parameter and result in one place
interface Kinds {
    fun num(a: Int, b: Long, c: Double, d: Boolean, e: Char): String
    fun unit(): Unit
    fun count(): Int
    fun maybe(): Int?
    fun text(): String
    suspend fun slow(n: Int): Int
    suspend fun nothing()
    suspend fun uid(u: Uid): Uid
}

@Retention(AnnotationRetention.RUNTIME)
annotation class Route(val path: String, val code: Int = 200)

suspend fun runJob(j: Job2): String { j.run(); return j.name }
fun jobFlags(j: Job2): String = "${j.name}/${j.allowParallelRun}"
fun useShape(s: Shape2): String = s.describe() + "|" + s.scale().area() + "|" + s.scale(3).area() + "|" + s.describe("p")
fun useHolder(h: Holder2): String {
    h.level = 5
    val u = h.next(h.uid)
    val n = h.label(Name("a"))
    val z = h.label(null)
    return "${h.level}/${u.v}/${n?.s}/$z"
}
fun useOver(o: Over): String = o.put(1) + "," + o.put("s")
fun <T> useVisitor(v: Visitor<T>, x: T): T { val r = v.visit(x); v.done(); return r }
fun useExt(w: WithExt): String { val sb = StringBuilder(); with(w) { sb.emit("a"); sb.emit("b") }; return sb.toString() }
fun useCb(c: Cb2): Int = c.onEvent { it + 1 } + c.handler()(10)
fun useSub(s: Sub): String = s.base() + "+" + s.more()
fun useBoth(b: Both): String { b.run(); return b.extra() }
fun annotationsOf(o: Any, method: String): List<String> =
    o.javaClass.methods.filter { it.name == method }.flatMap { m -> m.annotations.map { it.annotationClass.java.name } }
fun routeOf(o: Any, method: String): String =
    o.javaClass.methods.filter { it.name == method }.flatMap { m -> m.annotations.filterIsInstance<Route>() }
        .joinToString { "${it.path}:${it.code}" }
fun useKinds(k: Kinds): String = "${k.num(1, 2L, 3.5, true, 'c')}|${k.unit()}|${k.count()}|${k.maybe()}|${k.text()}"
suspend fun useKindsSuspend(k: Kinds): String { val r = k.slow(4); k.nothing(); return "$r/${k.uid(Uid(1)).v}" }
fun launchJob(scope: CoroutineScope, j: Job2): kotlinx.coroutines.Job = scope.launch { j.run() }
suspend fun runJobTagged(tag: String, j: Job2): String { withContext(Dispatchers.Default + TagContext(tag)) { j.run() }; return j.name }

@Retention(AnnotationRetention.RUNTIME)
annotation class Tagged2(val value: String)
fun tagOf(o: Any, method: String): String =
    o.javaClass.methods.filter { it.name == method }.flatMap { m -> m.annotations.filterIsInstance<Tagged2>() }
        .joinToString { it.value }
fun useShape2(s: Shape2): String = s.describe("d")
fun visitInt(v: Visitor<Int>): Int = v.visit(1) + 1

// a Kotlin property with the plain name of a method of a Java interface
interface Runs { val run: Boolean }
fun visitIsTwo(v: Visitor<Int>): Boolean { val r: Any? = v.visit(1); return r == 2 }
interface RunsToo : Runs, Runnable
