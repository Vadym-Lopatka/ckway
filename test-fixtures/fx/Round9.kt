// Batch A of the fixes after the spikes (http4k, Koin, Ktor): argument binding and overload selection. Each function
// returns a text that says which declaration ran. The Clojure calls are in test/ckway/round9_test.clj.
package fx.r9

// ---- A1: a function and a property of one name are one var
class Route9(val name: String)

fun routes9(vararg list: Route9): String = "routes-fun:" + list.size
val Route9.routes9: List<String> get() = listOf("routes-prop:" + name)

// a member property and a top-level function
class Box9(val items9: String)

fun items9(box: Box9): String = "items-fun:" + box.items9

// only one of them fits a call: the property takes a Route9, the function takes a Box9
fun pick9(box: Box9): String = "pick-fun"
val Route9.pick9: String get() = "pick-prop"

// a top-level property and a function without parameters
val zero9: String get() = "zero-prop"
fun zero9(): String = "zero-fun"

// ---- A2: a trailing lambda
class Module9(val tag: String = "m")

fun module9(createdAtStart: Boolean = false, declaration: (Module9) -> String): String =
    "module9:" + createdAtStart + ":" + declaration(Module9())

class Cfg9 {
    // after a vararg
    fun status(vararg status: Int, handler: suspend (Call9, Int) -> Unit): String = "status-call:" + status.size
    @JvmName("statusWithContext")
    fun status(vararg status: Int, handler: suspend (Ctx9, Int) -> Unit): String = "status-ctx:" + status.size
    fun only9(vararg codes: Int, handler: suspend (Call9, Int) -> Unit): String = "only9:" + codes.joinToString(",")
}

class Call9
class Ctx9

// with a receiver: the receiver is the first parameter
fun build9(name: String = "n", block: StringBuilder.() -> Unit): String {
    val sb = StringBuilder(name + ":")
    sb.block()
    return sb.toString()
}

fun interface Rule9 { fun test(x: Int): String }
fun rule9(prefix: String = "p", r: Rule9): String = prefix + r.test(1)

fun jrule9(n: Int = 1, p: java.util.function.IntPredicate): String = "jrule9:" + p.test(n)

// the last parameter has a default: no trailing lambda
fun noTrail9(a: Int = 1, b: () -> String = { "d" }): String = "noTrail9:" + a + ":" + b()

// the parameter before the lambda has no default
fun need9(a: String, b: () -> String): String = "need9:" + a + b()

// a candidate that fits by the usual binding wins over one that fits only with a trailing lambda
fun f9(x: Any): String = "f9-any"
fun f9(a: Int = 1, block: () -> String): String = "f9-trailing:" + a + block()

// a vararg and defaults before the lambda
fun mix9(a: Int = 1, b: Int = 2, vararg rest: String, tail: () -> String): String =
    "mix9:" + a + ":" + b + ":" + rest.joinToString(",") + ":" + tail()

// a lambda in the middle is not the last parameter
fun mid9(f: () -> String, n: Int = 3): String = "mid9:" + f() + n

// two overloads: the usual binding of one of them works, the other one needs the trailing lambda
fun two9(a: Int, b: Int = 5): String = "two9-int:" + a + b
fun two9(a: Int = 7, block: () -> String): String = "two9-trailing:" + a + block()

// ---- A3: a collection passed as a whole to a vararg
class Pair9(val v: String)

fun pr9(vararg list: Pair9): String = "pr9-pairs:" + list.size
fun pr9(vararg list: Route9): String = "pr9-routes:" + list.size

// the same, with a default and another parameter before
fun pt9(prefix: String, vararg list: Pair9): String = prefix + ":pt9-pairs:" + list.size
fun pt9(prefix: String, vararg list: Route9): String = prefix + ":pt9-routes:" + list.size

// a candidate that takes every element
fun any9(vararg list: Route9): String = "any9-routes:" + list.size
fun any9(vararg list: Any): String = "any9-any:" + list.size

// ---- A4: overloads that differ only in the parameter types of a lambda
fun on9(h: (Call9) -> String): String = "on9-call:" + h(Call9())
@JvmName("on9Ctx")
fun on9(h: (Ctx9) -> String): String = "on9-ctx:" + h(Ctx9())

fun onBoth9(h: (Call9, Int) -> String): String = "onBoth9-call:" + h(Call9(), 1)
@JvmName("onBoth9Ctx")
fun onBoth9(h: (Ctx9, Int) -> String): String = "onBoth9-ctx:" + h(Ctx9(), 2)

// one lambda parameter type is a supertype of the other
open class Base9
class Derived9 : Base9()
fun sup9(h: (Base9) -> String): String = "sup9-base:" + h(Base9())
@JvmName("sup9Derived")
fun sup9(h: (Derived9) -> String): String = "sup9-derived:" + h(Derived9())

// a member of a class: the receiver is known
class Host9 {
    fun at9(h: (Call9) -> String): String = "at9-call:" + h(Call9())
    @JvmName("at9Ctx")
    fun at9(h: (Ctx9) -> String): String = "at9-ctx:" + h(Ctx9())
}
fun makeHost9(): Host9 = Host9()
fun withHost9(block: (Host9) -> String): String = block(Host9())
