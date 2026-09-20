package com.modose.app.vision.tracking

import com.modose.app.core.CorePairEvidence
import com.modose.app.core.NativeFrameDropReason
import com.modose.app.core.NativeFrameProcessing
import com.modose.app.core.NativeGuidanceEpoch
import com.modose.app.core.NativeGuidancePipeline
import com.modose.app.core.NativeGuidanceSink
import com.modose.app.core.NativeImageCapture
import com.modose.app.core.NativeWorldFrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGuidanceBridgeContractTest {
    @Test
    fun alignedEvidenceReachesNativeVerificationAfterStableFrames() = Fixture().use { f ->
        for (time in 0L..800L step 100L) {
            val delivery = f.process(time)
            assertNull(delivery.rejection)
            assertEquals(NativeFrameProcessing.Published(time), delivery.processing)
        }
        assertNotNull(f.pipeline.beginVerification(f.pipeline.epoch))
    }

    @Test
    fun zeroTrackingIdIsAHintRatherThanTheNativeObjectId() = Fixture().use { f ->
        f.resolve = { observation ->
            assertEquals(0, observation.objects.single().detection.trackingId)
            assertEquals(1, observation.objects.single().currentId)
            proof(observation)
        }
        assertNull(f.process(0).rejection)
    }

    @Test
    fun missingTrackingIdStillRequiresExplicitEvidence() = Fixture().use { f ->
        f.resolve = { observation ->
            assertNull(observation.objects.single().detection.trackingId)
            proof(observation)
        }
        assertNull(f.process(0, detected(null)).rejection)
        f.resolve = { null }
        assertEquals(ImageGuidanceRejection.EVIDENCE_UNAVAILABLE, f.process(100, detected(null)).rejection)
    }

    @Test
    fun unavailableEvidenceRevokesThePreviousSnapshot() = Fixture().use { f ->
        f.process(0)
        val previous = f.snapshots.single()
        val presentation = previous.presentationAt(0)
        f.resolve = { null }
        assertEquals(ImageGuidanceRejection.EVIDENCE_UNAVAILABLE, f.process(100).rejection)
        assertNotEquals(presentation, previous.presentationAt(100))
    }

    @Test
    fun evidenceFromAnotherObservationOfTheSameImageIsRejected() = rejectedProof { valid ->
        valid.copy(observation = GuidanceImageObservation(
            valid.observation.capture, valid.observation.objects,
        ))
    }

    @Test
    fun unknownSavedIdIsRejected() = rejectedProof { valid ->
        valid.copy(pairs = listOf(valid.pairs.single().copy(savedId = 2)))
    }

    @Test
    fun unknownCurrentIdIsRejected() = rejectedProof { valid ->
        valid.copy(pairs = listOf(valid.pairs.single().copy(currentId = 2)))
    }

    @Test
    fun duplicatePairsAreRejected() = rejectedProof { valid ->
        valid.copy(pairs = valid.pairs + valid.pairs)
    }

    @Test
    fun everyScoreMustBeFiniteAndWithinTheUnitInterval() {
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            rejectedProof { it.copy(pairs = listOf(it.pairs.single().copy(semanticScore = score))) }
            rejectedProof { it.copy(pairs = listOf(it.pairs.single().copy(embeddingScore = score))) }
            rejectedProof { it.copy(pairs = listOf(it.pairs.single().copy(signatureScore = score))) }
        }
    }

    @Test
    fun occlusionMustBeProvidedForExactlyTheDetectedObjects() {
        rejectedProof { it.copy(occludesOther = emptyMap()) }
        rejectedProof { it.copy(occludesOther = mapOf(1 to false, 2 to false)) }
    }

    @Test
    fun duplicateTrackerIdsDoNotBecomeTwoConfidentObjects() = Fixture().use { f ->
        val objects = detected().objects
        val delivery = f.process(0, ImageDetectionResult.Detected(objects + objects))
        assertEquals(ImageGuidanceRejection.INVALID_OBJECTS, delivery.rejection)
        assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
    }

    @Test
    fun detectorFailureRevokesThePreviousSnapshot() = Fixture().use { f ->
        f.process(0)
        val previous = f.snapshots.single()
        val presentation = previous.presentationAt(0)
        val delivery = f.process(100, ImageDetectionResult.Failed(DetectionFailure.DETECTOR))
        assertEquals(ImageGuidanceRejection.DETECTION_FAILED, delivery.rejection)
        assertNotEquals(presentation, previous.presentationAt(100))
    }

    @Test
    fun evidenceProviderFailureInvalidatesInsteadOfReusingEvidence() = Fixture().use { f ->
        f.resolve = { throw IllegalStateException("evidence unavailable") }
        val delivery = f.process(0)
        assertEquals(ImageGuidanceRejection.EVIDENCE_FAILED, delivery.rejection)
        assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
    }

    @Test
    fun foreignEpochDoesNotTouchTheActivePipeline() = Fixture().use { f ->
        Fixture().use { other ->
            val delivery = f.bridge.process(f.capture(0, epoch = other.pipeline.epoch), detected())
            assertEquals(ImageGuidanceRejection.FOREIGN_STREAM, delivery.rejection)
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.FOREIGN_STREAM), delivery.processing)
            assertEquals(0, f.snapshots.size)
        }
    }

    @Test
    fun duplicateImageCannotPublishAgain() = Fixture().use { f ->
        f.process(0)
        f.now = 100
        val delivery = f.bridge.process(f.capture(100, imageTimestamp = 1_000_000), detected())
        assertEquals(ImageGuidanceRejection.NON_INCREASING, delivery.rejection)
        assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.NON_INCREASING), delivery.processing)
        assertEquals(1, f.snapshots.size)
    }

    @Test
    fun delayedRecognitionCannotBypassTheCoreAgeGate() = Fixture().use { f ->
        f.now = 201
        val delivery = f.bridge.process(f.capture(0), detected())
        assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.STALE), delivery.processing)
        assertEquals(0, f.snapshots.size)
    }

    @Test
    fun completionAfterPipelineCloseCannotPublish() = Fixture().use { f ->
        f.pipeline.close()
        val delivery = f.process(0)
        assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.CLOSED), delivery.processing)
        assertEquals(0, f.snapshots.size)
    }

    @Test
    fun imageBoundaryViolationIsRejectedByTheRealProjectionPath() = Fixture().use { f ->
        val outside = ImageDetectionResult.Detected(listOf(DetectedImageObject(0, 40, 40, 110, 60)))
        val delivery = f.process(0, outside)
        assertEquals(ImageGuidanceRejection.PROJECTION, delivery.rejection)
        assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
    }

    private fun rejectedProof(change: (GuidanceImageEvidence) -> GuidanceImageEvidence) =
        Fixture().use { f ->
            f.resolve = { change(proof(it)) }
            val delivery = f.process(0)
            assertEquals(ImageGuidanceRejection.INVALID_EVIDENCE, delivery.rejection)
            assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
        }

    private class Fixture : AutoCloseable {
        var now = 0L
        val snapshots = ArrayList<NativeWorldFrameSnapshot>()
        val pipeline = NativeGuidancePipeline.open(
            "bridge-contract",
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
                -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0),
            intArrayOf(1), doubleArrayOf(0.0, 0.0), { now },
            NativeGuidanceSink { snapshots.add(it); true },
        )
        var resolve: (GuidanceImageObservation) -> GuidanceImageEvidence? = { proof(it) }
        val bridge = ImageGuidanceBridge(pipeline, intArrayOf(1), GuidanceImageEvidenceSource { resolve(it) })

        fun process(time: Long, result: ImageDetectionResult = detected()): ImageGuidanceDelivery {
            now = time
            return bridge.process(capture(time), result)
        }

        fun capture(
            time: Long,
            imageTimestamp: Long = (time + 1) * 1_000_000,
            epoch: NativeGuidanceEpoch = pipeline.epoch,
        ): NativeImageCapture = requireNotNull(NativeImageCapture.create(
            epoch, time, imageTimestamp, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            // Near -> far points down onto the y=0 table.
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
        ))

        override fun close() = pipeline.close()
    }

    companion object {
        private fun detected(trackingId: Int? = 0) =
            ImageDetectionResult.Detected(listOf(DetectedImageObject(trackingId, 40, 40, 60, 60)))

        private fun proof(observation: GuidanceImageObservation) = GuidanceImageEvidence(
            observation, listOf(CorePairEvidence(1, 1, 1.0, 1.0, 1.0, true)), mapOf(1 to false),
        )
    }
}
