package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.core.NativeFrameProcessing
import com.modose.app.core.NativeGuidancePipeline
import com.modose.app.core.NativeGuidanceSink
import com.modose.app.core.NativeImageCapture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CpuMeasuredEvidenceContractTest {
    @Test
    fun sameImageEvidenceReachesNativeVerification(): Unit = Fixture().use { f ->
        for (time in 0L..800L step 100L) {
            f.now = time
            val capture = f.capture(time)
            val image = f.image(capture.imageTimestampNanos)
            val result = f.source.withImage(image) {
                f.bridge.process(capture, ImageDetectionResult.Detected(listOf(BOX)))
            }
            assertNull(result.rejection)
            assertEquals(NativeFrameProcessing.Published(time), result.processing)
            assertSame(image, f.engine.lastImage)
            assertSame(Thread.currentThread(), f.engine.lastThread)
        }
        assertEquals(1, f.opens)
        assertEquals(9, f.engine.calls)
        assertNotNull(f.pipeline.beginVerification(f.pipeline.epoch))
    }

    @Test
    fun imageIsUnavailableOutsideScopeAndAfterNormalReturn(): Unit = Fixture().use { f ->
        val observation = f.observation()
        assertNull(f.source.resolve(observation))
        assertNotNull(f.resolve(observation))
        assertNull(f.source.resolve(observation))
    }

    @Test
    fun throwingActionReleasesImageScope(): Unit = Fixture().use { f ->
        assertThrows(IllegalStateException::class.java) {
            f.source.withImage(f.image()) { error("action failed") }
        }
        assertNull(f.source.resolve(f.observation()))
        assertNotNull(f.resolve())
    }

    @Test
    fun nestedScopeIsRejectedWithoutDiscardingOuterImage(): Unit = Fixture().use { f ->
        f.source.withImage(f.image()) {
            assertThrows(IllegalStateException::class.java) {
                f.source.withImage(f.image()) { Unit }
            }
            assertNotNull(f.source.resolve(f.observation()))
        }
    }

    @Test
    fun wrongTimestampDoesNotReadSemanticsOrOpenModel(): Unit = Fixture().use { f ->
        f.semanticReader = { error("must not read") }
        assertNull(f.source.withImage(f.image(2L)) { f.source.resolve(f.observation()) })
        assertEquals(0, f.opens)
    }

    @Test
    fun missingSemanticsDoesNotOpenModel(): Unit = Fixture().use { f ->
        f.semanticReader = { null }
        assertNull(f.resolve())
        assertEquals(0, f.opens)
    }

    @Test
    fun equivalentButForeignSemanticObservationIsRejected(): Unit = Fixture().use { f ->
        f.semanticReader = { semantic(GuidanceImageObservation(it.capture, it.objects)) }
        assertNull(f.resolve())
        assertEquals(0, f.opens)
    }

    @Test
    fun startupFailureIsRememberedWithoutRepeatedModelOpen(): Unit = Fixture().use { f ->
        f.openResult = { AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.MODEL_UNAVAILABLE) }
        repeat(2) {
            assertNull(f.resolve())
            assertEquals(AppearanceExtractionFailure.MODEL_UNAVAILABLE, f.source.lastFailure)
        }
        assertEquals(1, f.opens)
        assertEquals(0, f.engine.calls)
    }

    @Test
    fun extractionRejectionReturnsNoEvidence(): Unit = Fixture().use { f ->
        f.engine.result = { _, _ -> AppearanceExtractionResult.Rejected(AppearanceExtractionFailure.INFERENCE) }
        assertNull(f.resolve())
        assertEquals(AppearanceExtractionFailure.INFERENCE, f.source.lastFailure)
    }

    @Test
    fun wrongExtractionTimestampIsRejected(): Unit = Fixture().use { f ->
        f.engine.result = { image, box ->
            AppearanceExtractionResult.Extracted(image.timestampNanos + 1, box, appearance())
        }
        assertNull(f.resolve())
        assertEquals(AppearanceExtractionFailure.INVALID_OUTPUT, f.source.lastFailure)
    }

    @Test
    fun wrongExtractionBoxIsRejected(): Unit = Fixture().use { f ->
        f.engine.result = { image, _ ->
            AppearanceExtractionResult.Extracted(image.timestampNanos, DetectedImageObject(0, 0, 0, 20, 20), appearance())
        }
        assertNull(f.resolve())
        assertEquals(AppearanceExtractionFailure.INVALID_OUTPUT, f.source.lastFailure)
    }

    @Test
    fun extractorExceptionInvalidatesBridgeAndReleasesScope(): Unit = Fixture().use { f ->
        f.engine.result = { _, _ -> error("inference failed") }
        val delivery = f.source.withImage(f.image()) {
            f.bridge.process(f.capture(0), ImageDetectionResult.Detected(listOf(BOX)))
        }
        assertEquals(ImageGuidanceRejection.EVIDENCE_FAILED, delivery.rejection)
        assertEquals(NativeFrameProcessing.Published(0), delivery.processing)
        assertNull(f.source.resolve(f.observation()))
        f.source.withImage(f.image()) { Unit }
    }

    @Test
    fun secondObjectFailureDoesNotReturnPartialEvidence(): Unit = Fixture().use { f ->
        val second = DetectedImageObject(1, 10, 10, 20, 20)
        val observation = GuidanceImageObservation(f.capture(0),
            listOf(GuidanceImageObject(1, BOX), GuidanceImageObject(2, second)))
        f.engine.result = { image, box ->
            if (box == second) AppearanceExtractionResult.Rejected(AppearanceExtractionFailure.INFERENCE)
            else AppearanceExtractionResult.Extracted(image.timestampNanos, box, appearance())
        }
        assertNull(f.resolve(observation))
        assertEquals(2, f.engine.calls)
    }

    @Test
    fun closeRunsOnceOnOwnerAndPreventsFurtherUse(): Unit = Fixture().use { f ->
        assertNotNull(f.resolve())
        f.source.close()
        f.source.close()
        assertEquals(1, f.engine.closes)
        assertSame(Thread.currentThread(), f.engine.closeThread)
        assertNull(f.source.resolve(f.observation()))
        assertThrows(IllegalStateException::class.java) {
            f.source.withImage(f.image()) { Unit }
        }
    }

    @Test
    fun wrongWorkerCannotEnterOrCloseOwnedSource(): Unit = Fixture().use { f ->
        assertNotNull(f.resolve())
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                assertThrows(IllegalStateException::class.java) {
                    f.source.withImage(f.image()) { Unit }
                }
                assertNull(f.source.resolve(f.observation()))
                assertThrows(IllegalStateException::class.java) { f.source.close() }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
        assertEquals(0, f.engine.closes)
        assertNotNull(f.resolve())
    }

    @Test
    fun closeBeforeFirstUseDoesNotOpenModel(): Unit = Fixture().use { f ->
        f.source.close()
        assertEquals(0, f.opens)
        assertEquals(0, f.engine.closes)
    }

    @Test
    fun explicitEmptyObservationDoesNotLoadModel(): Unit = Fixture().use { f ->
        val observation = GuidanceImageObservation(f.capture(0), emptyList())
        assertNotNull(f.resolve(observation))
        assertEquals(0, f.opens)
    }

    private class FakeExtractor : AppearanceExtractor {
        var calls = 0
        var closes = 0
        var lastImage: CpuCameraImage? = null
        var lastThread: Thread? = null
        var closeThread: Thread? = null
        var result: (CpuCameraImage, DetectedImageObject) -> AppearanceExtractionResult = { image, box ->
            AppearanceExtractionResult.Extracted(image.timestampNanos, box, appearance())
        }
        override fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult {
            calls++
            lastImage = image
            lastThread = Thread.currentThread()
            return result(image, box)
        }
        override fun close() {
            closes++
            closeThread = Thread.currentThread()
        }
    }

    private class Fixture : AutoCloseable {
        var now = 0L
        var opens = 0
        val engine = FakeExtractor()
        var openResult: () -> AppearanceExtractorOpen = { AppearanceExtractorOpen.Opened(engine) }
        var semanticReader: (GuidanceImageObservation) -> SemanticFrameMeasurements? = { semantic(it) }
        val pipeline = NativeGuidancePipeline.open("cpu-evidence",
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
                -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0),
            intArrayOf(1), doubleArrayOf(0.0, 0.0), { now }, NativeGuidanceSink { true })
        val source = CpuMeasuredEvidenceSource("cpu-evidence", listOf(SavedMeasuredAppearance(1, appearance())),
            SemanticFrameMeasurementSource { semanticReader(it) }, { opens++; openResult() })
        val bridge = ImageGuidanceBridge(pipeline, intArrayOf(1), source)
        fun capture(time: Long) = requireNotNull(NativeImageCapture.create(
            pipeline.epoch, time, (time + 1) * 1_000_000, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
        // Pixel decoding belongs to cropper tests; this fake measures image identity only.
        fun image(timestamp: Long = 1_000_000L) = CpuCameraImage(100, 100, timestamp, emptyList())
        fun observation() = GuidanceImageObservation(capture(0), listOf(GuidanceImageObject(1, BOX)))
        fun resolve(observation: GuidanceImageObservation = observation()) =
            source.withImage(image(observation.capture.imageTimestampNanos)) { source.resolve(observation) }
        override fun close() {
            try { source.close() } finally { pipeline.close() }
        }
    }

    companion object {
        private val BOX = DetectedImageObject(0, 40, 40, 60, 60)
        private fun appearance() = MeasuredAppearance(
            requireNotNull(ImageEmbedding.create("model", "crop", doubleArrayOf(1.0, 0.0))),
            requireNotNull(RgbAppearanceSignature.fromArgb("crop", intArrayOf(0xffff0000.toInt()))))
        private fun semantic(observation: GuidanceImageObservation) = SemanticFrameMeasurements(
            observation, observation.objects.map { SemanticPairMeasurement(1, it.currentId, 1.0, true) },
            observation.objects.associate { it.currentId to false })
    }
}
