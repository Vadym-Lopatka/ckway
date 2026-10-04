@file:JvmName("RbMulti")
@file:JvmMultifileClass
package fx.rb

var label: String = "a"
val doubled: Int get() = counter * 2
fun joinLabel(sep: String): String = label + sep + counter
