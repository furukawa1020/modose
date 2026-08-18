package com.modose.app.ar

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreAvailabilityPolicyTest {
    @Test
    fun mapsSupportedStates() {
        assertEquals(
            ArCoreGateState.Ready,
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.SUPPORTED_INSTALLED),
        )
        assertEquals(
            ArCoreGateState.NotInstalled,
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED),
        )
        assertEquals(
            ArCoreGateState.UpdateRequired,
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD),
        )
    }

    @Test
    fun mapsUnsupportedAndUnknownStates() {
        assertEquals(
            ArCoreGateState.Unsupported,
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE),
        )
        assertEquals(
            ArCoreGateState.Checking,
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.UNKNOWN_CHECKING),
        )
        assertEquals(
            ArCoreGateState.Unavailable(ArCoreFailureReason.AvailabilityTimedOut),
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.UNKNOWN_TIMED_OUT),
        )
        assertEquals(
            ArCoreGateState.Unavailable(ArCoreFailureReason.AvailabilityFailed),
            ArCoreAvailabilityPolicy.resolve(ArCoreApk.Availability.UNKNOWN_ERROR),
        )
    }
}
