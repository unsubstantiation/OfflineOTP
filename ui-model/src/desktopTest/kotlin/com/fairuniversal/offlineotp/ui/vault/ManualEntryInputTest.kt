package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Database.EntryDraft
import com.fairuniversal.offlineotp.Utilities.TokenCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualEntryInputTest {
    @Test
    fun artworkCanBeAutomaticExplicitOrPreservedWithoutMutatingTheOriginal() {
        val automatic = ManualEntryInput(issuer = "GitHub", secret = "JBSWY3DPEHPK3PXP").build()
        try {
            assertEquals("GitHub", automatic.getThumbnailName())
            automatic.setThumbnailName("Google")
            val initial = ManualEntryInput.fromEntry(automatic)
            assertEquals("Google", initial.thumbnailName)
            assertEquals("Google", initial.withoutSecrets().thumbnailName)
            for ((choice, expected) in listOf(null to "Google", "Default" to "Default", "GitHub" to "GitHub")) {
                val edited = initial.copy(thumbnailName = choice, issuer = "Changed").build(automatic)
                try {
                    assertEquals(expected, edited.getThumbnailName())
                    assertEquals("Google", automatic.getThumbnailName())
                    assertEquals(automatic.getSecretEncoded(), edited.getSecretEncoded())
                } finally { edited.destroy() }
            }
            val explicit = ManualEntryInput(issuer = "GitHub", secret = "JBSWY3DPEHPK3PXP", thumbnailName = "Default").build()
            try { assertEquals("Default", explicit.getThumbnailName()) } finally { explicit.destroy() }
        } finally { automatic.destroy() }
    }

    @Test
    fun thumbnailNamesMustComeFromTheBundledCatalog() {
        for (name in listOf("", "github", "../logo", "not-a-brand")) {
            var rejected = false
            try { ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", thumbnailName = name).build().destroy() }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("non-catalog choice was accepted", rejected)
        }
    }

    @Test
    fun editsKeepSecretsOutOfTheFormAndPreserveMetadata() {
        val original = ManualEntryInput(secret = "JBSWY3DPEHPK3PXP", hiddenLabel = "protected").build()
        original.setLastUsed(123L)
        original.setUsedFrequency(7L)
        original.setDetailsVisible(true)
        original.setThumbnailName("GitHub")
        val initial = ManualEntryInput.fromEntry(original)
        assertTrue(initial.secret.isEmpty() && initial.hiddenLabel.isEmpty() && initial.pin.isEmpty())
        val edited = initial.copy(label = "new label").build(original)
        try {
            assertEquals(original.getSecretEncoded(), edited.getSecretEncoded())
            assertEquals("protected", edited.getHiddenLabel())
            assertEquals("GitHub", edited.getThumbnailName())
            assertEquals(123L, edited.getLastUsed())
            assertEquals(7L, edited.getUsedFrequency())
            assertTrue(edited.areDetailsVisible())
            var rejected = false
            try { initial.copy(type = Entry.OTPType.HOTP).build(original).destroy() }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { edited.destroy(); original.destroy() }
    }

    @Test
    fun blankNoteKeyPinAndPasswordFieldsRetainTheOriginalValues() {
        val note = ManualEntryInput(type = Entry.OTPType.NOTE, label = "Note", secret = "  private note\n").build()
        val ssh = ManualEntryInput(type = Entry.OTPType.SSH_KEY, label = "Server", secret = "private key", publicKey = "public key",
            sshHost = "host", sshUser = "user", sshPort = "2222", sshPassword = "password").build()
        val motp = ManualEntryInput(type = Entry.OTPType.MOTP, secret = "0123456789abcdef", pin = "1234").build()
        try {
            for (original in listOf(note, ssh, motp)) {
                val initial = ManualEntryInput.fromEntry(original)
                assertTrue(listOf(initial.secret, initial.publicKey, initial.pin, initial.sshPassword).all { it.isEmpty() })
                val replacement = initial.copy(label = "renamed").build(original)
                try {
                    when (original.getType()) {
                        Entry.OTPType.NOTE -> assertEquals(note.getNote(), replacement.getNote())
                        Entry.OTPType.SSH_KEY -> {
                            assertEquals(ssh.getSshKeys()!!.privateKey, replacement.getSshKeys()!!.privateKey)
                            assertEquals(ssh.getSshKeys()!!.publicKey, replacement.getSshKeys()!!.publicKey)
                            assertEquals("password", replacement.getSshConnection()!!.password)
                            assertEquals(2222, replacement.getSshConnection()!!.port)
                        }
                        else -> assertEquals("1234", replacement.getPin())
                    }
                } finally { replacement.destroy() }
            }
        } finally { note.destroy(); ssh.destroy(); motp.destroy() }
    }

    @Test
    fun everyOfferedTypeUsesTheSharedBuilderAndTags() {
        for (type in EntryDraft.offerableTypes()) {
            val input = ManualEntryInput(type = type, issuer = "Example", label = "alice",
                secret = when (type) {
                    Entry.OTPType.NOTE -> " note\ntext "
                    Entry.OTPType.SSH_KEY -> "private key"
                    Entry.OTPType.MOTP -> "0123456789abcdef"
                    else -> "JBSW Y3DPEHPK3PXP"
                }, publicKey = "public key", tags = "work\n work\n home", timing = "30")
            val entry = input.build()
            try {
                assertEquals(type, entry.getType())
                assertEquals(listOf("work", "home"), entry.getTags())
                if (type == Entry.OTPType.NOTE) assertEquals(" note\ntext ", entry.getNote())
                assertTrue(entry.getLastUsed() > 0)
            } finally { entry.destroy() }
        }
    }

    @Test
    fun steamParametersAreFixedAndSecretsAreRedactedAndClearable() {
        val input = ManualEntryInput(type = Entry.OTPType.STEAM, secret = "JBSWY3DPEHPK3PXP",
            pin = "1234", sshPassword = "private-password", hiddenLabel = "hidden",
            publicKey = "public", digits = "invalid", timing = "invalid", algorithm = TokenCalculator.HashAlgorithm.SHA512)
        val entry = input.build()
        try {
            assertEquals(5, entry.getDigits())
            assertEquals(30, entry.getPeriod())
            assertEquals(TokenCalculator.HashAlgorithm.SHA1, entry.getAlgorithm())
            assertFalse(input.toString().contains(input.secret))
            val cleared = input.withoutSecrets()
            assertTrue(listOf(cleared.secret, cleared.pin, cleared.publicKey, cleared.sshPassword, cleared.hiddenLabel).all { it.isEmpty() })
        } finally { entry.destroy() }
    }

    @Test
    fun addActionRespectsFrameHostTerminalGuardAndNullAvailability() {
        var calls = 0
        val host = VaultFrameHost(VaultActions(onAddEntry = { calls++ }))
        val actions = host.actions
        actions.onAddEntry?.invoke()
        assertEquals(0, calls)
        host.render(VaultUiState())
        actions.onAddEntry?.invoke()
        assertEquals(1, calls)
        host.close()
        actions.onAddEntry?.invoke()
        assertEquals(1, calls)
        assertTrue(VaultActions().guarded { true }.onAddEntry == null)
    }
}
