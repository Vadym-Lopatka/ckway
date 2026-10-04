// Seventh review, V1 to V4. Each overload returns its own name. The Kotlin calls are in
// test-fixtures/oracle/Round8Calls.kt, the Clojure calls in test/ckway/round8_test.clj, by id.
package fx.r8

// ---- V1: a choice that would rest on what a collection HOLDS (the JVM erased it)
fun <T : Number> u9(a: MutableList<T>) = "u9-number"
fun <T> u9(a: MutableCollection<T>) = "u9-any"

fun <T : Comparable<T>> mxc(a: List<T>) = "mxc-cmp"
fun <T> mxc(a: Collection<T>) = "mxc-any"

fun <K : Comparable<K>, V> ks(m: HashMap<K, V>) = "ks-cmp"
fun <K, V> ks(m: Map<out K, V>) = "ks-any"

fun <T : Any> nn(a: List<T>) = "nn-nonnull"
fun <T> nn(a: Collection<T?>) = "nn-nullable"

fun <T> two(a: List<T>, b: List<T>) = "two-TT"
fun two(a: Collection<String>, b: Collection<Int>) = "two-SI"

fun <T> lk(a: MutableList<T>, b: MutableList<T>) = "lk-one"
fun <A, B> lk(a: MutableCollection<A>, b: MutableCollection<B>) = "lk-two"

fun <T> cg(a: MutableCollection<T>) = "cg-generic"
fun cg(a: MutableList<Int>) = "cg-int"

// controls: nothing rests on what the collection holds
fun <T> MutableIterable<T>.fr(p: (T) -> Boolean) = "fr-iterable"
fun <T> MutableList<T>.fr(p: (T) -> Boolean) = "fr-list"

fun <T> sm(a: MutableCollection<T>, b: T) = "sm-collection"
fun <T> sm(a: MutableList<T>, b: T) = "sm-list"

fun cs(a: Collection<String>) = "cs-collection"
fun cs(a: List<String>) = "cs-list"

fun <T : Number> tb(a: T) = "tb-number"
fun <T> tb(a: T) = "tb-any"

// ---- V2: a Clojure persistent collection is read-only
fun iv(x: MutableList<Int>) = "iv-mutable"
fun iv(x: Iterable<Int>) = "iv-iterable"

@JvmName("imMutable") fun im(x: MutableMap<Int, Int>) = "im-mutable"
fun im(x: Map<Int, Int>) = "im-map"

fun ist(x: MutableSet<Int>) = "is-mutable"
fun ist(x: Collection<Int>) = "is-collection"

fun onlyMutable(x: MutableList<Int>): Int { x.add(1); return x.size }
fun readOnly(): List<Int> = listOf(1, 2)
fun mutableOne(): MutableList<Int> = mutableListOf(1, 2)

// ---- V3: @Deprecated(level = ERROR): Kotlin refuses a call
@Deprecated("error", level = DeprecationLevel.ERROR) fun err8(a: Int = 1) = "error"
fun err8(a: Int = 1, b: Int = 2) = "visible"
// kotlinc refuses `err8()`: it resolves to the deprecated overload ("'fun err8(a: Int = ...): String' is deprecated. error.")
fun kErr8() = err8(1, 2)
@Deprecated("error", level = DeprecationLevel.ERROR) fun onlyError() = "error"
@Deprecated("error", level = DeprecationLevel.ERROR) val errorProp: Int get() = 1
@Deprecated("warn") fun warned8() = "warned"
class Dep8 {
  constructor()
  @Deprecated("error", level = DeprecationLevel.ERROR) constructor(x: Int) : this()
  @Deprecated("error", level = DeprecationLevel.ERROR) fun errorMember() = 5
  @Deprecated("error", level = DeprecationLevel.ERROR) val errorMemberProp: Int get() = 6
  fun fine() = 7
}
@Deprecated("error", level = DeprecationLevel.ERROR) class ErrorClass8

// ---- V4: a member of the run-time class and an extension on the static type
open class E1
class E2 : E1() { fun show() = "member" }
fun E1.show() = "ext"
fun e1(): E1 = E2()
fun kShow(): String { val e: E1 = E2(); return e.show() }
