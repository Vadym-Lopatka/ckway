package old11

// compiled with -jvm-target 11 (class-file version 55) by bin/build-fixtures
class Old11Box(val items: List<Any?>) {
    inline fun <reified T> firstOf(): T? = items.firstOrNull { it is T } as T?
}

inline fun <reified T> old11TypeName(): String = T::class.java.simpleName

interface Old11Iface { fun go(): String }
