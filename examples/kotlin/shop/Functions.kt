package shop

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val VERSION = "1.0"
var taxPercent: Int = 20

fun welcome(name: String, greeting: String = "Welcome", punct: String = "!"): String = "$greeting, $name$punct"

fun joinLabel(vararg parts: String, sep: String = " "): String = parts.joinToString(sep)

/** Overloads: the argument decides. */
fun kind(n: Int) = "Int"
fun kind(n: Long) = "Long"
fun kind(s: String) = "String"

/** Number widths: an Int parameter, a Long parameter, a Double parameter. */
fun addUp(a: Int, b: Long, c: Double): Double = a + b + c

fun nick(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }

// ---- functions

fun applyDiscount(total: Money, f: (Money) -> Money): Money = f(total)

/** A parameter with a default before the lambda: write the lambda with its name. */
fun report(cart: Cart, title: String = "Report", format: (Cart) -> String): String = "$title: ${format(cart)}"

fun buildText(block: StringBuilder.() -> Unit): String = StringBuilder().apply(block).toString()

fun <T> Cart.mapLines(f: (Cart.Line) -> T): List<T> = lines.map(f)

fun Cart.eachLine(action: (Product, Int) -> Unit) = lines.forEach { action(it.product, it.quantity) }

fun priceFormatter(prefix: String): (Money) -> String = { m -> "$prefix$m" }

fun taxer(percent: Int): (Money) -> Money = { m -> m + m.percent(percent) }

fun twice(f: (Money) -> Money): (Money) -> Money = { m -> f(f(m)) }

fun isCheap(discount: Discount, m: Money): Boolean = discount.adjust(m) < Money.of(2)

// ---- value classes in other positions

fun pause(d: Duration = 1.seconds): Long = d.inWholeMilliseconds

fun doubled(d: Duration): Duration = d * 2

fun parseMoney(text: String): Result<Money> = runCatching { Money.parse(text) }

// ---- type arguments

/** The reified function without a twin: it needs `:<>`. */
inline fun <reified T> typeName(): String = typeOf<T>().show()

/** KType.toString() needs kotlin-reflect for Kotlin names; this does not: `List<String?>`. */
fun KType.show(): String =
  ((classifier as? KClass<*>)?.simpleName ?: "?") +
    (if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.type?.show() ?: "*" }) +
    (if (isMarkedNullable) "?" else "")

inline fun <reified T : Any> String.parseAs(): T? = when (T::class) {
  Int::class -> toIntOrNull() as T?
  Long::class -> toLongOrNull() as T?
  Money::class -> runCatching { Money.parse(this) }.getOrNull() as T?
  String::class -> this as T
  else -> null
}

/** The reified `get` and the plain `get` have the same name: without `:<>` the plain one is called. */
class Settings(val values: Map<String, String>) {
  fun get(key: String): String? = values[key]
  inline fun <reified T : Any> get(key: String): T? = values[key]?.parseAs<T>()
}

inline fun <reified T> describeAs(x: T): String = "${typeOf<T>().show()}:$x"

fun <T> firstOr(xs: List<T>, fallback: T): T = xs.firstOrNull() ?: fallback

fun <A, B> pairUp(a: A, b: B): Pair<A, B> = a to b

fun typeToString(type: KType): String = type.show()

fun className(type: KClass<*>): String = type.simpleName ?: "?"

fun <V> readProperty(p: Product, prop: KProperty1<Product, V>): V = prop.get(p)

fun propertyName(prop: KProperty1<*, *>): String = prop.name

// ---- context parameter

context(percent: Int)
fun Money.withTax(): Money = this + percent(percent)

/** Two reified type parameters. */
inline fun <reified K, reified V> mapTypes(): String = "${typeOf<K>().show()} -> ${typeOf<V>().show()}"

/** A reified type parameter with a bound. */
inline fun <reified T : Number> numberType(): String = T::class.simpleName ?: "?"
