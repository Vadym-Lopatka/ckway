package shop

interface Repository {
  var name: String
  fun all(): List<Product>
  fun find(id: Long): Product?
  fun find(name: String): Product?
  fun price(id: Long): Money
  /** A default method: a Clojure object that implements Repository does not have to write it. */
  fun count(): Int = all().size
  suspend fun load(id: Long): Product?
}

class MemoryRepository(private val products: List<Product>, override var name: String = "memory") : Repository {
  override fun all() = products
  override fun find(id: Long) = products.firstOrNull { it.id == id }
  override fun find(name: String) = products.firstOrNull { it.name == name }
  override fun price(id: Long) = find(id)?.price ?: Money.ZERO
  override suspend fun load(id: Long): Product? {
    kotlinx.coroutines.delay(5)
    return find(id)
  }
}

object Catalog {
  val products = listOf(
    Product(1, "Tea", Money.of(3, 50), listOf("drink", "hot")),
    Product(2, "Coffee", Money.of(4, 20), listOf("drink", "hot")),
    Product(3, "Cake", Money.of(5), listOf("food")),
    Product(4, "Water", Money.of(1), listOf("drink"))
  )
  val repository: Repository = MemoryRepository(products, "catalog")
  val size: Int get() = products.size
  fun byTag(tag: String) = products.filter { it.hasTag(tag) }
  fun find(id: Long): Product = products.first { it.id == id }
}

/** Reports the use of a repository: Kotlin code that calls a Clojure object through the interface. */
fun describeRepository(repo: Repository): String = "${repo.name}: ${repo.count()} products, first=${repo.find(1L)?.name}"

fun rename(repo: Repository, name: String): String {
  repo.name = name
  return repo.name
}
