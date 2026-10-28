/*
 * Compile-only stand-in for javax.smartcardio.TerminalFactory. The sandbox JRE-like runtime omits
 * the java.smartcardio module, but every desktop target JDK (and the jpackage runtime) includes it,
 * so this stub exists only to compile the PC/SC discovery adapter in the no-network gate. It is on
 * the desktop gate's classpath only; :core never references smartcardio.
 */
package javax.smartcardio

open class TerminalFactory {
    companion object {
        @JvmStatic
        fun getDefault(): TerminalFactory =
            throw NotImplementedError("sandbox stub; java.smartcardio ships with the target JDK")
    }
}
