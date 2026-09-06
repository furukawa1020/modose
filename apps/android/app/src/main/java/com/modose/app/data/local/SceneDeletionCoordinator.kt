package com.modose.app.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SceneDeletionStage {
    DATABASE_LOOKUP,
    TOMBSTONE_RECORD,
    DATABASE_DELETE,
    IMAGE_DELETE,
    TOMBSTONE_CLEAR,
}

sealed interface SceneDeletionResult {
    data class Deleted(val sceneId: String) : SceneDeletionResult

    data class NotFound(val sceneId: String) : SceneDeletionResult

    data class Failed(
        val sceneId: String,
        val stage: SceneDeletionStage,
    ) : SceneDeletionResult

    data class PendingRecovery(
        val sceneId: String,
        val stage: SceneDeletionStage,
    ) : SceneDeletionResult
}

data class SceneDeletionRecoveryProblem(
    val sceneId: String,
    val stage: SceneDeletionStage,
)

data class SceneDeletionRecoveryReport(
    val pendingCount: Int,
    val completedCount: Int,
    val malformedMarkerNames: List<String>,
    val problems: List<SceneDeletionRecoveryProblem>,
)

sealed interface SceneDeletionRecoveryResult {
    data class Completed(
        val report: SceneDeletionRecoveryReport,
    ) : SceneDeletionRecoveryResult

    data object TombstoneStoreUnavailable : SceneDeletionRecoveryResult
}

class SceneDeletionCoordinator(
    private val dao: SceneSnapshotDao,
    private val imageStore: SceneImageFileStore,
    private val tombstoneStore: SceneDeletionTombstoneStore,
) {
    private val operationMutex = Mutex()

    suspend fun delete(sceneId: String): SceneDeletionResult = operationMutex.withLock {
        val scene = try {
            dao.findAnyState(sceneId)
        } catch (_: RuntimeException) {
            return@withLock SceneDeletionResult.Failed(
                sceneId,
                SceneDeletionStage.DATABASE_LOOKUP,
            )
        } ?: return@withLock SceneDeletionResult.NotFound(sceneId)

        val tombstone = SceneDeletionTombstone(scene.sceneId, scene.imageFileName)
        if (tombstoneStore.record(tombstone) !is SceneTombstoneWriteResult.Recorded) {
            return@withLock SceneDeletionResult.Failed(
                sceneId,
                SceneDeletionStage.TOMBSTONE_RECORD,
            )
        }

        val rowDeleted = try {
            dao.deleteScene(sceneId) == 1
        } catch (_: RuntimeException) {
            false
        }
        if (!rowDeleted) {
            return@withLock SceneDeletionResult.PendingRecovery(
                sceneId,
                SceneDeletionStage.DATABASE_DELETE,
            )
        }

        if (imageStore.deleteCommitted(scene.imageFileName) !is
            SceneImageDeleteResult.DeletedOrAbsent
        ) {
            return@withLock SceneDeletionResult.PendingRecovery(
                sceneId,
                SceneDeletionStage.IMAGE_DELETE,
            )
        }

        if (tombstoneStore.clear(sceneId) !is SceneTombstoneClearResult.ClearedOrAbsent) {
            return@withLock SceneDeletionResult.PendingRecovery(
                sceneId,
                SceneDeletionStage.TOMBSTONE_CLEAR,
            )
        }

        SceneDeletionResult.Deleted(sceneId)
    }

    suspend fun resumePending(): SceneDeletionRecoveryResult = operationMutex.withLock {
        val listed = tombstoneStore.list()
        if (listed !is SceneTombstoneListResult.Loaded) {
            return@withLock SceneDeletionRecoveryResult.TombstoneStoreUnavailable
        }

        var completedCount = 0
        val problems = mutableListOf<SceneDeletionRecoveryProblem>()
        for (tombstone in listed.tombstones) {
            val databaseReady = try {
                dao.deleteScene(tombstone.sceneId)
                true
            } catch (_: RuntimeException) {
                false
            }
            if (!databaseReady) {
                problems += SceneDeletionRecoveryProblem(
                    tombstone.sceneId,
                    SceneDeletionStage.DATABASE_DELETE,
                )
                continue
            }

            if (imageStore.deleteCommitted(tombstone.imageFileName) !is
                SceneImageDeleteResult.DeletedOrAbsent
            ) {
                problems += SceneDeletionRecoveryProblem(
                    tombstone.sceneId,
                    SceneDeletionStage.IMAGE_DELETE,
                )
                continue
            }

            if (tombstoneStore.clear(tombstone.sceneId) !is
                SceneTombstoneClearResult.ClearedOrAbsent
            ) {
                problems += SceneDeletionRecoveryProblem(
                    tombstone.sceneId,
                    SceneDeletionStage.TOMBSTONE_CLEAR,
                )
                continue
            }
            completedCount += 1
        }

        SceneDeletionRecoveryResult.Completed(
            SceneDeletionRecoveryReport(
                pendingCount = listed.tombstones.size,
                completedCount = completedCount,
                malformedMarkerNames = listed.malformedMarkerNames,
                problems = problems,
            ),
        )
    }
}
