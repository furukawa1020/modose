package com.modose.app.ar.session

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ArTrackingDiagnosticsMappingTableTest {
    @Test
    fun everyTrackingStateAndReasonMapsToSafeDomainDiagnostics() {
        val reasons = listOf(
            TrackingFailureReason.NONE to ArTrackingIssue.None,
            TrackingFailureReason.BAD_STATE to ArTrackingIssue.BadState,
            TrackingFailureReason.INSUFFICIENT_LIGHT to
                ArTrackingIssue.InsufficientLight,
            TrackingFailureReason.EXCESSIVE_MOTION to
                ArTrackingIssue.ExcessiveMotion,
            TrackingFailureReason.INSUFFICIENT_FEATURES to
                ArTrackingIssue.InsufficientFeatures,
            TrackingFailureReason.CAMERA_UNAVAILABLE to
                ArTrackingIssue.CameraUnavailable,
        )
        val phases = listOf(
            TrackingState.TRACKING to ArTrackingPhase.Tracking,
            TrackingState.PAUSED to ArTrackingPhase.Paused,
            TrackingState.STOPPED to ArTrackingPhase.Stopped,
        )

        phases.forEach { (trackingState, expectedPhase) ->
            reasons.forEach { (reason, mappedIssue) ->
                val expectedIssue =
                    if (trackingState == TrackingState.TRACKING) {
                        ArTrackingIssue.None
                    } else {
                        mappedIssue
                    }

                assertEquals(
                    "$trackingState / $reason",
                    ArTrackingDiagnostics(expectedPhase, expectedIssue),
                    ArTrackingDiagnosticsPolicy.resolve(
                        trackingState,
                        reason,
                    ),
                )
            }
        }
    }

    @Test
    fun trackingNeverCarriesAStaleFailureReason() {
        TrackingFailureReason.values().forEach { reason ->
            val diagnostics = ArTrackingDiagnosticsPolicy.resolve(
                TrackingState.TRACKING,
                reason,
            )

            assertEquals(ArTrackingPhase.Tracking, diagnostics.phase)
            assertEquals(ArTrackingIssue.None, diagnostics.issue)
        }
    }
}
