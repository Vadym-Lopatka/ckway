package fx

import java.io.IOException

/** Kotlin code that catches a checked exception thrown by the function it was given. */
fun catchIo(f: (Int) -> Int): String =
    try { f(1).toString() } catch (e: IOException) { "io:" + e.message } catch (e: Throwable) { "other:" + e.javaClass.simpleName }

fun catchIoPred(p: Pred): String =
    try { p.test(1).toString() } catch (e: IOException) { "io:" + e.message } catch (e: Throwable) { "other:" + e.javaClass.simpleName }
