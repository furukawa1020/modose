package com.modose.app.vision.tracking

import com.modose.app.ar.image.*
import com.modose.app.flow.compare.CurrentObjectState
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.compare.*
import org.junit.Assert.*
import org.junit.Test

class CompareAppearanceBuilderTest {
    private val image = CpuCameraImage(100, 100, 200L, emptyList())
    private val plan = (VlmImageEncodingPlanner.create(100, 100,
        PixelRoi(0, 0, 100, 100), 0) as VlmImagePlanResult.Planned).plan
    private fun feature(second: Boolean = false, model: String = "model", rgb: String = "rgb") =
        MeasuredAppearance(ImageEmbedding.create(model, "crop",
            if (second) doubleArrayOf(0.0, 1.0) else doubleArrayOf(1.0, 0.0))!!,
            RgbAppearanceSignature.fromArgb(rgb, intArrayOf(0xffff0000.toInt()))!!)
    private fun baseline() = MeasuredBaseline("scene", 100L, linkedMapOf("a" to 7, "b" to 3),
        listOf(SavedMeasuredAppearance(7, feature()), SavedMeasuredAppearance(3, feature(true))))
    private fun analysis() = CompareAnalysis("vlm", false, listOf(
        ComparedObject("b", CurrentObjectState.Moved, 0.8, NormalizedBoundingBox(100, 600, 500, 900), ""),
        ComparedObject("a", CurrentObjectState.Moved, 0.9, NormalizedBoundingBox(100, 200, 500, 500), ""),
    ), emptyList())
    private inner class Engine : AppearanceExtractor {
        var calls = 0
        var closes = 0
        var result: (CpuCameraImage, DetectedImageObject) -> AppearanceExtractionResult = { input, box ->
            AppearanceExtractionResult.Extracted(input.timestampNanos, box, feature(box.left == 60))
        }
        override fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult {
            calls++
            return result(image, box)
        }
        override fun close() { closes++ }
    }
    private fun build(engine: Engine, response: CompareAnalysis = analysis(),
        source: CpuCameraImage = image) =
        CompareAppearanceBuilder.build(baseline(), response, source, plan, engine)
    private fun reject(result: CompareAppearanceResult, reason: CompareAppearanceFailure) {
        assertEquals(CompareAppearanceResult.Rejected(reason), result)
    }

    @Test fun mapsCpuBoxesAndMeasuresAllPairsWithoutInventingSemanticScores() {
        val engine = Engine()
        val result = (build(engine) as CompareAppearanceResult.Built).measurements
        assertEquals("scene", result.sceneId)
        assertEquals(200L, result.imageTimestampNanos)
        assertEquals(listOf(7, 3), result.objects.map { it.claimedSavedId })
        assertEquals(DetectedImageObject(null, 20, 10, 50, 50), result.objects.first().box)
        assertEquals(4, result.pairs.size)
        val claimed = result.pairs.single { it.savedId == 7 && it.currentId == 1 }
        val rival = result.pairs.single { it.savedId == 3 && it.currentId == 1 }
        assertEquals(1.0, claimed.embeddingSimilarity, 1e-9)
        assertEquals(0.9, claimed.semanticConfidence!!, 1e-9)
        assertEquals(0.0, rival.embeddingSimilarity, 1e-9)
        assertNull(rival.semanticConfidence)
        assertEquals(2, engine.calls)
        assertEquals(0, engine.closes)
    }

    @Test fun unavailableAndAmbiguousStatesNeverGenerateFeatures() {
        for (state in listOf(CurrentObjectState.Missing, CurrentObjectState.Occluded, CurrentObjectState.Ambiguous)) {
            val engine = Engine()
            val response = analysis().let { it.copy(matches = it.matches.map { m -> m.copy(state = state) }) }
            val measured = (build(engine, response) as CompareAppearanceResult.Built).measurements
            assertTrue(measured.objects.isEmpty()); assertTrue(measured.pairs.isEmpty())
            assertEquals(0, engine.calls)
        }
    }

    @Test fun exactSavedIdSetAndFiniteConfidenceAreRequired() {
        val engine = Engine()
        val valid = analysis()
        for (response in listOf(valid.copy(matches = valid.matches.take(1)),
            valid.copy(matches = listOf(valid.matches[0], valid.matches[0])),
            valid.copy(matches = valid.matches.map { it.copy(confidence = Double.NaN) }))) {
            reject(build(engine, response), CompareAppearanceFailure.INVALID_MATCHES)
        }
        assertEquals(0, engine.calls)
    }

    @Test fun currentImageMustBeNewerThanTheBaseline() {
        val engine = Engine()
        reject(build(engine, source = image.copy(timestampNanos = 100)), CompareAppearanceFailure.INVALID_IMAGE)
        assertEquals(0, engine.calls)
    }

    @Test fun allMappingsAreValidatedBeforeExtraction() {
        val engine = Engine()
        val response = analysis().let { it.copy(matches = it.matches.map { m ->
            if (m.baselineObjectId == "b") m.copy(currentBox = null) else m }) }
        reject(build(engine, response), CompareAppearanceFailure.INVALID_MAPPING)
        assertEquals(0, engine.calls)
    }

    @Test fun competingIdenticalCropsAreRejected() {
        val engine = Engine()
        val response = analysis().let { it.copy(matches = it.matches.map { m ->
            m.copy(currentBox = it.matches.first().currentBox) }) }
        reject(build(engine, response), CompareAppearanceFailure.COMPETING_CROPS)
        assertEquals(0, engine.calls)
    }

    @Test fun secondExtractionFailureNeverPublishesAPartialResult() {
        val engine = Engine()
        engine.result = { input, box ->
            if (engine.calls == 2) AppearanceExtractionResult.Rejected(AppearanceExtractionFailure.INFERENCE)
            else AppearanceExtractionResult.Extracted(input.timestampNanos, box, feature())
        }
        reject(build(engine), CompareAppearanceFailure.EXTRACTION)
        assertEquals(2, engine.calls); assertEquals(0, engine.closes)
    }

    @Test fun extractionExceptionIsTypedAndBorrowedEngineIsNotClosed() {
        val engine = Engine()
        engine.result = { _, _ -> error("inference") }
        reject(build(engine), CompareAppearanceFailure.EXTRACTION)
        assertEquals(0, engine.closes)
    }

    @Test fun timestampsAndCropsMustBelongToThisExactImage() {
        val engine = Engine()
        engine.result = { input, box -> AppearanceExtractionResult.Extracted(input.timestampNanos + 1, box, feature()) }
        reject(build(engine), CompareAppearanceFailure.INVALID_OUTPUT)
        engine.result = { input, box -> AppearanceExtractionResult.Extracted(input.timestampNanos,
            box.copy(left = 0), feature()) }
        reject(build(engine), CompareAppearanceFailure.INVALID_OUTPUT)
    }

    @Test fun modelAndSignatureIncompatibilityAreRejected() {
        val engine = Engine()
        for (appearance in listOf(feature(model = "other"), feature(rgb = "other"))) {
            engine.result = { input, box -> AppearanceExtractionResult.Extracted(input.timestampNanos, box, appearance) }
            reject(build(engine), CompareAppearanceFailure.INCOMPATIBLE_FEATURES)
        }
    }

    @Test fun returnedCollectionsAreImmutable() {
        val result = (build(Engine()) as CompareAppearanceResult.Built).measurements
        assertThrows(UnsupportedOperationException::class.java) {
            (result.objects as MutableList<ComparedAppearance>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.pairs as MutableList<ComparedAppearancePair>).clear()
        }
    }

    @Test fun invalidationAfterExtractionPreventsPublication() {
        val engine = Engine()
        var active = true
        engine.result = { input, box ->
            active = false
            AppearanceExtractionResult.Extracted(input.timestampNanos, box, feature())
        }
        assertThrows(IllegalStateException::class.java) {
            CompareAppearanceBuilder.build(baseline(), analysis(), image, plan, engine) { check(active) }
        }
        assertEquals(1, engine.calls)
    }
}
