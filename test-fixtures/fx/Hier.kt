package fx

interface Named {
  fun hello(): String
  fun bye(): String = "bye"
  val label: String get() = "label"
}

class NamedImpl : Named { override fun hello(): String = "hi" }
class Deleg(n: Named) : Named by n

abstract class Base : Named {
  fun shared(): String = "shared:" + hello()
  abstract val tag: String
  open fun greetAll(prefix: String = "all"): String = "$prefix-${hello()}"
}

class BaseImpl : Base() {
  override fun hello(): String = "base-impl"
  override val tag: String = "bi"
}

class Oops(msg: String) : RuntimeException(msg) { fun own(): String = "oops" }

class MyLazy : Lazy<Int> {
  override val value: Int = 7
  override fun isInitialized(): Boolean = true
}
