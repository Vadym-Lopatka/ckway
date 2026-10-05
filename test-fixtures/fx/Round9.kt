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

// ================================================================ Batch B: declarations and function values

// ---- B1: a companion `operator fun invoke` is a constructor form (http4k: `Request(GET, "/a")`)
interface Req9 {
    val text: String

    companion object {
        operator fun invoke(method: String, uri: String): Req9 = object : Req9 { override val text = "$method $uri" }
        operator fun invoke(uri: String): Req9 = invoke("GET", uri)
        // not an operator: Kotlin does not call it as `Req9(...)`
        fun make(uri: String): Req9 = invoke("MAKE", uri)
    }
}

// a constructor and a companion invoke: a constructor that fits always wins
class Both9(val tag: String) {
    constructor(n: Int) : this("ctor-int:$n")

    companion object {
        operator fun invoke(flag: Boolean): Both9 = Both9("invoke-flag:$flag")
        operator fun invoke(a: String, b: String): Both9 = Both9("invoke-two:$a$b")
        // an invoke that fits what the constructor fits as well: the constructor still wins
        operator fun invoke(n: Int, extra: Int = 0): Both9 = Both9("invoke-int:$n")
    }
}

// an invoke that is no operator: Kotlin does not call it as `NoOp9(...)`
class NoOp9(val tag: String) {
    companion object { fun invoke(x: Int): NoOp9 = NoOp9("noop:$x") }
}

// a named companion
class Named9(val tag: String) {
    companion object Factory {
        operator fun invoke(x: Int): Named9 = Named9("factory:$x")
    }
}

// a class with a constructor and a companion, but no invoke
class Plain9(val tag: String) {
    companion object { fun other(x: Int) = Plain9("other:$x") }
}

// an abstract class with a companion invoke
abstract class Abs9 {
    abstract val tag: String
    companion object {
        operator fun invoke(s: String): Abs9 = object : Abs9() { override val tag = "abs:$s" }
    }
}

// an extension invoke on a Companion that the package declares
class Ext9 {
    val tag: String = "ext"
    companion object
}
operator fun Ext9.Companion.invoke(x: Int): String = "ext-invoke:$x"
fun Ext9.Companion.build9(x: Int): String = "ext-fun:$x"

// an object with an invoke
object Obj9 {
    operator fun invoke(x: Int): String = "obj-invoke:$x"
    fun plain(): String = "obj-plain"
}
object NoInv9 { fun plain(): String = "noinv" }
// an extension invoke on an object
object ObjExt9
operator fun ObjExt9.invoke(x: String): String = "objext-invoke:$x"

// ---- B2: a class that implements a Kotlin function type
typealias Handler9 = (String) -> String

interface Router9 : Handler9 {
    val name: String
}

fun router9(name: String): Router9 = object : Router9 {
    override val name = name
    override fun invoke(p1: String): String = "routed:$p1:$name"
}

// no `invoke` in the metadata of the class itself, a function type with two parameters and an Int
abstract class Calc9 : (Int, Int) -> Int
fun calc9(): Calc9 = object : Calc9() { override fun invoke(p1: Int, p2: Int): Int = p1 * p2 }
fun calcCall9(c: Calc9, a: Int, b: Int): Int = c(a, b)

// a function type that returns a function type, and one with no parameter that returns Unit
interface Curry9 : (String) -> (String) -> String
fun curry9(): Curry9 = object : Curry9 { override fun invoke(p1: String): (String) -> String = { p2 -> "curry:$p1:$p2" } }
interface Eff9 : () -> Unit
fun eff9(log: StringBuilder): Eff9 = object : Eff9 { override fun invoke() { log.append("eff;") } }

// a function type that takes a function
interface Apply9 : ((String) -> String) -> String
fun apply9(): Apply9 = object : Apply9 { override fun invoke(p1: (String) -> String): String = p1("in") }

// a suspend function type as the supertype
interface SRouter9 : suspend (String) -> String
fun sRouter9(): SRouter9 = object : SRouter9 { override suspend fun invoke(p1: String): String = "s-routed:$p1" }

// another `invoke` of the package fits as well
class Doer9 { operator fun invoke(x: String): String = "doer:$x" }
fun doer9(): Doer9 = Doer9()
operator fun Router9.invoke(x: Int): String = "ext-int:$x"
operator fun Doer9.invoke(x: Int, y: Int): String = "doer-ext:$x$y"

// a value class in the type argument is no problem
@JvmInline value class Id9(val v: Int)
interface IdFn9 : (Id9) -> Id9
fun idFn9(): IdFn9 = object : IdFn9 { override fun invoke(p1: Id9): Id9 = Id9(p1.v + 1) }
fun idOf9(i: Int): Id9 = Id9(i)
fun idInt9(i: Id9): Int = i.v

// ---- B3: the class var as the receiver of an extension property on a Companion (http4k: `Filter.NoOp`)
class Prop9(val tag: String = "p") {
    companion object
}
val Prop9.Companion.zero9: Prop9 get() = Prop9("zero")
var level9Store: Int = 0
var Prop9.Companion.level9: Int
    get() = level9Store
    set(v) { level9Store = v }
val Prop9.Companion.nullable9: String? get() = null
class Fac9 {
    companion object Factory
}
val Fac9.Factory.made9: String get() = "factory-prop"
var Fac9.Factory.mark9: String
    get() = marks9
    set(v) { marks9 = v }
var marks9: String = "m0"
fun Fac9.Factory.make9(x: Int): String = "factory-fun:$x"
// a member property of a companion keeps working, and a property of an ordinary class
class Mem9 {
    companion object { val answer9: Int = 42; var counter9: Int = 0 }
    val inst9: Int = 7
}
val Mem9.extra9: Int get() = 8

// ---- B4: a Clojure fn that a member of `kt/reify` returns
fun interface Sam9 { fun go9(x: Int): String }
interface Gen9 {
    fun fn1(): (Int) -> Int
    fun fn0(): () -> String
    fun fn2(): (Int, Int) -> Int
    fun fnNullable(): ((Int) -> Int)?
    fun fnSusp(): suspend (Int) -> Int
    fun fnRecv(): String.(Int) -> String
    fun curried(): (Int) -> (Int) -> Int
    fun sam(): Sam9
    fun javaSam(): java.util.function.Function<String, String>
    fun runnable(): Runnable
    fun num(): Int
    fun short9(): Short
    fun double9(): Double
    fun id(): Id9
    fun any(): Any
    fun str(): String
    fun unit(): Unit
    val prop: (Int) -> Int
    val sprop: Sam9
    val nprop: Int
}
fun callFn1(g: Gen9, x: Int): Int = g.fn1()(x)
fun callFn0(g: Gen9): String = g.fn0()()
fun callFn2(g: Gen9, a: Int, b: Int): Int = g.fn2()(a, b)
fun callNullable(g: Gen9): String = g.fnNullable()?.invoke(3)?.toString() ?: "null"
fun callFnSusp(g: Gen9, x: Int): Int = runSusp9 { g.fnSusp()(x) }
fun <T> runSusp9(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
fun callFnRecv(g: Gen9, s: String, x: Int): String = g.fnRecv().invoke(s, x)
fun callCurried(g: Gen9, a: Int, b: Int): Int = g.curried()(a)(b)
fun callSam(g: Gen9, x: Int): String = g.sam().go9(x)
fun callJavaSam(g: Gen9, s: String): String = g.javaSam().apply(s)
fun callRunnable(g: Gen9, log: StringBuilder): String { g.runnable().run(); return log.toString() }
fun callNum(g: Gen9): String = g.num().toString()
fun callShort(g: Gen9): String = g.short9().toString()
fun callDouble(g: Gen9): String = g.double9().toString()
fun callId(g: Gen9): Int = g.id().v
fun callIdObj(g: Gen9): Any = g.id()
fun callAny(g: Gen9): String = g.any().toString()
fun callStr(g: Gen9): String = g.str()
fun callUnit(g: Gen9): String = g.unit().toString()
fun callProp(g: Gen9, x: Int): Int = g.prop(x)
fun callSprop(g: Gen9, x: Int): String = g.sprop.go9(x)
fun callNprop(g: Gen9): String = g.nprop.toString()

// a Kotlin function value
fun adder9(n: Int): (Int) -> Int = { it + n }

// a Kotlin lambda whose RESULT is a function type
fun lam9(f: (Int) -> (Int) -> Int): Int = f(2)(3)
fun lamSam9(f: (Int) -> Sam9): String = f(4).go9(5)
fun lamNum9(f: (Int) -> Long): String = f(1).toString()
fun lamSusp9(f: (Int) -> suspend (Int) -> Int): Int = runSusp9 { f(1)(10) }
fun lamUnit9(f: (Int) -> Unit): String = f(1).toString()

// a function-typed supertype that is a `fun interface` (http4k Filter)
fun interface Flt9 : (Handler9) -> Handler9
fun Flt9.then9(h: Handler9): Handler9 = this(h)
fun runFlt9(f: Flt9, s: String): String = f.then9 { x -> "base:$x" }(s)
