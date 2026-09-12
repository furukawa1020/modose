package com.modose.app.flow.verification

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionApiResult
import com.modose.app.network.verify.VerifyRequestBuildResult
import com.modose.app.network.verify.VerifyRequestRejection
import com.modose.app.network.verify.VerifyVisionRequestFactory
import java.time.Instant

data class RawVerificationCorrection(
    val baselineObjectId: String,
    val reason: String,
)

data class RawSceneVerification(
    val schemaVersion: String,
    val status: String,
    val modelId: String,
    val promptVersion: String,
    val verificationStatus: String,
    val corrections: List<RawVerificationCorrection>,
    val uncertaintyReason: String,
)

enum class VerificationDecodeFailure {
    MalformedJson,
    ContractMismatch,
}

sealed interface VerificationDecodeResult {
    data class Decoded(val value: RawSceneVerification) : VerificationDecodeResult
    data class Rejected(val reason: VerificationDecodeFailure) : VerificationDecodeResult
}

fun interface SceneVerificationDecoder {
    fun decode(body: ByteArray): VerificationDecodeResult
}

data class ExecuteVerificationInput(
    val sceneId: String,
    val capturedAt: Instant,
    val idempotencyKey: String,
    val baselineImage: VlmJpegImage,
    val finalImage: VlmJpegImage,
    val confirmedObjectsJson: String,
    val expectedObjectIds: Set<String>,
    val expectedModelId: String? = null,
)

enum class VerificationMappingFailure {
    UnsupportedSchema,
    InvalidStatus,
    InvalidModel,
    InvalidPromptVersion,
    UnknownVerificationStatus,
    InvalidVerifiedPayload,
    InvalidCorrectionPayload,
    UnknownCorrectionObject,
    InvalidUncertainPayload,
}

sealed interface ExecuteVerificationFailure {
    data class InvalidRequest(val reason: VerifyRequestRejection) :
        ExecuteVerificationFailure
    data object IdTokenUnavailable : ExecuteVerificationFailure
    data object AppCheckTokenUnavailable : ExecuteVerificationFailure
    data object InvalidApiRequest : ExecuteVerificationFailure
    data object TimedOut : ExecuteVerificationFailure
    data object NetworkUnavailable : ExecuteVerificationFailure
    data object ResponseTooLarge : ExecuteVerificationFailure
    data class HttpFailure(val statusCode: Int, val retryable: Boolean) :
        ExecuteVerificationFailure
    data class DecodeRejected(val reason: VerificationDecodeFailure) :
        ExecuteVerificationFailure
    data class MappingRejected(val reason: VerificationMappingFailure) :
        ExecuteVerificationFailure
}

sealed interface ExecuteVerificationResult {
    data class Completed(val decision: SceneVerificationDecision) :
        ExecuteVerificationResult
    data class Failed(val reason: ExecuteVerificationFailure) :
        ExecuteVerificationResult
}

class ExecuteFinalVerificationUseCase(
    private val executeRequest: (VisionApiRequest) -> VisionApiResult,
    private val decoder: SceneVerificationDecoder,
) {
    fun execute(input: ExecuteVerificationInput): ExecuteVerificationResult {
        val request = when (
            val built = VerifyVisionRequestFactory.create(
                sceneId = input.sceneId,
                capturedAt = input.capturedAt,
                idempotencyKey = input.idempotencyKey,
                baselineImage = input.baselineImage,
                finalImage = input.finalImage,
                confirmedObjectsJson = input.confirmedObjectsJson,
            )
        ) {
            is VerifyRequestBuildResult.Built -> built.request
            is VerifyRequestBuildResult.Rejected -> return failed(
                ExecuteVerificationFailure.InvalidRequest(built.reason),
            )
        }

        return when (val response = executeRequest(request)) {
            is VisionApiResult.Success -> decodeAndMap(input, response.body)
            VisionApiResult.IDTokenUnavailable ->
                failed(ExecuteVerificationFailure.IdTokenUnavailable)
            VisionApiResult.AppCheckTokenUnavailable ->
                failed(ExecuteVerificationFailure.AppCheckTokenUnavailable)
            VisionApiResult.InvalidRequest ->
                failed(ExecuteVerificationFailure.InvalidApiRequest)
            VisionApiResult.TimedOut -> failed(ExecuteVerificationFailure.TimedOut)
            VisionApiResult.NetworkFailure ->
                failed(ExecuteVerificationFailure.NetworkUnavailable)
            VisionApiResult.ResponseTooLarge ->
                failed(ExecuteVerificationFailure.ResponseTooLarge)
            is VisionApiResult.HttpFailure -> failed(
                ExecuteVerificationFailure.HttpFailure(
                    response.statusCode,
                    response.retryable,
                ),
            )
        }
    }

    private fun decodeAndMap(
        input: ExecuteVerificationInput,
        body: ByteArray,
    ): ExecuteVerificationResult = when (val decoded = decoder.decode(body)) {
        is VerificationDecodeResult.Rejected -> failed(
            ExecuteVerificationFailure.DecodeRejected(decoded.reason),
        )
        is VerificationDecodeResult.Decoded -> map(input, decoded.value)
    }

    private fun map(
        input: ExecuteVerificationInput,
        raw: RawSceneVerification,
    ): ExecuteVerificationResult {
        if (raw.schemaVersion != "1.0") return mapping(
            VerificationMappingFailure.UnsupportedSchema,
        )
        if (raw.status != "ok") return mapping(VerificationMappingFailure.InvalidStatus)
        if (raw.modelId.isBlank() ||
            input.expectedModelId?.let { it != raw.modelId } == true
        ) return mapping(VerificationMappingFailure.InvalidModel)
        if (raw.promptVersion != "verify-v1") return mapping(
            VerificationMappingFailure.InvalidPromptVersion,
        )

        return when (raw.verificationStatus) {
            "verified" -> {
                if (raw.corrections.isNotEmpty() || raw.uncertaintyReason.isNotEmpty()) {
                    mapping(VerificationMappingFailure.InvalidVerifiedPayload)
                } else {
                    ExecuteVerificationResult.Completed(
                        SceneVerificationDecision(SceneVerificationStatus.Verified),
                    )
                }
            }
            "needs_correction" -> mapCorrections(input, raw)
            "uncertain" -> {
                if (raw.corrections.isNotEmpty() || raw.uncertaintyReason.isBlank()) {
                    mapping(VerificationMappingFailure.InvalidUncertainPayload)
                } else {
                    ExecuteVerificationResult.Completed(
                        SceneVerificationDecision(
                            status = SceneVerificationStatus.Uncertain,
                            reasonCodes = setOf(raw.uncertaintyReason),
                        ),
                    )
                }
            }
            else -> mapping(VerificationMappingFailure.UnknownVerificationStatus)
        }
    }

    private fun mapCorrections(
        input: ExecuteVerificationInput,
        raw: RawSceneVerification,
    ): ExecuteVerificationResult {
        val ids = raw.corrections.map { it.baselineObjectId }
        if (
            ids.isEmpty() ||
            ids.distinct().size != ids.size ||
            raw.corrections.any { it.reason.isBlank() } ||
            raw.uncertaintyReason.isNotEmpty()
        ) return mapping(VerificationMappingFailure.InvalidCorrectionPayload)
        if (ids.any { it !in input.expectedObjectIds }) {
            return mapping(VerificationMappingFailure.UnknownCorrectionObject)
        }
        return ExecuteVerificationResult.Completed(
            SceneVerificationDecision(
                status = SceneVerificationStatus.NeedsCorrection,
                correctionObjectIds = ids,
                reasonCodes = raw.corrections.map { it.reason }.toSet(),
            ),
        )
    }

    private fun mapping(reason: VerificationMappingFailure) = failed(
        ExecuteVerificationFailure.MappingRejected(reason),
    )

    private fun failed(reason: ExecuteVerificationFailure) =
        ExecuteVerificationResult.Failed(reason)
}
