package org.shadowice.flocke.andotp.desktop.openpgp

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.key.protection.PasswordProtector
import org.shadowice.flocke.andotp.Utilities.OpenPgpPort

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [OpenPgpPort] backed by the embedded PGPainless library (BouncyCastle underneath). It speaks
 * standard OpenPGP, so the messages it produces are the same `.json.gpg` documents an Android
 * OpenKeychain-backed install writes, and vice versa.
 *
 * Key material is passed as standard-format byte blocks (armored or binary), keeping every
 * BouncyCastle type inside this adapter. The class is compile-verified in the no-network sandbox
 * against narrow PGPainless stubs and exercised for real only under the Gradle build that declares
 * the dependency.
 */
class PgpainlessOpenPgpPort : OpenPgpPort {

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        var options = EncryptionOptions.encryptCommunications()
        for (block in recipientKeyBlocks) {
            val certificate: PGPPublicKeyRing = ByteArrayInputStream(block).use {
                PGPainless.readKeyRing().publicKeyRing(it)
            }
            options = options.addRecipient(certificate)
        }

        val output = ByteArrayOutputStream()
        // Closing the stream finalizes framing and flushes the authentication tag.
        PGPainless.encryptAndOrSign()
            .onOutputStream(output)
            .withOptions(ProducerOptions.encrypt(options))
            .use { it.write(plaintext) }
        return output.toByteArray()
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    override fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: CharArray?): ByteArray {
        val secretKeys = ByteArrayInputStream(secretKeyBlock).use {
            PGPainless.readKeyRing().secretKeyRing(it)
        }
        val options = ConsumerOptions.get().let { base ->
            if (passphrase == null) base.addDecryptionKey(secretKeys)
            else base.addDecryptionKey(
                secretKeys, PasswordProtector.forKey(secretKeys).withPassphrase(passphrase))
        }

        val output = ByteArrayOutputStream()
        PGPainless.decryptAndOrVerify()
            .onInputStream(ByteArrayInputStream(message))
            .withOptions(options)
            .use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
            }
        return output.toByteArray()
    }
}
