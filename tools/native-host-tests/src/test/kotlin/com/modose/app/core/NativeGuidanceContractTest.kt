package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeGuidanceContractTest {
    @Test
    fun realNativeMovePreservesTableCoordinatesAndDistance() {
        newSession().use { session ->
            session.update(0, true, detections(0.3), evidence())
            val move = session.guidance(0) as NativeGuidance.Move
            assertEquals(1, move.objectId)
            assertEquals(NativeTablePosition(0.3, 0.0), move.from)
            assertEquals(NativeTablePosition(0.0, 0.0), move.to)
            assertEquals(0.3, move.distanceMeters, 1e-9)
        }
    }

    @Test
    fun ringBecomesNoActionButStillRequiresVerification() {
        newSession().use { session ->
            session.update(0, true, detections(), evidence())
            assertEquals(NativeGuidance.Ring(1, NativeTablePosition(0.0, 0.0)), session.guidance(0))
            for (time in 100L..800L step 100) {
                session.update(time, true, detections(), evidence())
            }
            assertNull(session.guidance(800))
            val ticket = session.beginVerification(800)
            assertEquals(CoreRestoreState.VERIFIED,
                session.completeVerification(ticket, 800, verifiedResult()))
        }
    }

    @Test
    fun orientationIsNotMisrepresentedAsAnAngleOrCompletedRing() {
        newSession().use { session ->
            session.update(0, true, detections(),
                listOf(CorePairEvidence(1, 10, 1.0, 1.0, 1.0, false)))
            assertEquals(NativeGuidance.CheckOrientation(1, NativeTablePosition(0.0, 0.0)),
                session.guidance(0))
        }
    }

    @Test
    fun staleOrLostTrackingRemovesArrowAndFreshFrameRestoresIt() {
        newSession().use { session ->
            assertEquals(NativeGuidance.Recover(1, NativeRecoveryReason.UNOBSERVED), session.guidance(0))
            session.update(0, true, detections(0.2), evidence())
            assertEquals(NativeGuidance.Recover(1, NativeRecoveryReason.TRACKING_LOST),
                session.guidance(201))
            session.update(300, false, emptyList(), emptyList())
            assertEquals(NativeGuidance.Recover(1, NativeRecoveryReason.TRACKING_LOST),
                session.guidance(300))
            session.update(400, true, detections(0.2), evidence())
            assertEquals(1, (session.guidance(400) as NativeGuidance.Move).objectId)
        }
    }

    @Test
    fun invalidClockRetiresOnlyTheAddressedOwner() {
        newSession().use { other ->
            val rejected = newSession()
            assertThrows(IllegalArgumentException::class.java) { rejected.guidance(-1) }
            assertThrows(IllegalStateException::class.java) { rejected.guidance(0) }
            rejected.close()
            assertEquals(NativeGuidance.Recover(1, NativeRecoveryReason.UNOBSERVED), other.guidance(0))
        }
    }

    @Test
    fun nativeBoundaryRejectsUnknownHandleAndNegativeClock() {
        assertThrows(IllegalStateException::class.java) {
            NativeSceneBindings.nativeGuidance(Long.MAX_VALUE, 0)
        }
        val owner = NativeSceneBindings.nativeCreate(
            tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0))
        assertThrows(IllegalStateException::class.java) { NativeSceneBindings.nativeGuidance(owner, -1) }
        assertThrows(IllegalStateException::class.java) { NativeSceneBindings.nativeGuidance(owner, 0) }
    }

    @Test
    fun decoderRejectsMalformedOrUnknownWireValues() {
        for (payload in listOf(
            doubleArrayOf(), doubleArrayOf(2.0, 0.0, 0.0),
            doubleArrayOf(1.0, 1.0, 0.0), doubleArrayOf(1.0, 1.5, 2.0, 0.0, 0.0),
            doubleArrayOf(1.0, 1.0, 9.0), doubleArrayOf(1.0, 1.0, 2.0),
            doubleArrayOf(1.0, 1.0, 4.0, 9.0),
            doubleArrayOf(1.0, 1.0, 2.0, Double.NaN, 0.0),
            doubleArrayOf(1.0, 1.0, 1.0, 0.0, 0.0, 0.1, 0.0, -0.1),
            doubleArrayOf(1.0, 1.0, 4.0, 2.0, 0.0),
        )) {
            assertThrows(IllegalStateException::class.java) { decodeNativeGuidance(payload) }
        }
    }

    @Test
    fun recoveryReasonCodesRemainDistinct() {
        for ((code, reason) in NativeRecoveryReason.entries.withIndex()) {
            assertEquals(NativeGuidance.Recover(1, reason),
                decodeNativeGuidance(doubleArrayOf(1.0, 1.0, 4.0, code.toDouble())))
        }
    }
}
