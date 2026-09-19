package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeWorldGuidanceContractTest {
    private fun rotatedGeometry() = tableGeometry().apply {
        this[0] = 10.0; this[1] = 2.0; this[2] = 20.0
        this[3] = 0.0; this[4] = 0.0; this[5] = -1.0
        this[6] = 1.0; this[7] = 0.0; this[8] = 0.0
    }

    private fun worldDetections(x: Double = 10.4, z: Double = 19.7) = listOf(
        CoreDetection(10, CoreVector(x, 3.0, z), CoreVector(0.0, -1.0, 0.0), false),
    )

    private fun point(expected: CoreVector, actual: CoreVector) {
        assertEquals(expected.x, actual.x, 1e-8)
        assertEquals(expected.y, actual.y, 1e-8)
        assertEquals(expected.z, actual.z, 1e-8)
    }

    @Test
    fun realNativeGuideUsesTheRotatedAndTranslatedSavedBasis() {
        NativeSceneSession.create(rotatedGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0)).use {
            val frame = it.updateWorldGuidance(0, true, worldDetections(), evidence())
            val ready = frame.presentationAt(0) as NativeWorldFramePresentation.Ready
            assertEquals(CoreRestoreState.GUIDING, ready.state)
            val move = ready.guidance as NativeWorldGuidance.Move
            assertEquals(1, move.objectId)
            point(CoreVector(10.4, 2.0, 19.7), move.from)
            point(CoreVector(10.0, 2.0, 20.0), move.to)
            assertEquals(0.5, move.distanceMeters, 1e-8)
        }
    }

    @Test
    fun changingTheCallersGeometryDoesNotMoveTheSavedGuide() {
        val geometry = rotatedGeometry()
        NativeSceneSession.create(geometry, intArrayOf(1), doubleArrayOf(0.0, 0.0)).use {
            geometry.fill(Double.NaN)
            val frame = it.updateWorldGuidance(0, true, worldDetections(), evidence())
            val move = (frame.presentationAt(0) as NativeWorldFramePresentation.Ready).guidance
                as NativeWorldGuidance.Move
            point(CoreVector(10.0, 2.0, 20.0), move.to)
        }
    }

    @Test
    fun ringAndOrientationKeepThePlaneAxesWithoutInventingRotation() {
        NativeSceneSession.create(rotatedGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0)).use {
            val ringFrame = it.updateWorldGuidance(0, true, worldDetections(10.0, 20.0), evidence())
            val ring = (ringFrame.presentationAt(0) as NativeWorldFramePresentation.Ready).guidance
                as NativeWorldGuidance.Ring
            point(CoreVector(10.0, 2.0, 20.0), ring.target.position)
            assertEquals(CoreVector(0.0, 0.0, -1.0), ring.target.xAxis)
            assertEquals(CoreVector(1.0, 0.0, 0.0), ring.target.zAxis)
            val orientationFrame = it.updateWorldGuidance(100, true, worldDetections(10.0, 20.0),
                listOf(CorePairEvidence(1, 10, 1.0, 1.0, 1.0, false)))
            val orientation = (orientationFrame.presentationAt(100)
                as NativeWorldFramePresentation.Ready).guidance as NativeWorldGuidance.CheckOrientation
            assertEquals(ring.target, orientation.target)
        }
    }

    @Test
    fun recoveryHasNoWorldPositionAndNoGuideDoesNotMeanVerified() {
        newSession().use {
            val unseen = it.updateWorldGuidance(0, true, emptyList(), emptyList())
            val recovery = (unseen.presentationAt(0) as NativeWorldFramePresentation.Ready).guidance
            assertEquals(NativeWorldGuidance.Recover(1, NativeRecoveryReason.UNOBSERVED), recovery)
        }
        newSession().use {
            aligned(it)
            val local = it.updateWorldGuidance(900, true, detections(), evidence())
            val ready = local.presentationAt(900) as NativeWorldFramePresentation.Ready
            assertEquals(CoreRestoreState.AWAITING_VERIFICATION, ready.state)
            assertNull(ready.guidance)
        }
    }

    @Test
    fun worldFramesShareTrackingExpiryAndCloseInvalidation() {
        val session = newSession()
        session.use {
            val first = it.updateWorldGuidance(0, true, detections(0.3), evidence())
            assertEquals(NativeFrameHiddenReason.STALE_FRAME,
                (first.presentationAt(201) as NativeWorldFramePresentation.Hidden).source.reason)
            val lost = it.updateWorldGuidance(100, false, emptyList(), emptyList())
            assertEquals(NativeFrameHiddenReason.SUPERSEDED,
                (first.presentationAt(100) as NativeWorldFramePresentation.Hidden).source.reason)
            assertEquals(NativeFrameHiddenReason.TRACKING_LOST,
                (lost.presentationAt(100) as NativeWorldFramePresentation.Hidden).source.reason)
            val recovered = it.updateWorldGuidance(200, true, detections(0.3), evidence())
            it.close()
            assertEquals(NativeFrameHiddenReason.SUPERSEDED,
                (recovered.presentationAt(200) as NativeWorldFramePresentation.Hidden).source.reason)
        }
    }

    @Test
    fun overflowingProjectionCannotExposeEvenAnInjectedVerifiedState() {
        val geometry = tableGeometry().apply { this[0] = Double.MAX_VALUE }
        val source = NativeFrameSnapshot(0, NativeFramePresentation.Ready(
            CoreRestoreState.VERIFIED,
            NativeGuidance.Move(1, NativeTablePosition(Double.MAX_VALUE, 0.0),
                NativeTablePosition(0.0, 0.0), 1.0),
        ))
        val frame = NativeWorldFrameSnapshot(source, NativeWorldBasis.fromGeometry(geometry))
        assertEquals(NativeWorldFramePresentation.InvalidProjection, frame.presentationAt(0))
    }

    @Test
    fun hiddenFramesDoNotProjectInvalidCoordinates() {
        val source = NativeFrameSnapshot(0, NativeFramePresentation.Ready(
            CoreRestoreState.GUIDING, NativeGuidance.Ring(1, NativeTablePosition(Double.NaN, 0.0)),
        ))
        val frame = NativeWorldFrameSnapshot(source, NativeWorldBasis.fromGeometry(tableGeometry()))
        assertEquals(NativeWorldFramePresentation.InvalidProjection, frame.presentationAt(0))
        source.invalidate()
        assertEquals(NativeFrameHiddenReason.SUPERSEDED,
            (frame.presentationAt(0) as NativeWorldFramePresentation.Hidden).source.reason)
    }

    @Test
    fun projectionPreservesRustDistanceRatherThanRecomputingIt() {
        val basis = NativeWorldBasis.fromGeometry(rotatedGeometry())
        val guide = basis.project(NativeGuidance.Move(
            7, NativeTablePosition(0.2, 0.0), NativeTablePosition(0.0, 0.0), 0.123,
        )) as NativeWorldGuidance.Move
        assertEquals(0.123, guide.distanceMeters, 0.0)
        assertEquals(7, guide.objectId)
    }
}
