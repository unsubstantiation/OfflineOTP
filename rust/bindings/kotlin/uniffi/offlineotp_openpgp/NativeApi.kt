package uniffi.offlineotp_openpgp

/**
 * Byte-array facade over the generated UniFFI functions.
 *
 * UniFFI 0.28 lowers `sequence<u8>` to `List<UByte>` on Kotlin. The JVM OpenPGP
 * ports speak `ByteArray`, so this small adapter keeps the conversion in one
 * place and gives the reflective loaders a stable, native-looking API. It is
 * compiled only where the generated bindings and JNA are on the classpath
 * (Gradle app/desktop builds); the no-Gradle sandbox intentionally omits it so
 * the ports exercise their explicit unavailable/fallback paths.
 */
object NativeApi {

    @JvmStatic
    fun isAvailable(): Boolean {
        return try {
            uniffi.offlineotp_openpgp.rpgpVersion()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun encrypt(plaintext: ByteArray, recipientKeyBlocks: List<ByteArray>): ByteArray {
        val recipients = recipientKeyBlocks.map { it.toUByteList() }
        return uniffi.offlineotp_openpgp.encrypt(plaintext.toUByteList(), recipients).toByteArray()
    }

    @JvmStatic
    fun decrypt(message: ByteArray, secretKeyBlock: ByteArray, passphrase: String?): ByteArray {
        return uniffi.offlineotp_openpgp.decrypt(
            message.toUByteList(),
            secretKeyBlock.toUByteList(),
            passphrase
        ).toByteArray()
    }

    @JvmStatic
    fun rpgpVersion(): String = uniffi.offlineotp_openpgp.rpgpVersion()

    @JvmStatic
    fun crateVersion(): String = uniffi.offlineotp_openpgp.crateVersion()

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }

    private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
}
