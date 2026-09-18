package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeTrackingRecoveryContractTest {
    @Test
    fun repeatedTrackingLossPreservesOwnerAndRestartsFullStabilityWindow() {
        newSession().use { session ->
            aligned(session)
            val oldTicket = session.beginVerification(800)
            for (time in listOf(900L, 1000L)) {
                val result = session.update(time, false, emptyList(), emptyList()) as FrameSubmission.Applied
                assertEquals(CoreRestoreState.GUIDING, result.state)
            }
            reacquire(session, 1100)
            val newTicket = session.beginVerification(1900)
            assertEquals(oldTicket.owner, newTicket.owner)
            assertNotEquals(oldTicket.token, newTicket.token)
            assertEquals(CoreRestoreState.VERIFIED, session.completeVerification(newTicket, 1900, verifiedResult()))
        }
    }

    @Test
    fun trackingLossClearsPreviouslyVerifiedStateWithoutDestroyingOwner() {
        newSession().use { session ->
            aligned(session)
            val first = session.beginVerification(800)
            assertEquals(CoreRestoreState.VERIFIED, session.completeVerification(first, 800, verifiedResult()))
            val lost = session.update(900, false, emptyList(), emptyList()) as FrameSubmission.Applied
            assertEquals(CoreRestoreState.GUIDING, lost.state)
            reacquire(session, 1000)
            val second = session.beginVerification(1800)
            assertEquals(first.owner, second.owner)
            assertNotEquals(first.token, second.token)
            assertEquals(CoreRestoreState.VERIFIED, session.completeVerification(second, 1800, verifiedResult()))
        }
    }

    @Test
    fun encoderInvalidationUsesRecoverableTrackingLossRatherThanClosingSession() {
        newSession().use { session ->
            aligned(session)
            val old = session.beginVerification(800)
            val result = session.update(
                900, true, detections(), evidence().map { it.copy(semanticScore = Double.NaN) },
            ) as FrameSubmission.Invalidated
            assertEquals(FrameEncodingError.INVALID_SCORE, result.reason)
            assertEquals(CoreRestoreState.GUIDING, result.state)
            reacquire(session, 1000)
            val next = session.beginVerification(1800)
            assertEquals(old.owner, next.owner)
            assertNotEquals(old.token, next.token)
            assertEquals(CoreRestoreState.VERIFIED, session.completeVerification(next, 1800, verifiedResult()))
        }
    }

    private fun reacquire(session: NativeSceneSession, start: Long) {
        for (elapsed in 0L..700L step 100) {
            val result = session.update(start + elapsed, true, detections(), evidence()) as FrameSubmission.Applied
            assertEquals(CoreRestoreState.GUIDING, result.state)
        }
        val before = session.update(start + 799, true, detections(), evidence()) as FrameSubmission.Applied
        assertEquals(CoreRestoreState.GUIDING, before.state)
        val aligned = session.update(start + 800, true, detections(), evidence()) as FrameSubmission.Applied
        assertEquals(CoreRestoreState.AWAITING_VERIFICATION, aligned.state)
    }
}
