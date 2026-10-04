package shop

/** Takes a total, gives a new total. A Clojure function can be used where Kotlin wants it. */
fun interface Discount {
  fun adjust(total: Money): Money
}

class Cart(val owner: String = "guest") {
  /** A class inside a class: Cart.Line */
  class Line(val product: Product, val quantity: Int) {
    val subtotal: Money get() = product.price * quantity
  }

  private val items = linkedMapOf<Long, Line>()

  var note: String? = null
  var status: Status = Status.NEW
  var priority: Int = 0
  var budget: Money = Money.ZERO

  /** The number of items. */
  val count: Int get() = items.values.sumOf { it.quantity }

  /** The same name as the property `count`: this one counts the items with a tag. */
  fun count(tag: String): Int = items.values.filter { it.product.hasTag(tag) }.sumOf { it.quantity }

  val lines: List<Line> get() = items.values.toList()
  val total: Money get() = items.values.fold(Money.ZERO) { sum, line -> sum + line.subtotal }

  /** Returns the new quantity of the product. */
  fun add(product: Product, quantity: Int = 1, note: String? = null): Int {
    if (note != null) this.note = note
    val old = items[product.id]?.quantity ?: 0
    items[product.id] = Line(product, old + quantity)
    return old + quantity
  }

  fun addAll(vararg products: Product): Int {
    products.forEach { add(it) }
    return count
  }

  fun total(discount: Discount): Money = discount.adjust(total)

  override fun toString() = "Cart($owner, $count items)"

  companion object {
    const val MAX_LINES = 100
    val DEFAULT_NOTE = "none"
    fun of(owner: String, vararg products: Product) = Cart(owner).also { it.addAll(*products) }
  }
}

/** The builder DSL: cart("ann") { add(product) }. The block is a lambda with a receiver. */
fun cart(owner: String = "guest", build: Cart.() -> Unit): Cart = Cart(owner).apply(build)

/** A fun interface whose method takes a Product (not a value class). */
fun interface Rule {
  fun applies(product: Product): Boolean
}

fun Cart.countWhere(rule: Rule): Int = lines.filter { rule.applies(it.product) }.sumOf { it.quantity }
