package fx.r5far

import fx.r5.*

// the overrides are in another package than the originals
class Far : DSrc { override fun mk(x: Int): String = "far$x" }
class FarSub : DBase() { override fun get(x: Int, y: Int): String = "fs$x,$y" }
class FarLeaf : DMid() { override fun get(x: Int, y: Int): String = "fl$x,$y" }
class FarThis : DThis() { override fun m(a: String): String = "far:$a" }
class FarSusp : DSusp { override suspend fun s(n: Int): String = "fs$n" }
class FarVc : DVc { override fun vc(id: VId): String = "fvc${id.v}" }
class FarI2 : DIface2 { override fun mk(x: Int): String = "fi2-$x" }
fun kFar() = Far().mk()
fun kFarSub() = FarSub().get()
fun kFarSubY() = FarSub().get(y = 5)
fun kFarLeaf() = FarLeaf().get(2)
fun kFarThis() = FarThis().m()
suspend fun kFarSusp() = FarSusp().s()
fun kFarVc() = FarVc().vc()
fun kFarI2() = FarI2().mk()
