// Sandbox-only stand-ins, see android_content.kt.
package android.net

/**
 * Stand-in for `android.net.Uri`.
 *
 * `parse`/`fromFile` stay accessor-level inert values: the checked sources never read back a parsed
 * URI, they only route on scheme/last-segment. `Builder`/`build` are a *replication* instead,
 * because the entry model's `toUri()` is executed here: it assembles a scheme, an authority, one
 * path segment and query parameters, and the checks read those parameters back. Values are stored
 * decoded and rendered percent-encoded over the unreserved set, which is what `appendQueryParameter`
 * does on the platform; the platform's exact escaping, hierarchy and normalization rules are not
 * replicated. See ../../README.md.
 */
class Uri internal constructor(
    private val text: String? = null,
    private val schemeValue: String? = null,
    private val authorityValue: String? = null,
    private val pathValue: String? = null,
    private val parameters: List<Pair<String, String>> = emptyList()
) : android.os.Parcelable {
    override fun toString(): String = text ?: ""
    // Android Uri equality compares its encoded representation, including separately parsed copies.
    override fun equals(other: Any?): Boolean = other is Uri && toString() == other.toString()
    override fun hashCode(): Int = toString().hashCode()

    fun getScheme(): String? = schemeValue

    fun getAuthority(): String? = authorityValue

    fun getPath(): String? = pathValue

    fun getLastPathSegment(): String? =
        pathValue?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /** The first decoded value of the named query parameter, or null. */
    fun getQueryParameter(key: String?): String? =
        parameters.firstOrNull { it.first == key }?.second

    /** Every decoded value of the named query parameter, in insertion order. */
    fun getQueryParameters(key: String?): List<String> =
        parameters.filter { it.first == key }.map { it.second }

    fun getQuery(): String? =
        parameters.takeIf { it.isNotEmpty() }?.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

    fun buildUpon(): Builder = Builder()

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private val pathSegments = ArrayList<String>()
        private val parameters = ArrayList<Pair<String, String>>()

        fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }

        fun authority(authority: String?): Builder = apply { this.authority = authority }

        fun appendPath(path: String?): Builder = apply { if (path != null) pathSegments.add(path) }

        fun opaquePart(part: String?): Builder = this

        fun encodedOpaquePart(part: String?): Builder = this

        fun appendQueryParameter(key: String?, value: String?): Builder = apply {
            if (key != null) parameters.add(key to (value ?: ""))
        }

        fun clearQuery(): Builder = apply { parameters.clear() }

        fun build(): Uri = Uri(render(), scheme, authority, renderPath(), parameters)

        private fun renderPath(): String =
            pathSegments.joinToString("/") { encode(it) }.let { if (it.isEmpty()) "" else "/$it" }

        private fun render(): String = buildString {
            append(scheme ?: "")
            append(':')
            if (authority != null) {
                append("//")
                append(authority)
            }
            append(renderPath())
            if (parameters.isNotEmpty()) {
                append('?')
                append(parameters.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" })
            }
        }
    }

    companion object {
        @JvmStatic
        val EMPTY: Uri = Uri("stub:uri")

        @JvmStatic
        fun parse(uri: String?): Uri = Uri(uri)

        @JvmStatic
        fun fromFile(file: java.io.File?): Uri = Uri(file?.path)

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        internal fun encode(value: String): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val character = (byte.toInt() and 0xFF).toChar()
                if (UNRESERVED.indexOf(character) >= 0) out.append(character)
                else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }
    }
}
