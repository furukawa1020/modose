package com.modose.app.flow.tracking

import com.modose.app.flow.compare.CompareBoundingBox
import com.modose.app.flow.compare.CurrentObjectState

data class TrackerInitializationObject(
    val sceneObjectId: String,
    val state: CurrentObjectState,
    val currentBoundingBox: CompareBoundingBox?,
    val sameObjectConfidence: Double,
)

data class TrackerDetectionCandidate(
    val trackingId: Int,
    val boundingBox: CompareBoundingBox,
    val detectionConfidence: Double,
    val embeddingSimilarity: Double? = null,
)

data class TrackerInitializationInput(
    val objects: List<TrackerInitializationObject>,
    val detections: List<TrackerDetectionCandidate>,
)

enum class TrackerAssignmentReason {
    BoundingBoxMatch,
    EmbeddingFallback,
    NoDetectionCandidate,
    MissingFromCurrentScene,
    AmbiguousIdentity,
}

sealed interface TrackerObjectAssignment {
    val sceneObjectId: String
    val reason: TrackerAssignmentReason

    data class Trackable(
        override val sceneObjectId: String,
        val trackingId: Int,
        val confidence: Double,
        override val reason: TrackerAssignmentReason,
    ) : TrackerObjectAssignment

    data class StaticGuide(
        override val sceneObjectId: String,
        val currentBoundingBox: CompareBoundingBox,
        override val reason: TrackerAssignmentReason,
    ) : TrackerObjectAssignment

    data class Missing(
        override val sceneObjectId: String,
        override val reason: TrackerAssignmentReason =
            TrackerAssignmentReason.MissingFromCurrentScene,
    ) : TrackerObjectAssignment

    data class Ambiguous(
        override val sceneObjectId: String,
        override val reason: TrackerAssignmentReason =
            TrackerAssignmentReason.AmbiguousIdentity,
    ) : TrackerObjectAssignment
}

data class TrackerInitializationPlan(
    val assignments: List<TrackerObjectAssignment>,
) {
    val trackable: List<TrackerObjectAssignment.Trackable>
        get() = assignments.filterIsInstance<TrackerObjectAssignment.Trackable>()

    val staticGuides: List<TrackerObjectAssignment.StaticGuide>
        get() = assignments.filterIsInstance<TrackerObjectAssignment.StaticGuide>()

    val missing: List<TrackerObjectAssignment.Missing>
        get() = assignments.filterIsInstance<TrackerObjectAssignment.Missing>()

    val ambiguous: List<TrackerObjectAssignment.Ambiguous>
        get() = assignments.filterIsInstance<TrackerObjectAssignment.Ambiguous>()
}

enum class TrackerInitializationFailure {
    ObjectCountOutOfRange,
    DuplicateSceneObjectId,
    DuplicateTrackingId,
    InvalidBoundingBox,
    InvalidConfidence,
    MissingStateHasCoordinates,
    AmbiguousStateHasCoordinates,
}

sealed interface TrackerInitializationResult {
    data class Initialized(val plan: TrackerInitializationPlan) : TrackerInitializationResult
    data class Rejected(val reason: TrackerInitializationFailure) : TrackerInitializationResult
}

object TrackerInitializationContract {
    const val MIN_OBJECTS = 1
    const val MAX_OBJECTS = 5
    const val MIN_EMBEDDING_SIMILARITY = 0.80
}
