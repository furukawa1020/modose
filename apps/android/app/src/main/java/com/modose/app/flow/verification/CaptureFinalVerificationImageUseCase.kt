package com.modose.app.flow.verification

import com.modose.app.ar.image.PixelRoi
import com.modose.app.flow.compare.CaptureCurrentSceneFailure
import com.modose.app.flow.compare.CaptureCurrentSceneRequest
import com.modose.app.flow.compare.CaptureCurrentSceneResult
import com.modose.app.flow.compare.CaptureCurrentSceneUseCase
import com.modose.app.flow.compare.CapturedCurrentScene

sealed interface FinalImageCaptureResult {
    data class Captured(
        val image: CapturedCurrentScene,
        val reused: Boolean,
    ) : FinalImageCaptureResult

    data class Rejected(
        val reason: VerificationAttemptFailure,
    ) : FinalImageCaptureResult

    data class Failed(
        val reason: CaptureCurrentSceneFailure,
    ) : FinalImageCaptureResult
}

class CaptureFinalVerificationImageUseCase(
    private val captureCurrentScene: CaptureCurrentSceneUseCase,
) {
    suspend fun execute(
        eligibility: VerificationEligibility,
        attempt: VerificationAttempt,
        rotationDegreesClockwise: Int,
        roi: PixelRoi? = null,
    ): FinalImageCaptureResult {
        validate(eligibility, attempt)?.let {
            return FinalImageCaptureResult.Rejected(it)
        }

        return when (
            val result = captureCurrentScene.execute(
                CaptureCurrentSceneRequest(
                    sceneId = attempt.sceneId,
                    captureKey = attempt.attemptKey,
                    roi = roi,
                    rotationDegreesClockwise = rotationDegreesClockwise,
                ),
            )
        ) {
            is CaptureCurrentSceneResult.Captured ->
                FinalImageCaptureResult.Captured(result.capture, reused = false)
            is CaptureCurrentSceneResult.AlreadyCaptured ->
                FinalImageCaptureResult.Captured(result.capture, reused = true)
            is CaptureCurrentSceneResult.Failed ->
                FinalImageCaptureResult.Failed(result.reason)
        }
    }

    fun resetForNextAttempt() {
        captureCurrentScene.reset()
    }

    private fun validate(
        eligibility: VerificationEligibility,
        attempt: VerificationAttempt,
    ): VerificationAttemptFailure? {
        if (eligibility.sceneId.isBlank() || attempt.sceneId != eligibility.sceneId) {
            return VerificationAttemptFailure.BlankSceneId
        }
        val expected = eligibility.expectedObjectIds
        if (expected.isEmpty()) {
            return VerificationAttemptFailure.EmptyExpectedObjects
        }
        if (expected.distinct().size != expected.size) {
            return VerificationAttemptFailure.DuplicateExpectedObject
        }
        if (!eligibility.locallyCompletedObjectIds.all { it in expected }) {
            return VerificationAttemptFailure.UnknownCompletedObject
        }
        if (
            eligibility.locallyCompletedObjectIds != expected.toSet()
        ) {
            return VerificationAttemptFailure.ObjectsNotLocallyCompleted
        }
        if (eligibility.unresolvedObjectIds.isNotEmpty()) {
            return VerificationAttemptFailure.UnresolvedObjectsRemain
        }
        if (attempt.attemptNumber !in 1..FinalVerificationContract.MAX_ATTEMPTS) {
            return VerificationAttemptFailure.AttemptLimitExceeded
        }
        return null
    }
}
