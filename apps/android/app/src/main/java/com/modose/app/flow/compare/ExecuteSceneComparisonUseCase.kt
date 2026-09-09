package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionApiResult
import com.modose.app.network.compare.CompareRequestBuildResult
import com.modose.app.network.compare.CompareRequestRejection
import com.modose.app.network.compare.CompareVisionRequestFactory
import java.time.Instant

data class SceneCompareCapture(
    val mappingContext: CompareMappingContext,
    val capturedAt: Instant,
    val idempotencyKey: String,
    val baselineImage: VlmJpegImage,
    val currentImage: VlmJpegImage,
    val confirmedObjectsJson: String,
)

enum class CompareResponseViolation {
    MalformedJson,
    ContractMismatch,
}

sealed interface CompareResponseDecodeResult {
    data class Decoded(val response: RawSceneComparison) : CompareResponseDecodeResult
    data class Rejected(val reason: CompareResponseViolation) : CompareResponseDecodeResult
}

fun interface SceneComparisonResponseDecoder {
    fun decode(body: ByteArray): CompareResponseDecodeResult
}

sealed interface SceneCompareFailure {
    data class InvalidCapture(val reason: CompareRequestRejection) : SceneCompareFailure
    data object IdTokenUnavailable : SceneCompareFailure
    data object AppCheckTokenUnavailable : SceneCompareFailure
    data object InvalidApiRequest : SceneCompareFailure
    data object TimedOut : SceneCompareFailure
    data object NetworkUnavailable : SceneCompareFailure
    data object ResponseTooLarge : SceneCompareFailure
    data class HttpFailure(
        val statusCode: Int,
        val retryable: Boolean,
    ) : SceneCompareFailure
    data class InvalidResponse(val reason: CompareResponseViolation) : SceneCompareFailure
    data class MappingRejected(val reason: CompareMappingFailure) : SceneCompareFailure
}

sealed interface SceneCompareResult {
    data class Completed(val comparison: CurrentSceneComparison) : SceneCompareResult
    data class Failed(val reason: SceneCompareFailure) : SceneCompareResult
    data object DuplicateIgnored : SceneCompareResult
}

sealed interface SceneCompareFlowState {
    data class Ready(val lastFailure: SceneCompareFailure? = null) : SceneCompareFlowState
    data class Comparing(val capture: SceneCompareCapture) : SceneCompareFlowState
    data class Guiding(val comparison: CurrentSceneComparison) : SceneCompareFlowState
}

class ExecuteSceneComparisonUseCase(
    private val executeRequest: (VisionApiRequest) -> VisionApiResult,
    private val decoder: SceneComparisonResponseDecoder,
    private val onStateChanged: (SceneCompareFlowState) -> Unit = {},
) {
    private val stateLock = Any()

    @Volatile
    var state: SceneCompareFlowState = SceneCompareFlowState.Ready()
        private set

    fun execute(capture: SceneCompareCapture): SceneCompareResult {
        if (!begin(capture)) {
            return SceneCompareResult.DuplicateIgnored
        }

        val request = when (
            val built = CompareVisionRequestFactory.create(
                sceneId = capture.mappingContext.sceneId,
                capturedAt = capture.capturedAt,
                idempotencyKey = capture.idempotencyKey,
                baselineImage = capture.baselineImage,
                currentImage = capture.currentImage,
                confirmedObjectsJson = capture.confirmedObjectsJson,
            )
        ) {
            is CompareRequestBuildResult.Built -> built.request
            is CompareRequestBuildResult.Rejected ->
                return fail(SceneCompareFailure.InvalidCapture(built.reason))
        }

        return when (val response = executeRequest(request)) {
            is VisionApiResult.Success -> decodeAndMap(capture, response.body)
            VisionApiResult.IDTokenUnavailable -> fail(SceneCompareFailure.IdTokenUnavailable)
            VisionApiResult.AppCheckTokenUnavailable ->
                fail(SceneCompareFailure.AppCheckTokenUnavailable)
            VisionApiResult.InvalidRequest -> fail(SceneCompareFailure.InvalidApiRequest)
            VisionApiResult.TimedOut -> fail(SceneCompareFailure.TimedOut)
            VisionApiResult.NetworkFailure -> fail(SceneCompareFailure.NetworkUnavailable)
            VisionApiResult.ResponseTooLarge -> fail(SceneCompareFailure.ResponseTooLarge)
            is VisionApiResult.HttpFailure -> fail(
                SceneCompareFailure.HttpFailure(
                    statusCode = response.statusCode,
                    retryable = response.retryable,
                ),
            )
        }
    }

    fun resetForRecapture(): Boolean {
        val changed = synchronized(stateLock) {
            if (state is SceneCompareFlowState.Comparing) {
                false
            } else {
                state = SceneCompareFlowState.Ready()
                true
            }
        }
        if (changed) {
            onStateChanged(state)
        }
        return changed
    }

    private fun begin(capture: SceneCompareCapture): Boolean {
        val started = synchronized(stateLock) {
            if (state !is SceneCompareFlowState.Ready) {
                false
            } else {
                state = SceneCompareFlowState.Comparing(capture)
                true
            }
        }
        if (started) {
            onStateChanged(state)
        }
        return started
    }

    private fun decodeAndMap(
        capture: SceneCompareCapture,
        body: ByteArray,
    ): SceneCompareResult = when (val decoded = decoder.decode(body)) {
        is CompareResponseDecodeResult.Rejected ->
            fail(SceneCompareFailure.InvalidResponse(decoded.reason))
        is CompareResponseDecodeResult.Decoded -> {
            when (
                val mapped = SceneComparisonMapper.map(
                    context = capture.mappingContext,
                    raw = decoded.response,
                )
            ) {
                is CompareMappingResult.Rejected ->
                    fail(SceneCompareFailure.MappingRejected(mapped.reason))
                is CompareMappingResult.Mapped -> {
                    transition(SceneCompareFlowState.Guiding(mapped.comparison))
                    SceneCompareResult.Completed(mapped.comparison)
                }
            }
        }
    }

    private fun fail(reason: SceneCompareFailure): SceneCompareResult {
        transition(SceneCompareFlowState.Ready(lastFailure = reason))
        return SceneCompareResult.Failed(reason)
    }

    private fun transition(next: SceneCompareFlowState) {
        synchronized(stateLock) {
            state = next
        }
        onStateChanged(next)
    }
}
