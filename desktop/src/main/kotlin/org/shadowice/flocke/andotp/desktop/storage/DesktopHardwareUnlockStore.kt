package org.shadowice.flocke.andotp.desktop.storage

import org.shadowice.flocke.andotp.Utilities.HardwareUnlockEnvelope
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockStoragePort

import java.io.File
import java.util.Base64

/**
 * File-backed [HardwareUnlockStoragePort]. The record is one standard-Base64 line under the same
 * key the Android SharedPreferences adapter uses, holding an authenticated [HardwareUnlockEnvelope]
 * — never the FIDO2 PRF output or the vault password. Reads re-validate the envelope and writes
 * enforce the same 2048-byte bound, so a corrupt or oversized record is ignored rather than trusted.
 */
class DesktopHardwareUnlockStore(directory: File) : HardwareUnlockStoragePort {

    private val file = File(directory, RECORD_FILE)

    @Synchronized
    override fun readEnvelope(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val encoded = file.readText(Charsets.US_ASCII).trim()
            if (encoded.isEmpty() || encoded.length > HardwareUnlockStoragePort.MAX_ENCODED_BYTES) return null
            val bytes = Base64.getDecoder().decode(encoded)
            // Only accept a parseable envelope; mirrors the Android read() guard.
            if (HardwareUnlockEnvelope.request(bytes) == null) null else bytes
        } catch (error: RuntimeException) {
            null
        }
    }

    @Synchronized
    override fun writeEnvelope(envelope: ByteArray): Boolean {
        return try {
            if (HardwareUnlockEnvelope.request(envelope) == null) return false
            val encoded = Base64.getEncoder().encodeToString(envelope)
            if (encoded.length > HardwareUnlockStoragePort.MAX_ENCODED_BYTES) return false
            // Persist through the same atomic temp-and-move path as the settings store.
            AtomicFiles.writeAtomic(file, encoded.toByteArray(Charsets.US_ASCII))
            true
        } catch (error: RuntimeException) {
            false
        }
    }

    @Synchronized
    override fun removeEnvelope() {
        file.delete()
    }

    private companion object {
        const val RECORD_FILE = "hardware-unlock.b64"
    }
}
