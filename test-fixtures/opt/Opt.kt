package fx.opt

import pbopt.Missing
import pbopt.MissingBase
import pbopt.MissingIface

// R1: classes that mention a class which is absent at run time
class Opt {
    fun uses(x: Missing): String = "uses"
    fun ok(): String = "ok"
    fun ok2(n: Int): String = "ok2:$n"
    val okProp: String = "prop"
    val missProp: Missing? = null
}
class Plain { fun fine() = "fine" }
class NeedsBase : MissingBase() { fun hello() = "hi" }
interface Capable : MissingIface { fun cap() = "cap" }
fun topOk() = "top"
fun topUses(x: Missing) = "tu"
