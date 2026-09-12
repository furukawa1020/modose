package com.modose.app.ui.completion

sealed interface RestoredResetState {
    data object Idle : RestoredResetState
    data class InFlight(val sceneId: String) : RestoredResetState
    data class Completed(val sceneId: String) : RestoredResetState
    data class Failed(val sceneId: String) : RestoredResetState
}

enum class RestoredResetRejection {
    CompletionHidden,
    AlreadyInFlight,
    AlreadyCompleted,
}

sealed interface RestoredResetRequestResult {
    data class Started(val sceneId: String) : RestoredResetRequestResult
    data class Rejected(val reason: RestoredResetRejection) :
        RestoredResetRequestResult
}

enum class RestoredResetTransitionFailure {
    NotInFlight,
    SceneMismatch,
}

sealed interface RestoredResetTransitionResult {
    data class Updated(val state: RestoredResetState) :
        RestoredResetTransitionResult

    data class Rejected(val reason: RestoredResetTransitionFailure) :
        RestoredResetTransitionResult
}

class RestoredCompletionResetCoordinator {
    var state: RestoredResetState = RestoredResetState.Idle
        private set

    @Synchronized
    fun begin(
        presentation: RestoredCompletionPresentation,
    ): RestoredResetRequestResult {
        val visible = presentation as? RestoredCompletionPresentation.Visible
            ?: return RestoredResetRequestResult.Rejected(
                RestoredResetRejection.CompletionHidden,
            )
        val sceneId = visible.model.sceneId

        when (val current = state) {
            is RestoredResetState.InFlight ->
                return RestoredResetRequestResult.Rejected(
                    RestoredResetRejection.AlreadyInFlight,
                )
            is RestoredResetState.Completed -> if (current.sceneId == sceneId) {
                return RestoredResetRequestResult.Rejected(
                    RestoredResetRejection.AlreadyCompleted,
                )
            }
            RestoredResetState.Idle,
            is RestoredResetState.Failed,
            -> Unit
        }

        state = RestoredResetState.InFlight(sceneId)
        return RestoredResetRequestResult.Started(sceneId)
    }

    @Synchronized
    fun complete(sceneId: String): RestoredResetTransitionResult =
        finish(sceneId, RestoredResetState.Completed(sceneId))

    @Synchronized
    fun fail(sceneId: String): RestoredResetTransitionResult =
        finish(sceneId, RestoredResetState.Failed(sceneId))

    private fun finish(
        sceneId: String,
        next: RestoredResetState,
    ): RestoredResetTransitionResult {
        val current = state as? RestoredResetState.InFlight
            ?: return RestoredResetTransitionResult.Rejected(
                RestoredResetTransitionFailure.NotInFlight,
            )
        if (current.sceneId != sceneId) {
            return RestoredResetTransitionResult.Rejected(
                RestoredResetTransitionFailure.SceneMismatch,
            )
        }

        state = next
        return RestoredResetTransitionResult.Updated(next)
    }
}
