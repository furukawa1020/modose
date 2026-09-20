package com.modose.app.vision.tracking

import com.modose.app.core.NativeFrameProcessing
import com.modose.app.core.NativeGuidanceEpoch
import com.modose.app.core.NativeGuidancePipeline
import com.modose.app.core.NativeGuidanceSink
import com.modose.app.core.NativeImageCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeasuredEvidenceContractTest {
    @Test
    fun cosineProducesKnownScores() {
        val reference = embedding(1.0, 0.0)
        assertEquals(0.6, requireNotNull(reference.similarity(embedding(3.0, 4.0))), 1e-12)
        assertEquals(0.0, requireNotNull(reference.similarity(embedding(0.0, 1.0))), 1e-12)
        assertEquals(0.0, requireNotNull(reference.similarity(embedding(-1.0, 0.0))), 1e-12)
    }

    @Test
    fun incompatibleEmbeddingSpacesAreRejected() {
        val reference = embedding(1.0, 0.0)
        assertNull(reference.similarity(requireNotNull(ImageEmbedding.create("other", "crop", doubleArrayOf(1.0, 0.0)))))
        assertNull(reference.similarity(requireNotNull(ImageEmbedding.create("model", "other", doubleArrayOf(1.0, 0.0)))))
        assertNull(reference.similarity(embedding(1.0)))
    }

    @Test
    fun invalidEmbeddingVectorsAreRejected() {
        for (values in listOf(doubleArrayOf(), doubleArrayOf(0.0), doubleArrayOf(Double.NaN),
            doubleArrayOf(Double.POSITIVE_INFINITY), DoubleArray(4097) { 1.0 })) {
            assertNull(ImageEmbedding.create("model", "crop", values))
        }
        assertNull(ImageEmbedding.create("", "crop", doubleArrayOf(1.0)))
        assertNull(ImageEmbedding.create("model", "", doubleArrayOf(1.0)))
    }

    @Test
    fun extremeFiniteValuesDoNotOverflowOrUnderflowNormalization() {
        val reference = embedding(1.0, 1.0)
        for (scale in listOf(Double.MAX_VALUE, Double.MIN_VALUE)) {
            assertEquals(1.0, requireNotNull(reference.similarity(embedding(scale, scale))), 1e-12)
        }
    }

    @Test
    fun embeddingDoesNotRetainMutableInput() {
        val values = doubleArrayOf(1.0, 0.0)
        val reference = requireNotNull(ImageEmbedding.create("model", "crop", values))
        values[0] = 0.0
        values[1] = 1.0
        assertEquals(1.0, requireNotNull(reference.similarity(embedding(1.0, 0.0))), 1e-12)
    }

    @Test
    fun rgbSignatureUsesIndependentChannelDistributions() {
        assertEquals(1.0, requireNotNull(signature(RED).similarity(signature(RED))), 1e-12)
        // Red and blue share only the green-channel bin.
        assertEquals(1.0 / 3.0, requireNotNull(signature(RED).similarity(signature(BLUE))), 1e-12)
    }

    @Test
    fun transparentPixelsDoNotContributeToTheHistogram() {
        assertEquals(1.0, requireNotNull(signature(RED).similarity(signature(0, RED))), 1e-12)
    }

    @Test
    fun emptyOrTransparentCropsAreRejected() {
        assertNull(RgbAppearanceSignature.fromArgb("crop", intArrayOf()))
        assertNull(RgbAppearanceSignature.fromArgb("crop", intArrayOf(0)))
        assertNull(RgbAppearanceSignature.fromArgb("", intArrayOf(RED)))
    }

    @Test
    fun signatureCopiesInputAndRejectsDifferentPreprocessing() {
        val pixels = intArrayOf(RED)
        val reference = requireNotNull(RgbAppearanceSignature.fromArgb("crop", pixels))
        pixels[0] = BLUE
        assertEquals(1.0, requireNotNull(reference.similarity(signature(RED))), 1e-12)
        assertNull(reference.similarity(requireNotNull(RgbAppearanceSignature.fromArgb("other", intArrayOf(RED)))))
    }

    @Test
    fun evidencePreservesSemanticAndOrientationButComputesDistinctVisualScores() = Fixture().use { f ->
        f.reader = { observation ->
            measurements(observation).copy(
                appearances = mapOf(1 to MeasuredAppearance(embedding(3.0, 4.0), signature(BLUE))),
                semanticPairs = listOf(SemanticPairMeasurement(1, 1, 0.8, false)),
            )
        }
        val pair = requireNotNull(f.source.resolve(f.observation())).pairs.single()
        assertEquals(0.8, pair.semanticScore, 1e-12)
        assertEquals(0.6, pair.embeddingScore, 1e-12)
        assertEquals(1.0 / 3.0, pair.signatureScore, 1e-12)
        assertFalse(pair.orientationAligned)
    }

    @Test
    fun measuredEvidenceReachesRealNativeVerificationAfterStability() = Fixture().use { f ->
        for (time in 0L..800L step 100L) {
            f.now = time
            val delivery = f.bridge.process(f.capture(time),
                ImageDetectionResult.Detected(listOf(DetectedImageObject(0, 40, 40, 60, 60))))
            assertNull(delivery.rejection)
            assertEquals(NativeFrameProcessing.Published(time), delivery.processing)
        }
        assertNotNull(f.pipeline.beginVerification(f.pipeline.epoch))
    }

    @Test
    fun unavailableMeasurementsNeverProduceDefaultEvidence() = Fixture().use { f ->
        f.reader = { null }
        assertNull(f.source.resolve(f.observation()))
    }

    @Test
    fun measurementsOfAnotherObservationAreRejected() = Fixture().use { f ->
        f.reader = { measurements(GuidanceImageObservation(it.capture, it.objects)) }
        assertNull(f.source.resolve(f.observation()))
    }

    @Test
    fun evidenceSourceCannotBeReusedAcrossRestartEpochs() = Fixture().use { f ->
        assertNotNull(f.source.resolve(f.observation()))
        assertNull(f.source.resolve(f.observation(NativeGuidanceEpoch("measured"))))
    }

    @Test
    fun differentSceneIsRejectedWithoutClaimingTheSource() = Fixture().use { f ->
        assertNull(f.source.resolve(f.observation(NativeGuidanceEpoch("other"))))
        assertNotNull(f.source.resolve(f.observation()))
    }

    @Test
    fun missingFeaturesAndOcclusionAreRejected() = Fixture().use { f ->
        f.reader = { measurements(it).copy(appearances = emptyMap()) }
        assertNull(f.source.resolve(f.observation()))
        f.reader = { measurements(it).copy(occludesOther = emptyMap()) }
        assertNull(f.source.resolve(f.observation()))
    }

    @Test
    fun invalidSemanticScoresIdsAndDuplicatePairsAreRejected() = Fixture().use { f ->
        val valid = SemanticPairMeasurement(1, 1, 1.0, true)
        for (pairs in listOf(listOf(valid.copy(confidence = Double.NaN)),
            listOf(valid.copy(confidence = -0.1)), listOf(valid.copy(confidence = 1.1)),
            listOf(valid.copy(savedId = 2)), listOf(valid.copy(currentId = 2)),
            listOf(valid, valid))) {
            f.reader = { measurements(it).copy(semanticPairs = pairs) }
            assertNull(f.source.resolve(f.observation()))
        }
    }

    @Test
    fun unknownOrientationIsNotTreatedAsAligned() = Fixture().use { f ->
        f.reader = { measurements(it).copy(semanticPairs = listOf(SemanticPairMeasurement(1, 1, 1.0, null))) }
        assertNull(f.source.resolve(f.observation()))
    }

    @Test
    fun incompatibleCurrentModelPreventsEvidence() = Fixture().use { f ->
        val other = requireNotNull(ImageEmbedding.create("other-model", "crop", doubleArrayOf(1.0, 0.0)))
        f.reader = { measurements(it).copy(appearances = mapOf(1 to MeasuredAppearance(other, signature(RED)))) }
        assertNull(f.source.resolve(f.observation()))
    }

    @Test
    fun baselineCollectionIsSnapshotted() = Fixture().use { f ->
        val baseline = mutableListOf(SavedMeasuredAppearance(1, appearance()))
        val source = MeasuredGuidanceEvidenceSource("measured", baseline, GuidanceMeasurementReader { measurements(it) })
        baseline.clear()
        assertNotNull(source.resolve(f.observation()))
    }

    @Test
    fun readerFailureBecomesBridgeInvalidation() = Fixture().use { f ->
        f.reader = { throw IllegalStateException("reader failed") }
        val delivery = f.bridge.process(f.capture(0),
            ImageDetectionResult.Detected(listOf(DetectedImageObject(0, 40, 40, 60, 60))))
        assertEquals(ImageGuidanceRejection.EVIDENCE_FAILED, delivery.rejection)
        assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
    }

    private class Fixture : AutoCloseable {
        var now = 0L
        val pipeline = NativeGuidancePipeline.open("measured",
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
                -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0),
            intArrayOf(1), doubleArrayOf(0.0, 0.0), { now }, NativeGuidanceSink { true })
        var reader: (GuidanceImageObservation) -> GuidanceFrameMeasurements? = { measurements(it) }
        val source = MeasuredGuidanceEvidenceSource("measured",
            listOf(SavedMeasuredAppearance(1, appearance())), GuidanceMeasurementReader { reader(it) })
        val bridge = ImageGuidanceBridge(pipeline, intArrayOf(1), source)

        fun capture(time: Long, epoch: NativeGuidanceEpoch = pipeline.epoch) =
            requireNotNull(NativeImageCapture.create(epoch, time, (time + 1) * 1_000_000,
                100, 100, 100, 100, doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                    0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)))

        fun observation(epoch: NativeGuidanceEpoch = pipeline.epoch) = GuidanceImageObservation(
            capture(0, epoch), listOf(GuidanceImageObject(1, DetectedImageObject(0, 40, 40, 60, 60))))
        override fun close() = pipeline.close()
    }

    companion object {
        private val RED = 0xffff0000.toInt()
        private val BLUE = 0xff0000ff.toInt()
        private fun embedding(vararg values: Double) =
            requireNotNull(ImageEmbedding.create("model", "crop", values))
        private fun signature(vararg pixels: Int) = requireNotNull(RgbAppearanceSignature.fromArgb("crop", pixels))
        private fun appearance() = MeasuredAppearance(embedding(1.0, 0.0), signature(RED))
        private fun measurements(observation: GuidanceImageObservation) = GuidanceFrameMeasurements(
            observation, mapOf(1 to appearance()), listOf(SemanticPairMeasurement(1, 1, 1.0, true)),
            mapOf(1 to false))
    }
}
