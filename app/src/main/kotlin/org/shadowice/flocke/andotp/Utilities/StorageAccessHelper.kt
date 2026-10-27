package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.net.Uri

import androidx.documentfile.provider.DocumentFile
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.function.BooleanSupplier

/** Bounded document reads and chunked writes through the Storage Access Framework. */
object StorageAccessHelper {
    /** Write granularity: the permission is consulted again before every chunk. */
    private const val WRITE_CHUNK_BYTES = 8192

    /** Legacy overload without a permission callback; provider writes are not atomic. */
    @JvmStatic
    fun saveFile(context: Context, file: Uri?, data: ByteArray?): Boolean =
        saveFile(context, file, data, BooleanSupplier { true })

    /**
     * Writes [data] to a `content:` document, re-checking [permitted] before opening, after
     * opening, before every chunk and once more after the flush. Invalid input, denied checks,
     * and caught provider I/O or runtime failures return `false`. Provider writes are not atomic;
     * automatic backups therefore use a new document.
     */
    @JvmStatic
    fun saveFile(context: Context, file: Uri?, data: ByteArray?, permitted: BooleanSupplier): Boolean {
        if (file == null || file.toString().length > InputPolicy.MAX_URI_CHARS || "content" != file.getScheme()
                || data == null || data.size > BoundedInputReader.MAX_DOCUMENT_BYTES || !permitted.getAsBoolean())
            return false
        return try {
            context.getContentResolver().openFileDescriptor(file, "wt").use { descriptor ->
                if (descriptor == null || !permitted.getAsBoolean()) return false
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    var offset = 0
                    while (offset < data.size) {
                        if (!permitted.getAsBoolean()) return false
                        output.write(data, offset, minOf(WRITE_CHUNK_BYTES, data.size - offset))
                        offset += WRITE_CHUNK_BYTES
                    }
                    output.flush()
                    permitted.getAsBoolean()
                }
            }
        } catch (error: IOException) {
            false
        } catch (error: RuntimeException) {
            false
        }
    }

    /** The display name of the document, falling back to the last path segment. */
    @JvmStatic
    fun getContentFileName(context: Context, uri: Uri?): String {
        if (uri == null || uri.toString().length > InputPolicy.MAX_URI_CHARS) return "backup"
        var result: String? = null
        if ("content" == uri.getScheme()) {
            try {
                context.getContentResolver().query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    .use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && !cursor.isNull(column)) result = cursor.getString(column)
                        }
                    }
            } catch (ignored: RuntimeException) {
                // Metadata is advisory, never a reason to crash.
            }
        }
        val name = result ?: uri.getLastPathSegment()
        if (name == null || name.length > InputPolicy.MAX_TEXT_CHARS) return "backup"
        // Enforce the length bound before stripping characters matched by \p{Cntrl}.
        return name.replace(Regex("[\\p{Cntrl}]"), "")
    }

    /** Requests deletion of a `content:` document; callers must restrict this to their own failed exports. */
    @JvmStatic
    fun deleteContentFile(context: Context, file: Uri?): Boolean {
        if (file == null || file.toString().length > InputPolicy.MAX_URI_CHARS || file.getScheme() != "content") return false
        return try {
            DocumentFile.fromSingleUri(context, file)?.delete() == true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Legacy UTF-8 overload without a permission callback. */
    @JvmStatic
    fun saveFile(context: Context, file: Uri?, data: String): Boolean =
        saveFile(context, file, data.toByteArray(StandardCharsets.UTF_8))

    /** Reads a `content:` document or private-cache file with a hard byte limit. */
    @JvmStatic
    @Throws(IOException::class)
    fun loadFile(context: Context, file: Uri?): ByteArray {
        if (file == null || file.toString().length > InputPolicy.MAX_URI_CHARS) throw IOException("Invalid document URI")
        // File URIs are for private cache fixtures only, not arbitrary caller-supplied paths.
        if ("content" != file.getScheme()) {
            val path = file.getPath()
            if ("file" != file.getScheme() || path == null
                    || !File(path).canonicalPath.startsWith(context.getCacheDir().canonicalPath + File.separator)) {
                throw IOException("Unsupported document URI")
            }
        }
        return context.getContentResolver().openInputStream(file).use { inputStream ->
            BoundedInputReader.read(inputStream, BoundedInputReader.MAX_DOCUMENT_BYTES)
        }
    }

    /** Reads the document as UTF-8 text, or "" if it cannot be read. */
    @JvmStatic
    fun loadFileString(context: Context, file: Uri?): String {
        return try {
            String(loadFile(context, file), StandardCharsets.UTF_8)
        } catch (error: IOException) {
            // No provider messages or document content in logs.
            ""
        } catch (error: RuntimeException) {
            ""
        }
    }
}
