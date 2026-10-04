package web

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.reflect.KClass

typealias Handler = suspend Exchange.() -> Any?
typealias Decorator = suspend (Exchange, Handler) -> Any?
typealias Headers = com.sun.net.httpserver.Headers
typealias Params = Map<String, String?>
typealias DefaultRenderer = TextBody

@JvmInline value class StatusCode(val value: Int) {
  companion object {
    val OK = StatusCode(200); val Created = StatusCode(201); val NoContent = StatusCode(204)
    val Found = StatusCode(302); val BadRequest = StatusCode(400); val NotFound = StatusCode(404)
    val InternalServerError = StatusCode(500)
  }
}

class StatusCodeException(val statusCode: StatusCode, message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)

fun interface Before { suspend fun before(exchange: Exchange) }
fun interface After { suspend fun after(exchange: Exchange, error: Throwable?) }

interface Extension {
  fun install(server: Server)
  fun install(config: RouterConfig) { if (config is Server) install(config) }
}

interface RouterConfig {
  fun context(prefix: String, block: Router.() -> Unit = {}): Router
  fun decorator(decorator: Decorator)
  fun before(before: Before)
  fun after(after: After)
}

interface Registry {
  fun <T : Any> require(type: KClass<T>): T = optional(type) ?: error("${type.simpleName} is not registered")
  fun <T : Any> optional(type: KClass<T>): T?
}
inline fun <reified T : Any> Registry.require(): T = require(T::class)
inline fun <reified T : Any> Registry.optional(): T? = optional(T::class)
inline fun <reified T : Extension> Server.use(): T = optional(T::class) ?: use(T::class.java.getDeclaredConstructor().newInstance())

/** A route: the hooks and decorators that were registered before it are fixed in `handler` and `befores`/`afters`. */
class Route(val method: String, val path: String, val befores: List<Before>, val afters: List<After>, val handler: Handler) {
  val names = Regex(":(\\w+)").findAll(path).map { it.groupValues[1] }.toList()
  val regex = Regex(path.replace(Regex(":\\w+"), "([^/]+)"))
}

class Router(val server: Server, val prefix: String, private val parent: Router? = null) : RouterConfig {
  val routes = mutableListOf<Route>()
  private val decorators: MutableList<Decorator> = parent?.decorators?.toMutableList() ?: mutableListOf()
  private val befores: MutableList<Before> = parent?.befores?.toMutableList() ?: mutableListOf()
  private val afters: MutableList<After> = parent?.afters?.toMutableList() ?: mutableListOf()

  override fun context(prefix: String, block: Router.() -> Unit): Router = Router(server, this.prefix + prefix, this).apply(block)
  override fun decorator(decorator: Decorator) { decorators += decorator }
  override fun before(before: Before) { befores += before }
  override fun after(after: After) { afters += after }

  fun get(path: String = "", handler: Handler) = add("GET", path, handler)
  fun post(path: String = "", handler: Handler) = add("POST", path, handler)

  private fun add(method: String, path: String, handler: Handler) {
    val wrapped = decorators.foldRight(handler) { d, next -> val h: Handler = { d(this, next) }; h }
    Route(method, prefix + path, befores.toList(), afters.toList(), wrapped).also { routes += it; server.routes += it }
  }
}

class Server(
  val listen: InetSocketAddress = InetSocketAddress(Config.port),
  val workerPool: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
  val errorStatus: (Throwable) -> StatusCode = ::statusOf,
  val defaultRenderer: BodyRenderer = TextBody(),
) : RouterConfig, Registry {
  val routes = mutableListOf<Route>()
  val renderers = mutableListOf<BodyRenderer>()
  val parsers = mutableListOf<BodyParser>()
  private val root = Router(this, "")
  private val registry = LinkedHashMap<KClass<*>, Any>()
  private var http: HttpServer? = null

  val address: InetSocketAddress get() = http?.address ?: listen

  override fun context(prefix: String, block: Router.() -> Unit) = root.context(prefix, block)
  override fun decorator(decorator: Decorator) = root.decorator(decorator)
  override fun before(before: Before) = root.before(before)
  override fun after(after: After) = root.after(after)

  fun <E : Extension> use(extension: E): E {
    registry[extension::class] = extension
    val config: RouterConfig = this
    extension.install(config)
    return extension
  }
  override fun <T : Any> optional(type: KClass<T>): T? = registry.values.firstOrNull { type.java.isInstance(it) }?.let { type.java.cast(it) }

  /** [socketBacklog]: the queue of connections that wait to be accepted. Keep it big, or a burst of connects is refused. */
  fun start(gracefulStopDelaySec: Int = 5, socketBacklog: Int = 1000) {
    val server = HttpServer.create(listen, socketBacklog)
    server.executor = workerPool
    server.createContext("/") { raw -> runBlocking { dispatch(Exchange(this@Server, raw)) } }
    server.start()
    http = server
    if (gracefulStopDelaySec >= 0) Runtime.getRuntime().addShutdownHook(Thread { stop(gracefulStopDelaySec) })
  }

  /** Stops the HTTP server. The worker pool belongs to the caller: shut it down yourself. */
  fun stop(delaySec: Int = 0) { http?.stop(delaySec); http = null }

  private suspend fun dispatch(ex: Exchange) {
    try {
      val (r, match) = routes.firstNotNullOfOrNull { r -> if (r.method == ex.method) r.regex.matchEntire(ex.path)?.let { r to it } else null }
        ?: throw StatusCodeException(StatusCode.NotFound, "Not found: ${ex.path}")
      ex.pathParams = r.names.zip(match.groupValues.drop(1)).toMap()
      var result: Any? = null
      var error: Throwable? = null
      try {
        r.befores.forEach { it.before(ex) }
        result = ex.run { r.handler(this) }
      } catch (e: Redirected) {
      } catch (e: Throwable) { error = e; ex.failure = e }
      r.afters.forEach { it.after(ex, ex.failure) }
      error?.let { throw it }
      if (!ex.sent) ex.send(StatusCode.OK, result)
    } catch (e: Redirected) {
    } catch (e: Throwable) {
      if (!ex.sent) ex.send(errorStatus(e), e.message ?: e.javaClass.simpleName)
    } finally { ex.raw.close() }
  }
}

fun statusOf(e: Throwable): StatusCode = when (e) {
  is StatusCodeException -> e.statusCode
  is IllegalStateException, is IllegalArgumentException -> StatusCode.BadRequest
  else -> StatusCode.InternalServerError
}
