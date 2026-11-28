// Compile-only signatures. No clock or Foundation behavior is exercised on the JVM.
package platform.Foundation

class NSDate {
    val timeIntervalSince1970: Double get() = error("Foundation stub must not execute")
    companion object
}

fun NSDate.Companion.dateWithTimeIntervalSince1970(seconds: Double): NSDate =
    error("Foundation stub must not execute")
