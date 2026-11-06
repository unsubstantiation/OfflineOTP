/*
 * Deterministic generator for the desktop golden interop fixtures under
 * desktop/src/test/resources/golden/. It is intentionally plain Kotlin (no JUnit, no Gradle) so
 * scripts/packaging/generate-golden-fixtures.sh can compile it against the sandbox-built :core
 * output and the Kotlin stdlib. All salts, nonces and iteration counts are fixed vectors (see
 * golden/README.md); the only randomness in the real codecs is replaced here so regenerated
 * fixtures are byte-identical to the checked-in blobs.
 *
 * Usage: GoldenFixturesGeneratorKt <source-resources-dir> <output-dir>
 *   source-resources-dir holds the canonical expected-vault.json (the shared plaintext)
 *   output-dir receives the generator's fixture files, overwriting any previous copy
 */
package org.shadowice.flocke.andotp.packaging

import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.CryptoPrimitives
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.Properties
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val PASSWORD = "golden-interop-password"
private const val VAULT_ITERATIONS = 4096
private const val BACKUP_ITERATIONS = 140000

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: GoldenFixturesGeneratorKt <source-resources-dir> <output-dir>" }
    val source = Paths.get(args[0])
    val output = Paths.get(args[1])
    Files.createDirectories(output)

    val plaintext = Files.readAllBytes(source.resolve("expected-vault.json"))

    // Vault: PBKDF2-HMAC-SHA1 over salt 00..0f, lower half is the AES key, upper half the verifier.
    val vaultSalt = byteRange(0x00, 16)
    val vaultNonce = byteRange(0x10, 12)
    val vaultCredentials = CryptoPrimitives.generatePBKDF2Credentials(PASSWORD, vaultSalt, VAULT_ITERATIONS)
    val vaultKey = SecretKeySpec(vaultCredentials.key, "AES")
    val secrets = vaultNonce +
        CryptoPrimitives.encrypt(vaultKey, IvParameterSpec(vaultNonce), plaintext)
    write(output.resolve("secrets.dat"), secrets)

    val properties = Properties().apply {
        setProperty("auth_method", "password")
        setProperty("encryption", "password")
        setProperty("auth_credentials", Base64.getUrlEncoder().encodeToString(vaultCredentials.password))
        setProperty("auth_salt", Base64.getUrlEncoder().encodeToString(vaultSalt))
        setProperty("auth_iterations", VAULT_ITERATIONS.toString())
    }
    write(output.resolve("vault.properties"), deterministicProperties(properties))

    // Current backup frame: 4-byte BE iterations, 12-byte salt, then nonce-prefixed AES-GCM.
    val backupSalt = byteRange(0x20, 12)
    val backupNonce = byteRange(0x30, 12)
    val backupKey = CryptoPrimitives.generateSymmetricKeyPBKDF2(PASSWORD, BACKUP_ITERATIONS, backupSalt)
    val backupCipher = CryptoPrimitives.encrypt(backupKey, IvParameterSpec(backupNonce), plaintext)
    val backup = ByteBuffer
        .allocate(4 + backupSalt.size + backupNonce.size + backupCipher.size)
        .putInt(BACKUP_ITERATIONS)
        .put(backupSalt)
        .put(backupNonce)
        .put(backupCipher)
        .array()
    write(output.resolve("otp_accounts.json.aes"), backup)

    // Argon2id vault (64 MiB, t=3, p=4): salt 00..0f, GCM nonce 10..1b, lower half = AES key, upper half = verifier.
    val argonVaultCredentials = CryptoPrimitives.generateArgon2idCredentials(
        password = PASSWORD,
        salt = vaultSalt,
        memoryKiB = Constants.ARGON2_MEMORY_KIB,
        iterations = Constants.ARGON2_ITERATIONS,
        parallelism = Constants.ARGON2_PARALLELISM
    )
    val argonVaultKey = SecretKeySpec(argonVaultCredentials.key, "AES")
    val argonSecrets = vaultNonce +
        CryptoPrimitives.encrypt(argonVaultKey, IvParameterSpec(vaultNonce), plaintext)
    write(output.resolve("argon2id-secrets.dat"), argonSecrets)

    val argonProperties = Properties().apply {
        setProperty("auth_method", "password")
        setProperty("encryption", "password")
        setProperty("auth_kdf", Constants.KDF_ARGON2ID)
        setProperty("auth_credentials", Base64.getUrlEncoder().encodeToString(argonVaultCredentials.password))
        setProperty("auth_salt", Base64.getUrlEncoder().encodeToString(vaultSalt))
        setProperty("auth_memory_kib", Constants.ARGON2_MEMORY_KIB.toString())
        setProperty("auth_iterations", Constants.ARGON2_ITERATIONS.toString())
        setProperty("auth_parallelism", Constants.ARGON2_PARALLELISM.toString())
    }
    write(output.resolve("argon2id-vault.properties"), deterministicProperties(argonProperties))

    // Argon2id backup frame: "OOTP" || 0x01 || BE32(65536) || BE32(3) || 0x04 || 16B salt || nonce-prefixed AES-GCM.
    val argonBackupSalt = byteRange(0x40, Constants.ARGON2_SALT_LENGTH)
    val argonBackupNonce = byteRange(0x50, Constants.ENCRYPTION_IV_LENGTH)
    val argonBackupKey = CryptoPrimitives.generateSymmetricKeyArgon2id(
        password = PASSWORD,
        salt = argonBackupSalt,
        memoryKiB = Constants.ARGON2_MEMORY_KIB,
        iterations = Constants.ARGON2_ITERATIONS,
        parallelism = Constants.ARGON2_PARALLELISM
    )
    val argonBackupCipher = CryptoPrimitives.encrypt(argonBackupKey, IvParameterSpec(argonBackupNonce), plaintext)
    val argonBackup = EncryptedBackupHeader.frameArgon2id(
        memoryKiB = Constants.ARGON2_MEMORY_KIB,
        iterations = Constants.ARGON2_ITERATIONS,
        parallelism = Constants.ARGON2_PARALLELISM,
        salt = argonBackupSalt,
        encrypted = argonBackupNonce + argonBackupCipher
    )
    write(output.resolve("argon2id-otp_accounts.json.aes"), argonBackup)

    // The JSON document is the source of truth for the plaintext shared by all blobs.
    write(output.resolve("expected-vault.json"), plaintext)
}

private fun byteRange(first: Int, length: Int): ByteArray =
    ByteArray(length) { (first + it).toByte() }

/**
 * Properties.store output with every comment line stripped and no trailing newline, matching the
 * checked-in vault.properties convention (DesktopSettings additionally appends a final line
 * separator, which the properties loader ignores but the golden hash does not).
 */
private fun deterministicProperties(properties: Properties): ByteArray {
    val buffer = ByteArrayOutputStream()
    properties.store(buffer, null)
    val deterministic = buffer.toString(Charsets.ISO_8859_1.name())
        .lineSequence()
        .filterNot { it.startsWith("#") }
        .joinToString(separator = "\n")
    return deterministic.toByteArray(Charsets.ISO_8859_1)
}

private fun write(target: Path, bytes: ByteArray) {
    Files.write(target, bytes)
    println("wrote $target (${bytes.size} bytes)")
}
