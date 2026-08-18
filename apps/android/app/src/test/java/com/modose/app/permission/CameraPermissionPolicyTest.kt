package com.modose.app.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionPolicyTest {
    @Test
    fun grantedAlwaysOpensGate() {
        assertEquals(
            CameraPermissionState.Granted,
            CameraPermissionPolicy.resolve(
                isGranted = true,
                hasRequested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun permissionNotYetRequestedShowsInitialAction() {
        assertEquals(
            CameraPermissionState.InitialRequest,
            CameraPermissionPolicy.resolve(false, false, false),
        )
    }

    @Test
    fun recoverableDenialShowsRationale() {
        assertEquals(
            CameraPermissionState.Denied,
            CameraPermissionPolicy.resolve(false, true, true),
        )
    }

    @Test
    fun denialWithoutRationaleRoutesToSettings() {
        assertEquals(
            CameraPermissionState.PermanentlyDenied,
            CameraPermissionPolicy.resolve(false, true, false),
        )
    }
}
