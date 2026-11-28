package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.domain.SessionAuthority
import com.fairuniversal.offlineotp.domain.SessionPhase
import com.fairuniversal.offlineotp.domain.UnlockAttemptPolicy
import com.fairuniversal.offlineotp.domain.InactivityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class PortableSessionAuthorityTest {
    @Test fun unlockRekeyAndEveryLockReasonRevokeEarlierAuthority() {
        val session = SessionAuthority()
        assertEquals(SessionPhase.LOCKED, session.phase)
        var prior = session.generation
        for (reason in SessionLockReason.values()) {
            val ticket = session.beginUnlock()
            assertFalse(session.isCurrent(prior)); assertEquals(SessionPhase.UNLOCKING, session.phase)
            assertNull(session.activate(ticket, true)); assertTrue(session.isUnlocked(session.generation))
            val unlocked = session.generation
            assertNull(session.activate(unlocked, false)); assertEquals(unlocked, session.generation)
            assertNull(session.activate(unlocked, true)); assertFalse(session.isCurrent(unlocked))
            prior = session.generation
            session.lock(reason)
            assertFalse(session.isCurrent(prior)); assertEquals(reason, session.reason)
            assertEquals(SessionPhase.LOCKED, session.phase); prior = session.generation
        }
    }
    @Test fun staleAndFailedActivationsCannotOpenAndRecoveryRequiresANewChallenge() {
        val session = SessionAuthority(); val old = session.beginUnlock()
        session.fail(SessionFailure.RECOVERY_REQUIRED)
        assertEquals(SessionRejection.STALE_GENERATION, session.activate(old, true))
        assertEquals(SessionRejection.FAILED_SESSION, session.activate(session.generation, true))
        assertEquals(SessionRejection.FAILED_SESSION, session.handoffRejection(session.generation, 32))
        assertFalse(session.canOfferBackup(session.generation))
        val fresh = session.beginUnlock(); assertNull(session.activate(fresh, true))
    }
    @Test fun derivedAndBackupHandoffsStayBoundedAndGenerationScoped() {
        val session = SessionAuthority(); val ticket = session.beginUnlock()
        assertNull(session.handoffRejection(ticket, 1)); assertNull(session.handoffRejection(ticket, 64))
        assertEquals(SessionRejection.INVALID_HANDOFF, session.handoffRejection(ticket, 0))
        assertEquals(SessionRejection.INVALID_HANDOFF, session.handoffRejection(ticket, 65))
        assertTrue(session.canOfferBackup(ticket))
        session.activate(ticket, true)
        assertFalse(session.canOfferBackup(session.generation))
        assertEquals(SessionRejection.STALE_GENERATION, session.handoffRejection(ticket, 32))
    }
    @Test fun nativeControllerKeepsPayloadsNativeAndCleansHandoffsOnLock() {
        val controller = SessionController(Any()); val ticket = controller.beginUnlock().generation
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        assertTrue(controller.publishDerived(ByteArray(32) { 9 }, ticket) is SessionCommandResult.Applied)
        controller.lock(SessionLockReason.BACKGROUND)
        assertTrue(controller.consumeDerived(ticket) is SessionHandoff.Unavailable)
        assertTrue(controller.activate(key, ticket) is SessionCommandResult.Rejected)
        val next = controller.beginUnlock().generation
        assertTrue(controller.activate(key, next) is SessionCommandResult.Applied)
        assertTrue(controller.currentKey() === key)
        controller.fail(SessionFailure.VAULT_UNAVAILABLE)
        assertNull(controller.currentKey())
    }
    @Test fun nativeCallbacksRunAfterRevocationAndOneFailureDoesNotSkipCleanup() {
        val lock = Any(); val controller = SessionController(lock)
        val ticket = controller.beginUnlock().generation
        controller.activate(SecretKeySpec(ByteArray(32), "AES"), ticket)
        var called = false
        controller.addLockListener(Runnable { throw IllegalStateException("synthetic") })
        controller.addChangeListener(Runnable {
            assertFalse(Thread.holdsLock(lock)); assertNull(controller.currentKey()); called = true
        })
        controller.lock(SessionLockReason.EXPLICIT); assertTrue(called)
    }
    @Test fun attemptResultsAreOneShotAndOldProvidersCannotConsumeANewerRequest() {
        val attempt = UnlockAttemptPolicy()
        val old = attempt.start(7, true); assertTrue(old > 0)
        assertEquals(0L, attempt.start(7, true)); assertFalse(attempt.complete(old, 8, true, true))
        val fresh = attempt.start(8, true)
        assertFalse(attempt.complete(old, 7, true, true))
        assertTrue(attempt.current(fresh, 8, true)); assertTrue(attempt.complete(fresh, 8, true, true))
        assertFalse(attempt.complete(fresh, 8, true, true))
    }
    @Test fun cancelBackgroundAndCloseRejectLateResultsAndOnlyCancelPermitsExplicitRetry() {
        val attempt = UnlockAttemptPolicy(); val first = attempt.start(1, true)
        attempt.cancel(); assertFalse(attempt.complete(first, 1, true, true))
        val second = attempt.start(1, true)
        assertFalse(attempt.current(second, 1, false)); assertFalse(attempt.complete(second, 1, false, true))
        val third = attempt.start(1, true); attempt.close()
        assertFalse(attempt.complete(third, 1, true, true)); assertEquals(0L, attempt.start(1, true))
    }
    @Test fun unsuccessfulOrUnpermittedProviderResultsCannotBecomeSuccess() {
        val attempt = UnlockAttemptPolicy(); val ticket = attempt.start(1, true)
        assertFalse(attempt.complete(ticket, 1, true, false)); assertFalse(attempt.current(ticket, 1, true))
        assertEquals(0L, attempt.start(1, false))
    }
    @Test fun inactivityUsesElapsedTimeAndCannotBeRevivedByBackwardOrOverflowingClocks() {
        val policy = InactivityPolicy(); policy.arm(100, 1000)
        assertFalse(policy.expired(1099)); assertEquals(1L, policy.remaining(1099))
        assertTrue(policy.expired(1100)); assertEquals(0L, policy.remaining(1100))
        assertTrue(policy.expired(99))
        policy.arm(Long.MAX_VALUE - 5, 10)
        assertFalse(policy.expired(Long.MAX_VALUE - 1)); assertTrue(policy.expired(Long.MAX_VALUE))
        policy.cancel(); assertFalse(policy.expired(Long.MAX_VALUE))
        policy.arm(100, 0); assertFalse(policy.expired(1000))
        policy.arm(100, -1); assertFalse(policy.expired(1000))
    }
    @Test fun exhaustedGenerationsCannotWrapOrGrantFreshAuthority() {
        val session = SessionAuthority()
        session.javaClass.getDeclaredField("generation").apply { isAccessible = true }.setLong(session, Long.MAX_VALUE)
        session.beginUnlock()
        assertEquals(Long.MAX_VALUE, session.generation)
        assertEquals(SessionPhase.FAILED, session.phase)
        assertFalse(session.isCurrent(Long.MAX_VALUE))
        assertEquals(SessionRejection.STALE_GENERATION, session.activate(Long.MAX_VALUE, true))
        session.lock(SessionLockReason.EXPLICIT)
        assertEquals(SessionPhase.FAILED, session.phase)
    }

    @Test fun mixedDeterministicEventsNeverAcceptARevokedGeneration() {
        val session = SessionAuthority(); var old = 0L
        repeat(1000) { i ->
            val current = session.beginUnlock()
            if (i > 0) assertEquals(SessionRejection.STALE_GENERATION, session.activate(old, true))
            if (i % 2 == 0) session.lock(SessionLockReason.SCREEN_OFF) else session.fail(SessionFailure.RECOVERY_REQUIRED)
            assertFalse(session.isCurrent(current)); assertEquals(SessionRejection.STALE_GENERATION, session.activate(current, true))
            old = current
        }
        assertEquals("SessionAuthority(redacted)", session.toString())
        assertEquals("UnlockAttemptPolicy(redacted)", UnlockAttemptPolicy().toString())
    }
}
