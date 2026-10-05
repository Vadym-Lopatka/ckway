// Batch B (B2): a class that implements a function type, in a package other than the one that calls `.invoke`
// (http4k: `RoutingHttpHandler` is in `org.http4k.routing`, the call is `(core/.invoke handler req)`). Used by ckway.round9-test.
package fx.r9b

class Far9 : (String) -> String {
    override fun invoke(p1: String): String = "far:$p1"
}
fun far9(): Far9 = Far9()

// Batch D (D1): the declared override of the `invoke` of a function type, in another package than the call
interface ExtFar9<in IN, out OUT> : (IN) -> OUT { override operator fun invoke(target: IN): OUT }
class ExtFarImpl9 : ExtFar9<String, String> { override fun invoke(target: String): String = "ext-far:$target" }
fun extFar9(): ExtFarImpl9 = ExtFarImpl9()

// Batch D (D4): a function and a property of one name whose receiver class is in another package than the var
fun ambo9(vararg list: fx.r9.Route9): String = "ambo-fun:" + list.size
val fx.r9.Route9.ambo9: List<String> get() = listOf("ambo-prop:" + name)
