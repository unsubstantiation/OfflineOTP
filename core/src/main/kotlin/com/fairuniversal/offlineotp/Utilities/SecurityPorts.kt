package com.fairuniversal.offlineotp.Utilities

/**
 * Platform port for the hardware-unlock envelope record. Only the authenticated envelope — an
 * [HardwareUnlockEnvelope] blob, Base64-encoded — is stored; the FIDO2 PRF output and the vault
 * password are never persisted. The Android adapter is a SharedPreferences entry, the desktop one
 * a file in its private data directory.
 */
interface HardwareUnlockStoragePort {
    /** Returns the validated envelope bytes, or null when none is enrolled. */
    fun readEnvelope(): ByteArray?

    /**
     * Validates and stores [envelope], rejecting anything past the fixed record bound. Returns
     * false when the envelope is malformed or too large.
     */
    fun writeEnvelope(envelope: ByteArray): Boolean

    /** Removes the record; a missing record is not an error. */
    fun removeEnvelope()

    companion object {
        /** Mirrors the Android preference record bound; an envelope is always far smaller. */
        const val MAX_ENCODED_BYTES = 2048
    }
}
