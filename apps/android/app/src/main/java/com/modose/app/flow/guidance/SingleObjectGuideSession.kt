package com.modose.app.flow.guidance

enum class GuideProgressionFailure {
    AlreadyStarted,
    NotGuiding,
    ActiveObjectMismatch,
    NonPositionalGuideCannotComplete,
    InternalPlanningRejected,
}

sealed interface GuideProgressionResult {
    data class Updated(val state: SingleObjectGuideState) : GuideProgressionResult
    data class Rejected(val reason: GuideProgressionFailure) : GuideProgressionResult
}

class SingleObjectGuideSession(
    private val candidates: List<SingleObjectGuideCandidate>,
) {
    private val completedObjectIds = linkedSetOf<String>()

    var state: SingleObjectGuideState = SingleObjectGuideState.Idle
        private set

    @Synchronized
    fun start(): SingleObjectGuideResult {
        if (state !is SingleObjectGuideState.Idle) {
            return SingleObjectGuideResult.Rejected(
                SingleObjectGuideFailure.DuplicateSceneObjectId,
            )
        }
        return when (val planned = SingleObjectGuidePlanner.start(candidates)) {
            is SingleObjectGuideResult.Rejected -> planned
            is SingleObjectGuideResult.Accepted -> {
                state = planned.state
                planned
            }
        }
    }

    @Synchronized
    fun markActiveCompleted(sceneObjectId: String): GuideProgressionResult {
        val guiding = state as? SingleObjectGuideState.Guiding
            ?: return GuideProgressionResult.Rejected(
                GuideProgressionFailure.NotGuiding,
            )
        if (guiding.active.sceneObjectId != sceneObjectId) {
            return GuideProgressionResult.Rejected(
                GuideProgressionFailure.ActiveObjectMismatch,
            )
        }
        if (guiding.active !is ActiveGuideContent.Positional) {
            return GuideProgressionResult.Rejected(
                GuideProgressionFailure.NonPositionalGuideCannotComplete,
            )
        }

        completedObjectIds += sceneObjectId
        val remainingIds = candidates
            .map { it.sceneObjectId }
            .filterNot { it in completedObjectIds }
            .toSet()
        if (remainingIds.isEmpty()) {
            val next = SingleObjectGuideState.ReadyForVerification(
                completedObjectIds = completedObjectIds.toList(),
            )
            state = next
            return GuideProgressionResult.Updated(next)
        }

        val remaining = candidates
            .filter { it.sceneObjectId in remainingIds }
            .map { candidate ->
                candidate.copy(
                    occludesObjectIds =
                        candidate.occludesObjectIds.intersect(remainingIds),
                )
            }
        return when (val planned = SingleObjectGuidePlanner.start(remaining)) {
            is SingleObjectGuideResult.Rejected ->
                GuideProgressionResult.Rejected(
                    GuideProgressionFailure.InternalPlanningRejected,
                )
            is SingleObjectGuideResult.Accepted -> {
                state = planned.state
                GuideProgressionResult.Updated(planned.state)
            }
        }
    }
}
