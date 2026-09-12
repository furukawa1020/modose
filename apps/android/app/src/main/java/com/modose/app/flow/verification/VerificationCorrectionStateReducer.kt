package com.modose.app.flow.verification

enum class CorrectionObjectStatus {
    Completed,
    NeedsCorrection,
}

data class LocalRestorationProgress(
    val sceneId: String,
    val objectIds: List<String>,
    val objectStatuses: Map<String, CorrectionObjectStatus>,
    val verificationAttemptsUsed: Int,
)

enum class CorrectionApplyFailure {
    BlankSceneId,
    SceneMismatch,
    EmptyObjects,
    DuplicateObject,
    StatusSetMismatch,
    LocalRestorationIncomplete,
    AttemptMismatch,
    AttemptLimitExceeded,
    EmptyCorrection,
    DuplicateCorrection,
    UnknownCorrectionObject,
    PreservedSetMismatch,
}

sealed interface CorrectionApplyResult {
    data class Applied(val progress: LocalRestorationProgress) :
        CorrectionApplyResult

    data class Rejected(val reason: CorrectionApplyFailure) :
        CorrectionApplyResult
}

object VerificationCorrectionStateReducer {
    fun apply(
        current: LocalRestorationProgress,
        plan: VerificationCorrectionPlan,
    ): CorrectionApplyResult {
        validate(current, plan)?.let {
            return CorrectionApplyResult.Rejected(it)
        }

        val correctionIds = plan.correctionObjectIds.toSet()
        val nextStatuses = current.objectIds.associateWith { objectId ->
            if (objectId in correctionIds) {
                CorrectionObjectStatus.NeedsCorrection
            } else {
                CorrectionObjectStatus.Completed
            }
        }
        return CorrectionApplyResult.Applied(
            current.copy(objectStatuses = nextStatuses),
        )
    }

    private fun validate(
        current: LocalRestorationProgress,
        plan: VerificationCorrectionPlan,
    ): CorrectionApplyFailure? {
        if (current.sceneId.isBlank()) {
            return CorrectionApplyFailure.BlankSceneId
        }
        if (
            current.sceneId != plan.sceneId ||
            plan.attempt.sceneId != current.sceneId
        ) {
            return CorrectionApplyFailure.SceneMismatch
        }
        if (current.objectIds.isEmpty()) {
            return CorrectionApplyFailure.EmptyObjects
        }

        val known = current.objectIds.toSet()
        if (known.size != current.objectIds.size) {
            return CorrectionApplyFailure.DuplicateObject
        }
        if (current.objectStatuses.keys != known) {
            return CorrectionApplyFailure.StatusSetMismatch
        }
        if (
            current.objectStatuses.values.any {
                it != CorrectionObjectStatus.Completed
            }
        ) {
            return CorrectionApplyFailure.LocalRestorationIncomplete
        }
        if (plan.attempt.attemptNumber != current.verificationAttemptsUsed) {
            return CorrectionApplyFailure.AttemptMismatch
        }
        if (
            current.verificationAttemptsUsed !in
                1..FinalVerificationContract.MAX_ATTEMPTS
        ) {
            return CorrectionApplyFailure.AttemptLimitExceeded
        }

        val correctionIds = plan.correctionObjectIds
        if (correctionIds.isEmpty()) {
            return CorrectionApplyFailure.EmptyCorrection
        }
        if (correctionIds.toSet().size != correctionIds.size) {
            return CorrectionApplyFailure.DuplicateCorrection
        }
        if (correctionIds.any { it !in known }) {
            return CorrectionApplyFailure.UnknownCorrectionObject
        }

        val expectedPreserved = current.objectIds.filterNot {
            it in correctionIds
        }
        if (plan.preservedCompletedObjectIds != expectedPreserved) {
            return CorrectionApplyFailure.PreservedSetMismatch
        }
        return null
    }
}
