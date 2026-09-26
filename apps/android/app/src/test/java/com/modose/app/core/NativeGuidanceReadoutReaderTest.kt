package com.modose.app.core

import org.junit.Assert.*
import org.junit.Test

class NativeGuidanceReadoutReaderTest {
    private val basis = NativeWorldBasis.fromGeometry(
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
    private val target = NativeTablePosition(0.0, 0.0)
    private fun snapshot(
        guide: NativeGuidance? = NativeGuidance.Move(7, NativeTablePosition(0.3, 0.0), target, 0.3),
        state: CoreRestoreState = CoreRestoreState.GUIDING,
    ): Pair<NativeFrameSnapshot, NativeWorldFrameSnapshot> {
        val source = NativeFrameSnapshot(100L, NativeFramePresentation.Ready(state, guide))
        return source to NativeWorldFrameSnapshot(source, basis)
    }
    private fun read(snapshot: NativeWorldFrameSnapshot?, now: Long = 100L, ids: Set<Int> = setOf(7)) =
        NativeGuidanceReadoutReader.read(snapshot, now, ids)

    @Test
    fun readsMeasuredDistanceAndObjectWithoutRoundingOrExtrapolating() {
        assertEquals(NativeGuidanceReadout.Move(7, 0.3), read(snapshot().second))
    }

    @Test
    fun expiryAndClockRollbackRemoveDistance() {
        val frame = snapshot().second
        assertEquals(NativeGuidanceReadout.Move(7, 0.3), read(frame, 300L))
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.STALE_FRAME), read(frame, 301L))
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.INVALID_CLOCK), read(frame, 99L))
    }

    @Test
    fun revocationImmediatelyRemovesPreviouslyVisibleDistance() {
        val (source, frame) = snapshot()
        assertTrue(read(frame) is NativeGuidanceReadout.Move)
        source.invalidate()
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.SUPERSEDED), read(frame))
    }

    @Test
    fun absenceAndUnknownIdsCannotBecomeCompletion() {
        assertEquals(NativeGuidanceReadout.Waiting, read(null))
        assertEquals(NativeGuidanceReadout.Waiting, read(snapshot(guide = null).second))
        assertEquals(NativeGuidanceReadout.Unavailable, read(snapshot().second, ids = setOf(8)))
        assertEquals(NativeGuidanceReadout.Unavailable, read(null, ids = emptySet()))
        assertEquals(NativeGuidanceReadout.Unavailable, read(null, now = -1L))
    }

    @Test
    fun localRingAndOrientationRemainDistinctFromFinalConfirmation() {
        assertEquals(NativeGuidanceReadout.NearTarget(7), read(snapshot(NativeGuidance.Ring(7, target)).second))
        assertEquals(NativeGuidanceReadout.OrientationRequired(7),
            read(snapshot(NativeGuidance.CheckOrientation(7, target)).second))
        for ((state, expected) in mapOf(
            CoreRestoreState.AWAITING_VERIFICATION to NativeGuidanceReadout.ConfirmationRequired,
            CoreRestoreState.VERIFYING to NativeGuidanceReadout.Verifying,
            CoreRestoreState.VERIFIED to NativeGuidanceReadout.Verified,
            CoreRestoreState.MANUAL_CONFIRMATION to NativeGuidanceReadout.ManualConfirmation)) {
            assertEquals(expected, read(snapshot(state = state).second))
        }
    }

    @Test
    fun recoveryReasonsContainNoInventedPositionOrDistance() {
        for (reason in NativeRecoveryReason.entries) {
            assertEquals(NativeGuidanceReadout.Recover(7, reason),
                read(snapshot(NativeGuidance.Recover(7, reason)).second))
        }
    }

    @Test
    fun hiddenTrackingStateDoesNotReusePreviousMove() {
        val frame = NativeWorldFrameSnapshot(NativeFrameSnapshot(100L,
            NativeFramePresentation.Hidden(NativeFrameHiddenReason.TRACKING_LOST)), basis)
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.TRACKING_LOST), read(frame))
    }

    @Test
    fun verifiedDisplayExpiresAndIsRevokedLikeMovement() {
        val (source, frame) = snapshot(guide = null, state = CoreRestoreState.VERIFIED)
        assertEquals(NativeGuidanceReadout.Verified, read(frame))
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.STALE_FRAME), read(frame, 301L))
        source.invalidate()
        assertEquals(NativeGuidanceReadout.Hidden(NativeFrameHiddenReason.SUPERSEDED), read(frame))
    }

    @Test
    fun invalidNativeDistanceFailsClosed() {
        for (distance in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(NativeGuidanceReadout.Unavailable, read(snapshot(
                NativeGuidance.Move(7, target, target, distance)).second))
        }
    }
}
