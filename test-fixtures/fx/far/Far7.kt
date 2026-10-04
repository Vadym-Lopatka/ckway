// Sixth review, W1: subclasses in ANOTHER package of the hierarchy of fx.r7.
package fx.r7far

import fx.r7.*

class FarAmt : AmtImpl2()
class FarAmtOver : AmtImpl2() { override fun amt(): W = W(9) }
fun kFarAmt() = FarAmt().amt().n
fun kFarAmtOver() = FarAmtOver().amt().n
