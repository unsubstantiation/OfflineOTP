package org.shadowice.flocke.andotp.Tasks

import org.shadowice.flocke.andotp.Utilities.OwnedSecret

enum class AuthenticationError { DENIED, INVALID_CONFIGURATION, LEGACY_MIGRATION_FAILED }

/** Credentials are only present in Verified; generation is the originating task's, not completion time. */
sealed class AuthenticationOutcome {
    class Verified(val key: OwnedSecret, val generation: Long) : AuthenticationOutcome(), AutoCloseable {
        override fun close() = key.close()
        override fun toString(): String = "AuthenticationOutcome.Verified(redacted)"
    }
    data class Rejected(val error: AuthenticationError) : AuthenticationOutcome()
    companion object {
        @JvmStatic fun verified(bytes: ByteArray, generation: Long): Verified = Verified(OwnedSecret.copyOf(bytes), generation)
    }
}
