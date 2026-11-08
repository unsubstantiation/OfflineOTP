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
