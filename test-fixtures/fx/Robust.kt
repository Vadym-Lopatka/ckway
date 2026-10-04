package fx.rb

// robust_test: round 2 of the call-path review (R4-R16)

// R4, R9: checked conversions of every primitive width
fun intp(x: Int) = "int:$x"
fun shortp(x: Short) = "short:$x"
fun bytep(x: Byte) = "byte:$x"
fun longp(x: Long) = "long:$x"
fun boolp(b: Boolean) = if (b) "T" else "F"
fun floatp(x: Float) = "float:$x"
fun doublep(x: Double) = "double:$x"
fun charp(c: Char) = "char:$c"

// R8
fun ch(c: Char) = "ch:$c"
fun chd(c: Char) = "Char"
fun chd(d: Double) = "Double"
fun kChdD() = chd(65.0)

// R13: an integer literal that fits Int is an Int at Any / T
fun anyKind(x: Any?) = x?.javaClass?.simpleName ?: "null"
fun <T> tKind(x: T) = (x as Any?)?.javaClass?.simpleName ?: "null"
fun varKind(vararg xs: Any?) = xs.joinToString(",") { it?.javaClass?.simpleName ?: "null" }
fun numKind(x: Number) = x.javaClass.simpleName
fun longKind(x: Long?) = x?.javaClass?.simpleName ?: "null"
fun kAnyKind() = anyKind(1)
fun kAnyKindBig() = anyKind(5_000_000_000)
fun kTKind() = tKind(1)
fun kVarKind() = varKind(1, 2)
fun kNumKind() = numKind(1)
fun kLongKind() = longKind(1)

// R5: nine named arguments
fun nine(a: String = "", b: String = "", c: String = "", d: String = "", e: String = "", f: String = "", g: String = "", h: String = "", i: String = "") =
    a + b + c + d + e + f + g + h + i

// R6
fun <T> echo(x: T): T = x
fun str1(): String = "abc"

// R10
fun mapper(xs: List<String>, f: (String) -> String): List<String> = xs.map(f)

// R11
fun order(a: Int, b: Int = 0, c: Int = 0) = "$a-$b-$c"

// R12
class A
fun name(a: A) = "fun"
val A.name: String get() = "prop"

// R14
fun jfun(f: java.util.function.Function<String, Int>): Int = f.apply("abc")
fun jpred(p: java.util.function.Predicate<String>): Boolean = p.test("abc")
fun jsup(s: java.util.function.Supplier<String>): String = s.get()
fun jrun(r: Runnable): String { r.run(); return "ran" }
fun jbi(f: java.util.function.BiFunction<Int, Int, Int>): Int = f.apply(2, 3)
fun jcmp(c: java.util.Comparator<String>): Int = c.compare("a", "b")
fun jtwo(f: java.util.function.Function<String, Int>, g: (Int) -> Int): Int = g(f.apply("ab"))
fun plus1(x: Int): Int = x + 1
fun nineo(a: String = "", b: String = "", c: String = "", d: String = "", e: String = "", f: String = "", g: String = "", h: String = "", i: String = "") =
    a + b + c + d + e + f + g + h + i
fun nineo(a: Int, b: String = "", c: String = "", d: String = "", e: String = "", f: String = "", g: String = "", h: String = "", i: String = "") =
    "int" + a + b + c + d + e + f + g + h + i
