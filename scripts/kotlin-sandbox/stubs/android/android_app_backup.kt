// Sandbox-only stand-ins, see android_content.kt.
//
// The platform backup agent helper is not part of the app any more (the platform backup service
// is disabled); the converted agent only refuses the restore callback. The stand-ins are inert.
package android.app.backup

import android.os.ParcelFileDescriptor

import java.io.IOException

/** Field/type-only stand-ins for the platform backup callbacks. */
open class BackupDataInput

open class BackupDataOutput

open class FullBackupDataOutput

/**
 * Stand-in for the platform backup agent helper. The callbacks do nothing; the converted agent
 * refuses the restore itself, which is the behaviour under test.
 */
open class BackupAgentHelper {
    open fun onCreate() {}

    open fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}

    open fun onFullBackup(data: FullBackupDataOutput?) {}

    @Throws(IOException::class)
    open fun onRestore(data: BackupDataInput?, version: Int, state: ParcelFileDescriptor?) {}
}
