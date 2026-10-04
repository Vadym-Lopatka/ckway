package fx

suspend fun slow(x: Int): String = "slow$x"

fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))

fun interface Cb { fun call(x: Int): Int }
fun useCb(cb: Cb, x: Int): Int = cb.call(x)

inline fun <reified T> typeName(): String = T::class.java.simpleName

class Settable { var level: Int = 1 }

fun withSusp(f: suspend (Int) -> Int): String = "susp"
fun interface SuspCb { suspend fun go(x: Int): Int }
fun useSusp(c: SuspCb): String = "usp"
