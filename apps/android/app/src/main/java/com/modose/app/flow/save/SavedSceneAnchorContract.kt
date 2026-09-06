package com.modose.app.flow.save

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState

data class SavedSceneAnchorBinding(
    val sceneId: String,
    val anchorId: Long,
    val anchorPose: SceneAnchorPose,
    val boundAtEpochMillis: Long,
)

data class SavedSceneCommitRequest(
    val sceneId: String,
    val snapshotCommitted: Boolean,
    val anchorState: SceneAnchorState,
    val existingBinding: SavedSceneAnchorBinding?,
    val requestedAtEpochMillis: Long,
)

enum class SavedSceneCommitRejection {
    InvalidSceneId,
    SnapshotNotCommitted,
    AnchorUnavailable,
    AnchorPaused,
    AnchorLost,
    AnchorFailed,
    InvalidAnchor,
    InvalidTimestamp,
    SceneConflict,
    AnchorConflict,
}

sealed interface SavedSceneCommitDecision {
    data class Bind(
        val binding: SavedSceneAnchorBinding,
    ) : SavedSceneCommitDecision

    data class AlreadyBound(
        val binding: SavedSceneAnchorBinding,
    ) : SavedSceneCommitDecision

    data class Reject(
        val reason: SavedSceneCommitRejection,
    ) : SavedSceneCommitDecision
}

object SavedSceneCommitPolicy {
    fun decide(request: SavedSceneCommitRequest): SavedSceneCommitDecision {
        if (!SCENE_ID_PATTERN.matches(request.sceneId)) {
            return reject(SavedSceneCommitRejection.InvalidSceneId)
        }
        if (!request.snapshotCommitted) {
            return reject(SavedSceneCommitRejection.SnapshotNotCommitted)
        }
        if (request.requestedAtEpochMillis < 0) {
            return reject(SavedSceneCommitRejection.InvalidTimestamp)
        }

        val anchor = when (val state = request.anchorState) {
            is SceneAnchorState.Tracking -> state.anchor
            is SceneAnchorState.Paused ->
                return reject(SavedSceneCommitRejection.AnchorPaused)
            is SceneAnchorState.Lost ->
                return reject(SavedSceneCommitRejection.AnchorLost)
            is SceneAnchorState.Failed ->
                return reject(SavedSceneCommitRejection.AnchorFailed)
            SceneAnchorState.Unavailable ->
                return reject(SavedSceneCommitRejection.AnchorUnavailable)
        }
        if (!anchor.isValid()) {
            return reject(SavedSceneCommitRejection.InvalidAnchor)
        }

        val existing = request.existingBinding
        if (existing != null) {
            if (existing.sceneId != request.sceneId) {
                return reject(SavedSceneCommitRejection.SceneConflict)
            }
            if (existing.anchorId != anchor.id) {
                return reject(SavedSceneCommitRejection.AnchorConflict)
            }
            return SavedSceneCommitDecision.AlreadyBound(existing)
        }

        return SavedSceneCommitDecision.Bind(
            SavedSceneAnchorBinding(
                sceneId = request.sceneId,
                anchorId = anchor.id,
                anchorPose = anchor.pose,
                boundAtEpochMillis = request.requestedAtEpochMillis,
            ),
        )
    }

    private fun SceneAnchorSnapshot.isValid(): Boolean =
        id >= 0 &&
            pose.translationX.isFinite() &&
            pose.translationY.isFinite() &&
            pose.translationZ.isFinite() &&
            pose.rotationX.isFinite() &&
            pose.rotationY.isFinite() &&
            pose.rotationZ.isFinite() &&
            pose.rotationW.isFinite()

    private fun reject(
        reason: SavedSceneCommitRejection,
    ): SavedSceneCommitDecision = SavedSceneCommitDecision.Reject(reason)

    private val SCENE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
}
