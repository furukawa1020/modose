package com.modose.app.ar.anchor

import com.modose.app.ar.plane.HorizontalPlaneState

data class SceneAnchorPose(
    val translationX: Float,
    val translationY: Float,
    val translationZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
)

data class SceneAnchorSnapshot(
    val id: Long,
    val pose: SceneAnchorPose,
)

sealed interface SceneAnchorState {
    data object Unavailable : SceneAnchorState
    data class Tracking(val anchor: SceneAnchorSnapshot) : SceneAnchorState
    data class Paused(val anchor: SceneAnchorSnapshot) : SceneAnchorState
    data class Lost(val previousAnchorId: Long) : SceneAnchorState
    data class Failed(val reason: SceneAnchorFailureReason) : SceneAnchorState
}

enum class SceneAnchorFailureReason {
    NotTracking,
    ResourceExhausted,
    CreationFailed,
}

sealed interface SceneAnchorCreationDecision {
    data class CreateAtCenter(val planeId: Long) : SceneAnchorCreationDecision
    data class ReuseExisting(val anchorId: Long) : SceneAnchorCreationDecision
    data class Reject(val reason: SceneAnchorCreationRejection) : SceneAnchorCreationDecision
}

enum class SceneAnchorCreationRejection {
    SurfaceUnavailable,
    SurfaceLost,
}

object SceneAnchorCreationPolicy {
    fun decide(
        planeState: HorizontalPlaneState,
        anchorState: SceneAnchorState,
    ): SceneAnchorCreationDecision {
        val existingId = when (anchorState) {
            is SceneAnchorState.Tracking -> anchorState.anchor.id
            is SceneAnchorState.Paused -> anchorState.anchor.id
            SceneAnchorState.Unavailable,
            is SceneAnchorState.Lost,
            is SceneAnchorState.Failed,
            -> null
        }
        if (existingId != null) {
            return SceneAnchorCreationDecision.ReuseExisting(existingId)
        }

        return when (planeState) {
            is HorizontalPlaneState.Tracking -> SceneAnchorCreationDecision.CreateAtCenter(
                planeState.plane.id,
            )
            HorizontalPlaneState.Searching -> SceneAnchorCreationDecision.Reject(
                SceneAnchorCreationRejection.SurfaceUnavailable,
            )
            is HorizontalPlaneState.Lost -> SceneAnchorCreationDecision.Reject(
                SceneAnchorCreationRejection.SurfaceLost,
            )
        }
    }
}

enum class SceneAnchorTrackingState {
    Tracking,
    Paused,
    Stopped,
}

class SceneAnchorStateTracker {
    private var previousAnchorId: Long? = null

    fun update(
        anchor: SceneAnchorSnapshot?,
        trackingState: SceneAnchorTrackingState?,
    ): SceneAnchorState {
        if (anchor == null || trackingState == null) {
            val lostId = previousAnchorId
            previousAnchorId = null
            return if (lostId == null) {
                SceneAnchorState.Unavailable
            } else {
                SceneAnchorState.Lost(lostId)
            }
        }

        previousAnchorId = anchor.id
        return when (trackingState) {
            SceneAnchorTrackingState.Tracking -> SceneAnchorState.Tracking(anchor)
            SceneAnchorTrackingState.Paused -> SceneAnchorState.Paused(anchor)
            SceneAnchorTrackingState.Stopped -> {
                previousAnchorId = null
                SceneAnchorState.Lost(anchor.id)
            }
        }
    }

    fun reset() {
        previousAnchorId = null
    }
}
