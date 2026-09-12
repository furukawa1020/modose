package com.modose.app.flow.guidance.rotation

import com.modose.app.flow.compare.CurrentObjectState

enum class RotationSymmetry(
    val equivalentPeriodDegrees: Double?,
) {
    None(360.0),
    HalfTurn(180.0),
    QuarterTurn(90.0),
    Continuous(null),
}

data class RotationGuideInput(
    val sceneObjectId: String,
    val state: CurrentObjectState,
    val orientationMeaningful: Boolean,
    val symmetry: RotationSymmetry,
    val currentYawDegrees: Double?,
    val targetYawDegrees: Double?,
)

enum class RotationDirection {
    Clockwise,
    CounterClockwise,
}

enum class RotationGuideHiddenReason {
    OrientationNotMeaningful,
    ContinuousSymmetry,
    EquivalentBySymmetry,
    ObjectMissing,
    ObjectAmbiguous,
    StateDoesNotRequireRotation,
}

sealed interface RotationGuideDecision {
    data class Show(
        val sceneObjectId: String,
        val direction: RotationDirection,
        val degrees: Double,
    ) : RotationGuideDecision

    data class Hidden(
        val sceneObjectId: String,
        val reason: RotationGuideHiddenReason,
    ) : RotationGuideDecision
}

enum class RotationGuideFailure {
    BlankSceneObjectId,
    MissingYaw,
    NonFiniteYaw,
}

sealed interface RotationGuideResult {
    data class Decided(val decision: RotationGuideDecision) : RotationGuideResult
    data class Rejected(val reason: RotationGuideFailure) : RotationGuideResult
}

object RotationGuideContract {
    const val SHOW_THRESHOLD_DEGREES = 10.0
    const val HIDE_THRESHOLD_DEGREES = 5.0
}
