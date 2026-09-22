package com.modose.app.vision.tracking

import com.modose.app.ar.image.*
import com.modose.app.core.NativeGuidanceEpoch
import com.modose.app.core.NativeImageCapture
import com.modose.app.network.baseline.*
import org.junit.Assert.*
import org.junit.Test

class BaselineAppearanceBuilderTest {
    private val image = CpuCameraImage(100, 100, 123L, emptyList())
    private val plan = (VlmImageEncodingPlanner.create(100, 100,
        PixelRoi(0, 0, 100, 100), 0) as VlmImagePlanResult.Planned).plan

    private fun target(id: String) = BaselineObject(id, "object", listOf("red"),
        NormalizedBoundingBox(100, 200, 500, 600), false, ObjectSymmetry.Rotational)

    private fun build(engine: FakeExtractor, objects: List<BaselineObject> = listOf(target("wallet")),
        scene: String = "baseline") = BaselineAppearanceBuilder.build(scene, image, plan, objects, engine)

    private fun rejected(result: BaselineAppearanceResult, reason: BaselineAppearanceFailure) {
        assertTrue(result is BaselineAppearanceResult.Rejected)
        assertEquals(reason, (result as BaselineAppearanceResult.Rejected).reason)
    }

    @Test
    fun assignsStableIntegerIdsWithoutParsingExternalIds() {
        val engine = FakeExtractor()
        val objects = mutableListOf(target("007"), target("wallet"))
        val baseline = (build(engine, objects) as BaselineAppearanceResult.Built).baseline
        objects.reverse()
        objects.clear()
        assertEquals(mapOf("007" to 1, "wallet" to 2), baseline.objectIds)
        assertEquals(listOf(1, 2), baseline.appearances.map { it.savedId })
        assertEquals("baseline", baseline.sceneId)
        assertEquals(123L, baseline.imageTimestampNanos)
        assertEquals(2, engine.calls)
        assertSame(image, engine.lastImage)
        assertEquals(DetectedImageObject(null, 20, 10, 60, 50), engine.lastBox)
        assertEquals(0, engine.closes)
    }

    @Test
    fun resultCollectionsCannotBeMutated() {
        val baseline = (build(FakeExtractor()) as BaselineAppearanceResult.Built).baseline
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.objectIds as MutableMap<String, Int>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.appearances as MutableList<SavedMeasuredAppearance>).clear()
        }
    }

    @Test
    fun invalidSceneAndObjectSetsDoNotInvokeExtractor() {
        val engine = FakeExtractor()
        rejected(build(engine, scene = " "), BaselineAppearanceFailure.INVALID_SCENE)
        for (objects in listOf(emptyList(), List(6) { target("$it") },
            listOf(target("same"), target("same")), listOf(target(" ")),
            listOf(target("x".repeat(65))))) {
            rejected(build(engine, objects), BaselineAppearanceFailure.INVALID_OBJECTS)
        }
        assertEquals(0, engine.calls)
    }

    @Test
    fun validatesAllBoxesBeforeAnyExtraction() {
        val engine = FakeExtractor()
        val bad = target("bad").copy(boundingBox = NormalizedBoundingBox(500, 0, 100, 1000))
        rejected(build(engine, listOf(target("good"), bad)), BaselineAppearanceFailure.INVALID_IMAGE_MAPPING)
        assertEquals(0, engine.calls)
    }

    @Test
    fun secondExtractionFailureDoesNotExposeFirstResult() {
        val engine = FakeExtractor()
        engine.result = { input, box ->
            if (engine.calls == 2) AppearanceExtractionResult.Rejected(AppearanceExtractionFailure.INFERENCE)
            else AppearanceExtractionResult.Extracted(input.timestampNanos, box, appearance())
        }
        val result = build(engine, listOf(target("a"), target("b")))
        rejected(result, BaselineAppearanceFailure.EXTRACTION)
        assertEquals(AppearanceExtractionFailure.INFERENCE,
            (result as BaselineAppearanceResult.Rejected).extractionFailure)
        assertEquals(2, engine.calls)
        assertEquals(0, engine.closes)
    }

    @Test
    fun extractorExceptionBecomesTypedFailureWithoutClosingBorrowedEngine() {
        val engine = FakeExtractor()
        engine.result = { _, _ -> error("inference failed") }
        rejected(build(engine), BaselineAppearanceFailure.EXTRACTION)
        assertEquals(0, engine.closes)
    }

    @Test
    fun outputTimestampAndBoxMustMatchRequestedImage() {
        val engine = FakeExtractor()
        engine.result = { input, box ->
            AppearanceExtractionResult.Extracted(input.timestampNanos + 1, box, appearance())
        }
        rejected(build(engine), BaselineAppearanceFailure.INVALID_OUTPUT)
        engine.result = { input, _ ->
            AppearanceExtractionResult.Extracted(input.timestampNanos,
                DetectedImageObject(null, 0, 0, 10, 10), appearance())
        }
        rejected(build(engine), BaselineAppearanceFailure.INVALID_OUTPUT)
    }

    @Test
    fun differentEmbeddingModelCannotMixWithinBaseline() {
        val engine = FakeExtractor()
        engine.result = { input, box ->
            AppearanceExtractionResult.Extracted(input.timestampNanos, box,
                appearance(model = if (engine.calls == 1) "model" else "other"))
        }
        rejected(build(engine, listOf(target("a"), target("b"))), BaselineAppearanceFailure.INCOMPATIBLE_FEATURES)
    }

    @Test
    fun differentSignaturePreprocessingCannotMixWithinBaseline() {
        val engine = FakeExtractor()
        engine.result = { input, box ->
            AppearanceExtractionResult.Extracted(input.timestampNanos, box,
                appearance(signatureSpace = if (engine.calls == 1) "rgb" else "other"))
        }
        rejected(build(engine, listOf(target("a"), target("b"))), BaselineAppearanceFailure.INCOMPATIBLE_FEATURES)
    }

    @Test
    fun allFiveTargetsAreAccepted() {
        val engine = FakeExtractor()
        val baseline = (build(engine, List(5) { target("item-$it") }) as BaselineAppearanceResult.Built).baseline
        assertEquals(listOf(1, 2, 3, 4, 5), baseline.appearances.map { it.savedId })
        assertEquals(5, engine.calls)
    }

    @Test
    fun savedFeaturesCanProduceCurrentFrameEvidence() {
        val baseline = (build(FakeExtractor()) as BaselineAppearanceResult.Built).baseline
        val source = baseline.createEvidenceSource(
            SemanticFrameMeasurementSource { observation ->
                SemanticFrameMeasurements(observation,
                    listOf(SemanticPairMeasurement(baseline.objectIds.getValue("wallet"), 1, 0.9, true)),
                    mapOf(1 to false))
            }, { AppearanceExtractorOpen.Opened(FakeExtractor()) })
        try {
            val capture = requireNotNull(NativeImageCapture.create(
                NativeGuidanceEpoch("baseline"), 0L, 123L, 100, 100, 100, 100,
                doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                    0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
            val observation = GuidanceImageObservation(capture,
                listOf(GuidanceImageObject(1, DetectedImageObject(8, 20, 10, 60, 50))))
            val proof = requireNotNull(source.withImage(image) { source.resolve(observation) })
            assertSame(observation, proof.observation)
            assertEquals(1.0, proof.pairs.single().embeddingScore, 1e-12)
            assertEquals(1.0, proof.pairs.single().signatureScore, 1e-12)
            assertEquals(0.9, proof.pairs.single().semanticScore, 1e-12)
        } finally {
            source.close()
        }
    }

    private class FakeExtractor : AppearanceExtractor {
        var calls = 0
        var closes = 0
        var lastImage: CpuCameraImage? = null
        var lastBox: DetectedImageObject? = null
        var result: (CpuCameraImage, DetectedImageObject) -> AppearanceExtractionResult = { input, box ->
            AppearanceExtractionResult.Extracted(input.timestampNanos, box, appearance())
        }
        override fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult {
            calls++
            lastImage = image
            lastBox = box
            return result(image, box)
        }
        override fun close() { closes++ }
    }

    companion object {
        private fun appearance(model: String = "model", signatureSpace: String = "rgb") = MeasuredAppearance(
            requireNotNull(ImageEmbedding.create(model, "crop", doubleArrayOf(1.0, 0.0))),
            requireNotNull(RgbAppearanceSignature.fromArgb(signatureSpace, intArrayOf(0xffff0000.toInt()))))
    }
}
