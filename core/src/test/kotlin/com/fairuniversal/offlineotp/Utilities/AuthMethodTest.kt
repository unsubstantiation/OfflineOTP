package com.fairuniversal.offlineotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored authentication method is a plain string in the settings file, so a token this build no
 * longer has can arrive from an older release. The PIN login was removed; a vault set up with it is
 * still encrypted with that PIN, and the parsing must not strand it.
 */
class AuthMethodTest {
    @Test
    fun storedTokensRoundTripThroughTheirNames() {
        for (method in Constants.AuthMethod.values())
            assertEquals(method, Constants.AuthMethod.fromStored(method.name.lowercase()))
    }

    @Test
    fun storedTokensAreReadCaseInsensitivelyAndTrimmed() {
        // The old clear() case-mapped with the user's locale, so "PIN" could be stored as "pın".
        assertEquals(Constants.AuthMethod.PASSWORD, Constants.AuthMethod.fromStored(" Password "))
        assertEquals(Constants.AuthMethod.DEVICE, Constants.AuthMethod.fromStored("Device"))
        assertEquals(Constants.AuthMethod.NONE, Constants.AuthMethod.fromStored("none"))
    }

    @Test
    fun theRemovedPinLoginReadsAsAPassword() {
        // Older releases wrote this; the vault it belongs to is opened by the password screen, which
        // asks for the same credential and lets the stored salt and verifier decide.
        assertEquals(Constants.AuthMethod.PASSWORD, Constants.AuthMethod.fromStored("pin"))
        assertEquals(Constants.AuthMethod.PASSWORD, Constants.AuthMethod.fromStored("PIN"))
    }

    @Test
    fun anUnreadableValueNeverThrows() {
        // A corrupt or hand-edited preference must not crash the launch path that reads it.
        assertEquals(Constants.AuthMethod.PASSWORD, Constants.AuthMethod.fromStored(""))
        assertEquals(Constants.AuthMethod.PASSWORD, Constants.AuthMethod.fromStored("something else"))
    }
}
