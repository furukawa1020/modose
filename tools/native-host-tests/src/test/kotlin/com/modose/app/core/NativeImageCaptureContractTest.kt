package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageCaptureContractTest {
    // Inverse clip->world: near y=2, far y=0, camera looks down on the table.
    private fun inverse() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
        0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0,
    )
    private fun affine() = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private fun capture(
        epoch: NativeGuidanceEpoch, time: Long = 0, transform: DoubleArray = affine(),
        matrix: DoubleArray = inverse(),
    ) = NativeImageCapture.create(epoch, time, 1234, 100, 100, 100, 100, transform, matrix)!!
    private fun result(epoch: NativeGuidanceEpoch) = NativeImageRecognition(
        epoch, 1234, listOf(NativeImageBox(10, 55.0, 45.0, 65.0, 55.0, false)), evidence(),
    )
    private fun detection(capture: NativeImageCapture, result: NativeImageRecognition) =
        (capture.project(result) as NativeImageProjection.Projected).detections.single()

    @Test
    fun bboxCenterProducesAPhysicalDownwardRay() {
        val epoch = NativeGuidanceEpoch("scene")
        val ray = detection(capture(epoch), result(epoch))
        assertEquals(10, ray.currentId)
        assertEquals(0.2, ray.origin.x, 1e-9)
        assertEquals(2.0, ray.origin.y, 1e-9)
        assertEquals(0.0, ray.origin.z, 1e-9)
        assertEquals(CoreVector(0.0, -1.0, 0.0), ray.direction)
    }

    @Test
    fun imageRotationIsAppliedBeforeCameraUnprojection() {
        val epoch = NativeGuidanceEpoch("scene")
        val ray = detection(capture(epoch, transform = doubleArrayOf(0.0, -1.0, 1.0, 0.0, 100.0, 0.0)),
            result(epoch))
        assertEquals(0.0, ray.origin.x, 1e-9)
        assertEquals(-0.2, ray.origin.z, 1e-9)
    }

    @Test
    fun homogeneousCoordinatesAreDividedByW() {
        val epoch = NativeGuidanceEpoch("scene")
        val matrix = inverse().apply { this[11] = 0.25 }
        val ray = detection(capture(epoch, matrix = matrix), result(epoch))
        assertEquals(0.2 / 0.75, ray.origin.x, 1e-9)
        assertEquals(2.0 / 0.75, ray.origin.y, 1e-9)
    }

    @Test
    fun capturedMatricesDoNotFollowLaterCallerMutations() {
        val epoch = NativeGuidanceEpoch("scene")
        val matrix = inverse()
        val transform = affine()
        val captured = capture(epoch, transform = transform, matrix = matrix)
        matrix.fill(Double.NaN)
        transform.fill(Double.NaN)
        assertEquals(0.2, detection(captured, result(epoch)).origin.x, 1e-9)
    }

    @Test
    fun anotherImageOrGenerationIsNotProjected() {
        val epoch = NativeGuidanceEpoch("scene")
        val captured = capture(epoch)
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.IMAGE_MISMATCH),
            captured.project(result(epoch).copy(imageTimestampNanos = 9999)))
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.FOREIGN_STREAM),
            captured.project(result(NativeGuidanceEpoch("scene"))))
    }

    @Test
    fun invalidBoxesAndDuplicateIdsAreRejectedAsABatch() {
        val epoch = NativeGuidanceEpoch("scene")
        val captured = capture(epoch)
        val input = result(epoch)
        for (box in listOf(
            input.boxes.single().copy(left = -1.0),
            input.boxes.single().copy(right = 101.0),
            input.boxes.single().copy(top = Double.NaN),
            input.boxes.single().copy(left = 65.0),
        )) assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.INVALID_BOX),
            captured.project(input.copy(boxes = listOf(box))))
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.INVALID_OBJECT_SET),
            captured.project(input.copy(boxes = input.boxes + input.boxes)))
    }

    @Test
    fun offscreenCentersAndInvalidHomogeneousRaysAreRejected() {
        val epoch = NativeGuidanceEpoch("scene")
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.OUTSIDE_VIEW),
            capture(epoch, transform = affine().apply { this[4] = 1000.0 }).project(result(epoch)))
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.INVALID_RAY),
            capture(epoch, matrix = DoubleArray(16)).project(result(epoch)))
        assertEquals(NativeImageProjection.Rejected(NativeImageProjectionError.INVALID_RAY),
            capture(epoch, matrix = inverse().apply { this[9] = 0.0 }).project(result(epoch)))
    }

    @Test
    fun invalidCaptureMetadataIsRejected() {
        val epoch = NativeGuidanceEpoch("scene")
        assertNull(NativeImageCapture.create(epoch, -1, 1234, 100, 100, 100, 100, affine(), inverse()))
        assertNull(NativeImageCapture.create(epoch, 0, 0, 100, 100, 100, 100, affine(), inverse()))
        assertNull(NativeImageCapture.create(epoch, 0, 1234, 0, 100, 100, 100, affine(), inverse()))
        assertNull(NativeImageCapture.create(epoch, 0, 1234, 100, 100, 100, 100, DoubleArray(6), inverse()))
    }

    @Test
    fun imageBoxFlowsThroughRealRustIntoWorldGuidance() {
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        NativeGuidancePipeline.open("scene", tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0),
            { 0L }, NativeGuidanceSink { published.add(it); true }).use { pipeline ->
            val processed = pipeline.submitImage(capture(pipeline.epoch), result(pipeline.epoch))
            assertNull(processed.projectionError)
            assertEquals(NativeFrameProcessing.Published(0), processed.processing)
            val guide = (published.single().presentationAt(0) as NativeWorldFramePresentation.Ready)
                .guidance as NativeWorldGuidance.Move
            assertEquals(0.2, guide.distanceMeters, 1e-9)
        }
    }

    @Test
    fun projectionFailureInvalidatesThePreviousNativeGuide() {
        var now = 0L
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        NativeGuidancePipeline.open("scene", tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0),
            { now }, NativeGuidanceSink { published.add(it); true }).use { p ->
            p.submitImage(capture(p.epoch), result(p.epoch))
            val previous = published.single()
            now = 100
            val bad = p.submitImage(capture(p.epoch, 100), result(p.epoch).copy(imageTimestampNanos = 9999))
            assertEquals(NativeImageProjectionError.IMAGE_MISMATCH, bad.projectionError)
            assertEquals(NativeFrameProcessing.Published(100), bad.processing)
            assertTrue(previous.presentationAt(100) is NativeWorldFramePresentation.Hidden)
            assertTrue(published.last().presentationAt(100) is NativeWorldFramePresentation.Hidden)
        }
    }

    @Test
    fun foreignCaptureCannotInvalidateTheCurrentPipeline() {
        var publications = 0
        NativeGuidancePipeline.open("scene", tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0),
            { 0L }, NativeGuidanceSink { publications++; true }).use { p ->
            val submitted = p.submitImage(capture(NativeGuidanceEpoch("scene")), result(p.epoch))
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.FOREIGN_STREAM), submitted.processing)
            assertEquals(0, publications)
            assertEquals(NativeFrameProcessing.Published(0),
                p.submitImage(capture(p.epoch), result(p.epoch)).processing)
        }
    }

    @Test
    fun delayedRecognitionCannotBypassThePipelineAgeGate() {
        NativeGuidancePipeline.open("scene", tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0),
            { 300L }, NativeGuidanceSink { error("must not publish") }).use { p ->
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.STALE),
                p.submitImage(capture(p.epoch), result(p.epoch)).processing)
        }
    }
}
