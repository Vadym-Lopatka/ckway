// Fifth review, Z2: defaults that an INTERFACE declares. Compiled three times (bin/build-fixtures), once for each
// `-jvm-default` mode of kotlinc 2.4.20; the package name is rewritten for the other two:
//   fx.legacy6  -jvm-default=disable            the `$default` synthetics are in LS$DefaultImpls only (every Kotlin before 2.2)
//   fx.jd6en    -jvm-default=enable             in the interface, and a copy in LS$DefaultImpls
//   fx.jd6nc    -jvm-default=no-compatibility   in the interface only
// A `k...` function makes the same call in Kotlin: its result is what the Clojure call must give.
package fx.legacy6

@JvmInline value class LUid(val v: Int)

interface LS {
  fun mk(x: Int = 7): Any
  fun plain(x: Int = 1, y: String = "y"): String = "p$x$y"
  suspend fun sk(x: Int = 4): String = "sk$x"
  fun vk(u: LUid = LUid(9)): String
  fun String.ek(n: Int = 2): String = this + n
  val prop: String get() = "prop-default"
  var mprop: String
    get() = "m-default"
    set(v) {}
}

// a narrowing override in a class: it inherits the default that the interface declares (Z1 and Z2 together)
open class LSImpl : LS {
  override fun mk(x: Int): String = "ls$x"
  override fun vk(u: LUid): String = "vk${u.v}"
}
class LSSub : LSImpl()
// an override with the same result type, and a subclass of it
open class LSAny : LS {
  override fun mk(x: Int): Any = "any$x"
  override fun vk(u: LUid): String = "avk${u.v}"
  override val prop: String get() = "prop-any"
}
class LSAnySub : LSAny()

fun lsOf(): LS = LSImpl()

fun kMk() = lsOf().mk()
fun kMk2() = lsOf().mk(2)
fun kImplMk() = LSImpl().mk()
fun kSubMk() = LSSub().mk()
fun kAnyMk() = LSAny().mk()
fun kAnySubMk() = LSAnySub().mk()
fun kPlain() = lsOf().plain()
fun kPlainY() = lsOf().plain(y = "z")
fun kPlainX() = lsOf().plain(5)
suspend fun kSk() = lsOf().sk()
suspend fun kSk2() = lsOf().sk(2)
fun kVk() = lsOf().vk()
fun kVk2() = lsOf().vk(LUid(3))
fun kEk() = with(lsOf()) { "e".ek() }
fun kEk2() = with(lsOf()) { "e".ek(5) }
fun kProp() = lsOf().prop
fun kPropAny() = LSAnySub().prop
fun kMprop() = lsOf().mprop
