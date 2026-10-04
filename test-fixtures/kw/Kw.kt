package kw.`in`

class Holder(val items: List<Any?>) {
    inline fun <reified T> firstOf(): T? = items.firstOrNull { it is T } as T?
}

class `object`(val v: Int)

inline fun <reified T> kwTypeName(): String = T::class.java.simpleName

inline fun <reified T> kwTypeOfHolder(h: Holder): T? = h.items.firstOrNull { it is T } as T?

inline fun <reified T> kwPick(o: `object`, extra: Int = 1): String = T::class.java.simpleName + ":" + (o.v + extra)
