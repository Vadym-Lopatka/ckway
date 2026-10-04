// Sixth review, W2 to W4: overload sets for the comparison with Kotlin's own choice. Each function returns its own name.
// The calls are in test-fixtures/oracle/Round7Calls.kt (Kotlin) and test/ckway/round7_test.clj (Clojure), by id.
package fx.r7s

import java.util.function.IntUnaryOperator

// ---- W2: generic candidates on invariant classes
fun <T> MutableIterable<T>.u1(p: (T) -> Boolean) = "u1-iterable"
fun <T> MutableList<T>.u1(p: (T) -> Boolean) = "u1-list"

fun <T> u2(a: MutableCollection<T>, b: T) = "u2-collection"
fun <T> u2(a: MutableList<T>, b: T) = "u2-list"

fun <K, V> Map<K, V>.u3() = "u3-map"
@JvmName("u3mutable") fun <K, V> MutableMap<K, V>.u3() = "u3-mutable"

@JvmName("u4any") fun <T> MutableList<T>.u4() = "u4-any"
@JvmName("u4cmp") fun <T : Comparable<T>> MutableList<T>.u4() = "u4-comparable"

fun <A, B> u5(a: MutableCollection<A>, b: MutableCollection<B>) = "u5-two"
fun <T> u5(a: MutableList<T>, b: MutableList<T>) = "u5-one"

fun <T> u6(a: MutableCollection<T>) = "u6-generic"
fun u6(a: MutableList<Int>) = "u6-int"

fun <T> u7(a: MutableList<T>, b: MutableCollection<T>) = "u7-list-first"
fun <T> u7(a: MutableCollection<T>, b: MutableList<T>) = "u7-list-second"

fun <T> Array<T>.u8() = "u8-array"
fun <T> Iterable<T>.u8() = "u8-iterable"
fun <T> List<T>.u8() = "u8-list"
@JvmName("u8mutable") fun <T> MutableList<T>.u8() = "u8-mutable"
fun CharSequence.u8() = "u8-chars"
fun String.u8() = "u8-string"

fun <T : Number> u9(a: MutableList<T>) = "u9-number"
fun <T> u9(a: MutableCollection<T>) = "u9-any"

// ---- W3: a collection is a collection before it is a function
fun w3a(x: List<Int>) = "w3a-list"
fun w3a(f: (Int) -> Int) = "w3a-fn"

fun w3b(x: Set<Int>) = "w3b-set"
fun w3b(f: (Int) -> Boolean) = "w3b-fn"

fun w3c(x: Map<Int, Int>) = "w3c-map"
fun w3c(f: (Int) -> Int?) = "w3c-fn"

fun w3d(x: Collection<Int>, tag: String = "t") = "w3d-collection"
fun w3d(f: (Int) -> Boolean) = "w3d-fn"

fun w3e(f: (Int) -> Any?) = "w3e-fn"

// ---- W4: a function type, a Kotlin `fun interface`, a Java functional interface
fun interface FI7 { fun go(x: Int): Int }

fun a1(f: FI7) = "a1-fi"
fun a1(f: IntUnaryOperator) = "a1-java"

fun a2(f: (Int) -> Int) = "a2-fn"
fun a2(f: IntUnaryOperator) = "a2-java"

fun a3(f: (Int) -> Int) = "a3-fn"
fun a3(f: FI7) = "a3-fi"

fun a4(f: (Int) -> Int) = "a4-fn"
fun a4(f: FI7) = "a4-fi"
fun a4(f: IntUnaryOperator) = "a4-java"

fun a5(a: Any, f: FI7) = "a5-fi"
fun a5(a: String, f: IntUnaryOperator) = "a5-java"

fun a6(a: String, f: FI7) = "a6-fi"
fun a6(a: Any, f: IntUnaryOperator) = "a6-java"

fun a7(c: Comparator<Int>) = "a7-java"
fun a7(c: FI7) = "a7-fi"

fun a8(a: Any, f: (Int) -> Int) = "a8-fn"
fun a8(a: String, f: IntUnaryOperator) = "a8-java"

fun a9(a: Any, f: (Int) -> Int) = "a9-fn"
fun a9(a: String, f: FI7) = "a9-fi"
