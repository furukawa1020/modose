package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiResult
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuteSceneComparisonUseCaseTest {
    @Test
    fun invalidCaptureIsRejectedWithoutHttpCall() {
        var calls = 0
        val useCase = useCase(
            execute = {
                calls += 1
                VisionApiResult.NetworkFailure
            },
        )

        val result = useCase.execute(capture(sceneId = "invalid"))

        assertEquals(0, calls)
        assertTrue(result is SceneCompareResult.Failed)
        assertTrue(
            (result as SceneCompareResult.Failed).reason is SceneCompareFailure.InvalidCapture,
        )
        assertTrue(useCase.state is SceneCompareFlowState.Ready)
    }

    @Test
    fun networkFailureReturnsTypedFailureAndRestoresReadyState() {
        val useCase = useCase(execute = { VisionApiResult.NetworkFailure })

        val result = useCase.execute(capture())

        assertEquals(
            SceneCompareResult.Failed(SceneCompareFailure.NetworkUnavailable),
            result,
        )
        val ready = useCase.state as SceneCompareFlowState.Ready
        assertEquals(SceneCompareFailure.NetworkUnavailable, ready.lastFailure)
    }

    @Test
    fun decoderRejectionNeverTransitionsToGuiding() {
        val useCase = useCase(
            execute = { VisionApiResult.Success(200, "{}".toByteArray()) },
            decoder = SceneComparisonResponseDecoder {
                CompareResponseDecodeResult.Rejected(
                    CompareResponseViolation.ContractMismatch,
                )
            },
        )

        val result = useCase.execute(capture())

        assertEquals(
            SceneCompareResult.Failed(
                SceneCompareFailure.InvalidResponse(
                    CompareResponseViolation.ContractMismatch,
                ),
            ),
            result,
        )
        assertTrue(useCase.state is SceneCompareFlowState.Ready)
    }

    @Test
    fun preservesHttpStatusAndRetryDecision() {
        val useCase = useCase(
            execute = { VisionApiResult.HttpFailure(statusCode = 503, retryable = true) },
        )

        val result = useCase.execute(capture())

        assertEquals(
            SceneCompareResult.Failed(
                SceneCompareFailure.HttpFailure(statusCode = 503, retryable = true),
            ),
            result,
        )
    }

    private fun useCase(
        execute: (com.modose.app.network.VisionApiRequest) -> VisionApiResult,
        decoder: SceneComparisonResponseDecoder = SceneComparisonResponseDecoder {
            CompareResponseDecodeResult.Rejected(CompareResponseViolation.MalformedJson)
        },
    ) = ExecuteSceneComparisonUseCase(
        executeRequest = execute,
        decoder = decoder,
    )

    private fun capture(sceneId: String = SCENE_ID) = SceneCompareCapture(
        mappingContext = CompareMappingContext(
            sceneId = sceneId,
            expectedObjectIds = listOf("object-1"),
            expectedModelId = null,
            expectedPromptVersion = null,
        ),
        capturedAt = Instant.parse("2026-09-10T00:00:00Z"),
        idempotencyKey = IDEMPOTENCY_KEY,
        baselineImage = image(1),
        currentImage = image(2),
        confirmedObjectsJson = "{\"objects\":[{\"sceneObjectId\":\"object-1\"}]}",
    )

    private fun image(value: Int) = VlmJpegImage(
        bytes = byteArrayOf(value.toByte()),
        widthPx = 640,
        heightPx = 480,
        mimeType = VlmJpegImage.MIME_TYPE,
    )

    private companion object {
        const val SCENE_ID = "018f0f90-1234-7abc-8def-123456789abd"
        const val IDEMPOTENCY_KEY = "018f0f90-1234-7abc-8def-123456789abc"
    }
}
