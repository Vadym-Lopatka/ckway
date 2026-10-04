package web

import java.io.File

object Config {
  private val values = HashMap<String, String>()
  operator fun get(key: String): String? = values[key] ?: System.getenv(key)
  operator fun set(key: String, value: String) { values[key] = value }
  /** Reads KEY=value lines of a file, if the file exists. */
  fun useEnvFile(file: String = ".env") {
    File(file).takeIf { it.exists() }?.readLines()?.filter { '=' in it }?.forEach { this[it.substringBefore('=').trim()] = it.substringAfter('=').trim() }
  }
}
