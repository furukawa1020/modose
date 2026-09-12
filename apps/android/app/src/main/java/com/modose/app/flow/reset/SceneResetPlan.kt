package com.modose.app.flow.reset

import java.util.UUID

data class SceneResetRequest(
    val sceneId: String,
    val resetKey: String,
)

enum class SceneResetStep {
    MarkLocalTombstone,
    DeleteLocalImages,
    DeleteRoomScene,
    DetachSceneAnchor,
    ClearObjectTracker,
    ClearImageEmbeddings,
    ReturnUiToInitialState,
    EnqueueMetadataDeletion,
}

data class SceneResetPlan(
    val sceneId: String,
    val resetKey: String,
    val localCriticalSteps: List<SceneResetStep>,
    val runtimeReleaseSteps: List<SceneResetStep>,
    val deferredSteps: List<SceneResetStep>,
)

enum class SceneResetPlanFailure {
    BlankSceneId,
    InvalidResetKey,
}

sealed interface SceneResetPlanResult {
    data class Accepted(val plan: SceneResetPlan) : SceneResetPlanResult
    data class Rejected(val reason: SceneResetPlanFailure) :
        SceneResetPlanResult
}

object SceneResetPlanFactory {
    private val localCriticalSteps = listOf(
        SceneResetStep.MarkLocalTombstone,
        SceneResetStep.DeleteLocalImages,
        SceneResetStep.DeleteRoomScene,
    )
    private val runtimeReleaseSteps = listOf(
        SceneResetStep.DetachSceneAnchor,
        SceneResetStep.ClearObjectTracker,
        SceneResetStep.ClearImageEmbeddings,
        SceneResetStep.ReturnUiToInitialState,
    )
    private val deferredSteps = listOf(
        SceneResetStep.EnqueueMetadataDeletion,
    )

    fun create(request: SceneResetRequest): SceneResetPlanResult {
        if (request.sceneId.isBlank()) {
            return SceneResetPlanResult.Rejected(
                SceneResetPlanFailure.BlankSceneId,
            )
        }
        if (!isCanonicalUuidV7(request.resetKey)) {
            return SceneResetPlanResult.Rejected(
                SceneResetPlanFailure.InvalidResetKey,
            )
        }

        return SceneResetPlanResult.Accepted(
            SceneResetPlan(
                sceneId = request.sceneId,
                resetKey = request.resetKey,
                localCriticalSteps = localCriticalSteps,
                runtimeReleaseSteps = runtimeReleaseSteps,
                deferredSteps = deferredSteps,
            ),
        )
    }

    private fun isCanonicalUuidV7(value: String): Boolean = try {
        val parsed = UUID.fromString(value)
        parsed.version() == 7 && parsed.toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }
}
