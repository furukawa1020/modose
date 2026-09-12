package com.modose.app.ui.guidance.unresolved

import com.modose.app.flow.guidance.GuideCandidateLocation
import com.modose.app.flow.guidance.SingleObjectGuideCandidate
import com.modose.app.flow.guidance.SingleObjectGuidePlanner
import com.modose.app.flow.guidance.SingleObjectGuideResult
import com.modose.app.flow.guidance.SingleObjectGuideState

enum class UnresolvedPlannerReentryFailure {
    NotResolved,
    ObjectMismatch,
    StillNonPositional,
    PlannerRejected,
}

sealed interface UnresolvedPlannerReentryResult {
    data class Planned(val state: SingleObjectGuideState) :
        UnresolvedPlannerReentryResult

    data class Rejected(val reason: UnresolvedPlannerReentryFailure) :
        UnresolvedPlannerReentryResult
}

object UnresolvedObjectPlannerReentry {
    fun plan(
        rediscovery: UnresolvedRediscoveryState,
        resolvedCandidate: SingleObjectGuideCandidate,
        pendingCandidates: List<SingleObjectGuideCandidate>,
    ): UnresolvedPlannerReentryResult {
        if (rediscovery.status != RediscoveryStatus.Resolved) {
            return rejected(UnresolvedPlannerReentryFailure.NotResolved)
        }
        if (resolvedCandidate.sceneObjectId != rediscovery.model.objectId) {
            return rejected(UnresolvedPlannerReentryFailure.ObjectMismatch)
        }
        if (
            resolvedCandidate.location == GuideCandidateLocation.Missing ||
            resolvedCandidate.location == GuideCandidateLocation.Ambiguous ||
            resolvedCandidate.targetPose == null
        ) {
            return rejected(UnresolvedPlannerReentryFailure.StillNonPositional)
        }

        val replanned = SingleObjectGuidePlanner.start(
            pendingCandidates
                .filterNot { it.sceneObjectId == resolvedCandidate.sceneObjectId } +
                resolvedCandidate,
        )
        return when (replanned) {
            is SingleObjectGuideResult.Accepted ->
                UnresolvedPlannerReentryResult.Planned(replanned.state)
            is SingleObjectGuideResult.Rejected ->
                rejected(UnresolvedPlannerReentryFailure.PlannerRejected)
        }
    }

    private fun rejected(reason: UnresolvedPlannerReentryFailure) =
        UnresolvedPlannerReentryResult.Rejected(reason)
}

sealed interface UnresolvedCompletionDecision {
    data object EligibleForVerification : UnresolvedCompletionDecision
    data class Blocked(val unresolvedObjectIds: List<String>) :
        UnresolvedCompletionDecision
}

object UnresolvedCompletionGate {
    fun evaluate(
        states: List<UnresolvedRediscoveryState>,
    ): UnresolvedCompletionDecision {
        val unresolved = states
            .filter { it.status != RediscoveryStatus.Resolved }
            .map { it.model.objectId }
            .distinct()
            .sorted()
        return if (unresolved.isEmpty()) {
            UnresolvedCompletionDecision.EligibleForVerification
        } else {
            UnresolvedCompletionDecision.Blocked(unresolved)
        }
    }
}
