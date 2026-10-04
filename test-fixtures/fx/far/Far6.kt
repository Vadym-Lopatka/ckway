// Fifth review, Z1: subclasses in ANOTHER package of the hierarchies of fx.r6.
package fx.r6far

import fx.r6.*

class FarSub : StrSrc()
class FarOver : StrSrc() { override fun mk(x: Int): String = "far$x" }
class FarL4 : L3()
class FarDiamond : Diamond()
fun kFarSub() = FarSub().mk()
fun kFarSub2() = FarSub().mk(2)
fun kFarOver() = FarOver().mk()
fun kFarOver2() = FarOver().mk(2)
fun kFarL4() = FarL4().lv()
fun kFarL4y() = FarL4().lv(y = "z")
fun kFarDiamond() = FarDiamond().dm()
