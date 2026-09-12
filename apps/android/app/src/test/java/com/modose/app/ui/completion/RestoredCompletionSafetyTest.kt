package com.modose.app.ui.completion

import com.modose.app.flow.verification.FinalVerificationState
import com.modose.app.flow.verification.VerificationAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoredCompletionSafetyTest {
    @Test
    fun `verifiedかつ全物体完了の場合だけ表示する`() {
        val result = RestoredCompletionPresenter.present(
            input(FinalVerificationState.Verified(ATTEMPT)),
        )

        assertTrue(result is RestoredCompletionPresentation.Visible)
        val model = (result as RestoredCompletionPresentation.Visible).model
        assertEquals(SCENE_ID, model.sceneId)
        assertEquals("REALITY RESTORED", model.headline)
        assertEquals(2, model.restoredObjectCount)
    }

    @Test
    fun `verified以外の全状態で完了表示を禁止する`() {
        val states = listOf(
            FinalVerificationState.Idle,
            FinalVerificationState.Capturing(ATTEMPT),
            FinalVerificationState.Verifying(ATTEMPT),
            FinalVerificationState.NeedsCorrection(ATTEMPT, listOf("wallet")),
            FinalVerificationState.Uncertain(ATTEMPT, setOf("uncertain")),
            FinalVerificationState.WaitingForNetwork(ATTEMPT),
            FinalVerificationState.ManualConfirmationRequired(3),
        )

        states.forEach { state ->
            assertEquals(
                RestoredCompletionPresentation.Hidden(
                    RestoredCompletionSuppression.NotVerified,
                ),
                RestoredCompletionPresenter.present(input(state)),
            )
        }
    }

    @Test
    fun `verifiedでも局所完了集合が不足すれば表示しない`() {
        val result = RestoredCompletionPresenter.present(
            input(
                state = FinalVerificationState.Verified(ATTEMPT),
                completed = setOf("pen"),
            ),
        )

        assertEquals(
            RestoredCompletionPresentation.Hidden(
                RestoredCompletionSuppression.ObjectSetMismatch,
            ),
            result,
        )
    }

    @Test
    fun `別sceneのverified結果を表示しない`() {
        val foreign = ATTEMPT.copy(sceneId = "other-scene")
        val result = RestoredCompletionPresenter.present(
            input(FinalVerificationState.Verified(foreign)),
        )

        assertEquals(
            RestoredCompletionPresentation.Hidden(
                RestoredCompletionSuppression.SceneMismatch,
            ),
            result,
        )
    }

    @Test
    fun `同一sceneの成功ハプティクスは一度だけ実行する`() {
        val gate = RestoredCompletionHapticGate()
        var count = 0
        val haptic = RestoredSuccessHaptic { count += 1 }

        assertEquals(
            RestoredHapticDispatchResult.Performed,
            gate.dispatch(visible(), haptic),
        )
        assertEquals(
            RestoredHapticDispatchResult.AlreadyPerformed,
            gate.dispatch(visible(), haptic),
        )
        assertEquals(1, count)
    }

    @Test
    fun `非表示と実行失敗をハプティクス成功として記録しない`() {
        val gate = RestoredCompletionHapticGate()
        var count = 0
        assertEquals(
            RestoredHapticDispatchResult.Hidden,
            gate.dispatch(
                RestoredCompletionPresentation.Hidden(
                    RestoredCompletionSuppression.NotVerified,
                ),
                RestoredSuccessHaptic { count += 1 },
            ),
        )
        assertEquals(
            RestoredHapticDispatchResult.Failed,
            gate.dispatch(
                visible(),
                RestoredSuccessHaptic { throw IllegalStateException("failed") },
            ),
        )
        assertEquals(
            RestoredHapticDispatchResult.Performed,
            gate.dispatch(visible(), RestoredSuccessHaptic { count += 1 }),
        )
        assertEquals(1, count)
    }

    @Test
    fun `リセット連打と完了後の再実行を拒否する`() {
        val coordinator = RestoredCompletionResetCoordinator()

        assertEquals(
            RestoredResetRequestResult.Started(SCENE_ID),
            coordinator.begin(visible()),
        )
        assertEquals(
            RestoredResetRequestResult.Rejected(
                RestoredResetRejection.AlreadyInFlight,
            ),
            coordinator.begin(visible()),
        )
        assertEquals(
            RestoredResetTransitionResult.Updated(
                RestoredResetState.Completed(SCENE_ID),
            ),
            coordinator.complete(SCENE_ID),
        )
        assertEquals(
            RestoredResetRequestResult.Rejected(
                RestoredResetRejection.AlreadyCompleted,
            ),
            coordinator.begin(visible()),
        )
    }

    @Test
    fun `別sceneの完了通知を拒否し失敗後だけ再試行する`() {
        val coordinator = RestoredCompletionResetCoordinator()
        coordinator.begin(visible())

        assertEquals(
            RestoredResetTransitionResult.Rejected(
                RestoredResetTransitionFailure.SceneMismatch,
            ),
            coordinator.complete("other-scene"),
        )
        assertEquals(
            RestoredResetTransitionResult.Updated(
                RestoredResetState.Failed(SCENE_ID),
            ),
            coordinator.fail(SCENE_ID),
        )
        assertEquals(
            RestoredResetRequestResult.Started(SCENE_ID),
            coordinator.begin(visible()),
        )
    }

    private fun input(
        state: FinalVerificationState,
        completed: Set<String> = OBJECT_IDS.toSet(),
    ) = RestoredCompletionInput(
        sceneId = SCENE_ID,
        expectedObjectIds = OBJECT_IDS,
        locallyCompletedObjectIds = completed,
        verificationState = state,
    )

    private fun visible(): RestoredCompletionPresentation.Visible =
        RestoredCompletionPresenter.present(
            input(FinalVerificationState.Verified(ATTEMPT)),
        ) as RestoredCompletionPresentation.Visible

    private companion object {
        const val SCENE_ID = "scene-088"
        val OBJECT_IDS = listOf("pen", "wallet")
        val ATTEMPT = VerificationAttempt(
            sceneId = SCENE_ID,
            attemptNumber = 1,
            attemptKey = "018f0f90-1234-7abc-8def-123456789abc",
        )
    }
}
