// Only with -jvm-default=no-compatibility: an interface that opts in to the compatibility copy (LA$DefaultImpls too).
package fx.jd6nc

@JvmDefaultWithCompatibility interface LA { fun mk(x: Int = 7): Any; fun body(x: Int = 1) = "b$x" }
class LAImpl : LA { override fun mk(x: Int): String = "la$x" }
fun laOf(): LA = LAImpl()
fun kLaMk() = laOf().mk()
fun kLaBody() = laOf().body()
