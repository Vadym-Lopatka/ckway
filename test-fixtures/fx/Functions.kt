package fx

fun apply1(f: (Int) -> Int, x: Int): Int = f(x)
fun apply2(f: (String, Int) -> String): String = f("a", 2)
fun run0(f: () -> Unit): String { f(); return "ran" }
fun build(block: StringBuilder.() -> Unit): String = StringBuilder().apply(block).toString()
fun adder(n: Int): (Int) -> Int = { it + n }
fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { g(f(it)) }
fun maybe(f: ((Int) -> Int)? = null, x: Int = 1): Int = f?.invoke(x) ?: -x
fun withDefaultFn(x: Int, f: (Int) -> Int = { it * 2 }): Int = f(x)
fun callHandler(h: (Int, (Int) -> Int) -> Int): Int = h(5) { it + 1 }
fun echo(f: (Int) -> Int): (Int) -> Int = f
fun same(a: (Int) -> Int, b: (Int) -> Int): Boolean = a === b
fun twoArgs(f: (Int, Int) -> Int): Int = f(3, 4)
fun nested(f: (Int) -> (Int) -> Int): Int = f(1)(2)
fun pairFn(): (Int, String) -> String = { n, s -> s.repeat(n) }
fun optFn(): ((Int) -> Int)? = null

fun interface Pred {
  fun test(x: Int): Boolean
  fun negate(): Pred = Pred { !test(it) }
}
fun check(p: Pred, x: Int): Boolean = p.test(x)
fun negCheck(p: Pred, x: Int): Boolean = p.negate().test(x)
fun idPred(p: Pred): Pred = p

@Retention(AnnotationRetention.RUNTIME)
annotation class Marker

fun markerOf(f: (Int) -> Int): Boolean =
  f.javaClass.methods.find { it.name.startsWith("invoke") }?.annotations?.any { it is Marker } ?: false

fun interface Tagged { fun tag(x: Int): String }
fun tagMarker(t: Tagged): Boolean =
  t.javaClass.methods.find { it.name == "tag" }?.annotations?.any { it is Marker } ?: false

class Router2 {
  val log = mutableListOf<String>()
  fun get(path: String = "", handler: (String) -> String): String { log.add(path); return handler(path) }
  fun ctx(prefix: String, block: Router2.() -> Unit = {}): Router2 { val r = Router2(); r.log.add(prefix); r.block(); return r }
}

class Store { fun get(key: String, extra: Any? = null): String = "store:$key" }
