package com.modose.app.network.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionHttpMethod
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareVisionRequestFactoryTest {
    @Test
    fun buildsMultipartInContractOrder() {
        val result = create() as CompareRequestBuildResult.Built
        val request = result.request
        val body = request.body.toString(Charsets.ISO_8859_1)

        assertEquals(VisionHttpMethod.POST, request.method)
        assertEquals("/v1/vision/compare", request.path)
        assertEquals(IDEMPOTENCY_KEY, request.idempotencyKey)
        assertEquals(
            "multipart/form-data; boundary=modose-018f0f9012347abc8def123456789abc",
            request.contentType,
        )

        val metadata = body.indexOf("name=\"metadata\"")
        val baseline = body.indexOf("name=\"baselineImage\"")
        val current = body.indexOf("name=\"currentImage\"")
        val confirmed = body.indexOf("name=\"confirmedObjects\"")
        assertTrue(metadata >= 0)
        assertTrue(metadata < baseline)
        assertTrue(baseline < current)
        assertTrue(current < confirmed)
        assertTrue(body.contains("filename=\"baseline.jpg\""))
        assertTrue(body.contains("filename=\"current.jpg\""))
        assertTrue(body.contains(CONFIRMED_OBJECTS))
        assertTrue(body.endsWith("--modose-018f0f9012347abc8def123456789abc--\r\n"))
    }

    @Test
    fun sameInputProducesIdenticalRequestBytes() {
        val first = (create() as CompareRequestBuildResult.Built).request
        val second = (create() as CompareRequestBuildResult.Built).request

        assertEquals(first.contentType, second.contentType)
        assertArrayEquals(first.body, second.body)
    }

    @Test
    fun rejectsEachEmptyOrOversizedImage() {
        assertRejected(
            CompareRequestRejection.EmptyBaselineImage,
            baselineBytes = ByteArray(0),
        )
        assertRejected(
            CompareRequestRejection.EmptyCurrentImage,
            currentBytes = ByteArray(0),
        )
        assertRejected(
            CompareRequestRejection.ImageTooLarge,
            baselineBytes = ByteArray(2_000_001),
        )
        assertRejected(
            CompareRequestRejection.ImageTooLarge,
            currentBytes = ByteArray(2_000_001),
        )
    }

    @Test
    fun rejectsInvalidIdentifiersImageTypesAndConfirmedObjects() {
        assertRejected(CompareRequestRejection.InvalidSceneId, sceneId = "scene-1")
        assertRejected(
            CompareRequestRejection.InvalidIdempotencyKey,
            idempotencyKey = "018f0f90-1234-4abc-8def-123456789abc",
        )
        assertRejected(
            CompareRequestRejection.UnsupportedImageType,
            currentMimeType = "image/webp",
        )
        assertRejected(
            CompareRequestRejection.InvalidConfirmedObjects,
            confirmedObjectsJson = "[]",
        )
        assertRejected(
            CompareRequestRejection.InvalidConfirmedObjects,
            confirmedObjectsJson = " ",
        )
    }

    private fun create(
        sceneId: String = SCENE_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        baselineBytes: ByteArray = byteArrayOf(0x11, 0x12),
        currentBytes: ByteArray = byteArrayOf(0x21, 0x22),
        baselineMimeType: String = VlmJpegImage.MIME_TYPE,
        currentMimeType: String = VlmJpegImage.MIME_TYPE,
        confirmedObjectsJson: String = CONFIRMED_OBJECTS,
    ): CompareRequestBuildResult = CompareVisionRequestFactory.create(
        sceneId = sceneId,
        capturedAt = Instant.parse("2026-09-09T00:00:00Z"),
        idempotencyKey = idempotencyKey,
        baselineImage = image(baselineBytes, baselineMimeType),
        currentImage = image(currentBytes, currentMimeType),
        confirmedObjectsJson = confirmedObjectsJson,
    )

    private fun assertRejected(
        expected: CompareRequestRejection,
        sceneId: String = SCENE_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        baselineBytes: ByteArray = byteArrayOf(1),
        currentBytes: ByteArray = byteArrayOf(2),
        currentMimeType: String = VlmJpegImage.MIME_TYPE,
        confirmedObjectsJson: String = CONFIRMED_OBJECTS,
    ) {
        val result = create(
            sceneId = sceneId,
            idempotencyKey = idempotencyKey,
            baselineBytes = baselineBytes,
            currentBytes = currentBytes,
            currentMimeType = currentMimeType,
            confirmedObjectsJson = confirmedObjectsJson,
        )
        assertEquals(expected, (result as CompareRequestBuildResult.Rejected).reason)
    }

    private fun image(bytes: ByteArray, mimeType: String) = VlmJpegImage(
        bytes = bytes,
        widthPx = 640,
        heightPx = 480,
        mimeType = mimeType,
    )

    private companion object {
        const val SCENE_ID = "018f0f90-1234-7abc-8def-123456789abd"
        const val IDEMPOTENCY_KEY = "018f0f90-1234-7abc-8def-123456789abc"
        const val CONFIRMED_OBJECTS = "{\"objects\":[{\"sceneObjectId\":\"object-1\"}]}"
    }
}
