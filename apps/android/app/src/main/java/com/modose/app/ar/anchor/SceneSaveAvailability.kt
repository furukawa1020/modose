package com.modose.app.ar.anchor

sealed interface SceneSaveAvailability {
    data object Ready : SceneSaveAvailability
    data class Blocked(val reason: SceneSaveBlockedReason) : SceneSaveAvailability
}

enum class SceneSaveBlockedReason {
    AnchorUnavailable,
    AnchorPaused,
    AnchorLost,
    AnchorFailed,
}

object SceneSaveAvailabilityPolicy {
    fun resolve(anchorState: SceneAnchorState?): SceneSaveAvailability = when (anchorState) {
        is SceneAnchorState.Tracking -> SceneSaveAvailability.Ready
        is SceneAnchorState.Paused -> blocked(SceneSaveBlockedReason.AnchorPaused)
        is SceneAnchorState.Lost -> blocked(SceneSaveBlockedReason.AnchorLost)
        is SceneAnchorState.Failed -> blocked(SceneSaveBlockedReason.AnchorFailed)
        null,
        SceneAnchorState.Unavailable,
        -> blocked(SceneSaveBlockedReason.AnchorUnavailable)
    }

    private fun blocked(reason: SceneSaveBlockedReason) = SceneSaveAvailability.Blocked(reason)
}
