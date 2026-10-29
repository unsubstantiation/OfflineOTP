package org.shadowice.flocke.andotp.desktop.openpgp

import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.key.parsing.KeyRingReader
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.util.Passphrase
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [OpenPgpPort] backed by the embedded PGPainless 2.x library (BouncyCastle underneath). It speaks
 * standard OpenPGP with the shared backup JSON. Interchange also depends on the Android provider,
 * key capabilities and signature policy; desktop does not sign exports or verify signatures.
 *
 * Key material is passed as standard-format byte blocks (armored or binary), keeping every
 * BouncyCastle type inside this adapter. The class is compile-verified in the no-network sandbox
 * against narrow PGPainless stubs that mirror the published 2.0.4 API and exercised for real only
 * under the Gradle build that declares the dependency.
 */
class PgpainlessOpenPgpPort : OpenPgpPort {

    // PGPainless 2.x: the instance owns the OpenPGP implementation, the message builders and the
    // legacy-ring to BC api-type converters; the old static entry points are deprecated. Both
    // handles are lazy so merely constructing the port (e.g. a BackupCoordinator that never runs
    // an OpenPGP workflow) never touches the embedded library.
    private val api: PGPainless by lazy { PGPainless.getInstance() }
    private val keyRingReader: KeyRingReader by lazy { KeyRingReader() }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        var options = EncryptionOptions.encryptCommunications(api)
        for (block in recipientKeyBlocks) {
            val ring = ByteArrayInputStream(block).use {
                keyRingReader.publicKeyRing(it)
            } ?: throw GeneralSecurityException("No OpenPGP public key found in recipient block")
            val certificate: OpenPGPCertificate = api.toCertificate(ring)
            options = options.addRecipient(certificate)
        }

        val output = ByteArrayOutputStream()
        // Closing the stream finalizes framing and flushes the authentication tag.
        api.generateMessage()
            .onOutputStream(output)
            .withOptions(ProducerOptions.encrypt(options))
            .use { it.write(plaintext) }
        return output.toByteArray()
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
        val ring = ByteArrayInputStream(secretKeyBlock).use {
            keyRingReader.secretKeyRing(it)
        } ?: throw GeneralSecurityException("No OpenPGP secret key found in key block")
        val key: OpenPGPKey = api.toKey(ring)
        // PGPainless 2.x removed PasswordProtector: a Passphrase-backed protector unlocks any
        // passphrase-protected subkey; an unprotected key uses the empty-protector factory.
        val protector = if (passphrase == null) {
            SecretKeyRingProtector.unprotectedKeys()
        } else {
            SecretKeyRingProtector.unlockAnyKeyWith(Passphrase.fromPassword(String(passphrase)))
        }
        val options = ConsumerOptions(api).addDecryptionKey(key, protector)

        return api.processMessage()
            .onInputStream(ByteArrayInputStream(message))
            .withOptions(options)
            .use { stream -> BoundedInputReader.read(stream, BoundedInputReader.MAX_DOCUMENT_BYTES) }
    }
}
