package com.modose.app.permission

enum class CameraPermissionState {
    InitialRequest,
    Denied,
    PermanentlyDenied,
    Granted,
}

object CameraPermissionPolicy {
    fun resolve(
        isGranted: Boolean,
        hasRequested: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionState = when {
        isGranted -> CameraPermissionState.Granted
        !hasRequested -> CameraPermissionState.InitialRequest
        shouldShowRationale -> CameraPermissionState.Denied
        else -> CameraPermissionState.PermanentlyDenied
    }
}
