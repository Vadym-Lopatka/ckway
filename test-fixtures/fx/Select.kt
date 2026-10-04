package fx.sel

// Overload selection fixtures. Every `k...` function makes the same call as Kotlin source and returns the
// declaration that KOTLIN picked: the tests compare the ckway answer with it.

// ---- S1: vararg and single overloads
fun lo(x: Any?) = "single"
fun lo(vararg xs: Any?) = "vararg:" + xs.size
fun kLo1() = lo(1)
fun kLo2() = lo(1, 2)
fun kLo0() = lo()

fun lov(vararg xs: Int) = "int:" + xs.size
fun lov(vararg xs: String) = "str:" + xs.size
fun kLovInts() = lov(1, 2, 3)
fun kLovStrs() = lov("a", "b")

// overloads that differ only by a primitive / boxed parameter
fun prim(x: Int) = "int"
fun prim(x: Long) = "long"
fun prim(x: Short) = "short"
fun prim(x: Byte) = "byte"
fun prim(x: Char) = "char"
fun prim(x: Float) = "float"
fun prim(x: Double) = "double"
fun prim(x: Boolean) = "boolean"
fun prim(x: Any?) = "any"
fun kPrimInt() = prim(1)
fun kPrimLong() = prim(5_000_000_000)
fun kPrimShort() = prim(1.toShort())
fun kPrimByte() = prim(1.toByte())
fun kPrimChar() = prim('c')
fun kPrimFloat() = prim(1.5f)
fun kPrimDouble() = prim(1.5)
fun kPrimBool() = prim(true)
fun kPrimStr() = prim("s")

fun bb(x: Boolean) = "prim"
fun bb(x: Boolean?) = "boxed"
fun kBbTrue() = bb(true)
fun kBbNil() = bb(null)

// defaults with overloads of the same arity
fun dflt(a: String, b: Int = 1, c: Boolean = false) = "str:$a:$b:$c"
fun dflt(a: Int, b: Int = 1, c: Boolean = false) = "int:$a:$b:$c"
fun dflt(a: Any, vararg r: String) = "any:" + r.size
fun kDfltStr() = dflt("s")
fun kDfltInt() = dflt(7)
fun kDfltStrB() = dflt("s", b = 3)
fun kDfltStrC() = dflt("s", c = true)

// ---- S2: member vs extension
class A {
    fun f(x: Any) = "member"
    fun g(x: String) = "member-g"
    fun h(x: Int) = "member-h"
}
fun A.f(x: String) = "ext"
fun A.g(x: Any) = "ext-g"
fun A.h(x: Long) = "ext-h-long"
fun A.h(x: String) = "ext-h-str"
fun A.only(x: String) = "ext-only"
fun kAf(a: A) = a.f("s")
fun kAf2(a: A) = a.f(1)
fun kAg(a: A) = a.g("s")
fun kAg2(a: A) = a.g(1)
fun kAh(a: A) = a.h(1)
fun kAhs(a: A) = a.h("s")
fun kAonly(a: A) = a.only("s")

// ---- S4: most specific
fun coll(x: Iterable<Int>) = "iterable"
fun coll(x: Collection<Int>) = "collection"
fun coll(x: List<Int>) = "list"
fun kColl(x: List<Int>) = coll(x)
fun kCollC(x: Collection<Int>) = coll(x)
fun kCollI(x: Iterable<Int>) = coll(x)

fun nn(x: String?) = "String?"
fun nn(x: Any?) = "Any?"
fun kNnNil() = nn(null)
fun kNnStr() = nn("s")
fun kNnInt() = nn(1)

fun ni(x: Int) = "Int"
fun ni(x: Int?) = "Int?"
fun kNi() = ni(1)
fun kNiNil() = ni(null)

fun one(x: Int) = "fixed"
fun one(vararg xs: Int) = "vararg"
fun kOne1() = one(1)
fun kOne2() = one(1, 2)
fun kOne0() = one()

fun seqs(x: CharSequence) = "CharSequence"
fun seqs(x: String) = "String"
fun kSeqsStr() = seqs("s")
fun kSeqsCs(x: CharSequence) = seqs(x)

fun two(a: Int, b: Any) = "int-any"
fun two(a: Any, b: Int) = "any-int"
fun kTwoStr() = two(1, "s")
fun kTwoStr2() = two("s", 1)

// a receiver: String is more specific than CharSequence
fun CharSequence.rcv() = "CharSequence"
fun String.rcv() = "String"
fun kRcvStr() = "s".rcv()
fun kRcvCs(x: CharSequence) = x.rcv()

