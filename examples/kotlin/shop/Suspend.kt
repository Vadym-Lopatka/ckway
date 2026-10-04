package shop

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

suspend fun fetchProduct(id: Long, wait: Long = 10): Product? {
  delay(wait)
  return Catalog.products.firstOrNull { it.id == id }
}

suspend fun slowSum(a: Int, b: Int): Int {
  delay(5)
  return a + b
}

suspend fun Cart.slowTotal(): Money {
  delay(5)
  return total
}

suspend fun waitFor(d: Duration): String {
  delay(d)
  return "waited ${d.inWholeMilliseconds} ms"
}

/** Takes a suspend lambda. A Clojure function that waits for a suspend call is fine. */
suspend fun retrying(times: Int = 3, block: suspend (attempt: Int) -> String): String {
  var last: Throwable? = null
  for (i in 1..times) {
    try { return block(i) } catch (e: IllegalStateException) { last = e; delay(1) }
  }
  throw last!!
}

/** A default before a suspend lambda, a Duration with a default. */
suspend fun checkout(cart: Cart, pause: Duration = 5.milliseconds, charge: suspend (Money) -> Boolean = { true }): Receipt {
  delay(pause)
  val ok = charge(cart.total)
  cart.status = if (ok) Status.PAID else Status.NEW
  return Receipt(cart.owner, cart.total, cart.status)
}

fun productFlow(): Flow<Product> = flow {
  for (p in Catalog.products) {
    delay(1)
    emit(p)
  }
}

fun Cart.lineFlow(): Flow<Cart.Line> = flow {
  for (line in lines) {
    delay(1)
    emit(line)
  }
}
