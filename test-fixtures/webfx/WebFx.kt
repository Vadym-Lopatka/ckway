package webfx

import fx.Person
import web.query.*

// What Kotlin builds for `Person::firstName eq "Ann"`: the web tests compare it with (db/.eq (kt/ref f/Person firstName) "Ann")
fun eqAnn() = Person::firstName eq "Ann"
fun ilikeAnn() = Person::firstName ilike "A%"
fun gtId() = Person::id gt 5L
fun orBoth() = or(Person::firstName ilike "A%", Person::email ilike "A%")
fun andBoth() = and(Person::firstName eq "Ann", Person::id gt 5L)
