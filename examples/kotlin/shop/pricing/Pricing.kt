package shop.pricing

import shop.Money
import shop.Product

/** The extensions of this package are in the namespace of shop.pricing, not in the one of shop. */
fun Product.priceWithTax(percent: Int = 20): Money = price + price.percent(percent)

val Product.isFree: Boolean get() = price.cents == 0L

fun Money.discounted(percent: Int): Money = this - percent(percent)
