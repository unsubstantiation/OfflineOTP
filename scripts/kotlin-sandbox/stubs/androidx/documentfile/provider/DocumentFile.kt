// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.documentfile.provider

import android.content.Context
import android.net.Uri

import java.io.File

/** Tree/document stand-in with the factory and create/find surface the backup helper resolves. */
abstract class DocumentFile {
    abstract fun getUri(): Uri
    abstract fun delete(): Boolean
    open fun canWrite(): Boolean = false
    open fun findFile(name: String?): DocumentFile? = null
    open fun createFile(mimeType: String?, name: String?): DocumentFile? = null
    open fun exists(): Boolean = false

    companion object {
        /** Optional fake tree factory for sandbox tests; null preserves the compile-only default. */
        var treeFactory: ((Context?, Uri?) -> DocumentFile?)? = null

        @JvmStatic
        fun fromFile(file: File?): DocumentFile = object : DocumentFile() {
            override fun getUri(): Uri = Uri.EMPTY
            override fun delete(): Boolean = false
        }

        @JvmStatic
        fun fromTreeUri(context: Context?, uri: Uri?): DocumentFile? = treeFactory?.invoke(context, uri)

        @JvmStatic
        fun fromSingleUri(context: Context?, uri: Uri?): DocumentFile? = null
    }
}
