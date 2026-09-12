package com.modose.app.permission

class CameraPermissionAdapter(
    private val history: CameraPermissionHistory,
) {
    fun resolve(
        isGranted: Boolean,
        shouldShowRationale: Boolean,
    ): CameraPermissionState = CameraPermissionPolicy.resolve(
        isGranted = isGranted,
        hasRequested = history.hasRequested,
        shouldShowRationale = shouldShowRationale,
    )

    fun request(
        currentState: CameraPermissionState,
        launchPlatformRequest: () -> Unit,
    ): Boolean {
        if (
            currentState == CameraPermissionState.Granted ||
            currentState == CameraPermissionState.PermanentlyDenied
        ) {
            return false
        }

        history.markRequested()
        launchPlatformRequest()
        return true
    }
}
