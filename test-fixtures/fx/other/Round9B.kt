// Batch B (B2): a class that implements a function type, in a package other than the one that calls `.invoke`
// (http4k: `RoutingHttpHandler` is in `org.http4k.routing`, the call is `(core/.invoke handler req)`). Used by ckway.round9-test.
package fx.r9b

class Far9 : (String) -> String {
    override fun invoke(p1: String): String = "far:$p1"
}
fun far9(): Far9 = Far9()
