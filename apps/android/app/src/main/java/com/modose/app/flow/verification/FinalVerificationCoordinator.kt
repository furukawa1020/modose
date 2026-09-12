package com.modose.app.flow.verification

import java.util.UUID

enum class VerificationTransitionFailure {
    InvalidState,
    AttemptMismatch,
}

sealed interface VerificationTransitionResult {
    data class Updated(val state: FinalVerificationState) : VerificationTransitionResult
    data class Rejected(val reason: VerificationTransitionFailure) :
        VerificationTransitionResult
}

class FinalVerificationCoordinator {
    private var attemptsUsed = 0
    private val usedAttemptKeys = mutableSetOf<String>()

    var state: FinalVerificationState = FinalVerificationState.Idle
        private set

    @Synchronized
    fun begin(
        eligibility: VerificationEligibility,
        idempotencyKey: String,
    ): VerificationAttemptResult {
        validateEligibility(eligibility)?.let {
            return VerificationAttemptResult.Rejected(it)
        }
        if (!isCanonicalUuidV7(idempotencyKey)) {
            return VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.InvalidAttemptKey,
            )
        }
        if (!canBegin(state)) {
            return VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.InvalidState,
            )
        }
        if (idempotencyKey in usedAttemptKeys) {
            return VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.DuplicateAttempt,
            )
        }
        if (attemptsUsed >= FinalVerificationContract.MAX_ATTEMPTS) {
            state = FinalVerificationState.ManualConfirmationRequired(attemptsUsed)
            return VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.AttemptLimitExceeded,
            )
        }

        attemptsUsed += 1
        usedAttemptKeys += idempotencyKey
        val attempt = VerificationAttempt(
            sceneId = eligibility.sceneId,
            attemptNumber = attemptsUsed,
            attemptKey = idempotencyKey,
        )
        state = FinalVerificationState.Capturing(attempt)
        return VerificationAttemptResult.Started(attempt)
    }

    @Synchronized
    fun onCaptureReady(attempt: VerificationAttempt): VerificationTransitionResult {
        val current = state
        if (current !is FinalVerificationState.Capturing) {
            return rejected(VerificationTransitionFailure.InvalidState)
        }
        if (current.attempt != attempt) {
            return rejected(VerificationTransitionFailure.AttemptMismatch)
        }
        state = FinalVerificationState.Verifying(attempt)
        return updated()
    }

    @Synchronized
    fun onExecutionResult(
        attempt: VerificationAttempt,
        result: ExecuteVerificationResult,
    ): VerificationTransitionResult {
        val current = state
        if (current !is FinalVerificationState.Verifying) {
            return rejected(VerificationTransitionFailure.InvalidState)
        }
        if (current.attempt != attempt) {
            return rejected(VerificationTransitionFailure.AttemptMismatch)
        }

        state = when (result) {
            is ExecuteVerificationResult.Completed -> result.decision.toState(attempt)
            is ExecuteVerificationResult.Failed -> result.reason.toState(attempt)
        }
        return updated()
    }

    @Synchronized
    fun resumeAfterNetwork(): VerificationTransitionResult {
        val current = state
        if (current !is FinalVerificationState.WaitingForNetwork) {
            return rejected(VerificationTransitionFailure.InvalidState)
        }
        state = FinalVerificationState.Verifying(current.attempt)
        return updated()
    }

    private fun validateEligibility(
        eligibility: VerificationEligibility,
    ): VerificationAttemptFailure? {
        if (eligibility.sceneId.isBlank()) {
            return VerificationAttemptFailure.BlankSceneId
        }
        if (eligibility.expectedObjectIds.isEmpty()) {
            return VerificationAttemptFailure.EmptyExpectedObjects
        }
        val expected = eligibility.expectedObjectIds.toSet()
        if (expected.size != eligibility.expectedObjectIds.size) {
            return VerificationAttemptFailure.DuplicateExpectedObject
        }
        if (!expected.containsAll(eligibility.locallyCompletedObjectIds)) {
            return VerificationAttemptFailure.UnknownCompletedObject
        }
        if (eligibility.locallyCompletedObjectIds != expected) {
            return VerificationAttemptFailure.ObjectsNotLocallyCompleted
        }
        if (eligibility.unresolvedObjectIds.isNotEmpty()) {
            return VerificationAttemptFailure.UnresolvedObjectsRemain
        }
        return null
    }

    private fun canBegin(current: FinalVerificationState): Boolean =
        current is FinalVerificationState.Idle ||
            current is FinalVerificationState.NeedsCorrection ||
            current is FinalVerificationState.Uncertain

    private fun isCanonicalUuidV7(value: String): Boolean = try {
        val parsed = UUID.fromString(value)
        parsed.version() == 7 && parsed.toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun SceneVerificationDecision.toState(
        attempt: VerificationAttempt,
    ): FinalVerificationState = when (status) {
        SceneVerificationStatus.Verified -> FinalVerificationState.Verified(attempt)
        SceneVerificationStatus.NeedsCorrection ->
            FinalVerificationState.NeedsCorrection(attempt, correctionObjectIds)
        SceneVerificationStatus.Uncertain ->
            FinalVerificationState.Uncertain(attempt, reasonCodes)
    }

    private fun ExecuteVerificationFailure.toState(
        attempt: VerificationAttempt,
    ): FinalVerificationState = when (this) {
        ExecuteVerificationFailure.NetworkUnavailable,
        ExecuteVerificationFailure.TimedOut,
        -> FinalVerificationState.WaitingForNetwork(attempt)
        else -> FinalVerificationState.Uncertain(
            attempt = attempt,
            reasonCodes = setOf("verification_failed"),
        )
    }

    private fun updated() = VerificationTransitionResult.Updated(state)

    private fun rejected(reason: VerificationTransitionFailure) =
        VerificationTransitionResult.Rejected(reason)
}
