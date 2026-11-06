package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.VaultJson

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Arrays
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted vault engine, shared by the Android file store's password mode and the
 * desktop client. It owns only portable work — Argon2id (and legacy PBKDF2) credentials, AES-GCM
 * vault framing and the [VaultJson] document — and reaches the platform through [AtomicByteStore]
 * and [KeyValueSettings] ports, so the on-disk formats are identical everywhere.
 *
 * On-disk layout:
 *  - `secrets.dat`: nonce-prefixed AES-256-GCM over the compact `VaultJson` document, keyed by the
 *    lower 256 bits (32 bytes) of the 512-bit Argon2id output (legacy PBKDF2 vaults use the lower
 *    128 bits of the 256-bit PBKDF2 output);
 *  - settings: `auth_kdf = "argon2id"` (absent or `"pbkdf2"` on legacy vaults), a 16-byte salt,
 *    the Argon2id memory/iteration/parallelism parameters (or legacy PBKDF2 iteration count), and
 *    the Base64 URL-safe verifier (the upper 256 bits for Argon2id, upper 128 bits for legacy
 *    PBKDF2), compared in constant time.
 *
 * Android KeyStore vaults have no portable equivalent and are intentionally out of scope here.
 */
class PasswordVault(
    private val vaultFile: AtomicByteStore,
    private val backupFile: AtomicByteStore,
    private val settings: KeyValueSettings
) {
    /** Outcome of [open]: unlocked entries plus the session key, or a typed failure. */
    sealed class Open {
        class Unlocked(val entries: ArrayList<Entry>, val key: SecretKey) : Open()
        data class Failed(val error: VaultReadError) : Open()
    }

    fun isInitialized(): Boolean = vaultFile.exists()

    /**
     * The client's key/value settings beside the vault (the same file that holds the salt and
     * verifier). Only client preferences such as the list sort order belong there; this class
     * keeps ownership of the vault's own keys.
     */
    val preferences: KeyValueSettings get() = settings

    /**
     * Provisions fresh PBKDF2 credentials and writes an empty encrypted vault. Fails with
     * [VaultWriteError.ALREADY_EXISTS] if a vault or backup is already present.
     */
    fun create(password: String): VaultWriteResult {
        if (vaultFile.exists() || backupFile.exists())
            return VaultWriteResult.Rejected(VaultWriteError.ALREADY_EXISTS)
        return try {
            require(password.isNotEmpty() && password.length <= InputPolicy.MAX_TEXT_CHARS) {
                "Invalid vault password"
            }
            val key = provisionCredentials(password)
            writeEncrypted(key, ArrayList())
            VaultWriteResult.Saved
        } catch (error: IllegalArgumentException) {
            VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
        } catch (error: Exception) {
            VaultWriteResult.Rejected(VaultWriteError.IO_FAILURE)
        }
    }

    /**
     * Verifies [password] against the stored verifier and decrypts the vault. A wrong password
     * maps to [VaultReadError.AUTHENTICATION_FAILED]; missing/malformed settings or documents to
     * the same error categories [com.fairuniversal.offlineotp.Utilities.DatabaseHelper] uses.
     */
    fun open(password: String): Open {
        if (!vaultFile.exists()) return Open.Failed(VaultReadError.MISSING)

        val key: SecretKey = try {
            deriveSessionKey(password)
        } catch (error: BadPaddingException) {
            return Open.Failed(VaultReadError.AUTHENTICATION_FAILED)
        } catch (error: GeneralSecurityException) {
            return Open.Failed(VaultReadError.IO_FAILURE)
        } catch (error: Exception) {
            return Open.Failed(VaultReadError.CORRUPT)
        }

        var plain: ByteArray? = null
        return try {
            plain = CryptoPrimitives.decrypt(key, vaultFile.read())
            Open.Unlocked(VaultJson.stringToEntries(String(plain, StandardCharsets.UTF_8)), key)
        } catch (error: BadPaddingException) {
            Open.Failed(VaultReadError.AUTHENTICATION_FAILED)
        } catch (error: IOException) {
            Open.Failed(VaultReadError.IO_FAILURE)
        } catch (error: Exception) {
            Open.Failed(VaultReadError.CORRUPT)
        } finally {
            plain?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Opens the vault with an already-derived [key], the hardware-unlock counterpart of [open]:
     * no password verification is possible, so GCM authentication alone gates access. The error
     * categories match [open] so callers map them identically. The key is never stored here.
     */
    fun openWithKey(key: SecretKey): Open {
        if (!vaultFile.exists()) return Open.Failed(VaultReadError.MISSING)

        var plain: ByteArray? = null
        return try {
            plain = CryptoPrimitives.decrypt(key, vaultFile.read())
            Open.Unlocked(VaultJson.stringToEntries(String(plain, StandardCharsets.UTF_8)), key)
        } catch (error: BadPaddingException) {
            Open.Failed(VaultReadError.AUTHENTICATION_FAILED)
        } catch (error: IOException) {
            Open.Failed(VaultReadError.IO_FAILURE)
        } catch (error: Exception) {
            Open.Failed(VaultReadError.CORRUPT)
        } finally {
            plain?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /** Serializes [entries], encrypts them under [key] and promotes the vault atomically. */
    fun save(key: SecretKey, entries: ArrayList<Entry>?): VaultWriteResult {
        return try {
            writeEncrypted(key, entries)
            VaultWriteResult.Saved
        } catch (error: VaultJson.MalformedVaultException) {
            VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
        } catch (error: IllegalArgumentException) {
            VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
        } catch (error: Exception) {
            VaultWriteResult.Rejected(VaultWriteError.IO_FAILURE)
        }
    }

    /** Copies the current vault bytes to the backup store through its own atomic promotion. */
    fun backup(): Boolean = try {
        backupFile.writeAtomic(vaultFile.read())
        true
    } catch (error: RuntimeException) {
        false
    } catch (error: IOException) {
        false
    }

    /** Promotes the backup bytes back over the vault. */
    fun restoreBackup(): Boolean = try {
        vaultFile.writeAtomic(backupFile.read())
        true
    } catch (error: RuntimeException) {
        false
    } catch (error: IOException) {
        false
    }

    /**
     * Generates the Argon2id salt/verifier/parameter settings for a new password and returns the
     * matching AES key.
     */
    private fun provisionCredentials(password: String): SecretKey {
        val salt = CryptoPrimitives.generateRandom(Constants.ARGON2_SALT_LENGTH)
        val memoryKiB = Constants.ARGON2_MEMORY_KIB
        val iterations = Constants.ARGON2_ITERATIONS
        val parallelism = Constants.ARGON2_PARALLELISM
        val derived = CryptoPrimitives.generateArgon2idCredentials(
            password = password,
            salt = salt,
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism
        )
        try {
            settings.putString(VaultSettingsKeys.AUTH_METHOD,
                Constants.AuthMethod.PASSWORD.name.lowercase())
            settings.putString(VaultSettingsKeys.ENCRYPTION,
                Constants.EncryptionType.PASSWORD.name.lowercase())
            settings.putString(VaultSettingsKeys.AUTH_KDF, Constants.KDF_ARGON2ID)
            settings.putString(VaultSettingsKeys.AUTH_CREDENTIALS, base64Url(derived.password))
            settings.putString(VaultSettingsKeys.AUTH_SALT, base64Url(salt))
            settings.putInt(VaultSettingsKeys.AUTH_MEMORY_KIB, memoryKiB)
            settings.putInt(VaultSettingsKeys.AUTH_ITERATIONS, iterations)
            settings.putInt(VaultSettingsKeys.AUTH_PARALLELISM, parallelism)
            // SecretKeySpec copies the bytes, so wiping the derived material leaves the key intact.
            return SecretKeySpec(derived.key, "AES")
        } finally {
            Arrays.fill(derived.password, 0.toByte())
            Arrays.fill(derived.key, 0.toByte())
        }
    }

    private fun deriveSessionKey(password: String): SecretKey {
        val salt = base64UrlDecode(settings.getString(VaultSettingsKeys.AUTH_SALT, ""))
        val verifier = base64UrlDecode(settings.getString(VaultSettingsKeys.AUTH_CREDENTIALS, ""))
        if (salt.isEmpty() || verifier.isEmpty()) throw IllegalStateException("Missing vault credentials")

        val kdf = settings.getString(VaultSettingsKeys.AUTH_KDF, Constants.KDF_PBKDF2)
            .trim()
            .lowercase()
            .ifEmpty { Constants.KDF_PBKDF2 }

        val derived = when (kdf) {
            Constants.KDF_ARGON2ID -> {
                val memoryKiB = settings.getInt(VaultSettingsKeys.AUTH_MEMORY_KIB, Constants.ARGON2_MEMORY_KIB)
                val iterations = settings.getInt(VaultSettingsKeys.AUTH_ITERATIONS, Constants.ARGON2_ITERATIONS)
                val parallelism = settings.getInt(VaultSettingsKeys.AUTH_PARALLELISM, Constants.ARGON2_PARALLELISM)
                CryptoPrimitives.generateArgon2idCredentials(
                    password = password,
                    salt = salt,
                    memoryKiB = memoryKiB,
                    iterations = iterations,
                    parallelism = parallelism
                )
            }
            Constants.KDF_PBKDF2 -> {
                val iterations = settings.getInt(VaultSettingsKeys.AUTH_ITERATIONS,
                    Constants.PBKDF2_MIN_AUTH_ITERATIONS)
                CryptoPrimitives.generatePBKDF2Credentials(password, salt, iterations)
            }
            else -> throw IllegalStateException("Unsupported vault KDF: $kdf")
        }
        try {
            if (!MessageDigest.isEqual(verifier, derived.password))
                throw BadPaddingException("Vault password verification failed")
            return SecretKeySpec(derived.key, "AES")
        } finally {
            Arrays.fill(derived.password, 0.toByte())
            Arrays.fill(derived.key, 0.toByte())
        }
    }

    private fun writeEncrypted(key: SecretKey, entries: ArrayList<Entry>?) {
        val plain = VaultJson.entriesToString(entries).toByteArray(StandardCharsets.UTF_8)
        try {
            // Match DatabaseHelper.encryptedEntries: leave room for nonce/tag and the staged UUID.
            if (plain.size > BoundedInputReader.MAX_DOCUMENT_BYTES - 64)
                throw IllegalArgumentException("Vault too large")
            vaultFile.writeAtomic(CryptoPrimitives.encrypt(key, plain))
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)

    private fun base64UrlDecode(value: String): ByteArray =
        if (value.isEmpty()) ByteArray(0) else Base64.getUrlDecoder().decode(value)
}
