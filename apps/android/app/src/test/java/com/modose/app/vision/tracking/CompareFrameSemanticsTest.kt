package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.core.NativeGuidanceEpoch
import com.modose.app.core.NativeImageCapture
import com.modose.app.network.baseline.*
import org.junit.Assert.*
import org.junit.Test

class CompareFrameSemanticsTest {
    private val red = appearance()
    private val first = DetectedImageObject(null, 0, 0, 20, 20)
    private val second = DetectedImageObject(null, 40, 0, 60, 20)

    private fun seed(objects: List<ComparedAppearance> = listOf(ComparedAppearance(1, 7, first, red))) =
        MeasuredComparison("scene", 100L, objects, objects.map {
            ComparedAppearancePair(it.claimedSavedId, it.currentId, 1.0, 1.0, 0.9)
        })

    private fun observation(
        timestamp: Long = 101L, scene: String = "scene",
        boxes: List<DetectedImageObject> = listOf(first.copy(trackingId = 99)),
    ): GuidanceImageObservation {
        val capture = requireNotNull(NativeImageCapture.create(
            NativeGuidanceEpoch(scene), 0L, timestamp, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
        return GuidanceImageObservation(capture, boxes.mapIndexed { i, box ->
            GuidanceImageObject(i + 1, box)
        })
    }

    @Test
    fun preservesClaimButNeverConfirmsOrientationOrUsesTrackerIdentity() {
        val frame = observation()
        val result = requireNotNull(CompareFrameSemantics(seed()).readMeasured(frame, mapOf(1 to red)))
        assertSame(frame, result.observation)
        assertEquals(SemanticPairMeasurement(7, 1, 0.9, false), result.pairs.single())
        assertEquals(mapOf(1 to false), result.occludesOther)
    }

    @Test
    fun scoresEveryCandidateInsteadOfGreedilyAssigningTrackerIds() {
        val blue = appearance(vector = doubleArrayOf(0.0, 1.0), pixel = 0xff0000ff.toInt())
        val source = CompareFrameSemantics(seed(listOf(
            ComparedAppearance(1, 7, first, red), ComparedAppearance(2, 8, second, blue))))
        val frame = observation(boxes = listOf(first.copy(trackingId = 8), second.copy(trackingId = 7)))
        val result = requireNotNull(source.readMeasured(frame, mapOf(1 to blue, 2 to red)))
        assertEquals(4, result.pairs.size)
        assertEquals(0.0, result.pairs.single { it.savedId == 7 && it.currentId == 1 }.confidence, 0.0)
        assertEquals(0.9, result.pairs.single { it.savedId == 7 && it.currentId == 2 }.confidence, 1e-12)
        assertEquals(0.9, result.pairs.single { it.savedId == 8 && it.currentId == 1 }.confidence, 1e-12)
        assertTrue(result.pairs.all { it.orientationAligned == false })
    }

    @Test
    fun rejectsStaleAndForeignSceneFrames() {
        val source = CompareFrameSemantics(seed())
        for (time in listOf(99L, 100L)) assertNull(source.readMeasured(observation(time), mapOf(1 to red)))
        assertNull(source.readMeasured(observation(scene = "other"), mapOf(1 to red)))
    }

    @Test
    fun rejectsOverlappingSeedAndLiveRectangles() {
        assertThrows(IllegalArgumentException::class.java) {
            CompareFrameSemantics(seed(listOf(ComparedAppearance(1, 7, first, red),
                ComparedAppearance(2, 8, first.copy(left = 10), red))))
        }
        val frame = observation(boxes = listOf(first, first.copy(left = 10)))
        assertNull(CompareFrameSemantics(seed()).readMeasured(frame, mapOf(1 to red, 2 to red)))
    }

    @Test
    fun rejectsMissingFeaturesAndDifferentModelOrPreprocessing() {
        val source = CompareFrameSemantics(seed())
        val frame = observation()
        assertNull(source.readMeasured(frame, emptyMap()))
        assertNull(source.readMeasured(frame, mapOf(1 to red, 2 to red)))
        assertNull(source.readMeasured(frame, mapOf(1 to appearance(model = "other"))))
        assertNull(source.readMeasured(frame, mapOf(1 to appearance(signature = "other"))))
    }

    @Test
    fun emptyFrameHasNoInventedMissingObjectOrPosition() {
        val frame = observation(boxes = emptyList())
        val result = requireNotNull(CompareFrameSemantics(seed()).readMeasured(frame, emptyMap()))
        assertTrue(result.pairs.isEmpty())
        assertTrue(result.occludesOther.isEmpty())
    }

    @Test
    fun invalidAndDuplicateSeedClaimsCannotStart() {
        assertThrows(IllegalArgumentException::class.java) { CompareFrameSemantics(seed(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            CompareFrameSemantics(seed(listOf(ComparedAppearance(1, 7, first, red),
                ComparedAppearance(2, 7, second, red))))
        }
        for (confidence in listOf<Double?>(null, Double.NaN, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) {
                CompareFrameSemantics(MeasuredComparison("scene", 100L,
                    listOf(ComparedAppearance(1, 7, first, red)),
                    listOf(ComparedAppearancePair(7, 1, 1.0, 1.0, confidence))))
            }
        }
    }

    @Test
    fun cpuSourceExtractsBeforeResolvingAndClosesOwnedExtractorOnce() {
        val engine = Engine(red)
        val source = CpuMeasuredEvidenceSource("scene", listOf(SavedMeasuredAppearance(7, red)),
            CompareFrameSemantics(seed()), { AppearanceExtractorOpen.Opened(engine) })
        val frame = observation()
        val image = CpuCameraImage(100, 100, 101L, emptyList())
        try {
            assertNull(source.resolve(frame))
            val result = requireNotNull(source.withImage(image) { source.resolve(frame) })
            assertEquals(1, engine.calls)
            assertEquals(7, result.pairs.single().savedId)
            assertEquals(0.9, result.pairs.single().semanticScore, 1e-12)
            assertFalse(result.pairs.single().orientationAligned)
        } finally {
            source.close()
            source.close()
        }
        assertEquals(1, engine.closes)
    }

    @Test
    fun wrongExtractionTimestampCannotPublishEvidence() {
        val engine = Engine(red, wrongTime = true)
        val source = CpuMeasuredEvidenceSource("scene", listOf(SavedMeasuredAppearance(7, red)),
            CompareFrameSemantics(seed()), { AppearanceExtractorOpen.Opened(engine) })
        try {
            val frame = observation()
            assertNull(source.withImage(CpuCameraImage(100, 100, 101L, emptyList())) { source.resolve(frame) })
            assertEquals(AppearanceExtractionFailure.INVALID_OUTPUT, source.lastFailure)
        } finally { source.close() }
    }

    @Test
    fun unavailableModelRemainsFailClosedWithoutRepeatedOpening() {
        var opens = 0
        val source = CpuMeasuredEvidenceSource("scene", listOf(SavedMeasuredAppearance(7, red)),
            CompareFrameSemantics(seed()), {
                opens++
                AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.MODEL_UNAVAILABLE)
            })
        val frame = observation()
        try {
            repeat(2) {
                assertNull(source.withImage(CpuCameraImage(100, 100, 101L, emptyList())) { source.resolve(frame) })
            }
            assertEquals(1, opens)
            assertEquals(AppearanceExtractionFailure.MODEL_UNAVAILABLE, source.lastFailure)
        } finally { source.close() }
    }

    @Test
    fun savedOrientationRequirementReachesCoreEvidenceWithoutInventingAnAngle() {
        for (important in listOf(false, true)) {
            val objectSpec = BaselineObject("cup", "cup", listOf("red"),
                NormalizedBoundingBox(0, 0, 200, 200), important, ObjectSymmetry.Rotational)
            val policy = ConfirmedOrientationPolicy.create("scene", listOf(objectSpec), mapOf("cup" to 7))
            val source = CpuMeasuredEvidenceSource("scene", listOf(SavedMeasuredAppearance(7, red)),
                CompareFrameSemantics(seed(), policy), { AppearanceExtractorOpen.Opened(Engine(red)) })
            try {
                val frame = observation()
                val evidence = requireNotNull(source.withImage(CpuCameraImage(100, 100, 101L, emptyList())) {
                    source.resolve(frame)
                })
                assertEquals(!important, evidence.pairs.single().orientationAligned)
                assertEquals(0.9, evidence.pairs.single().semanticScore, 1e-12)
            } finally { source.close() }
        }
    }

    @Test
    fun foreignSceneOrUnknownSavedIdCannotWaiveOrientation() {
        val objectSpec = BaselineObject("cup", "cup", listOf("red"),
            NormalizedBoundingBox(0, 0, 200, 200), false, ObjectSymmetry.Rotational)
        for (policy in listOf(
            ConfirmedOrientationPolicy.create("other", listOf(objectSpec), mapOf("cup" to 7)),
            ConfirmedOrientationPolicy.create("scene", listOf(objectSpec), mapOf("cup" to 8)))) {
            val frame = observation()
            val result = requireNotNull(CompareFrameSemantics(seed(), policy).readMeasured(frame, mapOf(1 to red)))
            assertEquals(false, result.pairs.single().orientationAligned)
        }
    }

    private class Engine(val appearance: MeasuredAppearance, val wrongTime: Boolean = false) : AppearanceExtractor {
        var calls = 0
        var closes = 0
        override fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult {
            calls++
            return AppearanceExtractionResult.Extracted(image.timestampNanos + if (wrongTime) 1L else 0L,
                box, appearance)
        }
        override fun close() { closes++ }
    }

    private fun appearance(
        model: String = "model", signature: String = "rgb",
        vector: DoubleArray = doubleArrayOf(1.0, 0.0), pixel: Int = 0xffff0000.toInt(),
    ) = MeasuredAppearance(requireNotNull(ImageEmbedding.create(model, "crop", vector)),
        requireNotNull(RgbAppearanceSignature.fromArgb(signature, intArrayOf(pixel))))
}
