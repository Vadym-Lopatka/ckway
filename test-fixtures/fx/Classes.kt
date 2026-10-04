package fx

class User(val id: Long, val name: String = "anon", val email: String? = null) {
  constructor(name: String) : this(0L, name)

  fun greet(greeting: String = "Hi"): String = "$greeting $name"
  @JvmOverloads fun ov(a: Int, b: Int = 5): String = "$a $b"
  val path: String get() = "p"
  fun path(p: String): String = "p:$p"
  var nickname: String = "nick"
  fun same(other: User): Boolean = this === other
}

data class Pt(val x: Int, val y: Int = 0)

fun User.initial(): String = name.take(1)
val User.upper: String get() = name.uppercase()

class Box(val v: String)
fun f(x: Box): String = "fun:${x.v}"
val Box.f: String get() = "prop:$v"

object Registry {
  fun lookup(k: String): String = "v:$k"
  val size: Int = 3
  var level: Int = 0
}

class WithCompanion {
  companion object {
    fun make(): String = "made"
    const val NAME = "wc"
    val other: Int = 7
  }
}

class Outer {
  class Inner(val v: Int) { fun twice(): Int = v * 2 }
}

enum class Color(val hex: String) { RED("f00"), GREEN("0f0") }

class Dup { fun who(): String = "member" }
fun Dup.who(): String = "extension"
