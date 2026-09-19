package com.modose.app.core

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuideGeometryContractTest {
    private fun ready(guide: NativeWorldGuidance?) =
        NativeWorldFramePresentation.Ready(CoreRestoreState.GUIDING, guide)

    private fun target() = NativeWorldTarget(
        CoreVector(1.0, 2.0, 3.0), CoreVector(0.0, 0.0, -1.0), CoreVector(1.0, 0.0, 0.0),
    )

    @Test
    fun arrowContainsShaftAndTwoHeadsPointingToTheNativeTarget() {
        val result = NativeGuideGeometry.build(ready(NativeWorldGuidance.Move(
            1, CoreVector(0.0, 0.0, 0.0), CoreVector(0.3, 0.0, 0.0), 0.3,
        ))) as NativeGuideDrawData.Lines
        assertEquals(18, result.vertices.size)
        assertFalse(result.orientationCheck)
        assertEquals(0f, result.vertices[0], 0f)
        for (offset in listOf(3, 6, 12)) {
            assertEquals(0.3f, result.vertices[offset], 1e-6f)
        }
        assertTrue(result.vertices[9] < result.vertices[6])
        assertTrue(result.vertices[15] < result.vertices[12])
        assertTrue(result.vertices[11] * result.vertices[17] < 0f)
    }

    @Test
    fun ringUsesTheSuppliedPlaneRatherThanTheScreenPlane() {
        val result = NativeGuideGeometry.build(ready(NativeWorldGuidance.Ring(1, target())))
            as NativeGuideDrawData.Lines
        assertEquals(192, result.vertices.size)
        for (offset in result.vertices.indices step 3) {
            assertEquals(2f, result.vertices[offset + 1], 1e-6f)
            assertEquals(0.03, hypot(
                result.vertices[offset].toDouble() - 1.0,
                result.vertices[offset + 2].toDouble() - 3.0,
            ), 1e-6)
        }
        for (axis in 0..2) {
            assertEquals(result.vertices[axis], result.vertices[189 + axis], 1e-6f)
        }
    }

    @Test
    fun orientationHasItsOwnMarkerAndColorFlagWithoutAnInventedAngle() {
        val result = NativeGuideGeometry.build(ready(
            NativeWorldGuidance.CheckOrientation(1, target()),
        )) as NativeGuideDrawData.Lines
        assertTrue(result.orientationCheck)
        assertEquals(12, result.vertices.size)
        assertTrue(result.vertices.all { it.isFinite() })
    }

    @Test
    fun recoveryEmptyAndRejectedFramesNeverProduceVertices() {
        val frames = listOf(
            ready(null),
            ready(NativeWorldGuidance.Recover(1, NativeRecoveryReason.AMBIGUOUS)),
            NativeWorldFramePresentation.InvalidProjection,
            NativeWorldFramePresentation.Hidden(
                NativeFramePresentation.Hidden(NativeFrameHiddenReason.TRACKING_LOST)),
        )
        for (frame in frames) assertEquals(NativeGuideDrawData.Empty, NativeGuideGeometry.build(frame))
    }

    @Test
    fun floatOverflowAndNonFiniteCoordinatesAreRejected() {
        for (x in listOf(1e40, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(NativeGuideDrawData.Invalid, NativeGuideGeometry.build(ready(
                NativeWorldGuidance.Ring(1, target().copy(position = CoreVector(x, 2.0, 3.0))),
            )))
        }
    }

    @Test
    fun zeroLengthArrowHasNoInventedDirection() {
        val position = CoreVector(0.0, 0.0, 0.0)
        assertEquals(NativeGuideDrawData.Invalid, NativeGuideGeometry.build(ready(
            NativeWorldGuidance.Move(1, position, position, 0.0),
        )))
    }

    @Test
    fun revokedAndExpiredWorldSnapshotsHaveNoDrawData() {
        val source = NativeFrameSnapshot(0, NativeFramePresentation.Ready(
            CoreRestoreState.GUIDING, NativeGuidance.Ring(1, NativeTablePosition(0.0, 0.0)),
        ))
        val world = NativeWorldFrameSnapshot(source, NativeWorldBasis.fromGeometry(tableGeometry()))
        assertTrue(NativeGuideGeometry.build(world.presentationAt(200)) is NativeGuideDrawData.Lines)
        assertEquals(NativeGuideDrawData.Empty, NativeGuideGeometry.build(world.presentationAt(201)))
        source.invalidate()
        assertEquals(NativeGuideDrawData.Empty, NativeGuideGeometry.build(world.presentationAt(0)))
    }

    @Test
    fun verifiedWithNoActionDoesNotDrawASuccessMarker() {
        assertEquals(NativeGuideDrawData.Empty, NativeGuideGeometry.build(
            NativeWorldFramePresentation.Ready(CoreRestoreState.VERIFIED, null),
        ))
    }
}
