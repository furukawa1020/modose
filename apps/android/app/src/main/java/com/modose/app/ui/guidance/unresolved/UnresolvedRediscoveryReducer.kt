package com.modose.app.ui.guidance.unresolved

enum class RediscoveryStatus {
    Idle,
    InFlight,
    Resolved,
    Failed,
}

data class UnresolvedRediscoveryState(
    val model: UnresolvedObjectUiModel,
    val status: RediscoveryStatus = RediscoveryStatus.Idle,
    val attemptNumber: Int = 0,
)

sealed interface UnresolvedRediscoveryEvent {
    data class Request(
        val sceneId: String,
        val objectId: String,
    ) : UnresolvedRediscoveryEvent

    data object Resolved : UnresolvedRediscoveryEvent

    data class StillMissing(
        val reason: UnresolvedObjectReason,
    ) : UnresolvedRediscoveryEvent

    data class StillAmbiguous(
        val reason: UnresolvedObjectReason,
    ) : UnresolvedRediscoveryEvent

    data object Failed : UnresolvedRediscoveryEvent
}

sealed interface UnresolvedRediscoveryEffect {
    data class CaptureAndCompare(
        val sceneId: String,
        val objectId: String,
        val attemptKey: String,
    ) : UnresolvedRediscoveryEffect
}

data class UnresolvedRediscoveryUpdate(
    val state: UnresolvedRediscoveryState,
    val effect: UnresolvedRediscoveryEffect? = null,
)

object UnresolvedRediscoveryReducer {
    fun reduce(
        state: UnresolvedRediscoveryState,
        event: UnresolvedRediscoveryEvent,
    ): UnresolvedRediscoveryUpdate = when (event) {
        is UnresolvedRediscoveryEvent.Request -> request(state, event)
        UnresolvedRediscoveryEvent.Resolved -> complete(state)
        is UnresolvedRediscoveryEvent.StillMissing -> unresolved(
            state,
            UnresolvedObjectKind.Missing,
            event.reason,
        )
        is UnresolvedRediscoveryEvent.StillAmbiguous -> unresolved(
            state,
            UnresolvedObjectKind.Ambiguous,
            event.reason,
        )
        UnresolvedRediscoveryEvent.Failed -> fail(state)
    }

    private fun request(
        state: UnresolvedRediscoveryState,
        event: UnresolvedRediscoveryEvent.Request,
    ): UnresolvedRediscoveryUpdate {
        if (
            state.status == RediscoveryStatus.InFlight ||
            state.status == RediscoveryStatus.Resolved ||
            !state.model.rediscoveryEnabled ||
            event.sceneId != state.model.sceneId ||
            event.objectId != state.model.objectId
        ) {
            return UnresolvedRediscoveryUpdate(state)
        }

        val attempt = state.attemptNumber + 1
        return UnresolvedRediscoveryUpdate(
            state = state.copy(
                status = RediscoveryStatus.InFlight,
                attemptNumber = attempt,
            ),
            effect = UnresolvedRediscoveryEffect.CaptureAndCompare(
                sceneId = state.model.sceneId,
                objectId = state.model.objectId,
                attemptKey =
                    state.model.sceneId + ":" + state.model.objectId + ":" + attempt,
            ),
        )
    }

    private fun complete(state: UnresolvedRediscoveryState): UnresolvedRediscoveryUpdate =
        if (state.status != RediscoveryStatus.InFlight) {
            UnresolvedRediscoveryUpdate(state)
        } else {
            UnresolvedRediscoveryUpdate(
                state.copy(
                    status = RediscoveryStatus.Resolved,
                    model = state.model.copy(rediscoveryEnabled = false),
                ),
            )
        }

    private fun unresolved(
        state: UnresolvedRediscoveryState,
        kind: UnresolvedObjectKind,
        reason: UnresolvedObjectReason,
    ): UnresolvedRediscoveryUpdate =
        if (state.status != RediscoveryStatus.InFlight) {
            UnresolvedRediscoveryUpdate(state)
        } else {
            UnresolvedRediscoveryUpdate(
                state.copy(
                    status = RediscoveryStatus.Idle,
                    model = state.model.copy(kind = kind, reason = reason),
                ),
            )
        }

    private fun fail(state: UnresolvedRediscoveryState): UnresolvedRediscoveryUpdate =
        if (state.status != RediscoveryStatus.InFlight) {
            UnresolvedRediscoveryUpdate(state)
        } else {
            UnresolvedRediscoveryUpdate(
                state.copy(
                    status = RediscoveryStatus.Failed,
                    model = state.model.copy(reason = UnresolvedObjectReason.Unknown),
                ),
            )
        }
}
