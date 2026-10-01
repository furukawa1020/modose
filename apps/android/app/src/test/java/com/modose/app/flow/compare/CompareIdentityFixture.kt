package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.compare.ConfirmedObjectsFixture
import java.time.Instant

internal object CompareIdentityFixture {
    fun capture(ids: List<String> = listOf("cup"), payloadIds: List<String> = listOf("cup")) =
        SceneCompareCapture(
            mappingContext = CompareMappingContext(
                sceneId = "018f0f90-1234-7abc-8def-123456789abd",
                expectedObjectIds = ids,
            ),
            capturedAt = Instant.parse("2026-09-10T00:00:00Z"),
            idempotencyKey = "018f0f90-1234-7abc-8def-123456789abc",
            baselineImage = VlmJpegImage(byteArrayOf(1), 10, 10),
            currentImage = VlmJpegImage(byteArrayOf(2), 10, 10),
            confirmedObjectsJson = ConfirmedObjectsFixture.json(*payloadIds.toTypedArray()),
        )

    fun response(ids: List<String> = listOf("cup")) = CompareResponseDecodeResult.Decoded(
        RawSceneComparison(
            schemaVersion = "1.0",
            status = "ok",
            modelId = "model",
            promptVersion = "compare-v1",
            matches = ids.map {
                RawCompareMatch(
                    sceneObjectId = it,
                    state = "missing",
                    currentBoundingBox = null,
                    sameObjectConfidence = 0.9,
                    orientationDeltaDegrees = null,
                    occludedBy = emptyList(),
                    reasonCodes = listOf("OBJECT_MISSING"),
                )
            },
            extraObjects = emptyList(),
        ),
    )
}
