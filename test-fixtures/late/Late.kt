package late

// R2, R3: a package whose classes are put on the class path only later (target/late-classes)
class Late { fun hi() = "late" }
fun lateTop(n: Int) = n + 1
