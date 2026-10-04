package web

import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KType

interface BodyRenderer { val contentType: String; fun render(value: Any): String }
interface BodyParser { val contentType: String; fun parse(text: String, type: KType): Any? }

class TextBody : BodyRenderer {
  override val contentType = "text/plain"
  override fun render(value: Any) = value.toString()
}

/** JSON in and out, as an extension: `server.use<JsonBody>()`. */
class JsonBody : Extension, BodyRenderer, BodyParser {
  override val contentType = "application/json"
  override fun install(server: Server) { server.renderers += this; server.parsers += this }

  override fun render(value: Any) = StringBuilder().also { write(it, value) }.toString()

  private fun write(sb: StringBuilder, v: Any?) {
    when (v) {
      null -> sb.append("null")
      is Number, is Boolean -> sb.append(v)
      // Iterable comes BEFORE Map. A Clojure map is both, so it is written as a list of pairs: see doc/limits.md, item 12.
      is Iterable<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']') }
      is Map<*, *> -> obj(sb, v.entries.map { it.key.toString() to it.value })
      is CharSequence, is Enum<*> -> quote(sb, v.toString())
      else -> if (v.javaClass.isAnnotationPresent(Metadata::class.java)) obj(sb, fields(v.javaClass).map { it.name to it.get(v) }) else quote(sb, v.toString())
    }
  }

  private fun obj(sb: StringBuilder, entries: List<Pair<String, Any?>>) {
    sb.append('{')
    entries.filter { it.second != null }.forEachIndexed { i, (k, x) -> if (i > 0) sb.append(','); quote(sb, k); sb.append(':'); write(sb, x) }
    sb.append('}')
  }

  private fun quote(sb: StringBuilder, s: String) {
    sb.append('"')
    s.forEach { c -> when (c) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); else -> sb.append(c) } }
    sb.append('"')
  }

  private fun fields(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

  // ---- parsing: JSON text -> List / Map / String / Long / Double / Boolean / null, then to the type that typeOf<T>() asks for
  override fun parse(text: String, type: KType): Any? = coerce(Reader(text).value(), type.classifier as KClass<*>, type.arguments.map { it.type })

  private fun coerce(v: Any?, cls: KClass<*>, args: List<KType?>): Any? = when {
    v == null || cls == Any::class -> v
    cls == Int::class -> (v as Number).toInt()
    cls == Long::class -> (v as Number).toLong()
    cls == Double::class -> (v as Number).toDouble()
    v is List<*> -> v.map { coerce(it, args[0]!!.classifier as KClass<*>, args[0]!!.arguments.map { a -> a.type }) }
    v is Map<*, *> && Map::class.java.isAssignableFrom(cls.java) ->
      v.mapValues { coerce(it.value, args[1]!!.classifier as KClass<*>, args[1]!!.arguments.map { a -> a.type }) }
    v is Map<*, *> -> construct(cls, v)
    else -> v
  }

  /** A data class: the constructor properties are the first fields. A missing key takes the Kotlin default (the synthetic constructor with a mask). */
  private fun construct(cls: KClass<*>, values: Map<*, *>): Any {
    val ctors = cls.java.declaredConstructors
    val primary = ctors.filter { !it.isSynthetic }.maxByOrNull { it.parameterCount }!!
    val n = primary.parameterCount
    val names = fields(cls.java).map { it.name }
    val withDefaults = ctors.firstOrNull { it.isSynthetic && it.parameterCount == n + (n + 31) / 32 + 1 }
    var mask = 0
    val args = Array(n) { i ->
      val type = primary.parameterTypes[i]
      val key = names[i]
      if (values.containsKey(key)) coerce(values[key], type.kotlin, emptyList())
      else { mask = mask or (1 shl i); if (type.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0) else null }
    }
    return if (mask == 0 || withDefaults == null) primary.newInstance(*args) else withDefaults.newInstance(*args, mask, null)
  }

  private class Reader(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? { ws(); return when (val c = s[i]) {
      '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
        if (s[i] == '}') i++ else do { ws(); val k = string(); ws(); i++; m[k] = value(); ws() } while (s[i++] == ',')
        m }
      '[' -> { i++; val l = ArrayList<Any?>(); ws()
        if (s[i] == ']') i++ else do { l += value(); ws() } while (s[i++] == ',')
        l }
      '"' -> string()
      else -> { val j = i; while (i < s.length && s[i] !in ",]} \n\r\t") i++
        when (val t = s.substring(j, i)) { "null" -> null; "true" -> true; "false" -> false
          else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: error("Bad JSON at $j: $c") } }
    } }
    fun string(): String {
      val sb = StringBuilder(); i++
      while (s[i] != '"') { if (s[i] == '\\') { i++; sb.append(when (val e = s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> e }) } else sb.append(s[i]); i++ }
      i++
      return sb.toString()
    }
  }
}
