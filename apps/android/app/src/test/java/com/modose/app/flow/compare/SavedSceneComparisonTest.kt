package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.ar.render.BaselineCaptureValidity
import com.modose.app.network.*
import com.modose.app.network.baseline.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SavedSceneComparisonTest {
    private fun image(text: String = "CURRENT") = VlmJpegImage(text.toByteArray(), 100, 100)
    private fun objectValue() = BaselineObject("wallet", "Wallet", listOf("black"),
        NormalizedBoundingBox(0, 0, 100, 100), false, ObjectSymmetry.None)
    private fun owner(validity: BaselineCaptureValidity, image: VlmJpegImage = image("BASELINE")) =
        SavedSceneComparison("123e4567-e89b-42d3-a456-426614174000", 100, validity, image, listOf(objectValue()))
    private fun response() = VisionApiResult.Success(200,
        """{"schemaVersion":"1.0","status":"ok","modelId":"model","promptVersion":"compare-v1","repaired":false,"matches":[{"baselineObjectId":"wallet","state":"missing","confidence":0.9,"currentBox":null,"ambiguityReason":""}],"addedObjects":[]}""".toByteArray())

    @Test fun requestContainsBothImagesConfirmedObjectsAndUuidV7() {
        val validity = BaselineCaptureValidity(1)
        val result = owner(validity).execute(validity, 200, image(), {}) { request ->
            assertEquals("/v1/vision/compare", request.path)
            assertEquals(7, UUID.fromString(request.idempotencyKey).version())
            assertEquals(2, UUID.fromString(request.idempotencyKey).variant())
            val body = request.body.toString(Charsets.UTF_8)
            assertTrue(body.contains("BASELINE")); assertTrue(body.contains("CURRENT"))
            assertTrue(body.contains("confirmedObjects")); assertTrue(body.contains("wallet"))
            response()
        }
        assertTrue(result is SavedCompareResult.Compared)
    }

    @Test fun successfulRequestCannotBeRepeated() {
        val validity = BaselineCaptureValidity(1)
        val saved = owner(validity)
        var calls = 0
        saved.execute(validity, 200, image(), {}) { calls++; response() }
        assertEquals(SavedCompareResult.AlreadyAttempted,
            saved.execute(validity, 300, image(), {}) { calls++; response() })
        assertEquals(1, calls)
    }

    @Test fun networkFailureCannotTriggerAnUnboundedRetry() {
        val validity = BaselineCaptureValidity(1)
        val saved = owner(validity)
        assertEquals(SavedCompareResult.TransportFailure(VisionApiResult.NetworkFailure),
            saved.execute(validity, 200, image(), {}) { VisionApiResult.NetworkFailure })
        assertTrue(saved.hasAttempted)
        assertEquals(SavedCompareResult.AlreadyAttempted,
            saved.execute(validity, 300, image(), {}) { error("must not send") })
    }

    @Test fun anotherTrackingIntervalOrOldImageNeverSends() {
        val validity = BaselineCaptureValidity(1)
        val saved = owner(validity)
        assertEquals(SavedCompareResult.InvalidCapture,
            saved.execute(BaselineCaptureValidity(1), 200, image(), {}) { error("must not send") })
        assertEquals(SavedCompareResult.InvalidCapture,
            saved.execute(validity, 100, image(), {}) { error("must not send") })
        assertFalse(saved.hasAttempted)
    }

    @Test fun invalidationDuringRequestDiscardsTheResult() {
        val validity = BaselineCaptureValidity(1)
        val result = owner(validity).execute(validity, 200, image(), {}) {
            validity.invalidate()
            response()
        }
        assertEquals(SavedCompareResult.InvalidCapture, result)
    }

    @Test fun baselineBytesAreCopiedAndInvalidResponseIsRejected() {
        val validity = BaselineCaptureValidity(1)
        val baseline = image("BASELINE")
        val saved = owner(validity, baseline)
        baseline.bytes.fill(0)
        assertEquals(SavedCompareResult.InvalidResponse,
            saved.execute(validity, 200, image(), {}) {
                assertTrue(it.body.toString(Charsets.UTF_8).contains("BASELINE"))
                VisionApiResult.Success(200, "{}".toByteArray())
            })
    }

    @Test fun reentrantSendDoesNotCreateASecondRequest() {
        val validity = BaselineCaptureValidity(1)
        val saved = owner(validity)
        saved.execute(validity, 200, image(), {}) {
            assertEquals(SavedCompareResult.AlreadyAttempted,
                saved.execute(validity, 300, image(), {}) { error("must not send") })
            response()
        }
    }

    @Test fun preflightRejectionDoesNotConsumeTheOnlyAttempt() {
        val validity = BaselineCaptureValidity(1)
        val saved = owner(validity)
        assertEquals(SavedCompareResult.InvalidCapture,
            saved.execute(validity, 200, image(""), {}) { error("must not send") })
        assertFalse(saved.hasAttempted)
        assertTrue(saved.execute(validity, 300, image(), {}) { response() } is SavedCompareResult.Compared)
    }
}
