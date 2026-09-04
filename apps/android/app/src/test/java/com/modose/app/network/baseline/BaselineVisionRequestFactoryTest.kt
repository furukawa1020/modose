package com.modose.app.network.baseline

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionHttpMethod
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineVisionRequestFactoryTest {
    @Test
    fun buildsServerCompatibleMultipartRequest() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

        val result = BaselineVisionRequestFactory.create(
            sceneId = SCENE_ID,
            capturedAt = Instant.parse("2026-09-04T00:00:00Z"),
            idempotencyKey = IDEMPOTENCY_KEY,
            image = image(jpeg),
        ) as BaselineRequestBuildResult.Built

        val request = result.request
        assertEquals(VisionHttpMethod.POST, request.method)
        assertEquals("/v1/vision/baseline", request.path)
        assertEquals(IDEMPOTENCY_KEY, request.idempotencyKey)
        assertEquals(
            "multipart/form-data; boundary=modose-018f0f9012347abc8def123456789abc",
            request.contentType,
        )
        val body = request.body.toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"metadata\""))
        assertTrue(
            body.contains(
                """{"sceneId":"$SCENE_ID","capturedAt":"2026-09-04T00:00:00Z"}""",
            ),
        )
        assertTrue(body.contains("name=\"image\"; filename=\"baseline.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.endsWith("--modose-018f0f9012347abc8def123456789abc--\r\n"))
        assertTrue(request.body.containsSubsequence(jpeg))
    }

    @Test
    fun rejectsNonCanonicalIdentifiersBeforeBuildingRequest() {
        assertRejected(
            BaselineRequestRejection.InvalidSceneId,
            sceneId = "scene-1",
        )
        assertRejected(
            BaselineRequestRejection.InvalidIdempotencyKey,
            idempotencyKey = "018f0f90-1234-4abc-8def-123456789abc",
        )
    }

    @Test
    fun rejectsEmptyUnsupportedAndOversizedImages() {
        assertRejected(BaselineRequestRejection.EmptyImage, bytes = ByteArray(0))
        assertRejected(BaselineRequestRejection.UnsupportedImageType, mimeType = "image/webp")
        assertRejected(
            BaselineRequestRejection.ImageTooLarge,
            bytes = ByteArray(2_000_001),
        )
    }

    private fun assertRejected(
        expected: BaselineRequestRejection,
        sceneId: String = SCENE_ID,
        idempotencyKey: String = IDEMPOTENCY_KEY,
        bytes: ByteArray = byteArrayOf(1),
        mimeType: String = VlmJpegImage.MIME_TYPE,
    ) {
        val result = BaselineVisionRequestFactory.create(
            sceneId = sceneId,
            capturedAt = Instant.parse("2026-09-04T00:00:00Z"),
            idempotencyKey = idempotencyKey,
            image = image(bytes, mimeType),
        )

        assertEquals(
            expected,
            (result as BaselineRequestBuildResult.Rejected).reason,
        )
    }

    private fun image(
        bytes: ByteArray,
        mimeType: String = VlmJpegImage.MIME_TYPE,
    ) = VlmJpegImage(
        bytes = bytes,
        widthPx = 640,
        heightPx = 480,
        mimeType = mimeType,
    )

    private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean =
        indices.any { start ->
            start + expected.size <= size &&
                copyOfRange(start, start + expected.size).contentEquals(expected)
        }

    private companion object {
        const val SCENE_ID = "018f0f90-1234-7abc-8def-123456789abd"
        const val IDEMPOTENCY_KEY = "018f0f90-1234-7abc-8def-123456789abc"
    }
}
