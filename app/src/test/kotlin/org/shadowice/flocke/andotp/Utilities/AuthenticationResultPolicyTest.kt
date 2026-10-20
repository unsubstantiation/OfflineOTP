package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationResultPolicyTest {
    @Test
    fun canceledAuthenticationCannotUnlock() {
        assertFalse(AuthenticationResultPolicy.accepts(
            resultOk = false,
            requestPending = true,
            sessionGenerationCurrent = true
        ))
    }

    @Test
    fun unrelatedOrStaleResultsCannotUnlock() {
        assertFalse(AuthenticationResultPolicy.accepts(true, requestPending = false, sessionGenerationCurrent = true))
        assertFalse(AuthenticationResultPolicy.accepts(true, requestPending = true, sessionGenerationCurrent = false))
    }

    @Test
    fun onlySuccessfulCurrentPendingChallengeIsAccepted() {
        assertTrue(AuthenticationResultPolicy.accepts(true, requestPending = true, sessionGenerationCurrent = true))
    }
}
