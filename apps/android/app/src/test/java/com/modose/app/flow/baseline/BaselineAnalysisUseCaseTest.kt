package com.modose.app.flow.baseline

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiResult
import com.modose.app.network.baseline.BaselineContractViolation
import com.modose.app.network.baseline.BaselineRequestRejection
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineAnalysisUseCaseTest {
    @Test
    fun successfulAnalysisTransitionsToReviewing() {
        val transitions = mutableListOf<BaselineFlowState>()
        var calls = 0
        val useCase = BaselineAnalysisUseCase(
            executeRequest = {
                calls += 1
                VisionApiResult.Success(200, validResponse().toByteArray())
            },
            onStateChanged = transitions::add,
        )

        val result = useCase.analyze(validCapture())

        assertTrue(result is BaselineRunResult.Completed)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                BaselineFlowState.CapturingBaseline::class,
                BaselineFlowState.AnalyzingBaseline::class,
                BaselineFlowState.ReviewingBaseline::class,
            ),
            transitions.map { it::class },
        )
        assertTrue(useCase.state is BaselineFlowState.ReviewingBaseline)
    }

    @Test
    fun invalidCaptureReturnsReadyWithoutCallingApi() {
        var calls = 0
        val useCase = BaselineAnalysisUseCase(
            executeRequest = {
                calls += 1
                VisionApiResult.NetworkFailure
            },
        )
        val invalid = validCapture().copy(image = image(ByteArray(0)))

        val result = useCase.analyze(invalid) as BaselineRunResult.Failed

        assertEquals(0, calls)
        assertEquals(
            BaselineFlowFailure.InvalidCapture(BaselineRequestRejection.EmptyImage),
            result.reason,
        )
        assertEquals(
            result.reason,
            (useCase.state as BaselineFlowState.CaptureReady).lastFailure,
        )
    }

    @Test
    fun contractViolationNeverTransitionsToReviewing() {
        val states = mutableListOf<BaselineFlowState>()
        val useCase = BaselineAnalysisUseCase(
            executeRequest = {
                VisionApiResult.Success(
                    200,
                    validResponse().replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
                        .toByteArray(),
                )
            },
            onStateChanged = states::add,
        )

        val result = useCase.analyze(validCapture()) as BaselineRunResult.Failed

        assertEquals(
            BaselineFlowFailure.ContractViolation(BaselineContractViolation.InvalidConstant),
            result.reason,
        )
        assertTrue(useCase.state is BaselineFlowState.CaptureReady)
        assertFalse(states.any { it is BaselineFlowState.ReviewingBaseline })
    }

    @Test
    fun concurrentSecondSaveIsIgnored() {
        val enteredApi = CountDownLatch(1)
        val releaseApi = CountDownLatch(1)
        var calls = 0
        val useCase = BaselineAnalysisUseCase(
            executeRequest = {
                calls += 1
                enteredApi.countDown()
                releaseApi.await(2, TimeUnit.SECONDS)
                VisionApiResult.Success(200, validResponse().toByteArray())
            },
        )
        val firstThread = Thread {
            useCase.analyze(validCapture())
        }
        firstThread.start()
        assertTrue(enteredApi.await(2, TimeUnit.SECONDS))

        val duplicate = useCase.analyze(validCapture())

        assertEquals(BaselineRunResult.DuplicateIgnored, duplicate)
        assertEquals(1, calls)
        releaseApi.countDown()
        firstThread.join(2_000)
        assertFalse(firstThread.isAlive)
        assertTrue(useCase.state is BaselineFlowState.ReviewingBaseline)
    }

    @Test
    fun transportFailureReturnsReadyWithTypedReason() {
        val useCase = BaselineAnalysisUseCase(
            executeRequest = { VisionApiResult.HttpFailure(503, retryable = true) },
        )

        val result = useCase.analyze(validCapture()) as BaselineRunResult.Failed

        assertEquals(BaselineFlowFailure.HttpFailure(503, true), result.reason)
        assertEquals(result.reason, (useCase.state as BaselineFlowState.CaptureReady).lastFailure)
    }

    private fun validCapture() = BaselineCapture(
        sceneId = "018f0f90-1234-7abc-8def-123456789abd",
        capturedAt = Instant.parse("2026-09-04T00:00:00Z"),
        idempotencyKey = "018f0f90-1234-7abc-8def-123456789abc",
        image = image(byteArrayOf(1, 2, 3)),
    )

    private fun image(bytes: ByteArray) = VlmJpegImage(
        bytes = bytes,
        widthPx = 640,
        heightPx = 480,
    )

    private fun validResponse() =
        """
        {
          "schemaVersion":"1.0",
          "status":"ok",
          "modelId":"gemini-test",
          "promptVersion":"baseline-v1",
          "repaired":false,
          "objects":[{
            "id":"object-1",
            "displayName":"鍵",
            "appearanceFeatures":["銀色"],
            "boundingBox":{"yMin":100,"xMin":200,"yMax":600,"xMax":800},
            "orientationImportant":true,
            "symmetry":"bilateral"
          }],
          "excludedCandidates":[]
        }
        """.trimIndent()
}
