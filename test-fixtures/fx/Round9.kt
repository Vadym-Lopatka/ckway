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

// ================================================================ Batch C

// ---- C1: nil for a parameter whose type is a type parameter with a non-null bound
fun <T : Any> nnBound9(key: String, d: T): String = "nnBound9:" + d
fun <T> plainT9(d: T): String = "plainT9:" + d
fun <T> qT9(d: T?): String = "qT9:" + d
fun <T : Number?> nullBound9(d: T): String = "nullBound9:" + d
fun <T> twoBounds9(d: T): String where T : Any, T : Comparable<T> = "twoBounds9:" + d
fun <T : Any> tAny9(d: T): String = "tAny9:" + d
fun <T : Any> varNn9(vararg xs: T): String = "varNn9:" + xs.size
fun <T> varPl9(vararg xs: T): String = "varPl9:" + xs.size
fun <T : Any> varNnList9(vararg xs: T?): String = "varNnList9:" + xs.size
fun plainStr9(s: String): String = "plainStr9:" + s
// a non-null bound and a nullable parameter type of one overload family: nil cannot choose the first
fun <T : Any> ovNil9(x: T): String = "ovNil9-any"
fun ovNil9(x: Int?): String = "ovNil9-int?"
fun <T : Any> ovNil9b(x: T): String = "ovNil9b-any"
class BoxNn9<T : Any> {
    fun put(x: T): String = "BoxNn9.put:" + x
    fun <U : Any> mix(x: T, y: U): String = "BoxNn9.mix"
}
class BoxPl9<T> {
    fun put(x: T): String = "BoxPl9.put:" + x
}
class BoxQ9<T : Any> {
    fun put(x: T?): String = "BoxQ9.put:" + x
}
// an explicit nullable type argument makes nil legal for T : Any? only when the bound allows it
fun <T> typed9(d: T): String = "typed9:" + d
fun <T : Any> typedNn9(d: T?): String = "typedNn9:" + d

// ---- C2: a literal and an enum entry var have a static type, also as the receiver
enum class Verb9 { GET, POST { override fun weight() = 2 }; open fun weight() = 1 }
fun String.bind9(v: Verb9): String = "bind9-String:" + this + ":" + v
fun CharSequence.bind9(v: Verb9): String = "bind9-CharSequence:" + this + ":" + v
fun String.bind9(n: Int): String = "bind9-Int:" + this + ":" + n
fun Verb9.on9(s: String): String = "on9-Verb:" + this + ":" + s
fun Any.on9(s: String): String = "on9-Any:" + s
fun pickV9(v: Verb9): String = "pickV9-Verb"
fun pickV9(v: Any): String = "pickV9-Any"
fun pickV9(v: String): String = "pickV9-String"
fun pickC9(c: Char): String = "pickC9-Char"
fun pickC9(c: String): String = "pickC9-String"
fun pickB9(b: Boolean): String = "pickB9-Boolean"
fun pickB9(b: String): String = "pickB9-String"
object Single9 { override fun toString() = "Single9" }
fun pickO9(o: Single9): String = "pickO9-Single9"
fun pickO9(o: Any): String = "pickO9-Any"

// a literal is the receiver
fun Char.cc9(): String = "cc9:" + this
fun Boolean.bb9(): String = "bb9:" + this
fun Long.ll9(): String = "ll9-Long:" + this
fun Int.ll9(): String = "ll9-Int:" + this
fun Double.dd9(): String = "dd9:" + this
fun String.ss9(): String = "ss9-String"
fun CharSequence.ss9(): String = "ss9-CharSequence"
fun Any.ss9(): String = "ss9-Any"

// ---- C3: the tag of a Clojure function is the static type of a call of it
fun tag9(x: String): String = "tag9-String"
fun tag9(x: Int): String = "tag9-Int"
fun tag9(x: CharSequence): String = "tag9-CharSequence"
fun tagB9(x: ByteArray): String = "tagB9-ByteArray"
fun tagB9(x: String): String = "tagB9-String"
fun tagL9(x: Long): String = "tagL9-Long"
fun tagL9(x: String): String = "tagL9-String"
fun tagD9(x: Double): String = "tagD9-Double"
fun tagD9(x: String): String = "tagD9-String"
// a call of a kt function as the argument: the declared Kotlin return type flows
class Rq9(val path: String, val code: Int)
class Holder9 { companion object { val NAME: String = "holder"; val CODE: Int = 7 } }
fun Rq9.pathOf9(): String = path
open class Call9c
class RCall9 : Call9c()
val Call9c.params9: String get() = "p-call"
val RCall9.params9: Int get() = 5
fun nested9(x: String): String = "nested9-String"
fun nested9(x: Int): String = "nested9-Int"
fun nested9(x: Any): String = "nested9-Any"

// ---- C5: a Kotlin function value that takes a function
fun hofNn9(): ((Int) -> Int) -> String = { f -> "hofNn9:" + f(1) }
fun hofN9(): (((Int) -> Int)?) -> String = { f -> "hofN9:" + (f?.invoke(1) ?: "null") }
fun hofFiNn9(): (Sam9) -> String = { f -> "hofFiNn9:" + f.go9(2) }
fun hofFiN9(): (Sam9?) -> String = { f -> "hofFiN9:" + (f?.go9(2) ?: "null") }
fun hofJavaNn9(): (java.util.function.Function<String, String>) -> String = { f -> "hofJavaNn9:" + f.apply("x") }
fun hofJavaN9(): (java.util.function.Function<String, String>?) -> String = { f -> "hofJavaN9:" + (f?.apply("x") ?: "null") }
fun hofRun9(): (Runnable?) -> String = { r -> if (r == null) "hofRun9:null" else { r.run(); "hofRun9:ran" } }
typealias Cb9 = (Int) -> Int
fun hofAliasNn9(): (Cb9) -> String = { f -> "hofAliasNn9:" + f(1) }
fun hofAliasN9(): (Cb9?) -> String = { f -> "hofAliasN9:" + (f?.invoke(1) ?: "null") }
fun hofSuspN9(): ((suspend (Int) -> Int)?) -> String = { f -> if (f == null) "hofSuspN9:null" else "hofSuspN9:fn" }
fun hofSuspNn9(): (suspend (Int) -> Int) -> String = { _ -> "hofSuspNn9:fn" }
fun hofInt9(): (Int?, String?) -> String = { a, b -> "hofInt9:" + a + ":" + b }
fun hofIntNn9(): (Int, String) -> String = { a, b -> "hofIntNn9:" + a + ":" + b }
fun hofAny9(): (Any?) -> String = { a -> "hofAny9:" + a }
fun <T> hofT9(): (T) -> String = { a -> "hofT9:" + a }
fun hofTNn9(): (List<String>?) -> String = { a -> "hofTNn9:" + a }

// ================================================================ Batch D

// ---- D1: a class that declares its own `invoke` and also implements a function type (http4k `LensExtractor`)
// The declared `invoke` overrides the one of the function type: one member, with its own names and types.
interface ExtD9<in IN, out OUT> : (IN) -> OUT {
    override operator fun invoke(target: IN): OUT
    fun meta9(): String = "ext-meta"
}
abstract class ExtBaseD9<IN, OUT>(val tag: String) : ExtD9<IN, OUT>              // an abstract class in between: no invoke
open class ExtPath9<OUT>(private val f: (String) -> OUT) : ExtBaseD9<String, OUT>("path") {
    override fun invoke(target: String): OUT = f(target)                      // a more specific JVM signature, and the bridge
}
class ExtBi9<OUT>(private val f: (String) -> OUT) : ExtPath9<OUT>(f) {
    operator fun <R : CharSequence> invoke(value: OUT, into: R): String = "inject:$value:$into"   // another arity
}
fun extPath9(): ExtPath9<String> = ExtPath9 { s -> "path:$s" }
fun extBi9(): ExtBi9<String> = ExtBi9 { s -> "bi:$s" }
fun extD9(): ExtD9<String, Int> = object : ExtD9<String, Int> { override fun invoke(target: String): Int = target.length }

// the override is in a subclass only; the interface has the function type's own `invoke`
interface PlainD9 : (String) -> String
class SubOnly9 : PlainD9 { override operator fun invoke(name: String): String = "sub-only:$name" }
class SubNone9 : PlainD9 { override fun invoke(p1: String): String = "sub-none:$p1" }
fun subOnly9(): SubOnly9 = SubOnly9()
fun subNone9(): PlainD9 = SubNone9()

// a generic class that has the type parameters in the function type
abstract class GenD9<IN, OUT> : (IN) -> OUT
class GenOwn9<IN, OUT>(private val f: (IN) -> OUT) : GenD9<IN, OUT>() {
    override operator fun invoke(value: IN): OUT = f(value)
}
fun genOwn9(): GenOwn9<String, String> = GenOwn9 { n -> "gen-own:$n" }
fun genNone9(): GenD9<String, String> = object : GenD9<String, String>() { override fun invoke(p1: String): String = "gen-none:$p1" }

// a fun interface that extends a function type and declares the member
fun interface FiOwn9 : (String) -> Int { override operator fun invoke(text: String): Int }
fun fiOwn9(): FiOwn9 = FiOwn9 { t -> t.length * 10 }
fun runFiOwn9(f: FiOwn9, s: String): Int = f(s)

// a suspend function type with its own override
interface SExt9<in IN, out OUT> : suspend (IN) -> OUT { override suspend operator fun invoke(target: IN): OUT }
fun sExt9(): SExt9<String, String> = object : SExt9<String, String> { override suspend fun invoke(target: String): String = "s-ext:$target" }

// reify: a function-typed parameter of an inherited member
interface Mw9 : (Handler9) -> Handler9
fun runMw9(m: Mw9, s: String): String = m.invoke { x -> "core:$x" }(s)

// ---- D2: a suspend function that returns a value class (kotlinx.coroutines `receiveCatching(): ChannelResult<E>`)
@JvmInline value class SvInt9(val v: Int)
@JvmInline value class SvDbl9(val d: Double)
@JvmInline value class SvStr9(val s: String)
@JvmInline value class SvAny9(val a: Any?)           // like ChannelResult: the underlying type is Any?
@JvmInline value class SvNn9(val a: Any)
class SvHost9(val base: Int) {
    suspend fun int9(n: Int): SvInt9 { kotlinx.coroutines.delay(1); return SvInt9(base + n) }
    suspend fun intNow9(n: Int): SvInt9 = SvInt9(base + n)                     // no suspension
    suspend fun dbl9(): SvDbl9 { kotlinx.coroutines.delay(1); return SvDbl9(base + 0.5) }
    suspend fun str9(): SvStr9 = SvStr9("host$base")
    suspend fun any9(x: Any?): SvAny9 { kotlinx.coroutines.delay(1); return SvAny9(x) }
    suspend fun nn9(x: Any): SvNn9 = SvNn9(x)
    suspend fun nIntOrNull9(yes: Boolean): SvInt9? { kotlinx.coroutines.delay(1); return if (yes) SvInt9(base) else null }
    suspend fun nStrOrNull9(yes: Boolean): SvStr9? = if (yes) SvStr9("s") else null
    suspend fun nAnyOrNull9(yes: Boolean): SvAny9? = if (yes) SvAny9(null) else null
    suspend fun withDef9(n: Int = 5): SvInt9 { kotlinx.coroutines.delay(1); return SvInt9(base * n) }
}
suspend fun SvHost9.ext9(n: Int): SvInt9 { kotlinx.coroutines.delay(1); return SvInt9(base * 100 + n) }
suspend fun SvHost9.extAny9(x: Any?): SvAny9 = SvAny9(x)
suspend fun top9(n: Int): SvInt9 { kotlinx.coroutines.delay(1); return SvInt9(n) }
suspend fun topAny9(x: Any?): SvAny9 { kotlinx.coroutines.delay(1); return SvAny9(x) }
suspend fun topNull9(): SvAny9? = null
fun SvInt9.plain9(): Int = v + 1000
fun SvAny9.plain9(): String = "any:" + a
fun SvStr9.plain9(): String = "str:" + s
fun SvInt9.plusOne9(): SvInt9 = SvInt9(v + 1)
fun anyOf9(a: SvAny9): Any? = a.a
fun intOf9(a: SvInt9): Int = a.v
// generic result: the call site decides the type argument; the object is boxed once
suspend fun <T> sId9(x: T): T { kotlinx.coroutines.delay(1); return x }
suspend fun <T> sIdNow9(x: T): T = x
suspend fun <T : Any> sIdNn9(x: T): T = x
suspend fun <T> sFirst9(xs: List<T>): T? = xs.firstOrNull()
// kotlin.Result and kotlin.time.Duration (value classes of the standard library) from a suspend function
suspend fun sResult9(ok: Boolean): Result<Int> { kotlinx.coroutines.delay(1); return if (ok) Result.success(7) else Result.failure(IllegalStateException("res-boom")) }
suspend fun sResultStr9(): Result<String> = Result.success("fine")
suspend fun sResultN9(ok: Boolean): Result<Int>? = if (ok) Result.success(1) else null
suspend fun sDur9(): kotlin.time.Duration { kotlinx.coroutines.delay(1); return kotlin.time.Duration.parse("2s") }
suspend fun sDurN9(yes: Boolean): kotlin.time.Duration? = if (yes) kotlin.time.Duration.parse("3s") else null
fun resultOk9(r: Result<Int>): Boolean = r.isSuccess
// a suspend lambda: a Clojure function returns the underlying value or the object, Kotlin wants the object
fun runSvInt9(f: suspend (Int) -> SvInt9): Int = kotlinx.coroutines.runBlocking { f(4).v }
fun runSvAny9(f: suspend (Int) -> SvAny9): String = kotlinx.coroutines.runBlocking { "got:" + f(4).a }
fun runSvIntN9(f: suspend (Int) -> SvInt9?): String = kotlinx.coroutines.runBlocking { "got:" + f(4)?.v }
fun runSvRes9(f: suspend () -> Result<Int>): String = kotlinx.coroutines.runBlocking { f().fold({ "ok:$it" }, { "fail:${it.message}" }) }
fun runSvDur9(f: suspend () -> kotlin.time.Duration): Long = kotlinx.coroutines.runBlocking { f().inWholeMilliseconds }
fun runBlock9(block: suspend () -> Any?): Any? = kotlinx.coroutines.runBlocking { block() }
// a suspend member of an interface, written by kt/reify, that returns a value class
interface SvIface9 { suspend fun give9(n: Int): SvAny9; suspend fun giveN9(n: Int): SvInt9? }
fun useSvIface9(i: SvIface9): String = kotlinx.coroutines.runBlocking { "give:" + i.give9(3).a + ":" + i.giveN9(1)?.v + ":" + i.giveN9(0)?.v }

// ---- D3: nil that only the run time knows, at a parameter or receiver that is plainly not nullable
fun welcome9(name: String): String = "welcome:$name"
fun twoNn9(a: String, b: String?, c: Any): String = "twoNn9:$a:$b:$c"
fun lenOfNn9(xs: List<String>): Int = xs.size
fun String.shoutNn9(): String = "shoutNn9:" + uppercase()
fun String?.shoutN9(): String = "shoutN9:" + this
fun Any.tagOfNn9(): String = "tagOfNn9:$this"
fun <T> T.idRecv9(): String = "idRecv9:$this"
class Nn3Box9(val label: String) {
    fun hi9(who: String): String = "$label hi $who"
    fun maybe9(who: String?): String = "$label maybe $who"
    companion object { fun make9(label: String): Nn3Box9 = Nn3Box9(label) }
}
interface Sink9 { fun put9(s: String): String }
fun useSink9(s: Sink9, x: String): String = s.put9(x)
fun primNn9(n: Int, d: Double, b: Boolean): String = "primNn9:$n:$d:$b"
fun fnNn9(f: (String) -> String): String = f("in")
fun ovNn9(x: String): String = "ovNn9-String"
fun ovNn9(x: Int): String = "ovNn9-Int"
fun varStrNn9(vararg xs: String): String = "varStrNn9:" + xs.size
fun defNn9(a: String = "d", b: String): String = "defNn9:$a:$b"
fun sNn9(): String = "sNn9"

// ---- D5: positional arguments with no static type, and a trailing lambda (Ktor `embeddedServer`)
class Env9(val name: String)
fun serve9(factory: String, port: Int = 80, host: String = "0.0.0.0", watch: List<String> = emptyList(), module: () -> String): String =
    "serve9-port:$factory:$port:$host:${module()}"
fun serve9(factory: String, env: Env9 = Env9("default"), configure: () -> String = { "cfg" }, module: () -> String = { "mod" }): String =
    "serve9-env:$factory:${env.name}:${configure()}:${module()}"
// two overloads that both fit the usual binding: only the values choose
fun pickU9(a: String, b: Int): String = "pickU9-String"
fun pickU9(a: Env9, b: Int): String = "pickU9-Env"

// ---- D6: a type parameter that another argument fixes gives the type of a lambda parameter (Ktor `install(plugin, configure)`)
class AppHost6
class Inst6
interface Plug6<in P, out B : Any, F : Any> { fun make6(): B }
interface AppPlug6<out C : Any> : Plug6<AppHost6, C, Inst6>
class CfgA6 {
    fun on6(h: (Call9) -> String): String = "on6-call:" + h(Call9())
    @JvmName("on6Ctx")
    fun on6(h: (Ctx9) -> String): String = "on6-ctx:" + h(Ctx9())
}
class CfgB6 { fun other6(): String = "other6" }
fun appPlug6(): AppPlug6<CfgA6> = object : AppPlug6<CfgA6> { override fun make6() = CfgA6() }
fun appPlugB6(): AppPlug6<CfgB6> = object : AppPlug6<CfgB6> { override fun make6() = CfgB6() }
fun starPlug6(): Plug6<AppHost6, *, Inst6> = appPlug6()
fun <P : Any, B : Any, F : Any> P.install6(plugin: Plug6<P, B, F>, configure: B.() -> String = { "none" }): String =
    "install6:" + plugin.make6().configure()
fun <B : Any> both6(a: Plug6<AppHost6, B, Inst6>, b: Plug6<AppHost6, B, Inst6>, configure: B.() -> String): String =
    "both6:" + a.make6().configure()
// the type parameter is fixed in the class of the receiver and in a nested position, not as a plain argument
fun <B : Any> list6(plugins: List<Plug6<AppHost6, B, Inst6>>, configure: B.() -> String): String = "list6:" + plugins.first().make6().configure()
fun <B : Any> unfixed6(configure: B.() -> String, make: () -> B): String = "unfixed6:" + make().configure()
fun makeHost6(): AppHost6 = AppHost6()
