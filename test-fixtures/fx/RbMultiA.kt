@file:JvmName("RbMulti")
@file:JvmMultifileClass
package fx.rb

var counter: Int = 0
fun bump(): Int { counter += 1; return counter }
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.InlineOnly
inline fun inlineOnlyTwice(x: Int): Int = x * 2
