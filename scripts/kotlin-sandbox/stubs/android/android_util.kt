// Sandbox-only stand-ins, see android_content.kt.
package android.util

/** Public density/xdpi fields are read by the converted code; the values are set by the checks. */
class DisplayMetrics {
    @JvmField var density: Float = 0f
    @JvmField var scaledDensity: Float = 0f
    @JvmField var xdpi: Float = 0f
    @JvmField var ydpi: Float = 0f
    @JvmField var widthPixels: Int = 0
    @JvmField var heightPixels: Int = 0
}

/**
 * Contains the platform's dimension constants and a *replication* of the documented
 * `applyDimension` formula (AOSP `TypedValue.applyDimension`). The replication is what makes the
 * density-dependent expectations in Checks.kt meaningful, so it is listed as an approximation in
 * the sandbox README rather than as verified platform behavior.
 */
class TypedValue {
    fun getFloat(): Float = 0f
    companion object {
        const val COMPLEX_UNIT_PX = 0
        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
        const val COMPLEX_UNIT_PT = 3
        const val COMPLEX_UNIT_IN = 4
        const val COMPLEX_UNIT_MM = 5

        @JvmStatic
        fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = when (unit) {
            COMPLEX_UNIT_PX -> value
            COMPLEX_UNIT_DIP -> value * metrics.density
            COMPLEX_UNIT_SP -> value * metrics.scaledDensity
            COMPLEX_UNIT_PT -> value * metrics.xdpi * (1.0f / 72)
            COMPLEX_UNIT_IN -> value * metrics.xdpi
            COMPLEX_UNIT_MM -> value * metrics.xdpi * (1.0f / 25.4f)
            else -> 0f
        }
    }
}

/** Sink for the task runner's error path; the checks never read logged text. */
object Log {
    @JvmStatic
    fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic
    fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic
    fun e(tag: String?, msg: String?): Int = 0
    @JvmStatic
    fun w(tag: String?, msg: String?): Int = 0
}

/**
 * Stand-in for `android.util.Base64`, with the documented flag constants.
 *
 * Replication, not an inert surface: the converted settings facade stores the authentication salt
 * and the credential verifier Base64-encoded, and the settings checks decode them again to compare
 * against an independent PBKDF2 derivation. For the flags the app uses (`DEFAULT`, `URL_SAFE`,
 * `NO_WRAP`, `NO_PADDING` combinations) the JDK's standard/URL-safe codecs produce the same
 * alphabet and padding. Not replicated: the platform decoder's leniency towards line breaks and its
 * exact invalid-input/exception behaviour, CRLF line wrapping, `NO_CLOSE`, and the `Base64` flags
 * beyond the bits modelled here.
 */
object Base64 {
    const val DEFAULT: Int = 0
    const val NO_PADDING: Int = 1
    const val NO_WRAP: Int = 2
    const val CRLF: Int = 4
    const val URL_SAFE: Int = 8
    const val NO_CLOSE: Int = 16

    @JvmStatic
    fun encode(input: ByteArray?, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray?, flags: Int): String {
        if (input == null) return ""
        return encoder(flags).encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String?, flags: Int): ByteArray {
        if (str == null) return ByteArray(0)
        return decoder(flags).decode(str)
    }

    @JvmStatic
    fun decode(input: ByteArray?, flags: Int): ByteArray {
        if (input == null) return ByteArray(0)
        return decode(String(input, Charsets.US_ASCII), flags)
    }

    private fun encoder(flags: Int): java.util.Base64.Encoder {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        return encoder
    }

    private fun decoder(flags: Int): java.util.Base64.Decoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
}

/**
 * Stand-in for the platform's atomic private-file transaction. It refuses every operation: the
 * real `FileHelper` runs up to this boundary and then fails with `UnsupportedOperationException`,
 * so a check that reaches the disk fails loudly. Nothing is read or written here, because
 * `Context.getFilesDir()` is the working directory in this sandbox.
 */
class AtomicFile(private val file: java.io.File) {
    fun delete(): Unit = unavailable()
    fun openRead(): java.io.InputStream = unavailable()
    fun startWrite(): java.io.FileOutputStream = unavailable()
    fun finishWrite(output: java.io.FileOutputStream?): Unit = unavailable()
    fun failWrite(output: java.io.FileOutputStream?): Unit = unavailable()

    private fun unavailable(): Nothing =
        throw UnsupportedOperationException("AtomicFile is not available in the sandbox: $file")
}

/**
 * The sparse array the intro slides map their spinner positions with. The stand-in keeps the
 * entries in a map; no sparse storage is modelled.
 */
class SparseArray<T> {
    private val values = LinkedHashMap<Int, T>()

    val size: Int get() = values.size

    fun put(key: Int, value: T) {
        values[key] = value
    }

    fun get(key: Int): T? = values[key]

    fun indexOfValue(value: T): Int {
        for ((key, entry) in values) if (entry == value) return key
        return -1
    }
}

/** XML attributes are not parsed by this harness. */
interface AttributeSet
