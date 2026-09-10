package com.modose.app.flow.tracking

data class EmbeddingReidentificationCandidate(
    val sceneObjectId: String,
    val trackingId: Int,
    val similarity: Double,
)

object EmbeddingTrackerReidentifier {
    fun apply(
        initial: TrackerInitializationPlan,
        detections: List<TrackerDetectionCandidate>,
        candidates: List<EmbeddingReidentificationCandidate>,
    ): TrackerInitializationResult {
        if (candidates.any { it.similarity !in 0.0..1.0 }) {
            return TrackerInitializationResult.Rejected(
                TrackerInitializationFailure.InvalidConfidence,
            )
        }

        val detectionById = detections.associateBy { it.trackingId }
        val staticById = initial.staticGuides.associateBy { it.sceneObjectId }
        val usedObjectIds = initial.trackable.mapTo(mutableSetOf()) { it.sceneObjectId }
        val usedTrackingIds = initial.trackable.mapTo(mutableSetOf()) { it.trackingId }
        val replacements = mutableMapOf<String, TrackerObjectAssignment.Trackable>()

        candidates
            .asSequence()
            .filter { it.similarity >= TrackerInitializationContract.MIN_EMBEDDING_SIMILARITY }
            .filter { it.sceneObjectId in staticById }
            .filter { it.trackingId in detectionById }
            .sortedWith(
                compareByDescending<EmbeddingReidentificationCandidate> { it.similarity }
                    .thenBy { it.sceneObjectId }
                    .thenBy { it.trackingId },
            )
            .forEach { candidate ->
                if (
                    candidate.sceneObjectId !in usedObjectIds &&
                    candidate.trackingId !in usedTrackingIds
                ) {
                    val detection = requireNotNull(detectionById[candidate.trackingId])
                    usedObjectIds += candidate.sceneObjectId
                    usedTrackingIds += candidate.trackingId
                    replacements[candidate.sceneObjectId] =
                        TrackerObjectAssignment.Trackable(
                            sceneObjectId = candidate.sceneObjectId,
                            trackingId = candidate.trackingId,
                            confidence = (
                                candidate.similarity * detection.detectionConfidence
                                ).coerceIn(0.0, 1.0),
                            reason = TrackerAssignmentReason.EmbeddingFallback,
                        )
                }
            }

        val assignments = initial.assignments.map { assignment ->
            replacements[assignment.sceneObjectId] ?: assignment
        }.sortedBy { it.sceneObjectId }

        return TrackerInitializationResult.Initialized(
            TrackerInitializationPlan(assignments),
        )
    }
}
