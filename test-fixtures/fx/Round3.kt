package fx.r3

// Fixtures of ckway.round3-test. Every `k...` function makes the call as Kotlin source and returns the
// declaration that KOTLIN picked: the tests compare the ckway answer with it.

// ---- a Clojure integer at Any / T / Number is passed unchanged (a Long stays a Long)
fun anyClass(x: Any?): String = if (x == null) "null" else x.javaClass.name
fun numClass(x: Number): String = x.javaClass.name
fun <T> tClass(x: T): String = if (x == null) "null" else x!!.javaClass.name
fun <T> tList(a: T, b: T): List<T> = listOf(a, b)
fun kotlinIntMap(): Map<Int, String> = mapOf(1 to "a")
fun kotlinIntList(): List<Int> = listOf(1, 2)
fun kotlinLongList(): List<Long> = listOf(1L, 2L)
fun kContains(xs: List<Any>, x: Any) = xs.contains(x)
fun amb(a: Int) = "Int"
fun amb(a: Long) = "Long"
fun kAmb() = amb(1)

// ---- targets for the hygiene test
fun takesInt(x: Int) = x
fun takesLong(x: Long) = x
fun takesShort(x: Short) = x.toInt()
fun takesByte(x: Byte) = x.toInt()
fun takesDouble(x: Double) = x
fun takesFloat(x: Float) = x.toDouble()
fun takesChar(c: Char) = c.code
fun takesBool(b: Boolean) = !b
fun takesBoxedInt(x: Int?) = x?.let { it + 1 }
fun withDefaults(a: Int, b: String = "d", c: Long = 7) = "$a$b$c"
fun spread(vararg xs: Int) = xs.sum()
fun strs(vararg xs: String) = xs.joinToString(",")
@JvmInline value class Meters(val v: Int)
fun meters(m: Meters) = m.v
fun mk(v: Int) = Meters(v)
fun applyF(x: Int, f: (Int) -> Int) = f(x)
fun applyP(f: (Int, String) -> String) = f(1, "a")
fun interface Conv { fun conv(x: Int): Int }
fun useConv(c: Conv) = c.conv(41)
class Holder(var n: Int, val s: String)
data class Pt(val x: Int, val y: String)
suspend fun susInt(x: Int): Int = x + 1
inline fun <reified T> typeNameOf(): String = T::class.simpleName ?: "?"
inline fun <reified T> castTo(x: Any?): T = x as T
fun d33(p: String, x: Int) = p + x

// ---- specificity with type arguments
fun <T> gl(xs: List<T>) = "List<T>"
fun gl(xs: Collection<Int>) = "Collection<Int>"
fun kGl(xs: List<Int>) = gl(xs)
fun m1(x: Collection<Any>) = "Collection<Any>"
fun m1(x: List<Int>) = "List<Int>"
fun kM1(xs: List<Int>) = m1(xs)
fun m2(x: MutableList<Int>) = "MutableList<Int>"
@JvmName("m2b") fun m2(x: List<Any>) = "List<Any>"
fun kM2(xs: java.util.ArrayList<Int>) = m2(xs)
fun <T> m3(x: Iterable<T>) = "Iterable<T>"
fun m3(x: List<String>) = "List<String>"
fun kM3(xs: List<String>) = m3(xs)
fun <T> m4(x: List<T>) = "List<T>"
fun <T> m4(x: Collection<T>) = "Collection<T>"
fun kM4(xs: List<Int>) = m4(xs)
fun m6(x: Set<Int>) = "Set<Int>"
fun <T> m6(x: Collection<T>) = "Collection<T>"
fun kM6(xs: java.util.HashSet<Int>) = m6(xs)
fun <T> m7(x: List<T>, y: T) = "List<T>,T"
fun m7(x: List<Int>, y: Int) = "List<Int>,Int"
fun kM7(xs: List<Int>, y: Int) = m7(xs, y)
fun m8(x: Collection<Number>) = "Collection<Number>"
fun m8(x: List<Int>) = "List<Int>"
fun kM8(xs: List<Int>) = m8(xs)
fun m9(x: Map<String, Int>) = "Map"
@JvmName("m9b") fun m9(x: MutableMap<String, Int>) = "MutableMap"
fun kM9(x: java.util.HashMap<String, Int>) = m9(x)
fun m11(x: Iterable<Any?>) = "Iterable<Any?>"
fun m11(x: Collection<Int>) = "Collection<Int>"
fun kM11(xs: List<Int>) = m11(xs)
fun m12(x: Map<String, Any>) = "Map<String,Any>"
@JvmName("m12b") fun <K, V> m12(x: Map<K, V>) = "Map<K,V>"
fun kM12(x: Map<String, Int>) = m12(x)
// neither is a subtype of the other, both are non-generic: Kotlin reports an ambiguity (no oracle)
fun m10(x: List<Number>) = "List<Number>"
fun m10(x: Collection<Int>) = "Collection<Int>"

// ---- a type the user wrote is the static type
class B {
    fun mu(x: String) = "member"
    fun mv(x: Any) = "member-any"
}
fun B.mu(x: CharSequence) = "ext"
fun B.mv(x: String) = "ext-string"
fun kMuCs(b: B, x: CharSequence) = b.mu(x)
fun kMuStr(b: B, x: String) = b.mu(x)
fun kMvAny(b: B, x: Any) = b.mv(x)
fun kMvStr(b: B, x: String) = b.mv(x)
fun nb(x: Number) = "Number"
fun nb(x: Long) = "Long"
fun kNbLong(x: Long) = nb(x)
fun cs(x: CharSequence) = "CharSequence"
fun cs(x: String) = "String"
fun kCsCs(x: CharSequence) = cs(x)
fun kCsStr(x: String) = cs(x)

// ---- a named vararg collection
fun va(vararg xs: Int) = "Int:" + xs.size
fun va(vararg xs: String) = "String:" + xs.size
fun sp(vararg d: Int) = "Int:" + d.size
fun sp(vararg d: String) = "String:" + d.size
fun kVaInts() = va(*intArrayOf(1, 2))
fun kVaStrs() = va(*arrayOf("a", "b"))
fun kSpInts() = sp(1, 2)
fun kSpStrs() = sp("a", "b")

// ---- messages
fun small(n: Int) = n
fun tiny(n: Short) = n.toInt()
fun small(n: Int, m: Int) = n + m

// ---- the library's own widening is used only when no candidate accepts the value as it is
fun w1(x: Double) = "Double"
fun w1(x: Any) = "Any"
fun kW1_int(x: Int) = w1(x)
fun kW1_long(x: Long) = w1(x)
fun kW1_short(x: Short) = w1(x)
fun kW1_byte(x: Byte) = w1(x)
fun kW1_float(x: Float) = w1(x)
fun kW1_double(x: Double) = w1(x)
fun kW1_lit() = w1(1)
fun kW1_big() = w1(5_000_000_000)
fun kW1_dlit() = w1(1.5)
fun w10(x: Short) = "Short"
fun w10(x: Any) = "Any"
fun kW10_int(x: Int) = w10(x)
fun kW10_long(x: Long) = w10(x)
fun kW10_short(x: Short) = w10(x)
fun kW10_byte(x: Byte) = w10(x)
fun kW10_float(x: Float) = w10(x)
fun kW10_double(x: Double) = w10(x)
fun kW10_lit() = w10(1)
fun kW10_big() = w10(5_000_000_000)
fun kW10_dlit() = w10(1.5)
fun w11(x: Byte) = "Byte"
fun w11(x: Any) = "Any"
fun kW11_int(x: Int) = w11(x)
fun kW11_long(x: Long) = w11(x)
fun kW11_short(x: Short) = w11(x)
fun kW11_byte(x: Byte) = w11(x)
fun kW11_float(x: Float) = w11(x)
fun kW11_double(x: Double) = w11(x)
fun kW11_lit() = w11(1)
fun kW11_big() = w11(5_000_000_000)
fun kW11_dlit() = w11(1.5)
fun w12(x: Double) = "Double"
fun w12(x: Comparable<*>) = "Comparable<*>"
fun kW12_int(x: Int) = w12(x)
fun kW12_long(x: Long) = w12(x)
fun kW12_short(x: Short) = w12(x)
fun kW12_byte(x: Byte) = w12(x)
fun kW12_float(x: Float) = w12(x)
fun kW12_double(x: Double) = w12(x)
fun kW12_lit() = w12(1)
fun kW12_big() = w12(5_000_000_000)
fun kW12_dlit() = w12(1.5)
fun w13(x: Float) = "Float"
fun w13(x: Number) = "Number"
fun kW13_int(x: Int) = w13(x)
fun kW13_long(x: Long) = w13(x)
fun kW13_short(x: Short) = w13(x)
fun kW13_byte(x: Byte) = w13(x)
fun kW13_float(x: Float) = w13(x)
fun kW13_double(x: Double) = w13(x)
fun kW13_lit() = w13(1)
fun kW13_big() = w13(5_000_000_000)
fun kW13_dlit() = w13(1.5)
fun w2(x: Float) = "Float"
fun w2(x: Any) = "Any"
fun kW2_int(x: Int) = w2(x)
fun kW2_long(x: Long) = w2(x)
fun kW2_short(x: Short) = w2(x)
fun kW2_byte(x: Byte) = w2(x)
fun kW2_float(x: Float) = w2(x)
fun kW2_double(x: Double) = w2(x)
fun kW2_lit() = w2(1)
fun kW2_big() = w2(5_000_000_000)
fun kW2_dlit() = w2(1.5)
fun w3(x: Int) = "Int"
fun w3(x: Any) = "Any"
fun kW3_int(x: Int) = w3(x)
fun kW3_long(x: Long) = w3(x)
fun kW3_short(x: Short) = w3(x)
fun kW3_byte(x: Byte) = w3(x)
fun kW3_float(x: Float) = w3(x)
fun kW3_double(x: Double) = w3(x)
fun kW3_lit() = w3(1)
fun kW3_big() = w3(5_000_000_000)
fun kW3_dlit() = w3(1.5)
fun w4(x: Long) = "Long"
fun w4(x: Any) = "Any"
fun kW4_int(x: Int) = w4(x)
fun kW4_long(x: Long) = w4(x)
fun kW4_short(x: Short) = w4(x)
fun kW4_byte(x: Byte) = w4(x)
fun kW4_float(x: Float) = w4(x)
fun kW4_double(x: Double) = w4(x)
fun kW4_lit() = w4(1)
fun kW4_big() = w4(5_000_000_000)
fun kW4_dlit() = w4(1.5)
fun w5(x: Double) = "Double"
fun w5(x: Number) = "Number"
fun kW5_int(x: Int) = w5(x)
fun kW5_long(x: Long) = w5(x)
fun kW5_short(x: Short) = w5(x)
fun kW5_byte(x: Byte) = w5(x)
fun kW5_float(x: Float) = w5(x)
fun kW5_double(x: Double) = w5(x)
fun kW5_lit() = w5(1)
fun kW5_big() = w5(5_000_000_000)
fun kW5_dlit() = w5(1.5)
fun w6(x: Float) = "Float"
fun w6(x: Double) = "Double"
fun w6(x: Any) = "Any"
fun kW6_int(x: Int) = w6(x)
fun kW6_long(x: Long) = w6(x)
fun kW6_short(x: Short) = w6(x)
fun kW6_byte(x: Byte) = w6(x)
fun kW6_float(x: Float) = w6(x)
fun kW6_double(x: Double) = w6(x)
fun kW6_lit() = w6(1)
fun kW6_big() = w6(5_000_000_000)
fun kW6_dlit() = w6(1.5)
fun w7(x: Comparable<*>) = "Comparable<*>"
fun w7(x: Double) = "Double"
fun kW7_int(x: Int) = w7(x)
fun kW7_long(x: Long) = w7(x)
fun kW7_short(x: Short) = w7(x)
fun kW7_byte(x: Byte) = w7(x)
fun kW7_float(x: Float) = w7(x)
fun kW7_double(x: Double) = w7(x)
fun kW7_lit() = w7(1)
fun kW7_big() = w7(5_000_000_000)
fun kW7_dlit() = w7(1.5)
fun w8(x: Long) = "Long"
fun w8(x: Number) = "Number"
fun kW8_int(x: Int) = w8(x)
fun kW8_long(x: Long) = w8(x)
fun kW8_short(x: Short) = w8(x)
fun kW8_byte(x: Byte) = w8(x)
fun kW8_float(x: Float) = w8(x)
fun kW8_double(x: Double) = w8(x)
fun kW8_lit() = w8(1)
fun kW8_big() = w8(5_000_000_000)
fun kW8_dlit() = w8(1.5)
fun w9(x: Int) = "Int"
fun w9(x: Long) = "Long"
fun w9(x: Number) = "Number"
fun kW9_int(x: Int) = w9(x)
fun kW9_long(x: Long) = w9(x)
fun kW9_short(x: Short) = w9(x)
fun kW9_byte(x: Byte) = w9(x)
fun kW9_float(x: Float) = w9(x)
fun kW9_double(x: Double) = w9(x)
fun kW9_lit() = w9(1)
fun kW9_big() = w9(5_000_000_000)
fun kW9_dlit() = w9(1.5)
