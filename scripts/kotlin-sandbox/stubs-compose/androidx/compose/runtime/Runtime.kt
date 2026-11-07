// Compose Multiplatform dependency stub: androidx.compose.runtime.
//
// These declarations exist so `ui/src/commonMain` can be type-checked without the Compose
// artifacts, which the sandbox cannot download. The shapes mirror the real API closely enough that
// a call site which compiles here compiles in Gradle, but nothing executes: there is no composer,
// no snapshot state and no recomposition. This is a compile gate, not a runtime.
package androidx.compose.runtime

import kotlin.reflect.KProperty

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class Composable

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
}

fun <T> mutableStateOf(value: T): MutableState<T> = object : MutableState<T> {
    override var value: T = value
}

fun <T> remember(calculation: () -> T): T = calculation()

fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()

fun LaunchedEffect(key1: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    // The real effect starts a coroutine; the stub does nothing.
}

fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {
    // No-op in the gate.
}

class DisposableEffectScope {
    fun onDispose(onDisposeEffect: () -> Unit): DisposableEffectResult = DisposableEffectResult {}
}

class DisposableEffectResult(private val dispose: () -> Unit)

inline operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

inline operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}
