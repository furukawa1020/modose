package com.modose.app.ar

import com.google.ar.core.ArCoreApk

enum class ArCoreFailureReason {
    AvailabilityTimedOut,
    AvailabilityFailed,
    InstallationDeclined,
    SdkTooOld,
    InstallationFailed,
}

sealed interface ArCoreGateState {
    data object Checking : ArCoreGateState
    data object Unsupported : ArCoreGateState
    data object NotInstalled : ArCoreGateState
    data object UpdateRequired : ArCoreGateState
    data object Installing : ArCoreGateState
    data object Ready : ArCoreGateState
    data class Unavailable(val reason: ArCoreFailureReason) : ArCoreGateState
}

object ArCoreAvailabilityPolicy {
    fun resolve(availability: ArCoreApk.Availability): ArCoreGateState = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreGateState.Ready
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArCoreGateState.NotInstalled
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCoreGateState.UpdateRequired
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArCoreGateState.Unsupported
        ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreGateState.Checking
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> ArCoreGateState.Unavailable(
            ArCoreFailureReason.AvailabilityTimedOut,
        )
        ArCoreApk.Availability.UNKNOWN_ERROR -> ArCoreGateState.Unavailable(
            ArCoreFailureReason.AvailabilityFailed,
        )
    }
}
