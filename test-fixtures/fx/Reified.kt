package fx

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.delay

// `inline fun <reified T> typeName(): String` is in Unsupported.kt

/** KType.toString() needs kotlin-reflect for Kotlin names; this does not: `List<String?>`. */
fun KType.show(): String =
  ((classifier as? KClass<*>)?.simpleName ?: "?") +
    (if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.type?.show() ?: "*" }) +
    (if (isMarkedNullable) "?" else "")

inline fun <reified T : Any> String.parseAs(): T? = when (T::class) {
  Int::class -> toIntOrNull() as T?
  Long::class -> toLongOrNull() as T?
  String::class -> this as T
  else -> null
}

/** The `path` shape of a web library: a generic inline twin and two non-generic overloads of one name. */
class Box2(val items: List<Any?>) {
  inline fun <reified T> firstOf(): T? = items.firstOrNull { it is T } as T?
  fun firstOf(type: KClass<*>): Any? = items.firstOrNull { type.isInstance(it) }
  fun firstOf(): String? = items.firstOrNull()?.toString()
}

inline fun <reified T> describe(x: T, prefix: String = "v", times: Int = 1): String =
  "$prefix:${typeOf<T>().show()}:$x".repeat(times)

inline fun <reified K, reified V> mapTypes(): String = "${typeOf<K>().show()}->${typeOf<V>().show()}"

inline fun <reified T : Number> sumAs(vararg xs: Int): String = "${T::class.simpleName}:${xs.sum()}"

inline fun <reified T> mapEach(xs: List<Int>, f: (Int) -> T): List<T> = xs.map { f(it) }

suspend inline fun <reified T> fetchAs(v: String): T? {
  delay(10)
  @Suppress("UNCHECKED_CAST")
  return when (typeOf<T>().classifier) {
    Int::class -> v.toIntOrNull() as T?
    String::class -> v as T
    else -> null
  }
}

inline operator fun <reified T> Box2.get(i: Int): T = items[i] as T

inline fun <reified T> uidType(u: Uid): String = "${typeOf<T>().show()}:${u.v}"
inline fun <reified T> uidFor(x: Long): Uid = Uid(typeOf<T>().show().length + x)
inline fun <reified T> uidOrNullFor(x: Long?): Uid? = x?.let { Uid(typeOf<T>().show().length + it) }

object Conv {
  val tag = "conv"
  inline fun <reified T> String.tagged(sep: String = ":"): String = "$tag$sep${typeOf<T>().show()}$sep$this"
  inline fun <reified T> kind(): String = "kind:${typeOf<T>().show()}"
}

class RComp {
  companion object {
    inline fun <reified T> construct(n: Int = 1): String = typeOf<T>().show().repeat(n)
    @JvmStatic fun jtwice(x: Int): Int = x * 2
    @JvmStatic val jans: Int = 43
    @JvmField val jfield: Int = 8
    const val JC: Int = 9
  }
}

context(prefix: String)
inline fun <reified T> ctxType(): String = "$prefix ${typeOf<T>().show()}"

inline fun <reified T> bothBounds(x: T): String where T : CharSequence, T : Comparable<T> = "${T::class.simpleName}:${x.length}"

fun <T> firstOrNullOf(xs: List<T>): T? = xs.firstOrNull()
fun <T> echo(x: T): T = x

inline fun <reified T> kTypeOf(): KType = typeOf<T>()

object Stat {
  @JvmStatic fun twice(x: Int): Int = x * 2
  @JvmStatic val answer: Int = 42
  @JvmField val field: Int = 7
  const val C: Int = 5
  @JvmStatic fun dflt(x: Int = 1, y: Int = 10): Int = x + y
  fun plain(x: Int): Int = x + 1
}

/** Two classes with the same reified member name: a call on a receiver of unknown type is not static. */
class PickA { inline fun <reified T> pick(): String = "A:" + typeOf<T>().show() }
class PickB { inline fun <reified T> pick(): String = "B:" + typeOf<T>().show() }

/** A reified member of a generic class: the class-level T is not one of the `:<>` arguments. */
class GBox<T>(val v: T) {
  inline fun <reified R> conv(x: T): R? = x as? R
  inline fun <reified R> only(): R? = v as? R
}

inline val <reified T : Any> T.refName: String get() = T::class.java.simpleName
