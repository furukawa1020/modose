package com.modose.app.flow.verification

data class VerificationEligibility(
    val sceneId: String,
    val expectedObjectIds: List<String>,
    val locallyCompletedObjectIds: Set<String>,
    val unresolvedObjectIds: Set<String>,
)

data class VerificationAttempt(
    val sceneId: String,
    val attemptNumber: Int,
    val attemptKey: String,
)

enum class SceneVerificationStatus {
    Verified,
    NeedsCorrection,
    Uncertain,
}

data class SceneVerificationDecision(
    val status: SceneVerificationStatus,
    val correctionObjectIds: List<String> = emptyList(),
    val reasonCodes: Set<String> = emptySet(),
)

sealed interface FinalVerificationState {
    data object Idle : FinalVerificationState
    data class Capturing(val attempt: VerificationAttempt) : FinalVerificationState
    data class Verifying(val attempt: VerificationAttempt) : FinalVerificationState
    data class Verified(val attempt: VerificationAttempt) : FinalVerificationState

    data class NeedsCorrection(
        val attempt: VerificationAttempt,
        val objectIds: List<String>,
    ) : FinalVerificationState

    data class Uncertain(
        val attempt: VerificationAttempt,
        val reasonCodes: Set<String>,
    ) : FinalVerificationState

    data class WaitingForNetwork(
        val attempt: VerificationAttempt,
    ) : FinalVerificationState

    data class ManualConfirmationRequired(
        val attemptsUsed: Int,
    ) : FinalVerificationState
}

enum class VerificationAttemptFailure {
    BlankSceneId,
    EmptyExpectedObjects,
    DuplicateExpectedObject,
    UnknownCompletedObject,
    ObjectsNotLocallyCompleted,
    UnresolvedObjectsRemain,
    AttemptLimitExceeded,
    DuplicateAttempt,
}

sealed interface VerificationAttemptResult {
    data class Started(val attempt: VerificationAttempt) : VerificationAttemptResult
    data class Rejected(val reason: VerificationAttemptFailure) : VerificationAttemptResult
}

object FinalVerificationContract {
    const val MAX_ATTEMPTS = 3
}
