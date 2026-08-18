package com.modose.app.ar.session

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ArTrackingDiagnosticsPolicyTest {
    @Test
    fun trackingClearsPreviousFailureReason() {
        assertEquals(
            ArTrackingDiagnostics(ArTrackingPhase.Tracking, ArTrackingIssue.None),
            ArTrackingDiagnosticsPolicy.resolve(
                TrackingState.TRACKING,
                TrackingFailureReason.INSUFFICIENT_LIGHT,
            ),
        )
    }

    @Test
    fun mapsEveryPausedRecoveryReason() {
        val cases = mapOf(
            TrackingFailureReason.NONE to ArTrackingIssue.None,
            TrackingFailureReason.BAD_STATE to ArTrackingIssue.BadState,
            TrackingFailureReason.INSUFFICIENT_LIGHT to ArTrackingIssue.InsufficientLight,
            TrackingFailureReason.EXCESSIVE_MOTION to ArTrackingIssue.ExcessiveMotion,
            TrackingFailureReason.INSUFFICIENT_FEATURES to ArTrackingIssue.InsufficientFeatures,
            TrackingFailureReason.CAMERA_UNAVAILABLE to ArTrackingIssue.CameraUnavailable,
        )

        cases.forEach { (reason, expected) ->
            assertEquals(
                ArTrackingDiagnostics(ArTrackingPhase.Paused, expected),
                ArTrackingDiagnosticsPolicy.resolve(TrackingState.PAUSED, reason),
            )
        }
    }

    @Test
    fun stoppedRemainsTerminal() {
        assertEquals(
            ArTrackingDiagnostics(ArTrackingPhase.Stopped, ArTrackingIssue.BadState),
            ArTrackingDiagnosticsPolicy.resolve(
                TrackingState.STOPPED,
                TrackingFailureReason.BAD_STATE,
            ),
        )
    }
}
