// The Kotlin side of the selection comparison of ckway.round7-test (W2 to W4). ONE call per line, `c("id") { call }`.
// See Round6Calls.kt. The answers go to target/fixtures/round7-oracle.txt.
package fx.oracle7

import fx.r7s.*

fun c(id: String, f: () -> String) = println(id + "\t" + f())

fun main() {
  c("u1-mutable") { mutableListOf(1, 2).u1 { true } }
  c("u1-arraylist") { arrayListOf(1, 2).u1 { true } }
  c("u1-set") { mutableSetOf(1, 2).u1 { true } }
  c("u2-mutable") { u2(mutableListOf("a"), "b") }
  c("u2-arraylist") { u2(arrayListOf("a"), "b") }
  c("u2-set") { u2(mutableSetOf("a"), "b") }
  c("u3-mutable") { mutableMapOf(1 to 2).u3() }
  c("u3-hashmap") { hashMapOf(1 to 2).u3() }
  c("u4-strings") { mutableListOf("a").u4() }
  c("u4-arraylist") { arrayListOf("a").u4() }
  c("u5-lists") { u5(mutableListOf(1), mutableListOf(2)) }
  c("u5-list-set") { u5(mutableListOf(1), mutableSetOf(2)) }
  c("u5-arraylists") { u5(arrayListOf(1), arrayListOf(2)) }
  c("u6-mutable") { u6(mutableListOf(1)) }
  c("u6-arraylist") { u6(arrayListOf(1)) }
  c("u6-set") { u6(mutableSetOf(1)) }
  c("u7-lists") { u7(mutableListOf(1), mutableListOf(2)) }
  c("u7-list-set") { u7(mutableListOf(1), mutableSetOf(2)) }
  c("u8-mutable") { mutableListOf(1).u8() }
  c("u8-arraylist") { arrayListOf(1).u8() }
  c("u8-set") { mutableSetOf(1).u8() }
  c("u8-string") { "abc".u8() }
  c("u8-builder") { StringBuilder("abc").u8() }
  c("u8-array") { arrayOf("a").u8() }
  c("u9-mutable") { u9(mutableListOf(1)) }
  c("u9-arraylist") { u9(arrayListOf(1)) }
  c("u9-set") { u9(mutableSetOf(1)) }
  c("w3a-list") { w3a(listOf(1, 2)) }
  c("w3a-fn") { w3a { it } }
  c("w3b-set") { w3b(setOf(1, 2)) }
  c("w3b-fn") { w3b { true } }
  c("w3c-map") { w3c(mapOf(1 to 2)) }
  c("w3c-fn") { w3c { it } }
  c("w3d-list") { w3d(listOf(1, 2)) }
  c("w3d-set") { w3d(setOf(1, 2)) }
  c("w3d-fn") { w3d { true } }
  c("a1") { a1 { it } }
  c("a2") { a2 { it } }
  c("a3") { a3 { it } }
  c("a4") { a4 { it } }
  c("a5") { a5("x") { it } }
  c("a5-int") { a5(1) { it } }
  c("a6") { a6("x") { it } }
  c("a7") { a7 { a -> a } }
  c("a8") { a8("x") { it } }
  c("a9") { a9("x") { it } }
}
