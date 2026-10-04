package fx.r4

// Fixtures of ckway.round4-test (third review). Every `k...` function makes the call as Kotlin source and returns
// what KOTLIN picked: the tests compare the ckway answer with it.

// ---- X1: library-added types on lambda parameters are upper bounds
interface Ev
class Click(val n: Int) : Ev { fun pos() = "pos$n" }
class Key(val n: Int) : Ev { fun code() = "code$n" }
fun handle(e: Click) = "click"
fun handle(e: Key) = "key"
fun eachEv(xs: List<Ev>, f: (Ev) -> String): List<String> = xs.map(f)
fun events(): List<Ev> = listOf(Click(1), Key(2))
fun eachEvs(xs: List<List<Ev>>, f: (List<Ev>) -> String): List<String> = xs.map(f)
suspend fun eachEvS(xs: List<Ev>, f: suspend (Ev) -> String): List<String> = xs.map { f(it) }
interface Visitor { fun visit(e: Ev): String }
fun runVisitor(v: Visitor, e: Ev) = v.visit(e)
fun runVisitorAll(v: Visitor) = events().map { v.visit(it) }

// ---- X3: specificity decides before the tiers
fun <T> gl(xs: List<T>) = "List<T>"
fun gl(xs: Collection<Int>) = "Collection<Int>"
fun kGl(xs: List<Int>) = gl(xs)
fun <T> vt(xs: List<T>) = "List<T>"
fun vt(xs: Iterable<Int>) = "Iterable<Int>"
fun kVt(xs: List<Int>) = vt(xs)
fun vc(xs: MutableList<Int>) = "MutableList<Int>"
fun vc(xs: Collection<String>) = "Collection<String>"

// ---- X4: hints that the user writes and that fit no candidate for sure
fun hasKey(m: Map<Int, String>, k: Int) = m.containsKey(k)
fun sizeOfList(xs: List<Int>) = xs.size
fun firstOfSeq(xs: Iterable<Int>) = xs.first()
fun joinColl(xs: Collection<Int>) = xs.joinToString(",")
fun strLen(s: String) = s.length
fun lenOf(s: String) = s.length
fun lenOf(n: Int) = n

// ---- X5: a wrong hint
class Sq(val s: Int) { fun area() = s * s }
class Circ { fun area() = 3 }
fun circ() = Circ()
fun areaOf(s: Sq) = s.area()

// ---- X7: one member narrowed to different types in two branches
interface Src { fun get(): Any }
interface S1 : Src { override fun get(): CharSequence }
interface S2 : Src { override fun get(): Comparable<*> }
interface StrSrc : Src { override fun get(): String }
fun readS1(s: S1): String = s.get().toString() + "/S1"
fun readS2(s: S2): String = s.get().toString() + "/S2"
fun readSrc(s: Src): String = s.get().toString() + "/Src"
fun readStr(s: StrSrc): String = s.get() + "/Str"
// incompatible parameter types in two branches
interface P1 { fun put(x: String): String }
interface P2 { fun put(x: Int): String }
class StrBox : StrSrc { override fun get(): String = "box" }
fun strBox(): StrSrc = StrBox()
// X11: a class that implements the narrowed member
class StrImpl : StrSrc { override fun get(): String = "impl" }
fun upper(s: String) = s.uppercase()
fun upper(s: Any) = "any"

// X7: leaves with different parameter types
interface G<T> { fun put(x: T): String }
interface G1 : G<String> { override fun put(x: String): String }
interface G2 : G<Int> { override fun put(x: Int): String }
fun putG1(g: G1, x: String) = g.put(x)
fun putG2(g: G2, x: Int) = g.put(x)
