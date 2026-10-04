package fx.other

import fx.Base

class Sub : Base() {
  override fun hello(): String = "sub-hello"
  override val tag: String = "sub"
  override fun greetAll(prefix: String): String = "sub:" + prefix
  fun own(): String = "own"
}
