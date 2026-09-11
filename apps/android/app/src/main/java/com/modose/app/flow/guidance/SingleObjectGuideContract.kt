package com.modose.app.flow.guidance

import com.modose.app.flow.compare.CompareBoundingBox

data class GuideTargetPose(
    val xMeters: Double,
    val zMeters: Double,
    val yawRadians: Double?,
)

sealed interface GuideCandidateLocation {
    data class Tracked(
        val trackingId: Int,
        val currentBoundingBox: CompareBoundingBox,
    ) : GuideCandidateLocation

    data class Static(
        val currentBoundingBox: CompareBoundingBox,
    ) : GuideCandidateLocation

    data object Missing : GuideCandidateLocation
    data object Ambiguous : GuideCandidateLocation
}

data class SingleObjectGuideCandidate(
    val sceneObjectId: String,
    val location: GuideCandidateLocation,
    val targetPose: GuideTargetPose?,
    val occludesObjectIds: Set<String>,
    val correspondenceConfidence: Double,
    val distanceToTargetMeters: Double?,
)

sealed interface ActiveGuideContent {
    val sceneObjectId: String

    data class Positional(
        override val sceneObjectId: String,
        val location: GuideCandidateLocation,
        val targetPose: GuideTargetPose,
    ) : ActiveGuideContent

    data class MissingObject(
        override val sceneObjectId: String,
    ) : ActiveGuideContent

    data class AmbiguousObject(
        override val sceneObjectId: String,
    ) : ActiveGuideContent
}

enum class GuidePauseReason {
    TrackingLost,
    ArTrackingUnavailable,
    AssignmentConflict,
}

sealed interface SingleObjectGuideState {
    data object Idle : SingleObjectGuideState

    data class Guiding(
        val active: ActiveGuideContent,
        val remainingObjectIds: List<String>,
    ) : SingleObjectGuideState

    data class Paused(
        val active: ActiveGuideContent.Positional,
        val remainingObjectIds: List<String>,
        val reason: GuidePauseReason,
    ) : SingleObjectGuideState

    data class ReadyForVerification(
        val completedObjectIds: List<String>,
    ) : SingleObjectGuideState
}

enum class SingleObjectGuideFailure {
    EmptyScene,
    TooManyObjects,
    DuplicateSceneObjectId,
    InvalidConfidence,
    InvalidDistance,
    InvalidTargetPose,
    PositionalTargetMissing,
    NonPositionalTargetHasPose,
    UnknownOcclusionReference,
}

sealed interface SingleObjectGuideResult {
    data class Accepted(val state: SingleObjectGuideState) : SingleObjectGuideResult
    data class Rejected(val reason: SingleObjectGuideFailure) : SingleObjectGuideResult
}

object SingleObjectGuideContract {
    const val MAX_OBJECTS = 5
}
