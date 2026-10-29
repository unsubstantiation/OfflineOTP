/*
 * Compile-only stand-in for javax.smartcardio.TerminalFactory. The Kotlin JVM compiler only exposes
 * the JDK modules that `java.se` aggregates, and `java.smartcardio` is not one of them, so the
 * type cannot be resolved from Kotlin even on a full JDK. Production code therefore only uses
 * yubikit's javax-free PcscManager() constructor; this stub keeps the sandbox's PcscManager stand-in
 * (which mirrors the real default constructor) compiling. It is on the desktop gate's classpath
 * only; :core never references smartcardio.
 */
package javax.smartcardio

open class TerminalFactory {
    companion object {
        @JvmStatic
        fun getDefault(): TerminalFactory =
            throw NotImplementedError("sandbox stub; java.smartcardio ships with the target JDK")
    }
}
