package web

import com.sun.net.httpserver.HttpExchange
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class Redirected : RuntimeException(null, null, false, false)

class Exchange(val server: Server, val raw: HttpExchange) {
  val method: String get() = raw.requestMethod
  val path: String get() = raw.requestURI.path
  var queryParams: Params = (raw.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
  var pathParams: Map<String, String> = emptyMap()
  var failure: Throwable? = null
  var sent = false

  fun path(param: String): String? = pathParams[param]
  inline fun <reified T : Any> path(param: String): T? = path(param)?.let { convert(it, T::class) }
  fun query(name: String): String? = queryParams[name]
  inline fun <reified T : Any> query(name: String): T? = query(name)?.let { convert(it, T::class) }

  fun header(name: String): String? = raw.requestHeaders.getFirst(name)
  fun header(name: String, value: String) { raw.responseHeaders.set(name, value) }

  inline fun <reified T : Any> body(): T = body(typeOf<T>())
  fun <T : Any> body(type: KType): T {
    val contentType = header("Content-Type") ?: ""
    val parser = server.parsers.firstOrNull { contentType.contains(it.contentType) } ?: error("No parser for $contentType")
    @Suppress("UNCHECKED_CAST") return parser.parse(raw.requestBody.readBytes().decodeToString(), type) as T
  }

  /** Renders [body] with the renderer that the Accept header selects, and sends it. */
  fun send(code: StatusCode, body: Any? = null, contentType: String? = null) {
    check(!sent) { "The response was already sent" }
    sent = true
    val accept = header("Accept") ?: ""
    val renderer = server.renderers.firstOrNull { accept.contains(it.contentType) } ?: server.defaultRenderer
    val bytes = if (body == null) ByteArray(0) else renderer.render(body).toByteArray()
    if (bytes.isNotEmpty()) header("Content-Type", contentType ?: renderer.contentType)
    raw.sendResponseHeaders(code.value, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) raw.responseBody.use { it.write(bytes) }
  }

  fun redirect(location: String, code: StatusCode = StatusCode.Found): Nothing {
    header("Location", location)
    send(code)
    throw Redirected()
  }
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> convert(text: String, type: KClass<T>): T = when (type) {
  String::class -> text
  Int::class -> text.toInt()
  Long::class -> text.toLong()
  Double::class -> text.toDouble()
  Boolean::class -> text.toBoolean()
  else -> throw StatusCodeException(StatusCode.BadRequest, "Cannot convert to ${type.simpleName}")
} as T

data class Cookie(
  val name: String, val value: String, val expires: java.time.Instant? = null, val maxAge: kotlin.time.Duration? = null,
  val path: String = "/", val domain: String? = null, val httpOnly: Boolean = false, val secure: Boolean = false,
  val sameSite: String? = null,
) {
  fun header() = "$name=$value; Path=$path" + (maxAge?.let { "; Max-Age=${it.inWholeSeconds}" } ?: "") + (if (httpOnly) "; HttpOnly" else "")
}

/** A server-sent event, a data class that a route can return. */
data class Event(val data: String, val name: String? = null, val id: Long? = null)
