package com.yubico.yubikit.oath

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialFixtureTest {
    @Test fun defaultTotpUsesIdentifierDerivedIdentity() {
        val credential = credentialFixture("Example:alice")
        assertEquals("Example", credential.issuer)
        assertEquals("alice", credential.accountName)
        assertEquals(30, credential.period)
        assertEquals(OathType.TOTP, credential.oathType)
    }

    @Test fun nondefaultTotpPeriodIsParsedFromIdentifier() {
        val credential = credentialFixture("60/Example:alice")
        assertEquals("Example", credential.issuer)
        assertEquals("alice", credential.accountName)
        assertEquals(60, credential.period)
        assertEquals("60/Example:alice", String(credential.id, Charsets.UTF_8))
    }

    @Test fun hotpHasNoTimePeriod() {
        val credential = credentialFixture("Example:counter", OathType.HOTP)
        assertEquals("Example", credential.issuer)
        assertEquals("counter", credential.accountName)
        assertEquals(0, credential.period)
        assertEquals(OathType.HOTP, credential.oathType)
    }
}
