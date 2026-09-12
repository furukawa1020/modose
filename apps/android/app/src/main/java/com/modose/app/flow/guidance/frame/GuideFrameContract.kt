package com.modose.app.flow.guidance.frame

data class ScenePlanePoint(
    val xMeters: Double,
    val zMeters: Double,
)

enum class GuideTrackingState {
    Valid,
    Lost,
    Ambiguous,
}

data class GuideFrameInput(
    val sceneObjectId: String,
    val frameTimestampNanos: Long,
    val currentPosition: ScenePlanePoint,
    val targetPosition: ScenePlanePoint,
    val trackingState: GuideTrackingState,
)

data class GuideArrowVector(
    val start: ScenePlanePoint,
    val end: ScenePlanePoint,
    val deltaXMeters: Double,
    val deltaZMeters: Double,
    val distanceMeters: Double,
)

sealed interface GuideFrameVisual {
    val sceneObjectId: String
    val distanceMeters: Double

    data class Arrow(
        override val sceneObjectId: String,
        override val distanceMeters: Double,
        val vector: GuideArrowVector,
    ) : GuideFrameVisual

    data class AlignmentRing(
        override val sceneObjectId: String,
        override val distanceMeters: Double,
        val stableForMillis: Long,
    ) : GuideFrameVisual

    data class LocallyCompleted(
        override val sceneObjectId: String,
        override val distanceMeters: Double,
    ) : GuideFrameVisual
}

enum class GuideFrameFreezeReason {
    TrackingLost,
    AmbiguousTracking,
    UpdateRateLimited,
}

enum class GuideFrameRejection {
    BlankSceneObjectId,
    InvalidTimestamp,
    NonFiniteCoordinate,
    NonFiniteDistance,
    TimestampMovedBackwards,
}

sealed interface GuideFrameUpdateResult {
    data class Updated(val visual: GuideFrameVisual) : GuideFrameUpdateResult

    data class Frozen(
        val previousVisual: GuideFrameVisual?,
        val reason: GuideFrameFreezeReason,
    ) : GuideFrameUpdateResult

    data class Rejected(val reason: GuideFrameRejection) : GuideFrameUpdateResult
}

object GuideFrameContract {
    const val UPDATE_HZ = 15L
    const val MIN_UPDATE_INTERVAL_NANOS = 1_000_000_000L / UPDATE_HZ
    const val ALIGNMENT_ENTER_METERS = 0.03
    const val ALIGNMENT_EXIT_METERS = 0.05
    const val REQUIRED_STABLE_MILLIS = 800L
}
