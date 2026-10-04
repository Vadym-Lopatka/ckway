package web.query

import java.sql.Connection
import kotlin.reflect.KProperty1

/** A query condition other than "equals". */
data class SqlOp(val op: String, val value: Any?)

infix fun <T, V> KProperty1<T, V>.eq(value: V): Pair<KProperty1<T, V>, V> = this to value
infix fun <T, V> KProperty1<T, V>.gt(value: V): Pair<KProperty1<T, V>, SqlOp> = this to SqlOp(">", value)
infix fun <T> KProperty1<T, String?>.ilike(pattern: String): Pair<KProperty1<T, String?>, SqlOp> = this to SqlOp("ilike", pattern)
infix fun String.eq(value: Any?): Pair<String, Any?> = this to value

fun or(vararg conditions: Pair<KProperty1<*, *>, Any?>): Pair<KProperty1<*, *>, SqlOp> = conditions.first().first to SqlOp("or", conditions.toList())
fun and(vararg conditions: Pair<KProperty1<*, *>, Any?>): Pair<KProperty1<*, *>, SqlOp> = conditions.first().first to SqlOp("and", conditions.toList())

/** A `var` extension property on a JDK type. */
var Connection.applicationName: String?
  get() = getClientInfo("ApplicationName")
  set(value) { setClientInfo("ApplicationName", value) }
