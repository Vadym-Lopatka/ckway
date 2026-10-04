package fx

// fixes_test: F1 fun interfaces whose method has a value class (mangled JVM name), F2-F5 error messages

@JvmInline value class Meters(val m: Long)
@JvmInline value class Label(val s: String)

fun interface VcBoth { fun adjust(u: Meters): Meters }
fun interface VcParam { fun accept(u: Meters, k: Int): Boolean }
fun interface VcRet { fun produce(k: Int): Meters }
fun interface VcRefBoth { fun shout(n: Label): Label }
fun interface VcNullBoth { fun adjust(u: Meters?): Meters? }
fun interface VcNullRef { fun adjust(n: Label?): Label? }
fun interface VcDefault {
  fun adjust(u: Meters): Meters
  fun twice(u: Meters): Meters = adjust(adjust(u))
  fun name(): String = "vcd"
}
fun interface VcSusp { suspend fun adjust(u: Meters): Meters }
fun interface VcFnArg { fun go(u: Meters, f: (Meters) -> Meters): Meters }

fun useBoth(a: VcBoth, u: Meters): Meters = a.adjust(u)
fun useParam(a: VcParam, u: Meters, k: Int): Boolean = a.accept(u, k)
fun useRet(a: VcRet, k: Int): Meters = a.produce(k)
fun useRefBoth(a: VcRefBoth, n: Label): Label = a.shout(n)
fun useNullBoth(a: VcNullBoth, u: Meters?): Meters? = a.adjust(u)
fun useNullRef(a: VcNullRef, n: Label?): Label? = a.adjust(n)
fun useDefault(a: VcDefault, u: Meters): Long = a.twice(u).m
fun useDefaultName(a: VcDefault): String = a.name()
fun useFnArg(a: VcFnArg, u: Meters): Meters = a.go(u) { Meters(it.m + 1) }
suspend fun useSusp(a: VcSusp, u: Meters): Meters = a.adjust(u)
fun useConvT(c: ConvT<Int>, x: Int): Int = c.conv(x)
fun sameVc(a: VcBoth): VcBoth = a
fun isKotlinImpl(a: VcBoth): Boolean = a is KImpl

class KImpl : VcBoth { override fun adjust(u: Meters): Meters = Meters(u.m * 10) }

class Cart2(val owner: String = "guest") {
  fun total(d: VcBoth): Meters = d.adjust(Meters(1000))
  val count: Int get() = 3
  val weigh: Int get() = 4
  fun ping(x: Int): Int = x
  fun count(tag: String): Int = tag.length
  fun add(m: Meters, n: Int = 1): Long = m.m + n
}

class Other(val name: String = "o") {
  fun size(): Int = 7
  fun count(): Int = 5
  fun weigh(): Int = 11
  val weight: Int get() = 9
  fun grow(by: Int): Int = by + 1
}

fun lenOf(s: String): Int = s.length
fun half(d: Double): Double = d / 2
fun flip(b: Boolean): Boolean = !b

// F2: `.size` with 0 arguments only exists on Other; a Cart2 passed to it is a wrong class
fun Other.twice(): Int = size() * 2

// F3: a Clojure function returns a wrong value where Kotlin wants a non-primitive type
fun interface StrFn { fun text(k: Int): String }
fun useStrFn(f: StrFn): String = f.text(1)
fun mapStr(f: (Int) -> String): String = f(1)
fun mapMeters(f: (Int) -> Meters): Long = f(1).m
fun mapLabelNull(f: (Int) -> Label?): String = f(1)?.s ?: "null"

// F4/F5: a Kotlin function value, and a reference with typed parameters
fun mkAdd(): (Int, String) -> String = { n, s -> s.repeat(n) }
fun mkMeters(): (Meters) -> Meters = { Meters(it.m + 1) }
fun describeIt(n: Int, s: String, m: Meters): String = "$n$s${m.m}"

// F1: plain function types with value classes (generic FunctionN: the boxed object), and references
fun recvVc(f: Meters.() -> Meters, u: Meters): Meters = u.f()
suspend fun suspVc(f: suspend (Meters) -> Meters, u: Meters): Meters = f(u)
fun retFnVc(): (Meters) -> Label? = { Label("x${it.m}") }
fun twoVc(f: (Meters, Label) -> Label, u: Meters): Label = f(u, Label("L"))
