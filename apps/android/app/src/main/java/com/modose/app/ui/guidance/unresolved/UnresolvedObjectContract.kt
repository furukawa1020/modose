package com.modose.app.ui.guidance.unresolved

import com.modose.app.ui.intermission.SavedObjectThumbnailModel

enum class UnresolvedObjectKind {
    Missing,
    Ambiguous,
}

enum class UnresolvedObjectReason {
    NotVisible,
    TemporarilyOccluded,
    MultipleSimilarCandidates,
    LowIdentityConfidence,
    TrackingUnavailable,
    Unknown,
}

data class UnresolvedObjectUiModel(
    val sceneId: String,
    val objectId: String,
    val displayName: String,
    val thumbnail: SavedObjectThumbnailModel,
    val kind: UnresolvedObjectKind,
    val reason: UnresolvedObjectReason,
    val rediscoveryEnabled: Boolean,
) {
    init {
        require(sceneId.isNotBlank())
        require(objectId.isNotBlank())
        require(displayName.isNotBlank())
        require(thumbnail.objectId == objectId)
    }
}

enum class UnresolvedObjectModelFailure {
    BlankSceneId,
    UnknownObject,
    SceneObjectMismatch,
    ObjectIsResolved,
    UnsupportedReason,
}

sealed interface UnresolvedObjectModelResult {
    data class Created(val model: UnresolvedObjectUiModel) :
        UnresolvedObjectModelResult

    data class Rejected(val reason: UnresolvedObjectModelFailure) :
        UnresolvedObjectModelResult
}

object UnresolvedObjectCopy {
    const val MISSING_TITLE = "この物体が見つかりません"
    const val AMBIGUOUS_TITLE = "同じ物体か判断できません"
    const val REDISCOVERY_ACTION = "カメラを向け直す"
    const val MISSING_DESCRIPTION =
        "保存時の画像を確認し、物体がカメラに映るようにしてください。"
    const val AMBIGUOUS_DESCRIPTION =
        "似た候補が複数あります。対象だけが見える角度へカメラを動かしてください。"
}
