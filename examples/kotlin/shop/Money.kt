package shop

/** An amount of money in cents. A value class: at run time it is a plain Long when Kotlin can do that. */
@JvmInline
value class Money(val cents: Long) {
  operator fun plus(other: Money) = Money(cents + other.cents)
  operator fun minus(other: Money) = Money(cents - other.cents)
  operator fun times(n: Int) = Money(cents * n)
  operator fun compareTo(other: Money) = cents.compareTo(other.cents)
  fun percent(p: Int) = Money(cents * p / 100)
  override fun toString() = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

  companion object {
    const val CURRENCY = "EUR"
    val ZERO = Money(0)
    fun of(units: Long, cents: Long = 0) = Money(units * 100 + cents)
    fun parse(text: String): Money {
      val (units, cents) = text.split(".").let { it[0] to (it.getOrNull(1) ?: "0") }
      return of(units.toLong(), cents.padEnd(2, '0').toLong())
    }
  }
}
