// The Kotlin side of the selection comparison of ckway.round6-test (Z4). ONE call per line, `c("id") { call }`.
// bin/kotlin-oracle compiles this file; a line that kotlinc refuses with "overload resolution ambiguity" is recorded
// as AMBIGUOUS, one that it refuses because no candidate takes the argument types as NONE, the others are run. The answers go to target/fixtures/round6-oracle.txt (`id<TAB>answer`).
package fx.oracle

import fx.r6s.*

fun c(id: String, f: () -> String) = println(id + "\t" + f())

fun main() {
  c("oo-named") { oo(a = 1, b = 2) }
  c("oo-named-rev") { oo(b = 1, a = 2) }
  c("oo-pos") { oo(1, 2) }
  c("oo-long") { oo(1, 2L) }
  c("s5-two") { s5(1, "x") }
  c("s5-one") { s5(1) }
  c("s5-long") { s5(1L, "x") }
  c("s5-three") { s5(1, "x", 2) }
  c("s5-named-c") { s5(1, c = 2) }
  c("t1-both") { t1("a", "b") }
  c("t1-int-first") { t1(1, "b") }
  c("t1-int-second") { t1("a", 1) }
  c("n1-int") { n1(1) }
  c("n1-long") { n1(1L) }
  c("n1-big") { n1(3000000000) }
  c("n2-int") { n2(1) }
  c("n2-long") { n2(1L) }
  c("n2-double") { n2(1.5) }
  c("n3-int") { n3(1) }
  c("n3-long") { n3(1L) }
  c("n4-int") { n4(1) }
  c("n5-int") { n5(1) }
  c("n5-null") { n5(null) }
  c("n6-int") { n6(1) }
  c("n7-int") { n7(1) }
  c("n7-str") { n7("x") }
  c("n8-int-int") { n8(1, 2) }
  c("n8-int-long") { n8(1, 2L) }
  c("n9-int") { n9(1) }
  c("r1-str") { r1("x") }
  c("r1-null") { r1(null) }
  c("r1-int") { r1(1) }
  c("r2-str") { r2("x") }
  c("r2-sb") { r2(StringBuilder("x")) }
  c("g1-str") { g1("x") }
  c("g1-int") { g1(1) }
  c("g1-null") { g1(null) }
  c("g2-int-str") { g2(1, "x") }
  c("g2-str-str") { g2("a", "x") }
  c("g3-str") { g3("x") }
  c("g3-int") { g3(1) }
  c("g4-int") { g4("x", 1) }
  c("g4-long") { g4("x", 1L) }
  c("v1-one") { v1(1) }
  c("v1-two") { v1(1, 2) }
  c("v1-none") { v1() }
  c("v2-one") { v2(1) }
  c("v2-two") { v2(1, "x") }
  c("v2-three") { v2(1, "x", "y") }
  c("v2-named") { v2(a = 1) }
  c("v3-two") { v3("a", "b") }
  c("v3-three") { v3("a", "b", "c") }
  c("v3-int") { v3(1, 2) }
  c("v4-one") { v4(1) }
  c("v4-two") { v4(1, "x") }
  c("v5-str") { v5("a", "b") }
  c("v5-mixed") { v5("a", 1) }
  c("d1-one") { d1(1) }
  c("d1-two") { d1(1, 2) }
  c("d1-named") { d1(a = 1) }
  c("d2-none") { d2() }
  c("d2-int") { d2(1) }
  c("d2-str") { d2("x") }
  c("d2-named-a") { d2(a = 1) }
  c("d2-named-b") { d2(b = "x") }
  c("d2-named-ab") { d2(a = 1, b = "x") }
  c("d2-named-ba") { d2(b = "x", a = 1) }
  c("d2-named-a-long") { d2(a = 1L) }
  c("d3-one") { d3(1) }
  c("d3-two") { d3(1, 2) }
  c("d3-three") { d3(1, 2, 3) }
  c("d3-named-c") { d3(1, c = 3) }
  c("m1-named-ba") { m1(b = "x", a = 1) }
  c("m1-named-ab") { m1(a = "x", b = 1) }
  c("m1-pos") { m1(1, "x") }
  c("m1-pos-rev") { m1("x", 1) }
  c("m2-x") { m2(x = 1) }
  c("m2-x-long") { m2(x = 1L) }
  c("m2-xy") { m2(x = 1, y = 2) }
  c("m2-yx") { m2(y = 1, x = 2) }
  c("m2-pos") { m2(1, 2) }
  c("c1-dog-dog") { c1(Dog(), Dog()) }
  c("c1-animal-dog") { c1(Animal(), Dog()) }
  c("c1-named") { c1(b = Dog(), a = Animal()) }
  c("c2-dog") { c2(Dog()) }
  c("c2-animal") { c2(Animal()) }
  c("c2-str") { c2("x") }
  c("k1") { k1(1, "x") }
  c("k1-long") { k1(1L, "x") }
  c("k2") { k2(1, "x") }
  c("k2-named") { k2(b = "x", a = 1) }
  c("nl-str") { nl("x", 1) }
  c("nl-null") { nl(null, 1) }
  c("nl-long") { nl("x", 1L) }
  c("sh") { sh(1, 2) }
  c("ll-list") { ll(listOf("a")) }
  c("w1-one") { w1(1) }
  c("w1-two") { w1(1, "x") }
  c("w1-three") { w1(1, "x", 2) }
  c("h1") { h1 { it } }
  c("h2") { h2("x") { it } }
  c("h2-int") { h2(1) { it } }
  c("h3") { h3(1) { it } }
  c("h3-long") { h3(1L) { it } }
  c("h4") { h4 { a, b -> a - b } }
  c("h5") { h5("x") { it } }
  c("h5-int") { h5(1) { it } }
  c("h6") { h6("x") { it } }
  c("h7") { h7("x") { it } }
  // the same positional calls with every integer a Long: what a var called as a VALUE gets (`(apply s/n1 [1])`:
  // the value is a Clojure Long, there is no literal). NONE = Kotlin has no candidate for a Long there.
  c("oo-pos/v") { oo(1L, 2L) }
  c("oo-long/v") { oo(1L, 2L) }
  c("s5-two/v") { s5(1L, "x") }
  c("s5-one/v") { s5(1L) }
  c("s5-three/v") { s5(1L, "x", 2L) }
  c("t1-int-first/v") { t1(1L, "b") }
  c("t1-int-second/v") { t1("a", 1L) }
  c("n1-int/v") { n1(1L) }
  c("n2-int/v") { n2(1L) }
  c("n3-int/v") { n3(1L) }
  c("n4-int/v") { n4(1L) }
  c("n5-int/v") { n5(1L) }
  c("n6-int/v") { n6(1L) }
  c("n7-int/v") { n7(1L) }
  c("n8-int-int/v") { n8(1L, 2L) }
  c("n8-int-long/v") { n8(1L, 2L) }
  c("n9-int/v") { n9(1L) }
  c("r1-int/v") { r1(1L) }
  c("g1-int/v") { g1(1L) }
  c("g2-int-str/v") { g2(1L, "x") }
  c("g3-int/v") { g3(1L) }
  c("g4-int/v") { g4("x", 1L) }
  c("v1-one/v") { v1(1L) }
  c("v1-two/v") { v1(1L, 2L) }
  c("v2-one/v") { v2(1L) }
  c("v2-two/v") { v2(1L, "x") }
  c("v2-three/v") { v2(1L, "x", "y") }
  c("v3-int/v") { v3(1L, 2L) }
  c("v4-one/v") { v4(1L) }
  c("v4-two/v") { v4(1L, "x") }
  c("v5-mixed/v") { v5("a", 1L) }
  c("d1-one/v") { d1(1L) }
  c("d1-two/v") { d1(1L, 2L) }
  c("d2-int/v") { d2(1L) }
  c("d3-one/v") { d3(1L) }
  c("d3-two/v") { d3(1L, 2L) }
  c("d3-three/v") { d3(1L, 2L, 3L) }
  c("m1-pos/v") { m1(1L, "x") }
  c("m1-pos-rev/v") { m1("x", 1L) }
  c("m2-pos/v") { m2(1L, 2L) }
  c("k1/v") { k1(1L, "x") }
  c("k2/v") { k2(1L, "x") }
  c("nl-str/v") { nl("x", 1L) }
  c("nl-null/v") { nl(null, 1L) }
  c("sh/v") { sh(1L, 2L) }
  c("w1-one/v") { w1(1L) }
  c("w1-two/v") { w1(1L, "x") }
  c("w1-three/v") { w1(1L, "x", 2L) }
  c("h2-int/v") { h2(1L) { it } }
  c("h3/v") { h3(1L) { it } }
  c("h5-int/v") { h5(1L) { it } }
}
