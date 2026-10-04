package shop

data class Product(val id: Long, val name: String, val price: Money, val tags: List<String> = emptyList()) {
  /** A member. An extension with the same name exists in Extensions.kt: the member wins. */
  fun summary() = "member: $name"
  fun hasTag(tag: String) = tag in tags
}

enum class Status(val code: String) {
  NEW("new"), PAID("paid"), SHIPPED("shipped");

  fun next(): Status? = entries.getOrNull(ordinal + 1)
}

data class Receipt(val owner: String, val total: Money, val status: Status = Status.PAID)

/** The body of an HTTP request (example 12). */
data class OrderRequest(val product: String, val quantity: Int = 1)
