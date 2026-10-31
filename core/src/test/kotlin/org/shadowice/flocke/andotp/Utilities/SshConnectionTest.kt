/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Bounds and formatting for the optional SSH connection metadata stored with an SSH key entry.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionTest {
    private fun rejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("invalid connection metadata was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue("a rejection needs a user-facing message", !expected.message.isNullOrBlank())
        }
    }

    @Test
    fun blankFieldsStoreNothing() {
        assertNull(SshConnection.of(null, 0, null, null))
        assertNull(SshConnection.of("", 0, "", ""))
        assertNull(SshConnection.of("   ", 0, "  ", " "))
        // A whitespace-only password is a typo, not a secret; it is not stored.
        assertNull(SshConnection.of("", 0, "", "   "))
    }

    @Test
    fun hostAndUserNameAreTrimmedButThePasswordIsNot() {
        val details = SshConnection.of("  example.com ", 22, " alice ", " secret ")!!
        assertEquals("example.com", details.host)
        assertEquals(22, details.port)
        assertEquals("alice", details.username)
        assertEquals(" secret ", details.password)
        assertTrue(details.isSet())
    }

    @Test
    fun aPortIsBlankForUnsetAndMustOtherwiseBeInRange() {
        assertEquals(0, SshConnection.port(null))
        assertEquals(0, SshConnection.port(""))
        assertEquals(0, SshConnection.port("   "))
        assertEquals(22, SshConnection.port("22"))
        assertEquals(2222, SshConnection.port(" 2222 "))
        assertEquals(1, SshConnection.port("1"))
        assertEquals(65535, SshConnection.port("65535"))
        for (invalid in listOf("0", "65536", "-1", "22a", "2 2", "22.0")) {
            rejected { SshConnection.port(invalid) }
        }
        rejected { SshConnection.of("example.com", 0 - 1, "", "") }
        rejected { SshConnection.of("example.com", 65536, "", "") }
    }

    @Test
    fun hostAndUserRejectSpacesAndControlCharacters() {
        for (host in listOf("exam ple.com", "example\u0000.com", "example\tcom")) {
            rejected { SshConnection.of(host, 0, "", "") }
        }
        for (user in listOf("ali ce", "alice\u0001")) {
            rejected { SshConnection.of("example.com", 0, user, "") }
        }
        // Surrounding whitespace from a paste is trimmed rather than rejected.
        assertEquals("example.com", SshConnection.of("example.com\n", 0, "", "")!!.host)
        assertEquals("alice", SshConnection.of("h", 0, "alice\r", "")!!.username)
        // Spaces are legitimate in a password; control characters are not.
        assertEquals("correct horse", SshConnection.of("h", 0, "u", "correct horse")!!.password)
        rejected { SshConnection.of("h", 0, "u", "line\nbreak") }
    }

    @Test
    fun everyFieldHasAnExplicitBound() {
        assertEquals(SshConnection.MAX_HOST_CHARS,
            SshConnection.of("h".repeat(SshConnection.MAX_HOST_CHARS), 0, "", "")!!.host.length)
        assertEquals(SshConnection.MAX_USERNAME_CHARS,
            SshConnection.of("h", 0, "u".repeat(SshConnection.MAX_USERNAME_CHARS), "")!!.username.length)
        assertEquals(SshConnection.MAX_PASSWORD_CHARS,
            SshConnection.of("h", 0, "", "p".repeat(SshConnection.MAX_PASSWORD_CHARS))!!.password.length)
        rejected { SshConnection.of("h".repeat(SshConnection.MAX_HOST_CHARS + 1), 0, "", "") }
        rejected { SshConnection.of("h", 0, "u".repeat(SshConnection.MAX_USERNAME_CHARS + 1), "") }
        rejected { SshConnection.of("h", 0, "", "p".repeat(SshConnection.MAX_PASSWORD_CHARS + 1)) }
    }

    @Test
    fun summaryReadsLikeAnSshDestination() {
        assertEquals("alice@example.com", SshConnection.of("example.com", 22, "alice", "")!!.summary())
        assertEquals("alice@example.com", SshConnection.of("example.com", 0, "alice", "")!!.summary())
        assertEquals("alice@example.com:2222", SshConnection.of("example.com", 2222, "alice", "")!!.summary())
        assertEquals("example.com:2222", SshConnection.of("example.com", 2222, "", "")!!.summary())
        // A user or port without a host is stored but cannot be summarised as a destination.
        assertNull(SshConnection.of("", 22, "alice", "")!!.summary())
        assertNull(SshConnection.of("", 0, "", "secret")!!.summary())
    }

    @Test
    fun identityIgnoresThePassword() {
        val first = SshConnection.of("example.com", 22, "alice", "one")!!
        val sameTarget = SshConnection.of("example.com", 22, "alice", "two")!!
        val otherHost = SshConnection.of("other.example.com", 22, "alice", "one")!!
        assertTrue(first.sameIdentity(sameTarget))
        assertFalse(first.sameIdentity(otherHost))
        assertFalse(first.sameIdentity(null))
        assertEquals(first.identityKey(), sameTarget.identityKey())
        assertNotEquals(first.identityKey(), otherHost.identityKey())
    }

    @Test
    fun detailsNeverPrintThePassword() {
        val details = SshConnection.of("example.com", 22, "alice", "correct horse")!!
        assertEquals("SshConnection.Details(redacted)", details.toString())
        assertFalse(details.toString().contains("correct horse"))
    }

    @Test
    fun displayPortFallsBackToTheSshDefault() {
        assertEquals("22", SshConnection.displayPort(0))
        assertEquals("2222", SshConnection.displayPort(2222))
    }
}
