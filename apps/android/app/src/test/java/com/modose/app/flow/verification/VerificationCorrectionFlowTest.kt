package com.modose.app.flow.verification

import com.modose.app.flow.guidance.ActiveGuideContent
import com.modose.app.flow.guidance.GuideCandidateLocation
import com.modose.app.flow.guidance.SingleObjectGuideCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationCorrectionFlowTest {
    @Test
    fun `修正対象と保持対象を既知物体順で分離する`() {
        val result = VerificationCorrectionMapper.map(
            mappingInput(corrections = listOf("wallet")),
        )

        assertEquals(
            VerificationCorrectionResult.Accepted(
                VerificationCorrectionPlan(
                    sceneId = SCENE_ID,
                    attempt = ATTEMPT,
                    correctionObjectIds = listOf("wallet"),
                    preservedCompletedObjectIds = listOf("pen", "cup"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `空重複未知の修正IDを拒否する`() {
        assertRejected(
            emptyList(),
            VerificationCorrectionFailure.EmptyCorrection,
        )
        assertRejected(
            listOf("wallet", "wallet"),
            VerificationCorrectionFailure.DuplicateCorrection,
        )
        assertRejected(
            listOf("unknown"),
            VerificationCorrectionFailure.UnknownCorrectionObject,
        )
    }

    @Test
    fun `uncertainを修正計画として受理しない`() {
        val input = mappingInput(corrections = emptyList()).copy(
            decision = SceneVerificationDecision(
                status = SceneVerificationStatus.Uncertain,
                reasonCodes = setOf("visual_ambiguity"),
            ),
        )

        assertEquals(
            VerificationCorrectionResult.Rejected(
                VerificationCorrectionFailure.NotNeedsCorrection,
            ),
            VerificationCorrectionMapper.map(input),
        )
    }

    @Test
    fun `指摘対象だけの完了を解除し試行回数を維持する`() {
        val result = VerificationCorrectionStateReducer.apply(
            current = completedProgress(attemptsUsed = 2),
            plan = correctionPlan(attempt = ATTEMPT.copy(attemptNumber = 2)),
        )

        assertTrue(result is CorrectionApplyResult.Applied)
        val progress = (result as CorrectionApplyResult.Applied).progress
        assertEquals(CorrectionObjectStatus.Completed, progress.objectStatuses["pen"])
        assertEquals(
            CorrectionObjectStatus.NeedsCorrection,
            progress.objectStatuses["wallet"],
        )
        assertEquals(CorrectionObjectStatus.Completed, progress.objectStatuses["cup"])
        assertEquals(2, progress.verificationAttemptsUsed)
    }

    @Test
    fun `修正対象以外をplannerの再選択候補に含めない`() {
        val attempt = ATTEMPT.copy(attemptNumber = 2)
        val applied = VerificationCorrectionStateReducer.apply(
            current = completedProgress(attemptsUsed = 2),
            plan = correctionPlan(attempt),
        ) as CorrectionApplyResult.Applied

        val result = VerificationCorrectionReplanner.plan(
            VerificationCorrectionReplanInput(
                plan = correctionPlan(attempt),
                progress = applied.progress,
                candidates = listOf(
                    candidate("pen"),
                    candidate("wallet", occludes = setOf("pen")),
                    candidate("cup"),
                ),
            ),
        )

        assertTrue(result is VerificationCorrectionReplanResult.Planned)
        val guidance =
            (result as VerificationCorrectionReplanResult.Planned).guidance
        assertEquals(
            ActiveGuideContent.MissingObject("wallet"),
            guidance.state.active,
        )
        assertEquals(emptyList<String>(), guidance.state.remainingObjectIds)
        assertEquals(listOf("pen", "cup"), guidance.preservedCompletedObjectIds)
        assertEquals(2, guidance.verificationAttempt.attemptNumber)
    }

    @Test
    fun `消費済みVerify回数と異なる修正計画を拒否する`() {
        val result = VerificationCorrectionStateReducer.apply(
            current = completedProgress(attemptsUsed = 2),
            plan = correctionPlan(ATTEMPT.copy(attemptNumber = 1)),
        )

        assertEquals(
            CorrectionApplyResult.Rejected(
                CorrectionApplyFailure.AttemptMismatch,
            ),
            result,
        )
    }

    private fun assertRejected(
        corrections: List<String>,
        reason: VerificationCorrectionFailure,
    ) {
        assertEquals(
            VerificationCorrectionResult.Rejected(reason),
            VerificationCorrectionMapper.map(mappingInput(corrections)),
        )
    }

    private fun mappingInput(
        corrections: List<String>,
    ) = VerificationCorrectionInput(
        sceneId = SCENE_ID,
        knownObjectIds = OBJECT_IDS,
        completedObjectIds = OBJECT_IDS.toSet(),
        attempt = ATTEMPT,
        decision = SceneVerificationDecision(
            status = SceneVerificationStatus.NeedsCorrection,
            correctionObjectIds = corrections,
            reasonCodes = setOf("position"),
        ),
    )

    private fun correctionPlan(
        attempt: VerificationAttempt = ATTEMPT,
    ) = VerificationCorrectionPlan(
        sceneId = SCENE_ID,
        attempt = attempt,
        correctionObjectIds = listOf("wallet"),
        preservedCompletedObjectIds = listOf("pen", "cup"),
    )

    private fun completedProgress(
        attemptsUsed: Int,
    ) = LocalRestorationProgress(
        sceneId = SCENE_ID,
        objectIds = OBJECT_IDS,
        objectStatuses = OBJECT_IDS.associateWith {
            CorrectionObjectStatus.Completed
        },
        verificationAttemptsUsed = attemptsUsed,
    )

    private fun candidate(
        id: String,
        occludes: Set<String> = emptySet(),
    ) = SingleObjectGuideCandidate(
        sceneObjectId = id,
        location = GuideCandidateLocation.Missing,
        targetPose = null,
        occludesObjectIds = occludes,
        correspondenceConfidence = 0.9,
        distanceToTargetMeters = null,
    )

    private companion object {
        const val SCENE_ID = "scene-087"
        val OBJECT_IDS = listOf("pen", "wallet", "cup")
        val ATTEMPT = VerificationAttempt(
            sceneId = SCENE_ID,
            attemptNumber = 1,
            attemptKey = "018f0f90-1234-7abc-8def-123456789abc",
        )
    }
}
