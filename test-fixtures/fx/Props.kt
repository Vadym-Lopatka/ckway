package fx

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

// ---------------------------------------------------------------- kt/set!

class Gadget {
  var level: Int = 0
  var label: String? = null
  val fixed: Int = 7
  var secret: Int = 1
    private set
  var inner: Int = 2
    internal set
  var uid: Uid = Uid(1)
  var maybeUid: Uid? = null
  lateinit var late: String
  @JvmField var raw: Int = 0
  var big: Long = 0L
  var tiny: Short = 0
  var ratio: Double = 0.0
  var flag: Boolean = false
  var hook: (Int) -> Int = { it }
  var pred: Pred? = null
  fun secretValue(): Int = secret
  fun bumpSecret() { secret++ }
  fun callHook(x: Int): Int = hook(x)
}

interface Ticking { var ticks: Int }
class Clock : Ticking { override var ticks: Int = 0 }
class Clock2 : Ticking {
  override var ticks: Int = 10
    set(v) { field = v * 2 }
}

var topVar: Int = 0
val topVal: Int = 41
var StringBuilder.firstChar: Char
  get() = this[0]
  set(v) { this.setCharAt(0, v) }

object Knob {
  var volume: Int = 0
  @JvmStatic var stat: Int = 0
  @JvmField var fld: Int = 0
}

class Dial {
  companion object {
    var setting: Int = 0
    @JvmStatic var jsetting: Int = 0
  }
}

open class Parent { open var width: Int = 1 }
class Child : Parent() { override var width: Int = 5 }

// ---------------------------------------------------------------- kt/ref and kt/data

data class Person(val id: Long, val firstName: String, val email: String? = null, val uid: Uid = Uid(1)) {
  fun greet(greeting: String = "Hi"): String = "$greeting $firstName"
  fun tag(a: Int): String = "int:$a"
  fun tag(a: String): String = "string:$a"
  fun withUid(u: Uid, n: Int): String = "${u.v}:$n"
}

data class Person2(val id: Long, val name: String)

class Account(var owner: String, val number: Int) {
  fun describe(): String = "$owner/$number"
}

data class Wide(
  val p1: Int, val p2: String, val p3: Long, val p4: Double, val p5: Boolean, val p6: Char,
  val p7: String?, val p8: Int, val p9: Int, val p10: Int, val p11: Int, val p12: Int,
)

class Mixed(val a: Int, b: Int, private val c: Int, var d: String) {
  val e = b * 2
  fun c(): Int = c
}

class Shadowed(b: Int) { val b: Int = b * 2 }

interface Emailed { val email: String }
class Contact(override val email: String, val name: String) : Emailed

open class Animal(open val name: String, val legs: Int)
class Dog(override val name: String, val tricks: List<String>) : Animal(name, 4)

class NoProps(x: Int) { val twice = x * 2 }
class SecondaryOnly {
  constructor(x: Int) { }
}

class JvmFields(@JvmField val a: Int, val b: String)

object Single { val one = 1 }

fun <T, V> propName(p: KProperty1<T, V>): String = p.name
fun <T, V> readProp(p: KProperty1<T, V>, x: T): V = p.get(x)
fun <T, V> writeProp(p: KMutableProperty1<T, V>, x: T, v: V) { p.set(x, v) }
infix fun <T, V> KProperty1<T, V>.eq(value: V): Pair<String, V> = name to value
fun className(c: KClass<*>): String = c.java.name
fun callRef(f: (Person, String) -> String, p: Person): String = f(p, "Yo")
fun fnName(f: KFunction<*>): String = f.name
fun make(ctor: (Long, String) -> Person2): Person2 = ctor(1L, "x")
fun isMutableRef(p: KProperty1<*, *>): Boolean = p is KMutableProperty1<*, *>
fun refOwnerName(p: KProperty1<*, *>): String = p.javaClass.superclass.simpleName
fun callFn2(f: (Int, Int) -> Int): Int = f(3, 4)
fun readProp0(p: kotlin.reflect.KProperty0<Int>): Int = p.get()
fun writeProp0(p: kotlin.reflect.KMutableProperty0<Int>, v: Int) { p.set(v) }
fun asFn1(f: (Person) -> String): String = f(Person(9L, "Zed"))
fun buildEq(): Pair<String, String> = Person::firstName eq "Ann"

// references that the Kotlin compiler makes: what kt/ref must agree with
fun kPersonFirstName(): KProperty1<Person, String> = Person::firstName
fun kPersonUid(): KProperty1<Person, Uid> = Person::uid
fun kGadgetLevel(): KMutableProperty1<Gadget, Int> = Gadget::level
fun kGadgetSecret(): KProperty1<Gadget, Int> = Gadget::secret
fun kWordCount(): KProperty1<String, Int> = String::wordCount
fun kFirstChar(): KMutableProperty1<StringBuilder, Char> = StringBuilder::firstChar
fun kTopVar(): kotlin.reflect.KMutableProperty0<Int> = ::topVar
fun kTopVal(): kotlin.reflect.KProperty0<Int> = ::topVal
fun kPersonClass(): KClass<Person> = Person::class
fun kGreet(): KFunction<String> = Person::greet
fun kGreetTop(): KFunction<String> = ::greet
fun kPerson2(): KFunction<Person2> = ::Person2

class CountMe(val a: Int, val b: String)
