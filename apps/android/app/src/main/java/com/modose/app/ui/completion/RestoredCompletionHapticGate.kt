package com.modose.app.ui.completion

enum class RestoredHapticDispatchResult {
    Performed,
    Hidden,
    AlreadyPerformed,
    Failed,
}

fun interface RestoredSuccessHaptic {
    fun perform()
}

class RestoredCompletionHapticGate {
    private val performedSceneIds = mutableSetOf<String>()

    @Synchronized
    fun dispatch(
        presentation: RestoredCompletionPresentation,
        haptic: RestoredSuccessHaptic,
    ): RestoredHapticDispatchResult {
        val visible = presentation as? RestoredCompletionPresentation.Visible
            ?: return RestoredHapticDispatchResult.Hidden
        val sceneId = visible.model.sceneId
        if (sceneId in performedSceneIds) {
            return RestoredHapticDispatchResult.AlreadyPerformed
        }

        return try {
            haptic.perform()
            performedSceneIds += sceneId
            RestoredHapticDispatchResult.Performed
        } catch (_: RuntimeException) {
            RestoredHapticDispatchResult.Failed
        }
    }
}
