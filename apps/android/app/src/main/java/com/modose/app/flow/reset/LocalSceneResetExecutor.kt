package com.modose.app.flow.reset

import com.modose.app.data.local.SceneDeletionResult
import com.modose.app.data.local.SceneDeletionStage
import com.modose.app.data.local.SceneStorageDeleteResult
import com.modose.app.data.local.SceneStorageService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LocalSceneResetState {
    data object Idle : LocalSceneResetState
    data class Deleting(val sceneId: String, val resetKey: String) :
        LocalSceneResetState
    data class PendingRecovery(
        val sceneId: String,
        val resetKey: String,
        val stage: SceneDeletionStage,
    ) : LocalSceneResetState
    data class Completed(val sceneId: String, val resetKey: String) :
        LocalSceneResetState
}

enum class LocalSceneResetBlockReason {
    StorageNotInitialized,
}

sealed interface LocalSceneResetResult {
    data class Completed(
        val sceneId: String,
        val reused: Boolean,
    ) : LocalSceneResetResult

    data class RetryRequired(
        val sceneId: String,
        val stage: SceneDeletionStage,
    ) : LocalSceneResetResult

    data class Blocked(val reason: LocalSceneResetBlockReason) :
        LocalSceneResetResult
}

class LocalSceneResetExecutor(
    private val storage: SceneStorageService,
) {
    private val mutex = Mutex()
    private val completedSceneIds = mutableSetOf<String>()

    var state: LocalSceneResetState = LocalSceneResetState.Idle
        private set

    suspend fun execute(plan: SceneResetPlan): LocalSceneResetResult =
        mutex.withLock {
            if (plan.sceneId in completedSceneIds) {
                return@withLock LocalSceneResetResult.Completed(
                    sceneId = plan.sceneId,
                    reused = true,
                )
            }

            state = LocalSceneResetState.Deleting(plan.sceneId, plan.resetKey)
            when (val result = storage.delete(plan.sceneId)) {
                SceneStorageDeleteResult.NotInitialized -> {
                    state = LocalSceneResetState.Idle
                    LocalSceneResetResult.Blocked(
                        LocalSceneResetBlockReason.StorageNotInitialized,
                    )
                }
                is SceneStorageDeleteResult.Completed ->
                    mapDeletion(plan, result.result)
            }
        }

    private fun mapDeletion(
        plan: SceneResetPlan,
        deletion: SceneDeletionResult,
    ): LocalSceneResetResult = when (deletion) {
        is SceneDeletionResult.Deleted,
        is SceneDeletionResult.NotFound,
        -> {
            completedSceneIds += plan.sceneId
            state = LocalSceneResetState.Completed(
                plan.sceneId,
                plan.resetKey,
            )
            LocalSceneResetResult.Completed(
                sceneId = plan.sceneId,
                reused = false,
            )
        }
        is SceneDeletionResult.Failed -> retry(plan, deletion.stage)
        is SceneDeletionResult.PendingRecovery -> retry(plan, deletion.stage)
    }

    private fun retry(
        plan: SceneResetPlan,
        stage: SceneDeletionStage,
    ): LocalSceneResetResult {
        state = LocalSceneResetState.PendingRecovery(
            sceneId = plan.sceneId,
            resetKey = plan.resetKey,
            stage = stage,
        )
        return LocalSceneResetResult.RetryRequired(plan.sceneId, stage)
    }
}
