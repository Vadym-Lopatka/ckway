package fx.legacy

// Compiled without JVM default methods: the sub-interface has no bridge method, the implementing class has to make it.
interface LgSrc<T> { fun lg(): T; fun lgPut(x: T): T }
interface LgStr : LgSrc<String> { override fun lg(): String; override fun lgPut(x: String): String }
fun lgUse(s: LgSrc<String>): String = s.lg() + s.lgPut("p")

@JvmInline value class LgUid(val v: Int)
interface LgVA { fun lgId(): Any }
interface LgVB : LgVA { override fun lgId(): LgUid }
fun lgUseVA(s: LgVA): String = s.lgId().toString()
