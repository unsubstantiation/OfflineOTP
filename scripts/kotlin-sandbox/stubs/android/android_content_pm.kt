// Sandbox-only stand-ins, see android_content.kt.
package android.content.pm

/**
 * Constant-only stand-in for permission-check results and package metadata. Only members used by
 * the compiled app/tests are declared; this is not PackageManager behavior.
 */
object PackageManager {
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1
    const val SIGNATURE_NO_MATCH = -3

    fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo = ApplicationInfo()
    fun checkSignatures(firstPackage: String, secondPackage: String): Int = SIGNATURE_NO_MATCH
    fun getPermissionInfo(permissionName: String, flags: Int): PermissionInfo = PermissionInfo()
    fun checkPermission(permissionName: String, packageName: String): Int = PERMISSION_DENIED

    /** The metadata [getPackageInfo] serves; the platform resolves it from installed packages. */
    var packageInfo: PackageInfo = PackageInfo()
    var missingPackage: String? = null

    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName == missingPackage) throw NameNotFoundException(packageName)
        return packageInfo
    }

    class NameNotFoundException(name: String) : Exception(name)
}

class ApplicationInfo {
    @JvmField var uid: Int = 0
}

class PermissionInfo {
    @JvmField var protectionLevel: Int = PROTECTION_SIGNATURE

    companion object {
        const val PROTECTION_SIGNATURE = 2
        const val PROTECTION_MASK_BASE = 0xF
    }
}

/** The version metadata of one package; the platform also fills the requested flags' fields. */
class PackageInfo {
    @JvmField var versionName: String? = null
}


/** Stand-in for the platform activity metadata: only the orientation constants are used. */
class ActivityInfo {
    companion object {
        /** The manifest value that lets the device orientation decide (no lock). */
        const val SCREEN_ORIENTATION_UNSPECIFIED: Int = -1
    }
}
