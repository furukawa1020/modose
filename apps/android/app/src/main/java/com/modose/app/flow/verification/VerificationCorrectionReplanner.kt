package com.modose.app.flow.verification

import com.modose.app.flow.guidance.SingleObjectGuideCandidate
import com.modose.app.flow.guidance.SingleObjectGuideFailure
import com.modose.app.flow.guidance.SingleObjectGuidePlanner
import com.modose.app.flow.guidance.SingleObjectGuideResult
import com.modose.app.flow.guidance.SingleObjectGuideState

data class VerificationCorrectionReplanInput(
    val plan: VerificationCorrectionPlan,
    val progress: LocalRestorationProgress,
    val candidates: List<SingleObjectGuideCandidate>,
)

data class VerificationCorrectionGuidance(
    val verificationAttempt: VerificationAttempt,
    val state: SingleObjectGuideState.Guiding,
    val preservedCompletedObjectIds: List<String>,
)

sealed interface VerificationCorrectionReplanFailure {
    data object SceneMismatch : VerificationCorrectionReplanFailure
    data object AttemptMismatch : VerificationCorrectionReplanFailure
    data object EmptyCorrection : VerificationCorrectionReplanFailure
    data object DuplicateCandidate : VerificationCorrectionReplanFailure
    data object MissingCorrectionCandidate : VerificationCorrectionReplanFailure
    data object ProgressMismatch : VerificationCorrectionReplanFailure

    data class PlanningRejected(val reason: SingleObjectGuideFailure) :
        VerificationCorrectionReplanFailure
}

sealed interface VerificationCorrectionReplanResult {
    data class Planned(val guidance: VerificationCorrectionGuidance) :
        VerificationCorrectionReplanResult

    data class Rejected(val reason: VerificationCorrectionReplanFailure) :
        VerificationCorrectionReplanResult
}

object VerificationCorrectionReplanner {
    fun plan(
        input: VerificationCorrectionReplanInput,
    ): VerificationCorrectionReplanResult {
        validate(input)?.let {
            return VerificationCorrectionReplanResult.Rejected(it)
        }

        val correctionIds = input.plan.correctionObjectIds.toSet()
        val correctionCandidates = input.candidates
            .filter { it.sceneObjectId in correctionIds }
            .map { candidate ->
                candidate.copy(
                    occludesObjectIds =
                        candidate.occludesObjectIds.intersect(correctionIds),
                )
            }

        return when (val planned = SingleObjectGuidePlanner.start(correctionCandidates)) {
            is SingleObjectGuideResult.Rejected ->
                VerificationCorrectionReplanResult.Rejected(
                    VerificationCorrectionReplanFailure.PlanningRejected(
                        planned.reason,
                    ),
                )
            is SingleObjectGuideResult.Accepted -> {
                val guiding = planned.state as? SingleObjectGuideState.Guiding
                    ?: return VerificationCorrectionReplanResult.Rejected(
                        VerificationCorrectionReplanFailure.ProgressMismatch,
                    )
                VerificationCorrectionReplanResult.Planned(
                    VerificationCorrectionGuidance(
                        verificationAttempt = input.plan.attempt,
                        state = guiding,
                        preservedCompletedObjectIds =
                            input.plan.preservedCompletedObjectIds,
                    ),
                )
            }
        }
    }

    private fun validate(
        input: VerificationCorrectionReplanInput,
    ): VerificationCorrectionReplanFailure? {
        if (
            input.plan.sceneId != input.progress.sceneId ||
            input.plan.attempt.sceneId != input.progress.sceneId
        ) {
            return VerificationCorrectionReplanFailure.SceneMismatch
        }
        if (
            input.plan.attempt.attemptNumber !=
                input.progress.verificationAttemptsUsed
        ) {
            return VerificationCorrectionReplanFailure.AttemptMismatch
        }

        val correctionIds = input.plan.correctionObjectIds.toSet()
        if (correctionIds.isEmpty()) {
            return VerificationCorrectionReplanFailure.EmptyCorrection
        }

        val candidateIds = input.candidates.map { it.sceneObjectId }
        if (
            candidateIds.any { it.isBlank() } ||
            candidateIds.toSet().size != candidateIds.size
        ) {
            return VerificationCorrectionReplanFailure.DuplicateCandidate
        }
        if (!candidateIds.toSet().containsAll(correctionIds)) {
            return VerificationCorrectionReplanFailure.MissingCorrectionCandidate
        }

        val expectedCorrections = input.progress.objectStatuses
            .filterValues { it == CorrectionObjectStatus.NeedsCorrection }
            .keys
        val expectedPreserved = input.progress.objectIds.filter {
            input.progress.objectStatuses[it] == CorrectionObjectStatus.Completed
        }
        if (
            expectedCorrections != correctionIds ||
            expectedPreserved != input.plan.preservedCompletedObjectIds
        ) {
            return VerificationCorrectionReplanFailure.ProgressMismatch
        }
        return null
    }
}
