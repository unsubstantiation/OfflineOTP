// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.core.content

import android.content.Context

/** Delegates to the context's own permission check, as the AndroidX helper does. */
object ContextCompat {
    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int = context.checkSelfPermission(permission)

    @JvmStatic
    fun getColor(context: Context, id: Int): Int = context.resources.getColor(id)
}
