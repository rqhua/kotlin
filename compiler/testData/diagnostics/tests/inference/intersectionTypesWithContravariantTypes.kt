// !LANGUAGE: +NewInference
// !DIAGNOSTICS: -UNUSED_PARAMETER

class Inv<T>(val x: T)
class In<in R>

fun <K> foo(x: K, i: In<K>) {}
//fun <V> foo(x: Inv<V>, i: In<V>) {}

fun <S> materializeIn(s: S): In<S> = TODO()

fun test(i: Inv<Int>, l: Int) {
    foo(i, materializeIn(l))
}