// Compose Multiplatform dependency stub: kotlinx-coroutines.
//
// The sandbox has no coroutines jar, and the shared UI only needs the two declarations that
// `LaunchedEffect` and the countdown's `while` loop touch. `delay` never suspends here: nothing in
// the gate *runs* a composable, so the stub only has to make the call sites type-check.
package kotlinx.coroutines

interface CoroutineScope

suspend fun delay(millis: Long) {
    // No-op: the sandbox gate compiles composables, it never executes them.
}
