// Fifth review, Z1 and Z3. A `k...` function makes the same call in Kotlin: its result is what the Clojure call must give.
package fx.r6

// ---- Z1: a narrowing override that inherits defaults, and a subclass that does not override
interface Src { fun mk(x: Int = 7): Any }
open class StrSrc : Src { override fun mk(x: Int): String = "mk$x" }
class SubSrc : StrSrc()
fun kStr() = StrSrc().mk()
fun kStr2() = StrSrc().mk(2)
fun kSub() = SubSrc().mk()
fun kSub2() = SubSrc().mk(2)

// an override with the SAME result type, and a subclass
open class AnySrc : Src { override fun mk(x: Int): Any = "any$x" }
class AnySub : AnySrc()
fun kAny() = AnySrc().mk()
fun kAnySub() = AnySub().mk()
fun kAnySub2() = AnySub().mk(2)

// a diamond: the member comes through two interfaces
interface DBase { fun dm(x: Int = 3): Any }
interface DLeft : DBase
interface DRight : DBase
open class Diamond : DLeft, DRight { override fun dm(x: Int): String = "dm$x" }
class DiamondSub : Diamond()
fun kDiamond() = Diamond().dm()
fun kDiamond2() = Diamond().dm(2)
fun kDiamondSub() = DiamondSub().dm()
fun kDiamondSub2() = DiamondSub().dm(2)

// three levels, a re-declaration in the middle, and a subclass below
interface L1 { fun lv(x: Int = 5, y: String = "y"): Any }
open class L2 : L1 { override fun lv(x: Int, y: String): CharSequence = "l2-$x$y" }
open class L3 : L2() { override fun lv(x: Int, y: String): String = "l3-$x$y" }
class L4 : L3()
fun kL2() = L2().lv().toString()
fun kL3() = L3().lv()
fun kL4() = L4().lv()
fun kL4x() = L4().lv(1)
fun kL4y() = L4().lv(y = "z")
fun kL4xy() = L4().lv(1, "z")

// ---- Z3: one member of two interfaces
interface Q1 { fun run2(): String }
interface Q2 { fun run2(): String }
class QC : Q1, Q2 { override fun run2() = "qc" }
class QX { fun run2() = "qx" }
fun kQC() = QC().run2()
fun q1Of(): Q1 = QC()

// the two interfaces give other result types
interface T1 { fun rt(): Any }
interface T2 { fun rt(): String }
class TC : T1, T2 { override fun rt(): String = "tc" }
fun kTC() = TC().rt()

// a class and an interface: the class has the body, nothing is overridden in CB
open class B1 { open fun cb(): String = "b1" }
interface I1 { fun cb(): String }
class CB : B1(), I1
fun kCB() = CB().cb()

// with a default, and through a property
interface W1 { fun wd(x: Int = 1): String; val wp: String }
interface W2 { fun wd(x: Int): String; val wp: String }
class WC : W1, W2 { override fun wd(x: Int) = "wd$x"; override val wp = "wp" }
fun kWd() = WC().wd()
fun kWd2() = WC().wd(5)
fun kWp() = WC().wp

// members that Kotlin keeps apart must stay apart
interface P1 { fun pm(x: Int): String }
interface P2 { fun pm(x: Long): String }
class PC : P1, P2 { override fun pm(x: Int) = "int"; override fun pm(x: Long) = "long" }
fun kPmInt() = PC().pm(1)
fun kPmLong() = PC().pm(1L)

interface R1 { val rv: String }
interface R2 { fun rv(): String }
class RC : R1, R2 { override val rv = "prop"; override fun rv() = "fun" }
fun kRvProp() = RC().rv
fun kRvFun() = RC().rv()

interface E1 { fun em(s: String): String }
interface E2 { fun em(s: String): String }
class EC : E1, E2 { override fun em(s: String) = "member$s" }
class EOther
fun EOther.em(s: String) = "ext$s"
fun kEm() = EC().em("x")
fun kEmExt() = EOther().em("x")

// two supertypes whose parameter types differ only before erasure: JVM gf(Object) and gf(String), one method in GC
interface G<T> { fun gf(x: T): String }
interface H { fun gf(x: String): String }
class GC : G<String>, H { override fun gf(x: String) = "gc$x" }
fun kGf() = GC().gf("x")
