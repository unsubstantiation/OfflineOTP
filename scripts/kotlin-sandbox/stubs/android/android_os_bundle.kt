// Sandbox-only stand-ins, see android_content.kt.
package android.os

/**
 * Stand-in for the platform bundle.
 *
 * It is a field/type-only placeholder: the converted base activities only pass it through to
 * `super.onCreate()`. No parceling, no saved state and no configuration change is modelled.
 */
open class Bundle {
    /** The values that were put into the bundle. */
    val values: MutableMap<String, Any?> = LinkedHashMap()

    fun putString(key: String, value: String?) {
        values[key] = value
    }

    fun getString(key: String): String? = values[key] as? String

    fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue

    fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue

    fun putInt(key: String, value: Int) {
        values[key] = value
    }

    fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue

    fun putLong(key: String, value: Long) {
        values[key] = value
    }

    fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue

    fun putParcelable(key: String, value: Parcelable?) {
        values[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Parcelable> getParcelable(key: String): T? = values[key] as? T
}
