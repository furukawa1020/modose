package com.modose.app.flow.verification

import com.modose.app.core.CoreRestoreState
import org.junit.Assert.*
import org.junit.Test

class VerificationFeedbackTest {
    private val labels = linkedMapOf("cup" to "カップ", "card" to "カード")
    private fun completed(status: SceneVerificationStatus, ids: List<String> = emptyList(),
        reasons: Set<String> = emptySet()) = ExecuteVerificationResult.Completed(
        SceneVerificationDecision(status, ids, reasons))
    private fun message(result: ExecuteVerificationResult, state: CoreRestoreState = CoreRestoreState.GUIDING) =
        VerificationFeedback.message(result, state, labels)
    private fun failed(reason: ExecuteVerificationFailure) = message(ExecuteVerificationResult.Failed(reason))

    @Test
    fun correctionsUseOnlySavedNamesAndDoNotRenderModelReason() {
        val text = message(completed(SceneVerificationStatus.NeedsCorrection,
            listOf("card", "cup"), setOf("untrusted model text")))
        assertTrue(text.contains("カード、カップ"))
        assertFalse(text.contains("untrusted model text"))
        assertFalse(text.contains("REALITY RESTORED"))
    }

    @Test
    fun unknownDuplicateOrEmptyCorrectionIdsAreRejected() {
        for (ids in listOf(listOf("unknown"), listOf("cup", "cup"), emptyList())) {
            assertTrue(message(completed(SceneVerificationStatus.NeedsCorrection, ids, setOf("position")))
                .contains("契約に適合しません"))
        }
    }

    @Test
    fun uncertainIsNotDisplayedAsSuccessOrRawModelText() {
        val text = message(completed(SceneVerificationStatus.Uncertain, reasons = setOf("private details")))
        assertTrue(text.contains("判断できません"))
        assertFalse(text.contains("private details"))
        assertFalse(text.contains("REALITY RESTORED"))
    }

    @Test
    fun evenVerifiedRequestFeedbackDoesNotEmitSuccessBanner() {
        for (state in CoreRestoreState.entries) {
            val text = message(completed(SceneVerificationStatus.Verified), state)
            assertFalse(text.contains("REALITY RESTORED"))
        }
    }

    @Test
    fun attemptLimitTakesPriorityOverNetworkAndCorrections() {
        for (result in listOf(ExecuteVerificationResult.Failed(ExecuteVerificationFailure.NetworkUnavailable),
            completed(SceneVerificationStatus.NeedsCorrection, listOf("cup"), setOf("position")))) {
            assertTrue(message(result, CoreRestoreState.MANUAL_CONFIRMATION).contains("手動で確認"))
        }
    }

    @Test
    fun authenticationAndDeviceAttestationAreDistinguished() {
        assertTrue(failed(ExecuteVerificationFailure.IdTokenUnavailable).contains("ユーザー認証"))
        assertTrue(failed(ExecuteVerificationFailure.AppCheckTokenUnavailable).contains("端末の正当性"))
        for (code in listOf(401, 403)) {
            assertTrue(failed(ExecuteVerificationFailure.HttpFailure(code, false)).contains("認証・端末検証"))
        }
    }

    @Test
    fun networkTimeoutAndRetryableApiFailureDoNotPromiseAutomaticRetry() {
        for (reason in listOf(ExecuteVerificationFailure.NetworkUnavailable,
            ExecuteVerificationFailure.TimedOut, ExecuteVerificationFailure.HttpFailure(503, true))) {
            val text = failed(reason)
            assertTrue(text.contains("自動再送せず"))
            assertTrue(text.contains("成功も保留"))
        }
    }

    @Test
    fun invalidResponsesUseSafeFixedMessage() {
        for (reason in listOf(ExecuteVerificationFailure.ResponseTooLarge,
            ExecuteVerificationFailure.DecodeRejected(VerificationDecodeFailure.MalformedJson),
            ExecuteVerificationFailure.MappingRejected(VerificationMappingFailure.UnknownCorrectionObject))) {
            assertTrue(failed(reason).contains("契約に適合しません"))
        }
    }

    @Test
    fun invalidLabelsAndContradictoryVerdictsCannotProduceCorrectionAdvice() {
        assertTrue(VerificationFeedback.message(completed(SceneVerificationStatus.Verified),
            CoreRestoreState.GUIDING, emptyMap()).contains("契約に適合しません"))
        assertTrue(message(completed(SceneVerificationStatus.Verified, listOf("cup")))
            .contains("契約に適合しません"))
        assertTrue(message(completed(SceneVerificationStatus.Uncertain)).contains("契約に適合しません"))
    }
}
