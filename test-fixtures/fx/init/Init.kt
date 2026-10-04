package fx.init

/** An object counts its own initialisation in a system property, so a test can see when it happens. */
object Probe {
  val n: Int
  init { n = (System.getProperty("kt.init.probe") ?: "0").toInt() + 1; System.setProperty("kt.init.probe", n.toString()) }
  fun hello(): String = "hello"
}

object Bad {
  init { error("boom") }
  val x = 1
  fun m(): Int = 2
}

object Good { val y = 2 }

enum class Shaky { A, B; companion object { fun make() = A } }

fun ok(): Int = 1
class Plain(val v: Int)
