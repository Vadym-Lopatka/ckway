// Fifth review, Z4: overload sets for the comparison with Kotlin's own choice. Each function returns its own name.
// The calls are in test-fixtures/oracle/Round6Calls.kt (Kotlin) and test/ckway/round6_test.clj (Clojure), by id.
package fx.r6s

open class Animal
class Dog : Animal()

fun oo(a: Int, b: Long) = "oo1"
fun oo(b: Int, a: Short) = "oo2"

fun s5(a: Long, b: String = "d") = "s51"
fun s5(a: Int, b: CharSequence = "d", c: Int = 0) = "s52"

fun t1(a: Any, b: String) = "t11"
fun t1(a: String, b: Any) = "t12"

fun n1(a: Int) = "n11"
fun n1(a: Long) = "n12"

fun n2(a: Long) = "n21"
fun n2(a: Number) = "n22"

fun n3(a: Short) = "n31"
fun n3(a: Long) = "n32"

fun n4(a: Byte) = "n41"
fun n4(a: Int) = "n42"

fun n5(a: Int?) = "n51"
fun n5(a: Long) = "n52"

fun n6(a: Short) = "n61"
fun n6(a: Byte) = "n62"

fun n7(a: Long) = "n71"
fun n7(a: Any) = "n72"

fun n8(a: Int, b: Long) = "n81"
fun n8(a: Long, b: Int) = "n82"

fun n9(a: Long) = "n91"
fun n9(a: Byte) = "n92"

fun r1(a: Any) = "r11"
fun r1(a: String?) = "r12"

fun r2(a: CharSequence) = "r21"
fun r2(a: String) = "r22"

fun <T> g1(a: T) = "g11"
fun g1(a: String) = "g12"

fun <T> g2(a: T, b: String) = "g21"
fun g2(a: Int, b: CharSequence) = "g22"

fun <T : CharSequence> g3(a: T) = "g31"
fun g3(a: Any) = "g32"

fun <T> g4(a: T, b: Int) = "g41"
fun <T> g4(a: T, b: Long) = "g42"

fun v1(vararg a: Int) = "v11"
fun v1(a: Int) = "v12"

fun v2(a: Int, vararg b: String) = "v21"
fun v2(a: Int, b: String = "d") = "v22"

fun v3(a: String, b: String) = "v31"
fun <T> v3(vararg a: T) = "v32"

fun v4(a: Int, b: String = "d", vararg c: Int) = "v41"
fun v4(a: Int) = "v42"

fun v5(vararg a: String) = "v51"
fun v5(vararg a: Any) = "v52"

fun d1(a: Int) = "d11"
fun d1(a: Int, b: Int = 0) = "d12"

fun d2(a: Int = 0, b: String = "b") = "d21"
fun d2(b: String = "b", a: Long = 0) = "d22"

fun d3(a: Int, b: Int = 0, c: Int = 0) = "d31"
fun d3(a: Int, b: Int = 0) = "d32"

fun m1(a: Int, b: String) = "m11"
fun m1(a: String, b: Int) = "m12"

fun m2(x: Int, y: Long = 1) = "m21"
fun m2(y: Short = 2, x: Long) = "m22"

fun c1(a: Animal, b: Dog) = "c11"
fun c1(a: Dog, b: Animal) = "c12"

fun c2(a: Animal) = "c21"
fun c2(a: Dog) = "c22"
fun c2(a: Any) = "c23"

fun k1(a: Int, b: Any) = "k11"
fun k1(a: Long, b: String) = "k12"

fun k2(a: Long, b: Any) = "k21"
fun k2(a: Int, b: String) = "k22"

fun nl(a: String?, b: Int) = "nl1"
fun nl(a: String, b: Long) = "nl2"

fun sh(a: Short, b: Int) = "sh1"
fun sh(a: Int, b: Short) = "sh2"

fun ll(a: List<Any>) = "ll1"
fun ll(a: Collection<Any>) = "ll2"

fun w1(a: Int, b: String = "d") = "w11"
fun w1(a: Int, b: CharSequence = "d", c: Int = 0) = "w12"

// a Clojure function as the argument: a Kotlin function type, a Kotlin `fun interface`, a Java SAM interface
fun interface FI { fun go(x: Int): Int }

fun h1(f: FI) = "h11"
fun h1(f: (Int) -> Int) = "h12"

fun h2(a: Any, f: (Int) -> Int) = "h21"
fun h2(a: String, f: FI) = "h22"

fun h3(a: Int, f: FI) = "h31"
fun h3(a: Long, f: (Int) -> Int) = "h32"

fun h4(c: Comparator<Int>) = "h41"
fun h4(c: (Int, Int) -> Int) = "h42"

fun <T> h5(a: T, f: FI) = "h51"
fun h5(a: String, f: (Int) -> Int) = "h52"

fun <T> h6(a: T, f: (Int) -> Int) = "h61"
fun h6(a: String, f: FI) = "h62"

fun h7(a: String, f: FI) = "h71"
fun h7(a: CharSequence, f: FI) = "h72"
