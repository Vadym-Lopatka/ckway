// Only with -jvm-default=enable: an interface that opts out of the compatibility copy (no LA$DefaultImpls).
package fx.jd6en

@JvmDefaultWithoutCompatibility interface LA { fun mk(x: Int = 7): Any; fun body(x: Int = 1) = "b$x" }
class LAImpl : LA { override fun mk(x: Int): String = "la$x" }
fun laOf(): LA = LAImpl()
fun kLaMk() = laOf().mk()
fun kLaBody() = laOf().body()
