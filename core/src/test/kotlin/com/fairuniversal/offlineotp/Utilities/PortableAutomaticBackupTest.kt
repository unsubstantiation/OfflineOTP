package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PortableAutomaticBackupTest {
    private val rules = BackupAutomationPolicy()
    private val retention = AutomaticBackupRetention()
    private fun name(index: Int, extension: String = "aes") = "auto-${index.toString().padStart(16, '0')}-00000000-0000-0000-0000-000000000000.$extension"
    private fun provider(index: Int) = "otp_accounts_${index.toString().padStart(8, '0')}-0000-0000-0000-000000000000.json.aes"

    @Test fun sharedConfigurationRejectsCoercionDuplicatesSigningSecretsAndModeConfusion() {
        val codec = AutomaticBackupConfigurationCodec()
        val good = """{"version":1,"folder":"/private","password":"secret","minutes":15,"keep":10}"""
        assertTrue(codec.canonical(good) != null)
        for (bad in listOf(good.replace("15", "15.0"), good.replace("15", "2147483648"),
            good.replace("\"version\":1", "\"version\":1,\"version\":1"),
            good.dropLast(1) + ",\"signingKey\":\"private\"}", good.dropLast(1) + ",\"requiresSigning\":true}",
            good.replace("secret", "x".repeat(1025)), good.replace("\"password\":\"secret\"", "\"password\":true"),
            good.replace("/private", "x".repeat(4097)))) assertNull(codec.canonical(bad))
        assertNull(codec.encode("/private", "", 1, 2, emptyList(), false, false))
        assertNull(codec.encode("/private", "", 1, 2, listOf("YQ==", "YQ=="), false, false))
        assertNull(codec.encode("/private", "", 1, 2, listOf("YQ=="), true, true))
        assertTrue(codec.encode("/private", "", 1440, 100, listOf("YQ=="), true, false) != null)
        assertTrue(codec.encode("/private", "", 1, 1, emptyList(), false, true) != null)
    }
    @Test fun nativeCredentialCryptoNeverWipesAProvidersAliasedVaultKey() {
        val alias = ByteArray(32) { 7 }
        val key = object : javax.crypto.SecretKey {
            override fun getAlgorithm() = "AES"
            override fun getFormat() = "RAW"
            override fun getEncoded() = alias
        }
        val plain = "synthetic-secret".toByteArray()
        val encrypted = AutomaticBackupCredentialCodec.seal(key, plain)
        try { assertArrayEquals(plain, AutomaticBackupCredentialCodec.open(key, encrypted)) }
        finally { encrypted.fill(0); plain.fill(0) }
        assertArrayEquals(ByteArray(32) { 7 }, alias)
    }
    @Test fun configurationRoundTripTransfersSecretsToNativeOwnerAndWipesThemOnClose() {
        val key = byteArrayOf(1, 2, 3)
        val document = AutomaticBackupCredentialCodec.recipientConfigurationDocument("/folder", listOf(key), 7, 8, true)
        val config = AutomaticBackupCredentialCodec.parseConfiguration(document)
        try { assertTrue(config.requiresSigning); assertArrayEquals(key, config.recipientKeys.single()); assertEquals(7, config.minutes) }
        finally { config.close(); document.fill(0) }
        assertTrue(config.recipientKeys.single().all { it == 0.toByte() })
        assertTrue(key.contentEquals(byteArrayOf(1, 2, 3)))
    }
    @Test fun schedulesCoalesceChangesAndNeverOverlapOrExtendFirstDeadline() {
        val policy = AutomaticBackupSchedule()
        assertTrue(policy.enable(7, 15))
        val first = policy.begin(7, true); assertTrue(first > 0)
        assertEquals(0L, policy.begin(7, true))
        assertEquals(-1L, policy.changed(7, true)); assertEquals(-1L, policy.changed(7, true))
        assertEquals(1000L, policy.complete(first, 7, true, true))
        assertEquals(-1L, policy.changed(7, true)) // catch-up is already due
        val second = policy.begin(7, true)
        assertEquals(900000L, policy.complete(second, 7, true, true))
        assertEquals(1000L, policy.changed(7, true)); assertEquals(-1L, policy.changed(7, true))
    }
    @Test fun staleLockedFailedAndDuplicateCompletionsCannotRestartDisabledScheduling() {
        val policy = AutomaticBackupSchedule()
        policy.enable(1, 1); val first = policy.begin(1, true)
        policy.disable(); policy.enable(1, 2); val second = policy.begin(1, true)
        assertEquals(-1L, policy.complete(first, 1, true, true)); assertTrue(policy.busy)
        assertEquals(-1L, policy.complete(second, 2, true, true)); assertFalse(policy.enabled)
        policy.enable(2, 1); val third = policy.begin(2, true)
        assertEquals(-1L, policy.complete(third, 2, false, true)); assertFalse(policy.enabled)
        policy.enable(2, 1); val fourth = policy.begin(2, true)
        assertEquals(-1L, policy.complete(fourth, 2, true, false)); assertFalse(policy.enabled)
        assertEquals(0L, policy.begin(2, true))
    }
    @Test fun signingResumeCannotDowngradeAndLockedSessionsNeverResume() {
        assertEquals("LOCKED", rules.resume(false, false, false))
        assertEquals("LOCKED", rules.resume(false, true, true))
        assertEquals("SIGNING_REQUIRED", rules.resume(true, true, false))
        assertEquals("READY", rules.resume(true, false, false))
        assertEquals("READY", rules.resume(true, true, true))
    }
    @Test fun retentionKeepsOtherModesManualFilesAndAmbiguousNames() {
        val files = (1..5).map { name(it) } + name(0, "pgp") + name(0, "offlineotp") + "manual.aes"
        assertEquals((1..3).map { name(it) }, retention.managedPlan(files, 2, "aes"))
        assertTrue(retention.managedPlan(files, 0, "aes").isEmpty())
        assertTrue(retention.managedPlan(files, 101, "aes").isEmpty())
        assertFalse(retention.managedName(name(1).replace("00000000-0000-0000-0000-000000000000", "------------------------------------"), "aes"))
        assertFalse(retention.managedName("../" + name(1), "aes"))
        assertFalse(retention.providerName(provider(1).replace('0', '\u0660')))
        val duplicate = name(1)
        assertFalse(retention.managedPlan(files + duplicate, 1, "aes").contains(duplicate))
    }
    @Test fun providerUnknownDatesAreNeverDeletedEvenIfTheyExceedRetention() {
        val files = listOf(BackupRetentionCandidate(provider(1), 0), BackupRetentionCandidate(provider(2), -1),
            BackupRetentionCandidate(provider(3), 2), BackupRetentionCandidate(provider(4), 1))
        assertEquals(listOf(provider(4), provider(3)), retention.providerPlan(files, 1))
        assertTrue(retention.providerPlan(files.take(2), 1).isEmpty())
        val duplicated = files + BackupRetentionCandidate(provider(4), 3)
        assertFalse(retention.providerPlan(duplicated, 1).contains(provider(4)))
    }
    @Test fun retryCannotDropRevertedWhileAnOlderSnapshotCanStillPublish() {
        assertTrue(rules.canDropReverted(true, "digest", "digest", false))
        assertFalse(rules.canDropReverted(true, "digest", "digest", true))
        assertFalse(rules.canDropReverted(false, "digest", "digest", false))
        assertFalse(rules.canDropReverted(true, null, null, false))
        assertTrue(rules.clearPriorDigest(false, true)); assertFalse(rules.clearPriorDigest(true, true))
    }
}
