package com.modose.app.data.local

enum class SceneRecoveryFailure {
    IMAGE_DELETE_FAILED,
    DATABASE_DELETE_FAILED,
    STATE_CHANGED,
}

data class SceneRecoveryProblem(
    val sceneId: String,
    val failure: SceneRecoveryFailure,
)

data class SceneRecoveryReport(
    val scannedCount: Int,
    val recoveredCount: Int,
    val problems: List<SceneRecoveryProblem>,
)

sealed interface SceneReconcileResult {
    data class Completed(val report: SceneRecoveryReport) : SceneReconcileResult

    data object DatabaseUnavailable : SceneReconcileResult
}

/**
 * Repairs saves interrupted before the Room row reached COMMITTED.
 *
 * This operation must run during application startup before save operations are made available.
 * Committed rows are never selected and therefore are never removed by this reconciler.
 */
class SceneStorageReconciler(
    private val dao: SceneSnapshotDao,
    private val imageStore: SceneImageFileStore,
) {
    suspend fun reconcileStaging(): SceneReconcileResult {
        val stagingScenes = try {
            dao.listStaging()
        } catch (_: RuntimeException) {
            return SceneReconcileResult.DatabaseUnavailable
        }

        var recoveredCount = 0
        val problems = mutableListOf<SceneRecoveryProblem>()
        for (staging in stagingScenes) {
            val current = try {
                dao.findAnyState(staging.sceneId)
            } catch (_: RuntimeException) {
                problems += SceneRecoveryProblem(
                    staging.sceneId,
                    SceneRecoveryFailure.DATABASE_DELETE_FAILED,
                )
                continue
            }

            if (current?.storageState != SceneStorageState.STAGING) {
                problems += SceneRecoveryProblem(
                    staging.sceneId,
                    SceneRecoveryFailure.STATE_CHANGED,
                )
                continue
            }

            val imageDeleted =
                imageStore.deleteCommitted(staging.imageFileName) is
                    SceneImageDeleteResult.DeletedOrAbsent
            if (!imageDeleted) {
                problems += SceneRecoveryProblem(
                    staging.sceneId,
                    SceneRecoveryFailure.IMAGE_DELETE_FAILED,
                )
                continue
            }

            val rowDeleted = try {
                dao.deleteScene(staging.sceneId) == 1
            } catch (_: RuntimeException) {
                false
            }
            if (rowDeleted) {
                recoveredCount += 1
            } else {
                problems += SceneRecoveryProblem(
                    staging.sceneId,
                    SceneRecoveryFailure.DATABASE_DELETE_FAILED,
                )
            }
        }

        return SceneReconcileResult.Completed(
            SceneRecoveryReport(
                scannedCount = stagingScenes.size,
                recoveredCount = recoveredCount,
                problems = problems,
            ),
        )
    }
}
