package com.modose.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object SceneStorageState {
    const val STAGING = "STAGING"
    const val COMMITTED = "COMMITTED"
}

@Entity(
    tableName = "scene_snapshots",
    indices = [
        Index(value = ["storage_state"]),
        Index(value = ["created_at_epoch_millis"]),
    ],
)
data class SceneEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "scene_id")
    val sceneId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long?,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "prompt_version")
    val promptVersion: String,
    @ColumnInfo(name = "vlm_repaired")
    val vlmRepaired: Boolean,
    @ColumnInfo(name = "image_file_name")
    val imageFileName: String,
    @ColumnInfo(name = "image_sha256")
    val imageSha256: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "storage_state")
    val storageState: String,
) {
    init {
        require(sceneId.isNotBlank())
        require(schemaVersion == "1.0")
        require(createdAtEpochMillis > 0)
        require(modelId.isNotBlank())
        require(promptVersion.isNotBlank())
        require(imageFileName.isSafeFileName())
        require(imageSha256.isSha256())
        require(contentFingerprint.isSha256())
        require(storageState == SceneStorageState.STAGING || storageState == SceneStorageState.COMMITTED)
        require(
            (storageState == SceneStorageState.STAGING && committedAtEpochMillis == null) ||
                (storageState == SceneStorageState.COMMITTED && committedAtEpochMillis != null),
        )
    }
}

@Entity(
    tableName = "scene_objects",
    primaryKeys = ["scene_id", "object_id"],
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["scene_id"],
            childColumns = ["scene_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["scene_id"]),
        Index(value = ["scene_id", "display_order"], unique = true),
    ],
)
data class SceneObjectEntity(
    @ColumnInfo(name = "scene_id")
    val sceneId: String,
    @ColumnInfo(name = "object_id")
    val objectId: String,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "appearance_features_json")
    val appearanceFeaturesJson: String,
    @ColumnInfo(name = "y_min")
    val yMin: Int,
    @ColumnInfo(name = "x_min")
    val xMin: Int,
    @ColumnInfo(name = "y_max")
    val yMax: Int,
    @ColumnInfo(name = "x_max")
    val xMax: Int,
    @ColumnInfo(name = "orientation_important")
    val orientationImportant: Boolean,
    val symmetry: String,
    val source: String,
) {
    init {
        require(sceneId.isNotBlank())
        require(objectId.isNotBlank())
        require(displayOrder in 0..4)
        require(displayName.isNotBlank())
        require(appearanceFeaturesJson.isNotBlank())
        require(yMin in 0..1000 && xMin in 0..1000)
        require(yMax in 0..1000 && xMax in 0..1000)
        require(yMin < yMax && xMin < xMax)
        require(symmetry in SUPPORTED_SYMMETRIES)
        require(source in SUPPORTED_SOURCES)
    }

    private companion object {
        val SUPPORTED_SYMMETRIES = setOf("none", "bilateral", "rotational")
        val SUPPORTED_SOURCES = setOf("detected", "manual")
    }
}

private fun String.isSafeFileName(): Boolean =
    isNotBlank() && '/' !in this && '\\' !in this && this != "." && this != ".."

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
