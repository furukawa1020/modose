package com.modose.app.flow.recovery

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRecoveryMatrixTest {
    @Test
    fun `全製品エラーコードに決定的な回復先とUIモデルがある`() {
        ProductFailureCode.values().forEach { code ->
            val failure = failure(code)
            assertEquals(
                ProductRecoveryMapper.map(failure),
                ProductRecoveryMapper.map(failure),
            )
            assertEquals(
                ProductRecoveryPresenter.present(failure),
                ProductRecoveryPresenter.present(failure),
            )
        }
    }

    @Test
    fun `VLM JSON修復は1回で上限になる`() {
        val first = RecoveryRetryBudget.evaluate(
            failure(ProductFailureCode.MalformedVlmJson, attempts = 0),
        )
        assertTrue(first is RecoveryBudgetDecision.Allowed)
        val permit = (first as RecoveryBudgetDecision.Allowed).permit
        assertEquals(1, permit.maximumAttempts)
        assertEquals(1, permit.failure.automaticAttemptsUsed)

        val exhausted = RecoveryRetryBudget.evaluate(
            failure(ProductFailureCode.MalformedVlmJson, attempts = 1),
        )
        assertTrue(exhausted is RecoveryBudgetDecision.Exhausted)
        assertEquals(
            RecoveryAction.ReviewObjectsManually,
            (exhausted as RecoveryBudgetDecision.Exhausted).fallback.action,
        )
    }

    @Test
    fun `API再送は2回で上限になる`() {
        val allowed = RecoveryRetryBudget.evaluate(
            failure(ProductFailureCode.RequestTimedOut, attempts = 1),
        )
        assertEquals(
            2,
            (allowed as RecoveryBudgetDecision.Allowed)
                .permit.failure.automaticAttemptsUsed,
        )

        assertTrue(
            RecoveryRetryBudget.evaluate(
                failure(ProductFailureCode.RequestTimedOut, attempts = 2),
            ) is RecoveryBudgetDecision.Exhausted,
        )
    }

    @Test
    fun `tracking再取得は3回で上限になる`() {
        val allowed = RecoveryRetryBudget.evaluate(
            failure(ProductFailureCode.ArTrackingLost, attempts = 2),
        )
        assertEquals(
            3,
            (allowed as RecoveryBudgetDecision.Allowed)
                .permit.failure.automaticAttemptsUsed,
        )

        assertTrue(
            RecoveryRetryBudget.evaluate(
                failure(ProductFailureCode.ArTrackingLost, attempts = 3),
            ) is RecoveryBudgetDecision.Exhausted,
        )
    }

    @Test
    fun `unknown errorは自動継続せずfail closedになる`() {
        val failure = failure(ProductFailureCode.UnknownFailure)
        assertEquals(
            RecoveryRoute(
                action = RecoveryAction.FailClosed,
                mode = RecoveryMode.Terminal,
            ),
            ProductRecoveryMapper.map(failure),
        )
        assertTrue(
            RecoveryRetryBudget.evaluate(failure) is
                RecoveryBudgetDecision.NoAutomaticRetry,
        )
        assertEquals(
            RecoveryUiModel(
                messageKey = RecoveryMessageKey.UnableToContinueSafely,
                actionLabelKey = RecoveryActionLabelKey.CloseScene,
                severity = RecoveryUiSeverity.Blocking,
            ),
            ProductRecoveryPresenter.present(failure),
        )
    }

    @Test
    fun `削除再試行の上限後は停止する`() {
        val result = RecoveryRetryBudget.evaluate(
            failure(ProductFailureCode.LocalDeleteFailed, attempts = 3),
        )

        assertTrue(result is RecoveryBudgetDecision.Exhausted)
        assertEquals(
            RecoveryRoute(
                action = RecoveryAction.FailClosed,
                mode = RecoveryMode.Terminal,
            ),
            (result as RecoveryBudgetDecision.Exhausted).fallback,
        )
    }

    @Test
    fun `ユーザー操作routeは自動試行予算を消費しない`() {
        val failure = failure(
            ProductFailureCode.CameraPermissionDenied,
            attempts = 99,
        )

        assertEquals(
            RecoveryBudgetDecision.NoAutomaticRetry(
                RecoveryRoute(
                    action = RecoveryAction.RequestCameraPermission,
                    mode = RecoveryMode.UserAction,
                ),
            ),
            RecoveryRetryBudget.evaluate(failure),
        )
    }

    @Test
    fun `UIモデルは自由文フィールドを持たない`() {
        val fields = RecoveryUiModel::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

        assertFalse(fields.isEmpty())
        assertTrue(fields.none { it.type == String::class.java })
        assertEquals(
            setOf("messageKey", "actionLabelKey", "severity"),
            fields.map { it.name }.toSet(),
        )
    }

    private fun failure(
        code: ProductFailureCode,
        attempts: Int = 0,
    ) = ProductFailure(
        code = code,
        stage = ProductFailureStage.FinalVerification,
        automaticAttemptsUsed = attempts,
    )
}
