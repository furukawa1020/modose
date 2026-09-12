package com.modose.app.ui.completion

import com.modose.app.flow.verification.FinalVerificationState

data class RestoredCompletionInput(
    val sceneId: String,
    val expectedObjectIds: List<String>,
    val locallyCompletedObjectIds: Set<String>,
    val verificationState: FinalVerificationState,
)

data class RestoredCompletionUiModel(
    val sceneId: String,
    val headline: String,
    val restoredObjectCount: Int,
    val resetLabel: String,
)

enum class RestoredCompletionSuppression {
    NotVerified,
    BlankSceneId,
    SceneMismatch,
    EmptyExpectedObjects,
    DuplicateExpectedObject,
    ObjectSetMismatch,
}

sealed interface RestoredCompletionPresentation {
    data class Visible(val model: RestoredCompletionUiModel) :
        RestoredCompletionPresentation

    data class Hidden(val reason: RestoredCompletionSuppression) :
        RestoredCompletionPresentation
}

object RestoredCompletionPresenter {
    const val HEADLINE = "REALITY RESTORED"
    const val RESET_LABEL = "新しいシーンを保存"

    fun present(
        input: RestoredCompletionInput,
    ): RestoredCompletionPresentation {
        val verified = input.verificationState as? FinalVerificationState.Verified
            ?: return hidden(RestoredCompletionSuppression.NotVerified)

        if (input.sceneId.isBlank()) {
            return hidden(RestoredCompletionSuppression.BlankSceneId)
        }
        if (verified.attempt.sceneId != input.sceneId) {
            return hidden(RestoredCompletionSuppression.SceneMismatch)
        }
        if (input.expectedObjectIds.isEmpty()) {
            return hidden(RestoredCompletionSuppression.EmptyExpectedObjects)
        }

        val expected = input.expectedObjectIds.toSet()
        if (expected.size != input.expectedObjectIds.size) {
            return hidden(RestoredCompletionSuppression.DuplicateExpectedObject)
        }
        if (input.locallyCompletedObjectIds != expected) {
            return hidden(RestoredCompletionSuppression.ObjectSetMismatch)
        }

        return RestoredCompletionPresentation.Visible(
            RestoredCompletionUiModel(
                sceneId = input.sceneId,
                headline = HEADLINE,
                restoredObjectCount = expected.size,
                resetLabel = RESET_LABEL,
            ),
        )
    }

    private fun hidden(
        reason: RestoredCompletionSuppression,
    ) = RestoredCompletionPresentation.Hidden(reason)
}
