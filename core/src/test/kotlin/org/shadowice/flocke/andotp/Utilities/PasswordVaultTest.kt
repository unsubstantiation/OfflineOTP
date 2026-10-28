/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the portable password-vault engine, driven through in-memory storage and
 * settings ports so the same tests bind every platform adapter. They pin provisioning, verifier
 * checking, GCM failure mapping, the VaultJson document round trip and backup promotion.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Database.Entry
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList

class PasswordVaultTest {

    private val password = "correct horse battery staple"

    private fun entry(issuer: String, label: String): Entry =
        Entry(Entry.OTPType.TOTP, "JBSWY3DPEHPK3PXP", 30, 6, issuer, label,
            TokenCalculator.HashAlgorithm.SHA1, ArrayList())

    private fun newHarness(): Triple<InMemoryByteStore, InMemoryByteStore, PasswordVault> {
        val vaultFile = InMemoryByteStore()
        val backupFile = InMemoryByteStore()
        val vault = PasswordVault(vaultFile, backupFile, InMemorySettings())
        return Triple(vaultFile, backupFile, vault)
    }

    @Test
    fun createProvisionsAnEmptyVaultAndOpensIt() {
        val (vaultFile, _, vault) = newHarness()
        assertFalse(vault.isInitialized())

        assertEquals(VaultWriteResult.Saved, vault.create(password))
        assertTrue(vault.isInitialized())
        assertTrue(vaultFile.bytes!!.size >= Constants.ENCRYPTION_IV_LENGTH + 16)

        val opened = vault.open(password)
        assertTrue(opened is PasswordVault.Open.Unlocked)
        assertEquals(0, (opened as PasswordVault.Open.Unlocked).entries.size)
    }

    @Test
    fun createRejectsEmptyPasswordAndAnExistingVault() {
        val (_, _, vault) = newHarness()
        assertTrue(vault.create("") is VaultWriteResult.Rejected)
        assertEquals(VaultWriteResult.Saved, vault.create(password))

        val second = vault.create(password)
        assertEquals(VaultWriteError.ALREADY_EXISTS, (second as VaultWriteResult.Rejected).error)
    }

    @Test
    fun openAMissingVaultReportsMissing() {
        val (_, _, vault) = newHarness()
        assertEquals(VaultReadError.MISSING, (vault.open(password) as PasswordVault.Open.Failed).error)
    }

    @Test
    fun wrongAndMissingCredentialsMapToTheirDocumentedErrors() {
        val (vaultFile, _, vault) = newHarness()
        vault.create(password)

        val wrong = vault.open("${password}-wrong")
        assertEquals(VaultReadError.AUTHENTICATION_FAILED,
            (wrong as PasswordVault.Open.Failed).error)

        // A vault without provisioned settings is a corrupt configuration, not a password failure.
        val orphan = PasswordVault(vaultFile, InMemoryByteStore(), InMemorySettings())
        assertEquals(VaultReadError.CORRUPT,
            (orphan.open(password) as PasswordVault.Open.Failed).error)
    }

    @Test
    fun savedEntriesSurviveReopeningInOrder() {
        val (_, _, vault) = newHarness()
        vault.create(password)
        val key = (vault.open(password) as PasswordVault.Open.Unlocked).key
        val entries = arrayListOf(entry("GitHub", "alice"), entry("Amazon", "orders"), entry("", "token"))

        assertEquals(VaultWriteResult.Saved, vault.save(key, entries))

        val reopened = vault.open(password) as PasswordVault.Open.Unlocked
        assertEquals(entries.size, reopened.entries.size)
        for (index in entries.indices) {
            assertEquals(entries[index].getIssuer(), reopened.entries[index].getIssuer())
            assertEquals(entries[index].getLabel(), reopened.entries[index].getLabel())
        }
    }

    @Test
    fun savingNullEntriesIsRejectedAsInvalidInput() {
        val (_, _, vault) = newHarness()
        vault.create(password)
        val key = (vault.open(password) as PasswordVault.Open.Unlocked).key

        val result = vault.save(key, null)
        assertEquals(VaultWriteError.INVALID_INPUT, (result as VaultWriteResult.Rejected).error)
    }

    @Test
    fun tamperedVaultBytesFailAuthentication() {
        val (vaultFile, _, vault) = newHarness()
        vault.create(password)

        val damaged = vaultFile.bytes!!.copyOf()
        damaged[damaged.lastIndex] = (damaged[damaged.lastIndex].toInt() xor 1).toByte()
        vaultFile.bytes = damaged

        assertEquals(VaultReadError.AUTHENTICATION_FAILED,
            (vault.open(password) as PasswordVault.Open.Failed).error)
    }

    @Test
    fun authenticatedButMalformedJsonIsCorrupt() {
        val (vaultFile, _, vault) = newHarness()
        vault.create(password)
        val unlocked = vault.open(password) as PasswordVault.Open.Unlocked

        // Valid GCM under the real key, but a document the JSON codec must reject.
        vaultFile.bytes = CryptoPrimitives.encrypt(
            unlocked.key, "not-a-vault-array".toByteArray(StandardCharsets.UTF_8))

        assertEquals(VaultReadError.CORRUPT,
            (vault.open(password) as PasswordVault.Open.Failed).error)
    }

    @Test
    fun backupAndRestorePromoteTheSameBytes() {
        val (vaultFile, backupFile, vault) = newHarness()
        vault.create(password)
        val key = (vault.open(password) as PasswordVault.Open.Unlocked).key
        vault.save(key, arrayListOf(entry("Issuer", "label")))

        assertTrue(vault.backup())
        assertTrue(backupFile.bytes!!.contentEquals(vaultFile.bytes!!))

        vaultFile.bytes = ByteArray(0)
        assertTrue(vault.restoreBackup())
        val restored = vault.open(password) as PasswordVault.Open.Unlocked
        assertEquals(1, restored.entries.size)
        assertEquals("Issuer", restored.entries[0].getIssuer())
    }

    @Test
    fun provisionedSettingsUseTheSharedKeysAndUrlSafeBase64() {
        val (_, _, vault) = newHarness()
        val settings = InMemorySettings()
        PasswordVault(InMemoryByteStore(), InMemoryByteStore(), settings).create(password)

        assertEquals("password", settings.strings[VaultSettingsKeys.AUTH_METHOD])
        assertEquals("password", settings.strings[VaultSettingsKeys.ENCRYPTION])
        val verifier = settings.strings[VaultSettingsKeys.AUTH_CREDENTIALS]!!
        val salt = settings.strings[VaultSettingsKeys.AUTH_SALT]!!
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' })
        assertTrue(salt.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' })
        assertEquals(Constants.PBKDF2_MIN_AUTH_ITERATIONS,
            settings.ints[VaultSettingsKeys.AUTH_ITERATIONS])
    }

    private class InMemoryByteStore : AtomicByteStore {
        var bytes: ByteArray? = null

        override fun exists(): Boolean = bytes != null

        override fun read(): ByteArray =
            bytes?.clone() ?: throw IOException("no such in-memory file")

        override fun writeAtomic(data: ByteArray) {
            // Model the adapter contract: a promoted file always reads back exactly what was written.
            bytes = data.clone()
        }

        override fun delete() {
            bytes = null
        }
    }

    private class InMemorySettings : KeyValueSettings {
        val strings = HashMap<String, String>()
        val ints = HashMap<String, Int>()

        override fun getString(key: String, default: String): String = strings[key] ?: default
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun remove(key: String) {
            strings.remove(key)
            ints.remove(key)
        }
    }
}
