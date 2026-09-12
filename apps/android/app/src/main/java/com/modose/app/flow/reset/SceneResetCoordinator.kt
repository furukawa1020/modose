package com.modose.app.flow.reset

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SceneResetExecutionStage {
    LocalDelete,
    RuntimeRelease,
    UiReset,
    MetadataEnqueue,
}

sealed interface SceneResetFlowState {
    data object Idle : SceneResetFlowState
    data class Running(
        val sceneId: String,
        val stage: SceneResetExecutionStage,
    ) : SceneResetFlowState
    data class RetryRequired(
        val sceneId: String,
        val stage: SceneResetExecutionStage,
    ) : SceneResetFlowState
    data class ReadyForNewScene(
        val sceneId: String,
        val metadataPending: Boolean,
    ) : SceneResetFlowState
}

sealed interface SceneResetExecutionResult {
    data class ReadyForNewScene(
        val sceneId: String,
        val metadataQueued: Boolean,
        val reused: Boolean,
    ) : SceneResetExecutionResult
    data class RetryRequired(
        val sceneId: String,
        val stage: SceneResetExecutionStage,
    ) : SceneResetExecutionResult
}

class SceneResetCoordinator(
    private val deleteLocal: suspend (SceneResetPlan) -> LocalSceneResetResult,
    private val releaseRuntime: (SceneResetPlan) -> SceneRuntimeReleaseResult,
    private val resetUi: (sceneId: String) -> Boolean,
    private val enqueueMetadata: (SceneResetPlan) -> MetadataDeletionEnqueueResult,
) {
    private val mutex = Mutex()

    var state: SceneResetFlowState = SceneResetFlowState.Idle
        private set

    suspend fun execute(plan: SceneResetPlan): SceneResetExecutionResult =
        mutex.withLock {
            val current = state
            if (
                current is SceneResetFlowState.ReadyForNewScene &&
                current.sceneId == plan.sceneId
            ) {
                if (!current.metadataPending) {
                    return@withLock SceneResetExecutionResult.ReadyForNewScene(
                        sceneId = plan.sceneId,
                        metadataQueued = true,
                        reused = true,
                    )
                }
                return@withLock enqueueOnly(plan)
            }

            state = SceneResetFlowState.Running(
                plan.sceneId,
                SceneResetExecutionStage.LocalDelete,
            )
            when (deleteLocal(plan)) {
                is LocalSceneResetResult.Completed -> Unit
                is LocalSceneResetResult.RetryRequired,
                is LocalSceneResetResult.Blocked,
                -> return@withLock retry(
                    plan.sceneId,
                    SceneResetExecutionStage.LocalDelete,
                )
            }

            state = SceneResetFlowState.Running(
                plan.sceneId,
                SceneResetExecutionStage.RuntimeRelease,
            )
            when (releaseRuntime(plan)) {
                is SceneRuntimeReleaseResult.Completed -> Unit
                is SceneRuntimeReleaseResult.RetryRequired ->
                    return@withLock retry(
                        plan.sceneId,
                        SceneResetExecutionStage.RuntimeRelease,
                    )
            }

            state = SceneResetFlowState.Running(
                plan.sceneId,
                SceneResetExecutionStage.UiReset,
            )
            if (!safeUiReset(plan.sceneId)) {
                return@withLock retry(
                    plan.sceneId,
                    SceneResetExecutionStage.UiReset,
                )
            }

            enqueueOnly(plan)
        }

    private fun enqueueOnly(
        plan: SceneResetPlan,
    ): SceneResetExecutionResult {
        state = SceneResetFlowState.Running(
            plan.sceneId,
            SceneResetExecutionStage.MetadataEnqueue,
        )
        val queued = enqueueMetadata(plan) is MetadataDeletionEnqueueResult.Queued
        state = SceneResetFlowState.ReadyForNewScene(
            sceneId = plan.sceneId,
            metadataPending = !queued,
        )
        return SceneResetExecutionResult.ReadyForNewScene(
            sceneId = plan.sceneId,
            metadataQueued = queued,
            reused = false,
        )
    }

    private fun safeUiReset(sceneId: String): Boolean = try {
        resetUi(sceneId)
    } catch (_: RuntimeException) {
        false
    }

    private fun retry(
        sceneId: String,
        stage: SceneResetExecutionStage,
    ): SceneResetExecutionResult {
        state = SceneResetFlowState.RetryRequired(sceneId, stage)
        return SceneResetExecutionResult.RetryRequired(sceneId, stage)
    }
}
