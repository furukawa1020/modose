package com.modose.app.flow.baseline

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionApiResult
import com.modose.app.network.baseline.BaselineAnalysis
import com.modose.app.network.baseline.BaselineAnalysisDecoder
import com.modose.app.network.baseline.BaselineContractViolation
import com.modose.app.network.baseline.BaselineDecodeResult
import com.modose.app.network.baseline.BaselineRequestBuildResult
import com.modose.app.network.baseline.BaselineRequestRejection
import com.modose.app.network.baseline.BaselineVisionRequestFactory
import java.time.Instant

data class BaselineCapture(
    val sceneId: String,
    val capturedAt: Instant,
    val idempotencyKey: String,
    val image: VlmJpegImage,
)

sealed interface BaselineFlowState {
    data class CaptureReady(
        val lastFailure: BaselineFlowFailure? = null,
    ) : BaselineFlowState

    data class CapturingBaseline(
        val capture: BaselineCapture,
    ) : BaselineFlowState

    data class AnalyzingBaseline(
        val capture: BaselineCapture,
    ) : BaselineFlowState

    data class ReviewingBaseline(
        val capture: BaselineCapture,
        val analysis: BaselineAnalysis,
    ) : BaselineFlowState
}

sealed interface BaselineFlowFailure {
    data class InvalidCapture(val reason: BaselineRequestRejection) : BaselineFlowFailure
    data object IdTokenUnavailable : BaselineFlowFailure
    data object AppCheckTokenUnavailable : BaselineFlowFailure
    data object InvalidApiRequest : BaselineFlowFailure
    data object TimedOut : BaselineFlowFailure
    data object NetworkUnavailable : BaselineFlowFailure
    data object ResponseTooLarge : BaselineFlowFailure
    data class HttpFailure(
        val statusCode: Int,
        val retryable: Boolean,
    ) : BaselineFlowFailure
    data class ContractViolation(
        val reason: BaselineContractViolation,
    ) : BaselineFlowFailure
}

sealed interface BaselineRunResult {
    data class Completed(val analysis: BaselineAnalysis) : BaselineRunResult
    data class Failed(val reason: BaselineFlowFailure) : BaselineRunResult
    data object DuplicateIgnored : BaselineRunResult
}

class BaselineAnalysisUseCase(
    private val executeRequest: (VisionApiRequest) -> VisionApiResult,
    private val onStateChanged: (BaselineFlowState) -> Unit = {},
) {
    private val stateLock = Any()

    @Volatile
    var state: BaselineFlowState = BaselineFlowState.CaptureReady()
        private set

    fun analyze(capture: BaselineCapture): BaselineRunResult {
        if (!begin(capture)) {
            return BaselineRunResult.DuplicateIgnored
        }

        val request = when (
            val built = BaselineVisionRequestFactory.create(
                sceneId = capture.sceneId,
                capturedAt = capture.capturedAt,
                idempotencyKey = capture.idempotencyKey,
                image = capture.image,
            )
        ) {
            is BaselineRequestBuildResult.Built -> built.request
            is BaselineRequestBuildResult.Rejected -> {
                return fail(BaselineFlowFailure.InvalidCapture(built.reason))
            }
        }

        transition(BaselineFlowState.AnalyzingBaseline(capture))
        return when (val response = executeRequest(request)) {
            is VisionApiResult.Success -> decode(capture, response.body)
            VisionApiResult.IDTokenUnavailable -> fail(BaselineFlowFailure.IdTokenUnavailable)
            VisionApiResult.AppCheckTokenUnavailable ->
                fail(BaselineFlowFailure.AppCheckTokenUnavailable)
            VisionApiResult.InvalidRequest -> fail(BaselineFlowFailure.InvalidApiRequest)
            VisionApiResult.TimedOut -> fail(BaselineFlowFailure.TimedOut)
            VisionApiResult.NetworkFailure -> fail(BaselineFlowFailure.NetworkUnavailable)
            VisionApiResult.ResponseTooLarge -> fail(BaselineFlowFailure.ResponseTooLarge)
            is VisionApiResult.HttpFailure -> fail(
                BaselineFlowFailure.HttpFailure(
                    statusCode = response.statusCode,
                    retryable = response.retryable,
                ),
            )
        }
    }

    fun resetForRecapture(): Boolean {
        val changed = synchronized(stateLock) {
            if (state is BaselineFlowState.CapturingBaseline ||
                state is BaselineFlowState.AnalyzingBaseline
            ) {
                false
            } else {
                state = BaselineFlowState.CaptureReady()
                true
            }
        }
        if (changed) {
            onStateChanged(state)
        }
        return changed
    }

    private fun begin(capture: BaselineCapture): Boolean {
        val started = synchronized(stateLock) {
            if (state !is BaselineFlowState.CaptureReady) {
                false
            } else {
                state = BaselineFlowState.CapturingBaseline(capture)
                true
            }
        }
        if (started) {
            onStateChanged(state)
        }
        return started
    }

    private fun decode(
        capture: BaselineCapture,
        body: ByteArray,
    ): BaselineRunResult = when (val decoded = BaselineAnalysisDecoder.decode(body)) {
        is BaselineDecodeResult.Decoded -> {
            transition(BaselineFlowState.ReviewingBaseline(capture, decoded.analysis))
            BaselineRunResult.Completed(decoded.analysis)
        }
        is BaselineDecodeResult.Rejected -> {
            fail(BaselineFlowFailure.ContractViolation(decoded.violation))
        }
    }

    private fun fail(reason: BaselineFlowFailure): BaselineRunResult {
        transition(BaselineFlowState.CaptureReady(lastFailure = reason))
        return BaselineRunResult.Failed(reason)
    }

    private fun transition(next: BaselineFlowState) {
        synchronized(stateLock) {
            state = next
        }
        onStateChanged(next)
    }
}
