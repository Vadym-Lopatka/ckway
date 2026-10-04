package web

val Config.isDev: Boolean get() = (this["ENV"] ?: "dev") == "dev"
val Config.port: Int get() = this["PORT"]?.toInt() ?: 8080
