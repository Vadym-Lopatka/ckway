package fx.legacy

// Compiled without JVM default methods: the body of `negate` is in LegacyPred$DefaultImpls.
fun interface LegacyPred {
  fun test(x: Int): Boolean
  fun negate(): LegacyPred = LegacyPred { !test(it) }
}
fun negLegacy(p: LegacyPred, x: Int): Boolean = p.negate().test(x)

// not a fun interface: abstract members, a default body (in $DefaultImpls) and a default property getter
interface LegacyGreeter {
  fun who(): String
  fun greet(): String = "hi " + who()
  val tag: String get() = "legacy-tag"
}
fun greetLegacy(g: LegacyGreeter): String = g.greet() + "/" + g.tag
