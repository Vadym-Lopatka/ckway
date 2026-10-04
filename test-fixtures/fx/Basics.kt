package fx

fun greet(name: String, greeting: String = "Hello", punct: String = "!"): String = "$greeting, $name$punct"

fun String.shout(times: Int = 1): String = uppercase() + "!".repeat(times)

val String.wordCount: Int get() = split(" ").size

fun prims(d: Double = 1.5, c: Char = 'a', b: Byte = 1, s: Short = 2, f: Float = 3.0f, flag: Boolean = true, l: Long = 4L, i: Int = 5): String =
  "$d $c $b $s $f $flag $l $i"

fun join(vararg parts: String): String = parts.joinToString(",")

fun joinSep(vararg parts: String, sep: String = ","): String = parts.joinToString(sep)

fun many(a1: Int = 1, a2: Int = 2, a3: Int = 3, a4: Int = 4, a5: Int = 5, a6: Int = 6, a7: Int = 7, a8: Int = 8, a9: Int = 9, a10: Int = 10, a11: Int = 11, a12: Int = 12, a13: Int = 13, a14: Int = 14, a15: Int = 15, a16: Int = 16, a17: Int = 17, a18: Int = 18, a19: Int = 19, a20: Int = 20, a21: Int = 21, a22: Int = 22, a23: Int = 23, a24: Int = 24, a25: Int = 25, a26: Int = 26, a27: Int = 27, a28: Int = 28, a29: Int = 29, a30: Int = 30, a31: Int = 31, a32: Int = 32, a33: Int = 33): String = "$a1,$a2,$a3,$a4,$a5,$a6,$a7,$a8,$a9,$a10,$a11,$a12,$a13,$a14,$a15,$a16,$a17,$a18,$a19,$a20,$a21,$a22,$a23,$a24,$a25,$a26,$a27,$a28,$a29,$a30,$a31,$a32,$a33"

var sideEffect: Int = 0

fun ping() { sideEffect++ }

fun nick(name: String?): String? = name?.trim()

fun nickLen(n: Int?): Int? = n?.let { it + 1 }

fun eq(a: String, b: Any): String = "string:$b"
fun eq(a: Int, b: Any): String = "int:$b"

val answer: Int = 42
var counter: Int = 0

fun twoLongs(a: Long, b: Long): Long = a * 10 + b

context(prefix: String)
fun ctxHello(name: String): String = "$prefix $name"

fun amb(a: Int): String = "int"
fun amb(a: Long): String = "long"

fun fl(a: Float): String = "float"
fun fl(a: Double): String = "double"

fun amb2(a: Int, b: Any): String = "int2"
fun amb2(a: Long, b: Any): String = "long2"
