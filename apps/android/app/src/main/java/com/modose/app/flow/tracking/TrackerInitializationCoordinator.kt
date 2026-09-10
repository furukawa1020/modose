package com.modose.app.flow.tracking

enum class TrackerInitializationInvariantFailure {
    AssignmentCountMismatch,
    AssignmentObjectMismatch,
    DuplicateAssignedObject,
    DuplicateAssignedTrackingId,
}

sealed interface CoordinatedTrackerInitializationResult {
    data class Initialized(val plan: TrackerInitializationPlan) :
        CoordinatedTrackerInitializationResult

    data class InputRejected(val reason: TrackerInitializationFailure) :
        CoordinatedTrackerInitializationResult

    data class InvariantRejected(val reason: TrackerInitializationInvariantFailure) :
        CoordinatedTrackerInitializationResult
}

object TrackerInitializationCoordinator {
    fun initialize(
        input: TrackerInitializationInput,
        embeddingCandidates: List<EmbeddingReidentificationCandidate>,
    ): CoordinatedTrackerInitializationResult {
        val direct = when (val result = BoundingBoxTrackerInitializer.initialize(input)) {
            is TrackerInitializationResult.Rejected ->
                return CoordinatedTrackerInitializationResult.InputRejected(result.reason)
            is TrackerInitializationResult.Initialized -> result.plan
        }

        val reidentified = when (
            val result = EmbeddingTrackerReidentifier.apply(
                initial = direct,
                detections = input.detections,
                candidates = embeddingCandidates,
            )
        ) {
            is TrackerInitializationResult.Rejected ->
                return CoordinatedTrackerInitializationResult.InputRejected(result.reason)
            is TrackerInitializationResult.Initialized -> result.plan
        }

        validatePlan(input, reidentified)?.let {
            return CoordinatedTrackerInitializationResult.InvariantRejected(it)
        }
        return CoordinatedTrackerInitializationResult.Initialized(reidentified)
    }

    private fun validatePlan(
        input: TrackerInitializationInput,
        plan: TrackerInitializationPlan,
    ): TrackerInitializationInvariantFailure? {
        if (plan.assignments.size != input.objects.size) {
            return TrackerInitializationInvariantFailure.AssignmentCountMismatch
        }

        val assignedObjectIds = plan.assignments.map { it.sceneObjectId }
        if (assignedObjectIds.distinct().size != assignedObjectIds.size) {
            return TrackerInitializationInvariantFailure.DuplicateAssignedObject
        }
        if (assignedObjectIds.toSet() != input.objects.map { it.sceneObjectId }.toSet()) {
            return TrackerInitializationInvariantFailure.AssignmentObjectMismatch
        }

        val trackingIds = plan.trackable.map { it.trackingId }
        if (trackingIds.distinct().size != trackingIds.size) {
            return TrackerInitializationInvariantFailure.DuplicateAssignedTrackingId
        }
        return null
    }
}
