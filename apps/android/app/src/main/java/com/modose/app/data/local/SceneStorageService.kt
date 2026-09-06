package com.modose.app.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SceneStorageRecoveryReport(
    val deletion: SceneDeletionRecoveryResult,
    val staging: SceneReconcileResult,
)

sealed interface SceneStorageInitializationResult {
    data class Ready(
        val report: SceneStorageRecoveryReport,
    ) : SceneStorageInitializationResult

    data class Blocked(
        val report: SceneStorageRecoveryReport,
    ) : SceneStorageInitializationResult
}

sealed interface SceneStorageSaveResult {
    data object NotInitialized : SceneStorageSaveResult

    data class Completed(
        val result: SceneSaveResult,
    ) : SceneStorageSaveResult
}

sealed interface SceneStorageDeleteResult {
    data object NotInitialized : SceneStorageDeleteResult

    data class Completed(
        val result: SceneDeletionResult,
    ) : SceneStorageDeleteResult
}

/**
 * Application-facing boundary for all SceneSnapshot storage mutations.
 *
 * Callers must use this service instead of invoking the individual repository,
 * reconciler, or deletion coordinator directly.
 */
class SceneStorageService(
    private val repository: SceneSnapshotRepository,
    private val stagingReconciler: SceneStorageReconciler,
    private val deletionCoordinator: SceneDeletionCoordinator,
) {
    private val operationMutex = Mutex()
    private var ready = false
    private var successfulRecovery: SceneStorageRecoveryReport? = null

    suspend fun initialize(): SceneStorageInitializationResult = operationMutex.withLock {
        if (ready) {
            return@withLock SceneStorageInitializationResult.Ready(
                checkNotNull(successfulRecovery),
            )
        }

        val deletion = deletionCoordinator.resumePending()
        val staging = stagingReconciler.reconcileStaging()
        val report = SceneStorageRecoveryReport(deletion, staging)
        if (deletion.isComplete() && staging.isComplete()) {
            ready = true
            successfulRecovery = report
            SceneStorageInitializationResult.Ready(report)
        } else {
            SceneStorageInitializationResult.Blocked(report)
        }
    }

    suspend fun save(write: SceneSnapshotWrite): SceneStorageSaveResult =
        operationMutex.withLock {
            if (!ready) {
                SceneStorageSaveResult.NotInitialized
            } else {
                SceneStorageSaveResult.Completed(repository.save(write))
            }
        }

    suspend fun delete(sceneId: String): SceneStorageDeleteResult =
        operationMutex.withLock {
            if (!ready) {
                SceneStorageDeleteResult.NotInitialized
            } else {
                SceneStorageDeleteResult.Completed(deletionCoordinator.delete(sceneId))
            }
        }

    private fun SceneDeletionRecoveryResult.isComplete(): Boolean =
        this is SceneDeletionRecoveryResult.Completed &&
            report.malformedMarkerNames.isEmpty() &&
            report.problems.isEmpty()

    private fun SceneReconcileResult.isComplete(): Boolean =
        this is SceneReconcileResult.Completed &&
            report.problems.isEmpty()
}
