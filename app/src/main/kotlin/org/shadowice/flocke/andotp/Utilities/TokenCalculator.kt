package org.shadowice.flocke.andotp.Utilities

import org.apache.commons.codec.binary.Hex

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Generates one-time passwords: TOTP/HOTP (RFC 6238/4226), Steam Guard and mOTP. */
object TokenCalculator {
    // Defaults used when an entry or otpauth URI does not specify them
    const val TOTP_DEFAULT_PERIOD = 30
    const val TOTP_DEFAULT_DIGITS = 6
    const val HOTP_INITIAL_COUNTER = 1
    const val STEAM_DEFAULT_DIGITS = 5
    /** mOTP rolls over every 10 seconds, independent of the period stored on the entry. */
    const val MOTP_STEP_SECONDS = 10

    // Steam's token alphabet (excludes easily confused characters)
    private val steamChars = charArrayOf(
            '2', '3', '4', '5', '6', '7', '8', '9', 'B', 'C',
            'D', 'F', 'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'T', 'V', 'W', 'X', 'Y'
    )

    /** Hash algorithms usable for the HMAC part of TOTP/HOTP. */
    enum class HashAlgorithm {
        SHA1, SHA256, SHA512
    }

    @JvmField
    val DEFAULT_ALGORITHM: HashAlgorithm = HashAlgorithm.SHA1

    /** HMAC of {@code data} with the given algorithm and key. */
    private fun generateHash(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
        val algo = "Hmac" + algorithm.toString()

        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key, algo))

        return mac.doFinal(data)
    }

    /** Legacy Java-compatible overload without a time-step offset; retained for existing callers. */
    @JvmStatic
    fun TOTP_RFC6238(secret: ByteArray, period: Int, time: Long, digits: Int, algorithm: HashAlgorithm): Int {
        return TOTP_RFC6238(secret, period, time, digits, algorithm, 0)
    }

    /** Computes the TOTP for the given unix time; {@code offset} shifts the time step. */
    @JvmStatic
    fun TOTP_RFC6238(secret: ByteArray, period: Int, time: Long, digits: Int, algorithm: HashAlgorithm, offset: Int): Int {
        InputPolicy.tokenParameters(period, digits, 0)
        val fullToken = TOTP(secret, period, time, algorithm, offset)
        val div = Math.pow(10.0, digits.toDouble()).toInt()

        return fullToken % div
    }

    /** Returns the current TOTP as a zero-padded string. */
    @JvmStatic
    fun TOTP_RFC6238(secret: ByteArray, period: Int, digits: Int, algorithm: HashAlgorithm, offset: Int): String {
        return Tools.formatTokenString(
                TOTP_RFC6238(secret, period, System.currentTimeMillis() / 1000, digits, algorithm, offset), digits)
    }

    /** Steam Guard variant of TOTP, mapped onto Steam's custom alphabet. */
    @JvmStatic
    fun TOTP_Steam(secret: ByteArray, period: Int, digits: Int, algorithm: HashAlgorithm, offset: Int): String =
        TOTP_SteamAt(secret, period, System.currentTimeMillis() / 1000, digits, algorithm, offset)

    /** Internal explicit-time variant used to keep entry updates on one clock sample. */
    internal fun TOTP_SteamAt(secret: ByteArray, period: Int, time: Long, digits: Int,
                              algorithm: HashAlgorithm, offset: Int): String {
        var fullToken = TOTP(secret, period, time, algorithm, offset)

        val tokenBuilder = StringBuilder()
        for (i in 0 until digits) {
            tokenBuilder.append(steamChars[fullToken % steamChars.size])
            fullToken /= steamChars.size
        }
        return tokenBuilder.toString()
    }

    /** Returns the HOTP for the given counter as a zero-padded string. */
    @JvmStatic
    fun HOTP(secret: ByteArray, counter: Long, digits: Int, algorithm: HashAlgorithm): String {
        InputPolicy.tokenParameters(TOTP_DEFAULT_PERIOD, digits, counter)
        val fullToken = HOTP(secret, counter, algorithm)
        val div = Math.pow(10.0, digits.toDouble()).toInt()

        return Tools.formatTokenString(fullToken % div, digits)
    }

    /** The raw (untruncated) TOTP for the given time step. */
    private fun TOTP(key: ByteArray, period: Int, time: Long, algorithm: HashAlgorithm, offset: Int): Int {
        return HOTP(key, time / period + offset, algorithm)
    }

    /** The raw (untruncated) HOTP for the given counter. */
    private fun HOTP(key: ByteArray, counter: Long, algorithm: HashAlgorithm): Int {
        var r = 0

        try {
            val data = ByteBuffer.allocate(8).putLong(counter).array()
            val hash = generateHash(algorithm, key, data)

            val offset = hash[hash.size - 1].toInt() and 0xF

            var binary = (hash[offset].toInt() and 0x7F) shl 0x18
            binary = binary or ((hash[offset + 1].toInt() and 0xFF) shl 0x10)
            binary = binary or ((hash[offset + 2].toInt() and 0xFF) shl 0x08)
            binary = binary or (hash[offset + 3].toInt() and 0xFF)

            r = binary
        } catch (e: Exception) {
            throw IllegalStateException("OTP calculation failed")
        }

        return r
    }

    /** Legacy mOTP: first 6 hex digits of MD5(epoch/10 + offset + secret + PIN). */
    @JvmStatic
    fun MOTP(PIN: String, secret: String, epoch: Long, offset: Int): String {
        val epochText = ((epoch / MOTP_STEP_SECONDS) + offset).toString()
        val hashText = epochText + secret + PIN
        var otp = ""

        try {
            // Create MD5 Hash
            val digest = MessageDigest.getInstance("MD5")
            digest.update(hashText.toByteArray())
            val messageDigest = digest.digest()

            // Create Hex String
            val hexString = String(Hex.encodeHex(messageDigest))
            otp = hexString.substring(0, 6)
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }

        return otp
    }
}
