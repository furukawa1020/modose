package com.modose.app.network.verify

import com.modose.app.ar.image.VlmJpegImage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VerifyMultipartContractTest {
    private fun build(final: VlmJpegImage = VlmJpegImage("final-jpeg".toByteArray(), 10, 10),
        key: String = "018f0f90-1234-7abc-8def-123456789abc") =
        VerifyVisionRequestFactory.create("018f0f90-1234-4abc-8def-123456789abc",
            Instant.parse("2026-09-26T00:00:00Z"), key,
            VlmJpegImage("saved-jpeg".toByteArray(), 10, 10), final,
            """{"objects":[],"excludedCandidates":[]}""")

    @Test
    fun sendsCurrentImageMatchingGoVerifyHandlerNotFinalImage() {
        val request = (build() as VerifyRequestBuildResult.Built).request
        val body = request.body.toString(Charsets.UTF_8)
        assertEquals("/v1/vision/verify", request.path)
        for (name in listOf("metadata", "baselineImage", "currentImage", "confirmedObjects")) {
            assertEquals(1, Regex("name=\\"" + name + "\\"").findAll(body).count())
        }
        assertFalse(body.contains("name=\\"finalImage\\""))
        assertTrue(body.contains("saved-jpeg"))
        assertTrue(body.contains("final-jpeg"))
        val boundary = requireNotNull(request.contentType).substringAfter("boundary=")
        assertTrue(body.endsWith("--" + boundary + "--\r\n"))
    }

    @Test
    fun emptyOrOversizedFinalImageRemainsRejected() {
        for (bytes in listOf(byteArrayOf(), ByteArray(2_000_001))) {
            assertTrue(build(VlmJpegImage(bytes, 10, 10)) is VerifyRequestBuildResult.Rejected)
        }
    }

    @Test
    fun nonV7KeyRemainsRejected() {
        assertEquals(VerifyRequestBuildResult.Rejected(VerifyRequestRejection.InvalidIdempotencyKey),
            build(key = "018f0f90-1234-4abc-8def-123456789abc"))
    }
}
