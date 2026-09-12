package com.modose.app.flow.recovery

enum class RecoveryMessageKey {
    CameraPermissionRequired,
    ArCoreSetupRequired,
    TrackingUnavailable,
    RetakeImage,
    ReviewDetectedObjects,
    AuthenticationUnavailable,
    NetworkUnavailable,
    ServiceTemporarilyUnavailable,
    LocalDataUnavailable,
    DeviceUnsupported,
    UnableToContinueSafely,
}

enum class RecoveryActionLabelKey {
    OpenPermissionSettings,
    SetUpArCore,
    Retake,
    ReviewObjects,
    Retry,
    CheckConnection,
    CloseScene,
}

enum class RecoveryUiSeverity {
    Notice,
    Attention,
    Blocking,
}

data class RecoveryUiModel(
    val messageKey: RecoveryMessageKey,
    val actionLabelKey: RecoveryActionLabelKey?,
    val severity: RecoveryUiSeverity,
)

object ProductRecoveryPresenter {
    fun present(failure: ProductFailure): RecoveryUiModel {
        val budget = RecoveryRetryBudget.evaluate(failure)
        val route = when (budget) {
            is RecoveryBudgetDecision.Allowed -> budget.permit.route
            is RecoveryBudgetDecision.Exhausted -> budget.fallback
            is RecoveryBudgetDecision.NoAutomaticRetry -> budget.route
        }
        return RecoveryUiModel(
            messageKey = message(failure, route),
            actionLabelKey = actionLabel(route.action),
            severity = severity(route.mode),
        )
    }

    private fun message(
        failure: ProductFailure,
        route: RecoveryRoute,
    ): RecoveryMessageKey = when (route.action) {
        RecoveryAction.RequestCameraPermission ->
            RecoveryMessageKey.CameraPermissionRequired
        RecoveryAction.InstallOrUpdateArCore ->
            RecoveryMessageKey.ArCoreSetupRequired
        RecoveryAction.ReacquireTracking ->
            RecoveryMessageKey.TrackingUnavailable
        RecoveryAction.RetryCapture ->
            RecoveryMessageKey.RetakeImage
        RecoveryAction.ReviewObjectsManually ->
            RecoveryMessageKey.ReviewDetectedObjects
        RecoveryAction.WaitForNetwork ->
            RecoveryMessageKey.NetworkUnavailable
        RecoveryAction.StopUnsupported ->
            RecoveryMessageKey.DeviceUnsupported
        RecoveryAction.RetryLocalOperation ->
            RecoveryMessageKey.LocalDataUnavailable
        RecoveryAction.RetryRequest -> when (failure.code.domain) {
            ProductFailureDomain.Authentication ->
                RecoveryMessageKey.AuthenticationUnavailable
            else -> RecoveryMessageKey.ServiceTemporarilyUnavailable
        }
        RecoveryAction.FailClosed ->
            RecoveryMessageKey.UnableToContinueSafely
    }

    private fun actionLabel(
        action: RecoveryAction,
    ): RecoveryActionLabelKey? = when (action) {
        RecoveryAction.RequestCameraPermission ->
            RecoveryActionLabelKey.OpenPermissionSettings
        RecoveryAction.InstallOrUpdateArCore ->
            RecoveryActionLabelKey.SetUpArCore
        RecoveryAction.RetryCapture -> RecoveryActionLabelKey.Retake
        RecoveryAction.ReviewObjectsManually ->
            RecoveryActionLabelKey.ReviewObjects
        RecoveryAction.WaitForNetwork ->
            RecoveryActionLabelKey.CheckConnection
        RecoveryAction.StopUnsupported,
        RecoveryAction.FailClosed,
        -> RecoveryActionLabelKey.CloseScene
        RecoveryAction.ReacquireTracking,
        RecoveryAction.RetryRequest,
        RecoveryAction.RetryLocalOperation,
        -> null
    }

    private fun severity(mode: RecoveryMode): RecoveryUiSeverity = when (mode) {
        RecoveryMode.Automatic -> RecoveryUiSeverity.Notice
        RecoveryMode.UserAction -> RecoveryUiSeverity.Attention
        RecoveryMode.Terminal -> RecoveryUiSeverity.Blocking
    }
}
