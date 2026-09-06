package com.modose.app.ui.intermission

import com.modose.app.ar.anchor.SceneAnchorState

data class SavedObjectThumbnailModel(
    val objectId: String,
    val displayName: String,
    val imageFileName: String,
    val yMin: Int,
    val xMin: Int,
    val yMax: Int,
    val xMax: Int,
) {
    init {
        require(objectId.isNotBlank())
        require(displayName.isNotBlank())
        require(imageFileName.isNotBlank())
        require(yMin in 0 until yMax)
        require(xMin in 0 until xMax)
        require(yMax <= NORMALIZED_IMAGE_EDGE)
        require(xMax <= NORMALIZED_IMAGE_EDGE)
    }

    private companion object {
        const val NORMALIZED_IMAGE_EDGE = 1_000
    }
}

enum class RestorationStartAvailability {
    Ready,
    AnchorUnavailable,
    AnchorPaused,
    AnchorLost,
    AnchorFailed,
}

data class MoveObjectsIntermissionState(
    val sceneId: String,
    val objects: List<SavedObjectThumbnailModel>,
    val startAvailability: RestorationStartAvailability,
    val startInFlight: Boolean = false,
    val resetConfirmationVisible: Boolean = false,
) {
    init {
        require(sceneId.isNotBlank())
        require(objects.size in 1..MAX_OBJECT_COUNT)
        require(objects.map { it.objectId }.distinct().size == objects.size)
    }

    private companion object {
        const val MAX_OBJECT_COUNT = 5
    }
}

sealed interface MoveObjectsIntermissionEvent {
    data object StartRestoration : MoveObjectsIntermissionEvent

    data object StartFailed : MoveObjectsIntermissionEvent

    data object RequestReset : MoveObjectsIntermissionEvent

    data object ConfirmReset : MoveObjectsIntermissionEvent

    data object CancelReset : MoveObjectsIntermissionEvent

    data class AnchorChanged(
        val anchorState: SceneAnchorState?,
    ) : MoveObjectsIntermissionEvent
}

sealed interface MoveObjectsIntermissionEffect {
    data class CaptureCurrentScene(
        val sceneId: String,
    ) : MoveObjectsIntermissionEffect

    data class ResetScene(
        val sceneId: String,
    ) : MoveObjectsIntermissionEffect
}

data class MoveObjectsIntermissionUpdate(
    val state: MoveObjectsIntermissionState,
    val effect: MoveObjectsIntermissionEffect? = null,
)

object MoveObjectsIntermissionReducer {
    fun reduce(
        state: MoveObjectsIntermissionState,
        event: MoveObjectsIntermissionEvent,
    ): MoveObjectsIntermissionUpdate = when (event) {
        MoveObjectsIntermissionEvent.StartRestoration -> startRestoration(state)
        MoveObjectsIntermissionEvent.StartFailed ->
            MoveObjectsIntermissionUpdate(state.copy(startInFlight = false))
        MoveObjectsIntermissionEvent.RequestReset ->
            MoveObjectsIntermissionUpdate(
                state.copy(resetConfirmationVisible = true),
            )
        MoveObjectsIntermissionEvent.CancelReset ->
            MoveObjectsIntermissionUpdate(
                state.copy(resetConfirmationVisible = false),
            )
        MoveObjectsIntermissionEvent.ConfirmReset -> confirmReset(state)
        is MoveObjectsIntermissionEvent.AnchorChanged ->
            MoveObjectsIntermissionUpdate(
                state.copy(
                    startAvailability = availability(event.anchorState),
                ),
            )
    }

    private fun startRestoration(
        state: MoveObjectsIntermissionState,
    ): MoveObjectsIntermissionUpdate {
        if (
            state.startAvailability != RestorationStartAvailability.Ready ||
            state.startInFlight ||
            state.resetConfirmationVisible
        ) {
            return MoveObjectsIntermissionUpdate(state)
        }
        return MoveObjectsIntermissionUpdate(
            state = state.copy(startInFlight = true),
            effect = MoveObjectsIntermissionEffect.CaptureCurrentScene(
                state.sceneId,
            ),
        )
    }

    private fun confirmReset(
        state: MoveObjectsIntermissionState,
    ): MoveObjectsIntermissionUpdate {
        if (!state.resetConfirmationVisible || state.startInFlight) {
            return MoveObjectsIntermissionUpdate(state)
        }
        return MoveObjectsIntermissionUpdate(
            state = state.copy(resetConfirmationVisible = false),
            effect = MoveObjectsIntermissionEffect.ResetScene(state.sceneId),
        )
    }

    fun availability(
        anchorState: SceneAnchorState?,
    ): RestorationStartAvailability = when (anchorState) {
        is SceneAnchorState.Tracking -> RestorationStartAvailability.Ready
        is SceneAnchorState.Paused -> RestorationStartAvailability.AnchorPaused
        is SceneAnchorState.Lost -> RestorationStartAvailability.AnchorLost
        is SceneAnchorState.Failed -> RestorationStartAvailability.AnchorFailed
        SceneAnchorState.Unavailable,
        null,
        -> RestorationStartAvailability.AnchorUnavailable
    }
}
