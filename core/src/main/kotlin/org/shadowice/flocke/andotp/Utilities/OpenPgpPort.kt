package org.shadowice.flocke.andotp.Utilities

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Platform port for OpenPGP encryption and decryption. The Android app reaches an external
 * OpenPGP provider through intents, while the desktop client embeds PGPainless; both speak the
 * standard RFC 4880 message format, so a `.json.gpg` produced on one platform opens on the other.
 *
 * Key material crosses this boundary as opaque, standard-format byte blocks (an armored or binary
 * public certificate for recipients, a secret key ring for decryption), so no third-party PGP type
 * leaks into `:core`.
 */
interface OpenPgpPort {
    /**
     * Encrypts [plaintext] to every public certificate in [recipientKeyBlocks] and returns the
     * OpenPGP message bytes. Throws [GeneralSecurityException] for bad key material and
     * [IOException] for a provider/stream failure.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray

    /**
     * Decrypts an OpenPGP [message] with [secretKeyBlock]; [passphrase] is used only when the
     * secret key is passphrase-protected and may be null for an unprotected key.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray
}

/**
 * Bounded, policy-enforcing OpenPGP backup codec shared by both platforms. It owns the same
 * document limits and empty-output rules as the encrypted-backup path and leaves the actual PGP
 * cryptography to [OpenPgpPort]. The plaintext is the same compact vault/backup JSON document, so
 * only the outer envelope differs from the password backup format.
 */
object OpenPgpBackupCodec {

    /**
     * Produces an OpenPGP backup of [plain] for the given recipient certificates. Rejects an empty
     * recipient set, empty key blocks and oversized documents before touching the provider.
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class, IOException::class)
    fun seal(port: OpenPgpPort, plain: String, recipientKeyBlocks: List<ByteArray>): ByteArray {
        require(recipientKeyBlocks.isNotEmpty()) { "At least one OpenPGP recipient is required" }
        require(recipientKeyBlocks.none { it.isEmpty() }) { "An OpenPGP recipient key block is empty" }

        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        // Match the password vault/backup headroom rule: stay inside the bounded document window.
        require(plainBytes.size <= BoundedInputReader.MAX_DOCUMENT_BYTES - 64) { "OpenPGP backup too large" }

        val message = port.encrypt(plainBytes, recipientKeyBlocks)
        require(message.isNotEmpty()) { "OpenPGP provider returned no output" }
        return message
    }

    /**
     * Decrypts [message] back to its JSON document, rejecting empty messages, missing key material
     * and blank provider output (the historical [OpenPgpOutputPolicy] rule).
     */
    @JvmStatic
    @Throws(GeneralSecurityException::class, IOException::class)
    fun open(port: OpenPgpPort, message: ByteArray, secretKeyBlock: ByteArray,
             passphrase: CharArray?): String {
        require(message.isNotEmpty()) { "Empty OpenPGP message" }
        require(message.size <= BoundedInputReader.MAX_DOCUMENT_BYTES) { "OpenPGP message exceeds size limit" }
        require(secretKeyBlock.isNotEmpty()) { "Missing OpenPGP secret key" }

        val plainBytes = port.decrypt(message, secretKeyBlock, passphrase)
        val document = String(plainBytes, Charsets.UTF_8)
        require(OpenPgpOutputPolicy.hasContent(document)) { "OpenPGP provider returned blank content" }
        return document
    }
}
