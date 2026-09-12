package com.modose.app.flow.recovery

enum class RecoveryAction {
    RetryCapture,
    RetryRequest,
    ReacquireTracking,
    RequestCameraPermission,
    InstallOrUpdateArCore,
    ReviewObjectsManually,
    RetryLocalOperation,
    WaitForNetwork,
    StopUnsupported,
    FailClosed,
}

enum class RecoveryMode {
    Automatic,
    UserAction,
    Terminal,
}

data class RecoveryRoute(
    val action: RecoveryAction,
    val mode: RecoveryMode,
)

object ProductRecoveryMapper {
    fun map(failure: ProductFailure): RecoveryRoute = when (failure.code) {
        ProductFailureCode.DeviceUnsupported,
        ProductFailureCode.ArCoreUnsupported,
        -> terminal(RecoveryAction.StopUnsupported)

        ProductFailureCode.CameraPermissionDenied ->
            user(RecoveryAction.RequestCameraPermission)
        ProductFailureCode.CameraPermissionPermanentlyDenied ->
            user(RecoveryAction.ReviewObjectsManually)

        ProductFailureCode.ArCoreInstallRequired,
        ProductFailureCode.ArCoreUpdateRequired,
        -> user(RecoveryAction.InstallOrUpdateArCore)

        ProductFailureCode.ArTrackingLost,
        ProductFailureCode.HorizontalPlaneUnavailable,
        ProductFailureCode.AnchorUnavailable,
        ProductFailureCode.TrackingIdentityLost,
        -> automatic(RecoveryAction.ReacquireTracking)

        ProductFailureCode.CameraUnavailable,
        ProductFailureCode.CpuImageUnavailable,
        ProductFailureCode.ImageTooDark,
        ProductFailureCode.ImageBlurred,
        ProductFailureCode.ImageTooLarge,
        ProductFailureCode.RequestTooLarge,
        ProductFailureCode.ResponseTooLarge,
        -> user(RecoveryAction.RetryCapture)

        ProductFailureCode.TooManyObjects,
        ProductFailureCode.AmbiguousCorrespondence,
        ProductFailureCode.MissingObject,
        ProductFailureCode.AssignmentConflict,
        -> user(RecoveryAction.ReviewObjectsManually)

        ProductFailureCode.IdTokenUnavailable,
        ProductFailureCode.AppCheckTokenUnavailable,
        ProductFailureCode.RequestTimedOut,
        ProductFailureCode.RateLimited,
        ProductFailureCode.ServerUnavailable,
        ProductFailureCode.MalformedVlmJson,
        -> automatic(RecoveryAction.RetryRequest)

        ProductFailureCode.NetworkUnavailable ->
            automatic(RecoveryAction.WaitForNetwork)

        ProductFailureCode.AuthenticationRejected,
        ProductFailureCode.AppCheckRejected,
        ProductFailureCode.NonRetryableHttpFailure,
        ProductFailureCode.UnsupportedSchema,
        ProductFailureCode.InvalidVlmStatus,
        ProductFailureCode.InvalidModel,
        ProductFailureCode.InvalidPromptVersion,
        ProductFailureCode.UnknownVlmEnum,
        ProductFailureCode.InvalidVlmPayload,
        -> user(RecoveryAction.ReviewObjectsManually)

        ProductFailureCode.LocalReadFailed,
        ProductFailureCode.LocalWriteFailed,
        ProductFailureCode.LocalDeleteFailed,
        ProductFailureCode.MetadataDeleteFailed,
        -> automatic(RecoveryAction.RetryLocalOperation)

        ProductFailureCode.ArSessionCreationFailed,
        ProductFailureCode.UnknownFailure,
        -> terminal(RecoveryAction.FailClosed)
    }

    private fun automatic(action: RecoveryAction) =
        RecoveryRoute(action, RecoveryMode.Automatic)

    private fun user(action: RecoveryAction) =
        RecoveryRoute(action, RecoveryMode.UserAction)

    private fun terminal(action: RecoveryAction) =
        RecoveryRoute(action, RecoveryMode.Terminal)
}
