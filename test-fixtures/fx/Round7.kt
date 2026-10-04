// Sixth review, W1 and W5. A `k...` function makes the same call in Kotlin: its result is what the Clojure call must give.
package fx.r7

// ---- W1: an override whose JVM name differs from the original's (a value class mangles it)
@JvmInline value class W(val n: Int)

interface AmtSrc2 {
  fun amt(): Any
  fun take(w: W): Any
  suspend fun samt(): Any
  fun dflt(w: W = W(5)): Any
  val pamt: Any
}
open class AmtImpl2 : AmtSrc2 {
  override fun amt(): W = W(1)
  override fun take(w: W): W = W(w.n + 1)
  override suspend fun samt(): W = W(2)
  override fun dflt(w: W): W = W(w.n * 2)
  override val pamt: W get() = W(8)
}
open class AmtMid : AmtImpl2()
class AmtLeaf : AmtMid() { override fun amt(): W = W(3) }
class AmtPlain : AmtSrc2 {
  override fun amt(): Any = "plain"
  override fun take(w: W): Any = "took${w.n}"
  override suspend fun samt(): Any = "splain"
  override fun dflt(w: W): Any = "d${w.n}"
  override val pamt: Any get() = "pplain"
}
fun amtSrc(): AmtSrc2 = AmtImpl2()
fun kAmt() = AmtImpl2().amt().n
fun kAmtMid() = AmtMid().amt().n
fun kAmtLeaf() = AmtLeaf().amt().n
fun kAmtSrc() = (amtSrc().amt() as W).n
fun kAmtPlain() = AmtPlain().amt()
fun kTake() = AmtImpl2().take(W(4)).n
fun kTakeLeaf() = AmtLeaf().take(W(4)).n
fun kTakePlain() = AmtPlain().take(W(4))
suspend fun kSamt() = AmtImpl2().samt().n
suspend fun kSamtLeaf() = AmtLeaf().samt().n
fun kDflt() = AmtImpl2().dflt().n
fun kDflt2() = AmtLeaf().dflt(W(2)).n
fun kPamt() = AmtLeaf().pamt.n

// an override that puts a type argument in place of the type parameter of the supertype
interface Box7<T> { fun put(x: T, tag: String = "t"): String; fun get7(): T }
open class SBox7 : Box7<String> { override fun put(x: String, tag: String) = "s$x$tag"; override fun get7(): String = "g" }
class SBox7Sub : SBox7()
fun kPut() = SBox7().put("a")
fun kPutSub() = SBox7Sub().put("a", "u")
fun kGet7() = SBox7Sub().get7()

// ---- W5: what Kotlin source cannot call is not a var
@Deprecated("hidden", level = DeprecationLevel.HIDDEN) fun hid(a: Int = 1) = "hidden"
fun hid(a: Int = 1, b: Int = 2) = "visible"
fun kHid() = hid()
@Deprecated("hidden", level = DeprecationLevel.HIDDEN) fun onlyHidden() = "hidden"
@Deprecated("hidden", level = DeprecationLevel.HIDDEN) val hiddenProp: Int get() = 1
@Deprecated("warn") fun warned() = "warned"
@Deprecated("err", level = DeprecationLevel.ERROR) fun errored() = "errored"
@PublishedApi internal fun pubApi() = 1
@JvmSynthetic fun synth() = "synth"
@SinceKotlin("1.0") fun since() = "since"
@RequiresOptIn annotation class Exp
@Exp fun optIn() = "optin"
internal fun internalFun() = 1
private fun privateFun() = 1
fun kSynth() = synth()

open class Vis7 {
  constructor()
  @Deprecated("hidden", level = DeprecationLevel.HIDDEN) constructor(x: Int) : this()
  protected fun protectedFun() = 1
  internal fun internalMember() = 2
  private fun privateMember() = 3
  fun publicMember() = 4
  @Deprecated("hidden", level = DeprecationLevel.HIDDEN) fun hiddenMember() = 5
  @Deprecated("hidden", level = DeprecationLevel.HIDDEN) val hiddenMemberProp: Int get() = 6
  protected val protectedProp: Int get() = 7
  val publicProp: Int get() = 8
  @JvmSynthetic fun synthMember() = 9
}
@Deprecated("hidden", level = DeprecationLevel.HIDDEN) class HiddenClass7

// two generic interfaces with the same member, with other type arguments: TWO members of the class
interface A2<T : Number> { fun f2(x: T): String }
interface B2<T : CharSequence> { fun f2(x: T): String }
class Two : A2<Int>, B2<String> { override fun f2(x: Int) = "a$x"; override fun f2(x: String) = "b$x" }
fun kTwoA() = Two().f2(1)
fun kTwoB() = Two().f2("x")
