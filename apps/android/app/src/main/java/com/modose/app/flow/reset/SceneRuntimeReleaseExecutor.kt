package com.modose.app.flow.reset

fun interface SceneAnchorRelease {
    fun detach(sceneId: String): Boolean
}

fun interface SceneTrackerRelease {
    fun clear(sceneId: String): Boolean
}

fun interface SceneEmbeddingRelease {
    fun clear(sceneId: String): Boolean
}

enum class RuntimeReleaseStage {
    Anchor,
    Tracker,
    Embeddings,
}

sealed interface SceneRuntimeReleaseState {
    data object Idle : SceneRuntimeReleaseState
    data class Releasing(
        val sceneId: String,
        val stage: RuntimeReleaseStage,
    ) : SceneRuntimeReleaseState
    data class Failed(
        val sceneId: String,
        val stage: RuntimeReleaseStage,
    ) : SceneRuntimeReleaseState
    data class Completed(val sceneId: String) : SceneRuntimeReleaseState
}

sealed interface SceneRuntimeReleaseResult {
    data class Completed(
        val sceneId: String,
        val reused: Boolean,
    ) : SceneRuntimeReleaseResult

    data class RetryRequired(
        val sceneId: String,
        val stage: RuntimeReleaseStage,
    ) : SceneRuntimeReleaseResult
}

class SceneRuntimeReleaseExecutor(
    private val anchor: SceneAnchorRelease,
    private val tracker: SceneTrackerRelease,
    private val embeddings: SceneEmbeddingRelease,
) {
    private val completedSceneIds = mutableSetOf<String>()

    var state: SceneRuntimeReleaseState = SceneRuntimeReleaseState.Idle
        private set

    @Synchronized
    fun release(plan: SceneResetPlan): SceneRuntimeReleaseResult {
        if (plan.sceneId in completedSceneIds) {
            return SceneRuntimeReleaseResult.Completed(
                sceneId = plan.sceneId,
                reused = true,
            )
        }

        if (!perform(plan.sceneId, RuntimeReleaseStage.Anchor) {
                anchor.detach(plan.sceneId)
            }
        ) {
            return retry(plan.sceneId, RuntimeReleaseStage.Anchor)
        }
        if (!perform(plan.sceneId, RuntimeReleaseStage.Tracker) {
                tracker.clear(plan.sceneId)
            }
        ) {
            return retry(plan.sceneId, RuntimeReleaseStage.Tracker)
        }
        if (!perform(plan.sceneId, RuntimeReleaseStage.Embeddings) {
                embeddings.clear(plan.sceneId)
            }
        ) {
            return retry(plan.sceneId, RuntimeReleaseStage.Embeddings)
        }

        completedSceneIds += plan.sceneId
        state = SceneRuntimeReleaseState.Completed(plan.sceneId)
        return SceneRuntimeReleaseResult.Completed(
            sceneId = plan.sceneId,
            reused = false,
        )
    }

    private fun perform(
        sceneId: String,
        stage: RuntimeReleaseStage,
        operation: () -> Boolean,
    ): Boolean {
        state = SceneRuntimeReleaseState.Releasing(sceneId, stage)
        return try {
            operation()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun retry(
        sceneId: String,
        stage: RuntimeReleaseStage,
    ): SceneRuntimeReleaseResult {
        state = SceneRuntimeReleaseState.Failed(sceneId, stage)
        return SceneRuntimeReleaseResult.RetryRequired(sceneId, stage)
    }
}
