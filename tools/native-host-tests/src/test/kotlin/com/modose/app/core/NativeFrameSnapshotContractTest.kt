package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeFrameSnapshotContractTest {
    private fun hidden(
        snapshot: NativeFrameSnapshot, now: Long, reason: NativeFrameHiddenReason,
    ): NativeFramePresentation.Hidden {
        val result = snapshot.presentationAt(now) as NativeFramePresentation.Hidden
        assertEquals(reason, result.reason)
        return result
    }

    @Test
    fun updateAndGuidanceDescribeTheSameObservation() {
        newSession().use { session ->
            val snapshot = session.updateGuidance(100, true, detections(0.3), evidence())
            val ready = snapshot.presentationAt(100) as NativeFramePresentation.Ready
            assertEquals(CoreRestoreState.GUIDING, ready.state)
            val move = ready.guidance as NativeGuidance.Move
            assertEquals(NativeTablePosition(0.3, 0.0), move.from)
            assertEquals(0.3, move.distanceMeters, 1e-9)
        }
    }

    @Test
    fun ageBoundaryAndInvalidClockNeverExposeStaleGuidance() {
        newSession().use { session ->
            val snapshot = session.updateGuidance(100, true, detections(0.3), evidence())
            assertEquals(CoreRestoreState.GUIDING,
                (snapshot.presentationAt(300) as NativeFramePresentation.Ready).state)
            hidden(snapshot, 301, NativeFrameHiddenReason.STALE_FRAME)
            hidden(snapshot, 99, NativeFrameHiddenReason.INVALID_CLOCK)
            hidden(snapshot, -1, NativeFrameHiddenReason.INVALID_CLOCK)
        }
    }

    @Test
    fun evenPlainUpdatesRevokeThePreviousFrame() {
        newSession().use { session ->
            val snapshot = session.updateGuidance(0, true, detections(0.3), evidence())
            session.update(100, true, detections(0.2), evidence())
            hidden(snapshot, 100, NativeFrameHiddenReason.SUPERSEDED)
        }
    }

    @Test
    fun rejectedEncodingSuppressesBothNewAndPreviousArrows() {
        newSession().use { session ->
            val old = session.updateGuidance(0, true, detections(0.3), evidence())
            val rejected = session.updateGuidance(100, true,
                List(6) { detections().single() }, emptyList())
            hidden(old, 100, NativeFrameHiddenReason.SUPERSEDED)
            assertEquals(FrameEncodingError.TOO_MANY_OBJECTS,
                hidden(rejected, 100, NativeFrameHiddenReason.INVALID_INPUT).encodingError)
            val recovered = session.updateGuidance(200, true, detections(0.2), evidence())
            assertEquals(1,
                ((recovered.presentationAt(200) as NativeFramePresentation.Ready).guidance
                    as NativeGuidance.Move).objectId)
            hidden(rejected, 200, NativeFrameHiddenReason.SUPERSEDED)
        }
    }

    @Test
    fun trackingLossPublishesNoArrowUntilAnotherValidFrame() {
        newSession().use { session ->
            val old = session.updateGuidance(0, true, detections(0.3), evidence())
            val lost = session.updateGuidance(100, false, emptyList(), emptyList())
            hidden(old, 100, NativeFrameHiddenReason.SUPERSEDED)
            hidden(lost, 100, NativeFrameHiddenReason.TRACKING_LOST)
            val recovered = session.updateGuidance(200, true, detections(0.2), evidence())
            assertEquals(CoreRestoreState.GUIDING,
                (recovered.presentationAt(200) as NativeFramePresentation.Ready).state)
        }
    }

    @Test
    fun closeRevokesPublishedSnapshotAndPreventsAnotherUpdate() {
        val session = newSession()
        val snapshot = session.updateGuidance(0, true, detections(0.3), evidence())
        session.close()
        session.close()
        hidden(snapshot, 0, NativeFrameHiddenReason.SUPERSEDED)
        assertThrows(IllegalStateException::class.java) {
            session.updateGuidance(100, true, detections(), evidence())
        }
    }

    @Test
    fun nativeRejectionRevokesFrameBeforeExceptionEscapes() {
        newSession().use { session ->
            val snapshot = session.updateGuidance(100, true, detections(0.3), evidence())
            assertThrows(IllegalStateException::class.java) {
                session.updateGuidance(100, true, detections(), evidence())
            }
            hidden(snapshot, 100, NativeFrameHiddenReason.SUPERSEDED)
        }
    }

    @Test
    fun verificationTransitionsRevokeOldStateAndVerifiedStateAlsoExpires() {
        newSession().use { session ->
            aligned(session)
            val local = session.updateGuidance(900, true, detections(), evidence())
            val ready = local.presentationAt(900) as NativeFramePresentation.Ready
            assertEquals(CoreRestoreState.AWAITING_VERIFICATION, ready.state)
            assertNull(ready.guidance)
            val ticket = session.beginVerification(900)
            hidden(local, 900, NativeFrameHiddenReason.SUPERSEDED)
            val pending = session.updateGuidance(1000, true, detections(), evidence())
            assertEquals(CoreRestoreState.VERIFYING,
                (pending.presentationAt(1000) as NativeFramePresentation.Ready).state)
            assertEquals(CoreRestoreState.VERIFIED,
                session.completeVerification(ticket, 1000, verifiedResult()))
            hidden(pending, 1000, NativeFrameHiddenReason.SUPERSEDED)
            val verified = session.updateGuidance(1100, true, detections(), evidence())
            assertEquals(CoreRestoreState.VERIFIED,
                (verified.presentationAt(1100) as NativeFramePresentation.Ready).state)
            hidden(verified, 1301, NativeFrameHiddenReason.STALE_FRAME)
        }
    }

    @Test
    fun largeMonotonicTimesDoNotOverflowAgeCalculation() {
        val snapshot = NativeFrameSnapshot(
            Long.MAX_VALUE - 100,
            NativeFramePresentation.Ready(CoreRestoreState.GUIDING, null),
        )
        assertEquals(CoreRestoreState.GUIDING,
            (snapshot.presentationAt(Long.MAX_VALUE) as NativeFramePresentation.Ready).state)
        hidden(snapshot, Long.MIN_VALUE, NativeFrameHiddenReason.INVALID_CLOCK)
    }
}
