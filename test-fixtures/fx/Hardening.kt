package fx

import java.io.IOException
import kotlin.reflect.typeOf

// ---- H1: Kotlin function values that throw a checked exception
fun throwingFn(): (Int) -> Int = { throw IOException("kboom") }
fun throwingSusp(): suspend (Int) -> Int = { throw IOException("sboom") }
fun callWithThrowing(d: ((Int) -> Int) -> Int): Int = d { throw IOException("in") }

// ---- H2: type aliases
typealias JList = java.util.ArrayList<String>
typealias UserAlias = User
typealias WcAlias = WithCompanion
typealias RegAlias = Registry
typealias ColorAlias = Color
typealias InnerAlias = Outer.Inner
typealias FnAlias = (Int) -> Int
typealias SuspHandler = suspend StringBuilder.() -> Any?
typealias Params = Map<String, String?>
typealias Twin<A> = Pair<A, A>
typealias PredAlias = Pred
typealias GBoxAlias<T> = GBox<T>
fun paramsSize(p: Params): Int = p.size
inline fun <reified T> typeOfName(): String = T::class.java.simpleName
inline fun <reified T> showType(): String = typeOf<T>().show()
fun applyFnAlias(f: FnAlias, x: Int): Int = f(x)

// ---- H3
fun applyProp(p: () -> Int): Int = p()

// ---- H6
sealed class Sealed3 { class A : Sealed3() }

// ---- H4
inline fun <reified R> List<*>.firstIs(): R? = firstOrNull { it is R } as R?
inline fun <T, reified R> Map<String, T>.pick(k: String): R? = this[k] as? R
class NBox<T : Number>(val n: T) {
  inline fun <reified R> conv(v: T): R? = v as? R
  inline fun <reified R> back(): R? = n as? R
  inline fun <reified R> keep(vs: List<T>): List<R> = vs.filterIsInstance<R>()
}
class Pairy<A, B : Comparable<B>>(val a: A, val b: B) {
  inline fun <reified R> mix(x: A, y: B): R? = (if (x == null) y else x) as? R
}
open class BaseG<T>(val t: T) { inline fun <reified R> asR(x: T): R? = x as? R }
class SubG(t: String) : BaseG<String>(t)

// ---- H5
class Lbl(val s: String)
class Num(val n: Int)
context(a: Lbl, b: Lbl) inline fun <reified T> both(): String = "${a.s}|${b.s}|${T::class.simpleName}"
context(a: Lbl, b: Lbl) fun bothPlain(): String = "${a.s}|${b.s}"
context(a: Lbl, n: Num) inline fun <reified T> mixed(): String = "${a.s}|${n.n}|${T::class.simpleName}"

// ---- H7
fun <T> applyT(x: T, f: (T) -> T): T = f(x)
fun <T> twiceT(x: T, f: (T) -> T): T = f(f(x))
fun <T> sameT(x: T): T = x
fun <T> listOfT(a: T, b: T): List<T> = listOf(a, b)
fun <T> mapT(xs: List<T>, f: (T) -> T): List<T> = xs.map(f)
fun <T> makeFn(f: (T) -> T): (T) -> T = f
fun sumInts(xs: List<Int>): Int = xs.sum()
fun firstInt(xs: List<Int>): Int = xs.first()
fun <T> describeT(x: T): String = x?.javaClass?.simpleName ?: "null"
fun <T> boxedT(x: T): String = (x as Int).toString()
fun interface ConvT<T> { fun conv(x: T): T }
fun <T> viaConv(c: ConvT<T>, x: T): T = c.conv(x)
suspend fun <T> suspT(x: T, f: suspend (T) -> T): T = f(x)
fun <T> nullT(x: T?, f: (T?) -> T?): T? = f(x)
fun <T> recvT(x: T, f: T.() -> T): T = x.f()
fun <T> twoFn(f: (T) -> T, g: ((T) -> T) -> T): T = g(f)
fun <T> varT(vararg xs: T): List<T> = xs.toList()
fun <T> isIntT(x: T): Boolean = x is Int
fun <T> useConv(c: ConvT<T>, x: T): Int = c.conv(x) as Int
fun <T> useFn(f: (T) -> T, x: T): Int = f(x) as Int

// ---- H9 limits
fun useWide(f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Int): Int = f(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
fun wideFn(): (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Int = { a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int, a14: Int, a15: Int, a16: Int, a17: Int, a18: Int, a19: Int, a20: Int -> a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20 }
interface Wide20 { fun w(a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int, a14: Int, a15: Int, a16: Int, a17: Int, a18: Int, a19: Int): Int }
fun callWide(w: Wide20): Int = w.w(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
class OuterI { val tag = "o"; inner class In(val v: Int) { fun both(): String = "$tag$v" } }
fun wideThrowing(): (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Int = { a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int, a14: Int, a15: Int, a16: Int, a17: Int, a18: Int, a19: Int, a20: Int -> throw java.io.IOException("wide") }
