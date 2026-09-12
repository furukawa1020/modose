package com.modose.app.flow.recovery

data class AutomaticRetryPermit(
    val failure: ProductFailure,
    val route: RecoveryRoute,
    val maximumAttempts: Int,
)

sealed interface RecoveryBudgetDecision {
    data class Allowed(val permit: AutomaticRetryPermit) :
        RecoveryBudgetDecision

    data class Exhausted(
        val fallback: RecoveryRoute,
        val attemptsUsed: Int,
    ) : RecoveryBudgetDecision

    data class NoAutomaticRetry(val route: RecoveryRoute) :
        RecoveryBudgetDecision
}

object RecoveryRetryBudget {
    fun evaluate(failure: ProductFailure): RecoveryBudgetDecision {
        val route = ProductRecoveryMapper.map(failure)
        if (route.mode != RecoveryMode.Automatic) {
            return RecoveryBudgetDecision.NoAutomaticRetry(route)
        }

        val maximum = maximumAttempts(failure.code)
        if (failure.automaticAttemptsUsed >= maximum) {
            return RecoveryBudgetDecision.Exhausted(
                fallback = exhaustedFallback(failure.code),
                attemptsUsed = failure.automaticAttemptsUsed,
            )
        }

        return RecoveryBudgetDecision.Allowed(
            AutomaticRetryPermit(
                failure = failure.copy(
                    automaticAttemptsUsed =
                        failure.automaticAttemptsUsed + 1,
                ),
                route = route,
                maximumAttempts = maximum,
            ),
        )
    }

    private fun maximumAttempts(code: ProductFailureCode): Int = when (code) {
        ProductFailureCode.MalformedVlmJson -> 1

        ProductFailureCode.IdTokenUnavailable,
        ProductFailureCode.AppCheckTokenUnavailable,
        ProductFailureCode.RequestTimedOut,
        ProductFailureCode.RateLimited,
        ProductFailureCode.ServerUnavailable,
        -> 2

        ProductFailureCode.ArTrackingLost,
        ProductFailureCode.HorizontalPlaneUnavailable,
        ProductFailureCode.AnchorUnavailable,
        ProductFailureCode.TrackingIdentityLost,
        ProductFailureCode.NetworkUnavailable,
        ProductFailureCode.LocalReadFailed,
        ProductFailureCode.LocalWriteFailed,
        ProductFailureCode.LocalDeleteFailed,
        ProductFailureCode.MetadataDeleteFailed,
        -> 3

        else -> 0
    }

    private fun exhaustedFallback(
        code: ProductFailureCode,
    ): RecoveryRoute = when (code) {
        ProductFailureCode.LocalDeleteFailed,
        ProductFailureCode.MetadataDeleteFailed,
        -> RecoveryRoute(
            action = RecoveryAction.FailClosed,
            mode = RecoveryMode.Terminal,
        )
        else -> RecoveryRoute(
            action = RecoveryAction.ReviewObjectsManually,
            mode = RecoveryMode.UserAction,
        )
    }
}
