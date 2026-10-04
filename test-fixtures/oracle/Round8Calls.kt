// The Kotlin side of ckway.round8-test (V1, V2). ONE call per line, `c("id") { call }`. See Round6Calls.kt.
// The answers go to target/fixtures/round8-oracle.txt.
package fx.oracle8

import fx.r8.*

fun c(id: String, f: () -> String) = println(id + "\t" + f())

fun main() {
  c("u9-ints") { u9(arrayListOf(1)) }
  c("u9-strings") { u9(arrayListOf("a")) }
  c("mxc-ints") { mxc(listOf(1)) }
  c("mxc-lists") { mxc(listOf(listOf(1))) }
  c("ks-strings") { ks(hashMapOf("a" to 1)) }
  c("ks-lists") { ks(hashMapOf(listOf(1) to 1)) }
  c("nn-ints") { nn(listOf(1)) }
  c("nn-null") { nn(listOf(1, null)) }
  c("two-ints") { two(listOf(1), listOf(2)) }
  c("two-string-int") { two(listOf("a"), listOf(1)) }
  c("lk-same") { lk(arrayListOf(1), arrayListOf(2)) }
  c("lk-other") { val a = arrayListOf(1); val b = arrayListOf("a"); lk(a, b) }
  c("cg-ints") { cg(arrayListOf(1)) }
  c("cg-strings") { cg(arrayListOf("a")) }
  c("fr-list") { arrayListOf(1).fr { true } }
  c("fr-set") { hashSetOf(1).fr { true } }
  c("sm-list") { sm(arrayListOf("a"), "b") }
  c("sm-set") { sm(hashSetOf("a"), "b") }
  c("cs-list") { cs(arrayListOf("a")) }
  c("cs-set") { cs(hashSetOf("a")) }
  c("tb-int") { tb(1) }
  c("tb-string") { tb("a") }
  c("iv-readonly") { iv(listOf(1)) }
  c("iv-mutable") { iv(arrayListOf(1)) }
  c("iv-set") { iv(setOf(1)) }
  c("im-readonly") { im(mapOf(1 to 2)) }
  c("im-mutable") { im(hashMapOf(1 to 2)) }
  c("is-readonly") { ist(setOf(1)) }
  c("is-mutable") { ist(hashSetOf(1)) }
  c("iv-kt-readonly") { iv(readOnly()) }
  c("iv-kt-mutable") { iv(mutableOne()) }
  c("u9-targ-int") { u9<Int>(arrayListOf(1)) }
  c("mxc-targ-int") { mxc<Int>(listOf(1)) }
}
