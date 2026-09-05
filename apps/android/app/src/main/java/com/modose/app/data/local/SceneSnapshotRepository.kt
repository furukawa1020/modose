package com.modose.app.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SceneSaveStage {
    IMAGE_PREPARE,
    DATABASE_LOOKUP,
    DATABASE_INSERT,
    IMAGE_COMMIT,
    DATABASE_COMMIT,
    COMPENSATION,
}

sealed interface SceneSaveResult {
    data class Saved(val sceneId: String) : SceneSaveResult

    data class AlreadySaved(val sceneId: String) : SceneSaveResult

    data class Conflict(val sceneId: String) : SceneSaveResult

    data class Rejected(val reason: ScenePlanRejection) : SceneSaveResult

    data class Failed(
        val stage: SceneSaveStage,
        val imageFailure: SceneImageFailure? = null,
    ) : SceneSaveResult

    data class RecoveryRequired(
        val sceneId: String,
        val stage: SceneSaveStage,
    ) : SceneSaveResult
}

class SceneSnapshotRepository(
    private val dao: SceneSnapshotDao,
    private val imageStore: SceneImageFileStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val saveMutex = Mutex()

    suspend fun save(write: SceneSnapshotWrite): SceneSaveResult = saveMutex.withLock {
        val prepared = when (val result = imageStore.prepare(write.sceneId, write.jpeg)) {
            is SceneImagePrepareResult.Prepared -> result.image
            is SceneImagePrepareResult.Rejected -> {
                return@withLock if (result.reason == SceneImageFailure.ExistingImageConflict) {
                    SceneSaveResult.Conflict(write.sceneId)
                } else {
                    SceneSaveResult.Failed(SceneSaveStage.IMAGE_PREPARE, result.reason)
                }
            }
        }

        val plan = when (val result = ScenePersistencePlanFactory.create(write, prepared)) {
            is ScenePlanResult.Planned -> result.plan
            is ScenePlanResult.Rejected -> {
                imageStore.abort(prepared)
                return@withLock SceneSaveResult.Rejected(result.reason)
            }
        }

        val existing = try {
            dao.findAnyState(write.sceneId)
        } catch (_: RuntimeException) {
            imageStore.abort(prepared)
            return@withLock SceneSaveResult.Failed(SceneSaveStage.DATABASE_LOOKUP)
        }

        if (existing != null) {
            imageStore.abort(prepared)
            return@withLock when {
                existing.storageState == SceneStorageState.COMMITTED &&
                    existing.contentFingerprint == plan.scene.contentFingerprint &&
                    prepared.alreadyCommitted -> SceneSaveResult.AlreadySaved(write.sceneId)

                existing.storageState == SceneStorageState.STAGING &&
                    existing.contentFingerprint == plan.scene.contentFingerprint ->
                    SceneSaveResult.RecoveryRequired(write.sceneId, SceneSaveStage.DATABASE_LOOKUP)

                else -> SceneSaveResult.Conflict(write.sceneId)
            }
        }

        try {
            dao.insertStagingSnapshot(plan.scene, plan.objects)
        } catch (_: RuntimeException) {
            imageStore.abort(prepared)
            return@withLock SceneSaveResult.Failed(SceneSaveStage.DATABASE_INSERT)
        }

        when (val result = imageStore.commit(prepared)) {
            is SceneImageCommitResult.Committed -> Unit
            is SceneImageCommitResult.Failed -> {
                imageStore.abort(prepared)
                return@withLock if (deleteStagingRow(write.sceneId)) {
                    SceneSaveResult.Failed(SceneSaveStage.IMAGE_COMMIT, result.reason)
                } else {
                    SceneSaveResult.RecoveryRequired(write.sceneId, SceneSaveStage.COMPENSATION)
                }
            }
        }

        val committed = try {
            dao.markCommitted(write.sceneId, nowEpochMillis()) == 1
        } catch (_: RuntimeException) {
            false
        }
        if (committed) {
            return@withLock SceneSaveResult.Saved(write.sceneId)
        }

        val imageDeleted =
            imageStore.deleteCommitted(plan.scene.imageFileName) is SceneImageDeleteResult.DeletedOrAbsent
        val rowDeleted = deleteStagingRow(write.sceneId)
        if (imageDeleted && rowDeleted) {
            SceneSaveResult.Failed(SceneSaveStage.DATABASE_COMMIT)
        } else {
            SceneSaveResult.RecoveryRequired(write.sceneId, SceneSaveStage.COMPENSATION)
        }
    }

    private suspend fun deleteStagingRow(sceneId: String): Boolean =
        try {
            dao.deleteScene(sceneId) == 1
        } catch (_: RuntimeException) {
            false
        }
}
