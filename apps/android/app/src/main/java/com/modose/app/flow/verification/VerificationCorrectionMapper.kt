package com.modose.app.flow.verification

data class VerificationCorrectionInput(
    val sceneId: String,
    val knownObjectIds: List<String>,
    val completedObjectIds: Set<String>,
    val attempt: VerificationAttempt,
    val decision: SceneVerificationDecision,
)

data class VerificationCorrectionPlan(
    val sceneId: String,
    val attempt: VerificationAttempt,
    val correctionObjectIds: List<String>,
    val preservedCompletedObjectIds: List<String>,
)

enum class VerificationCorrectionFailure {
    BlankSceneId,
    SceneMismatch,
    EmptyKnownObjects,
    DuplicateKnownObject,
    UnknownCompletedObject,
    InvalidAttemptNumber,
    NotNeedsCorrection,
    EmptyCorrection,
    DuplicateCorrection,
    UnknownCorrectionObject,
    CorrectionObjectNotCompleted,
}

sealed interface VerificationCorrectionResult {
    data class Accepted(val plan: VerificationCorrectionPlan) :
        VerificationCorrectionResult

    data class Rejected(val reason: VerificationCorrectionFailure) :
        VerificationCorrectionResult
}

object VerificationCorrectionMapper {
    fun map(input: VerificationCorrectionInput): VerificationCorrectionResult {
        validate(input)?.let {
            return VerificationCorrectionResult.Rejected(it)
        }

        val corrections = input.decision.correctionObjectIds.toSet()
        return VerificationCorrectionResult.Accepted(
            VerificationCorrectionPlan(
                sceneId = input.sceneId,
                attempt = input.attempt,
                correctionObjectIds = input.knownObjectIds.filter(corrections::contains),
                preservedCompletedObjectIds = input.knownObjectIds.filter {
                    it in input.completedObjectIds && it !in corrections
                },
            ),
        )
    }

    private fun validate(
        input: VerificationCorrectionInput,
    ): VerificationCorrectionFailure? {
        if (input.sceneId.isBlank()) {
            return VerificationCorrectionFailure.BlankSceneId
        }
        if (input.attempt.sceneId != input.sceneId) {
            return VerificationCorrectionFailure.SceneMismatch
        }
        if (input.knownObjectIds.isEmpty()) {
            return VerificationCorrectionFailure.EmptyKnownObjects
        }

        val known = input.knownObjectIds.toSet()
        if (known.size != input.knownObjectIds.size) {
            return VerificationCorrectionFailure.DuplicateKnownObject
        }
        if (!known.containsAll(input.completedObjectIds)) {
            return VerificationCorrectionFailure.UnknownCompletedObject
        }
        if (input.attempt.attemptNumber !in 1..FinalVerificationContract.MAX_ATTEMPTS) {
            return VerificationCorrectionFailure.InvalidAttemptNumber
        }
        if (input.decision.status != SceneVerificationStatus.NeedsCorrection) {
            return VerificationCorrectionFailure.NotNeedsCorrection
        }

        val correctionIds = input.decision.correctionObjectIds
        if (correctionIds.isEmpty()) {
            return VerificationCorrectionFailure.EmptyCorrection
        }
        if (correctionIds.toSet().size != correctionIds.size) {
            return VerificationCorrectionFailure.DuplicateCorrection
        }
        if (correctionIds.any { it !in known }) {
            return VerificationCorrectionFailure.UnknownCorrectionObject
        }
        if (correctionIds.any { it !in input.completedObjectIds }) {
            return VerificationCorrectionFailure.CorrectionObjectNotCompleted
        }
        return null
    }
}
