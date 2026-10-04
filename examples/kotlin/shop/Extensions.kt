package shop

/** Extension function on a JDK type. */
fun String.slug(separator: String = "-"): String = lowercase().trim().replace(Regex("\\s+"), separator)

/** Extension property on a JDK type. */
val String.initials: String get() = split(" ").filter { it.isNotEmpty() }.map { it[0].uppercaseChar() }.joinToString("")

/** An extension on the data class. */
fun Product.withTag(tag: String): Product = copy(tags = tags + tag)
val Product.label: String get() = "$name (${price})"

/** The member Product.summary wins over this extension. */
fun Product.summary() = "extension: $name"

fun List<Product>.totalPrice(): Money = fold(Money.ZERO) { sum, p -> sum + p.price }

/** An extension on a Kotlin interface: it works for each Repository. */
fun Repository.cheapest(): Product? = all().minByOrNull { it.price.cents }

/** A var extension property: it can be set with kt/set!. */
var StringBuilder.firstChar: Char
  get() = this[0]
  set(value) { this[0] = value }
