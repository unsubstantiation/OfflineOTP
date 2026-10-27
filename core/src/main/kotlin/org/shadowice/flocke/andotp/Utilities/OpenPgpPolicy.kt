package org.shadowice.flocke.andotp.Utilities

/** Readiness policy for the external OpenPGP service; restore does not require recipients. */
data class OpenPgpAvailability(val canRestore: Boolean, val canEncrypt: Boolean) {
    companion object {
        @JvmStatic
        fun evaluate(providerConfigured: Boolean, serviceReady: Boolean, recipientCount: Int): OpenPgpAvailability {
            val ready = providerConfigured && serviceReady
            return OpenPgpAvailability(canRestore = ready, canEncrypt = ready && recipientCount > 0)
        }
    }
}

/** Reject empty/whitespace-only provider output before a backup document is created or reported. */
object OpenPgpOutputPolicy {
    @JvmStatic
    fun hasContent(output: String?): Boolean = !output.isNullOrBlank()
}
