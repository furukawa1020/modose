package com.modose.app.flow.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalVerificationCoordinatorTest {
    @Test
    fun `局所完了していない場面は最終確認を開始しない`() {
        val coordinator = FinalVerificationCoordinator()
        val result = coordinator.begin(
            eligibility(
                completed = setOf("pen"),
                unresolved = setOf("wallet"),
            ),
            KEY_1,
        )

        assertEquals(
            VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.ObjectsNotLocallyCompleted,
            ),
            result,
        )
        assertEquals(FinalVerificationState.Idle, coordinator.state)
    }

    @Test
    fun `通信断後は同じ試行を同じ冪等キーで再開する`() {
        val coordinator = FinalVerificationCoordinator()
        val attempt = coordinator.start(KEY_1)
        coordinator.onCaptureReady(attempt)

        val waiting = coordinator.onExecutionResult(
            attempt,
            ExecuteVerificationResult.Failed(
                ExecuteVerificationFailure.NetworkUnavailable,
            ),
        )
        assertEquals(
            VerificationTransitionResult.Updated(
                FinalVerificationState.WaitingForNetwork(attempt),
            ),
            waiting,
        )

        val resumed = coordinator.resumeAfterNetwork()
        assertEquals(
            VerificationTransitionResult.Updated(
                FinalVerificationState.Verifying(attempt),
            ),
            resumed,
        )
        assertEquals(1, attempt.attemptNumber)
        assertEquals(KEY_1, attempt.attemptKey)
    }

    @Test
    fun `使用済み冪等キーの再利用を拒否する`() {
        val coordinator = FinalVerificationCoordinator()
        val first = coordinator.start(KEY_1)
        coordinator.onCaptureReady(first)
        coordinator.onExecutionResult(first, uncertain())

        assertEquals(
            VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.DuplicateAttempt,
            ),
            coordinator.begin(eligibility(), KEY_1),
        )
    }

    @Test
    fun `3回失敗後の4回目は手動確認へ移す`() {
        val coordinator = FinalVerificationCoordinator()

        listOf(KEY_1, KEY_2, KEY_3).forEachIndexed { index, key ->
            val attempt = coordinator.start(key)
            assertEquals(index + 1, attempt.attemptNumber)
            coordinator.onCaptureReady(attempt)
            coordinator.onExecutionResult(attempt, uncertain())
        }

        val fourth = coordinator.begin(eligibility(), KEY_4)
        assertEquals(
            VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.AttemptLimitExceeded,
            ),
            fourth,
        )
        assertEquals(
            FinalVerificationState.ManualConfirmationRequired(3),
            coordinator.state,
        )
    }

    @Test
    fun `needs correctionは成功にせず次試行を許可する`() {
        val coordinator = FinalVerificationCoordinator()
        val first = coordinator.start(KEY_1)
        coordinator.onCaptureReady(first)

        coordinator.onExecutionResult(
            first,
            ExecuteVerificationResult.Completed(
                SceneVerificationDecision(
                    status = SceneVerificationStatus.NeedsCorrection,
                    correctionObjectIds = listOf("wallet"),
                    reasonCodes = setOf("position"),
                ),
            ),
        )
        assertEquals(
            FinalVerificationState.NeedsCorrection(first, listOf("wallet")),
            coordinator.state,
        )

        val second = coordinator.start(KEY_2)
        assertEquals(2, second.attemptNumber)
    }

    @Test
    fun `verifiedだけが成功状態になる`() {
        val coordinator = FinalVerificationCoordinator()
        val attempt = coordinator.start(KEY_1)
        coordinator.onCaptureReady(attempt)

        val result = coordinator.onExecutionResult(
            attempt,
            ExecuteVerificationResult.Completed(
                SceneVerificationDecision(SceneVerificationStatus.Verified),
            ),
        )

        assertEquals(
            VerificationTransitionResult.Updated(
                FinalVerificationState.Verified(attempt),
            ),
            result,
        )
        assertEquals(
            VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.InvalidState,
            ),
            coordinator.begin(eligibility(), KEY_2),
        )
    }

    @Test
    fun `別試行の応答で現在状態を変更しない`() {
        val coordinator = FinalVerificationCoordinator()
        val attempt = coordinator.start(KEY_1)
        coordinator.onCaptureReady(attempt)
        val foreignAttempt = attempt.copy(attemptKey = KEY_2)

        val result = coordinator.onExecutionResult(foreignAttempt, uncertain())

        assertEquals(
            VerificationTransitionResult.Rejected(
                VerificationTransitionFailure.AttemptMismatch,
            ),
            result,
        )
        assertEquals(FinalVerificationState.Verifying(attempt), coordinator.state)
    }

    @Test
    fun `UUIDv7以外の試行キーを拒否する`() {
        val result = FinalVerificationCoordinator().begin(
            eligibility(),
            "018f0f90-1234-4abc-8def-123456789abc",
        )

        assertEquals(
            VerificationAttemptResult.Rejected(
                VerificationAttemptFailure.InvalidAttemptKey,
            ),
            result,
        )
    }

    private fun FinalVerificationCoordinator.start(key: String): VerificationAttempt {
        val result = begin(eligibility(), key)
        assertTrue(result is VerificationAttemptResult.Started)
        return (result as VerificationAttemptResult.Started).attempt
    }

    private fun eligibility(
        completed: Set<String> = setOf("pen", "wallet"),
        unresolved: Set<String> = emptySet(),
    ) = VerificationEligibility(
        sceneId = "scene-086",
        expectedObjectIds = listOf("pen", "wallet"),
        locallyCompletedObjectIds = completed,
        unresolvedObjectIds = unresolved,
    )

    private fun uncertain() = ExecuteVerificationResult.Completed(
        SceneVerificationDecision(
            status = SceneVerificationStatus.Uncertain,
            reasonCodes = setOf("visual_ambiguity"),
        ),
    )

    private companion object {
        const val KEY_1 = "018f0f90-1234-7abc-8def-123456789abc"
        const val KEY_2 = "018f0f90-1234-7abc-8def-123456789abd"
        const val KEY_3 = "018f0f90-1234-7abc-8def-123456789abe"
        const val KEY_4 = "018f0f90-1234-7abc-8def-123456789abf"
    }
}
