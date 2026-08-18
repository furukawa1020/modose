package com.modose.app.ar.session

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

object ArTrackingDiagnosticsPolicy {
    fun resolve(
        trackingState: TrackingState,
        failureReason: TrackingFailureReason,
    ): ArTrackingDiagnostics = ArTrackingDiagnostics(
        phase = when (trackingState) {
            TrackingState.TRACKING -> ArTrackingPhase.Tracking
            TrackingState.PAUSED -> ArTrackingPhase.Paused
            TrackingState.STOPPED -> ArTrackingPhase.Stopped
            else -> ArTrackingPhase.Stopped
        },
        issue = if (trackingState == TrackingState.TRACKING) {
            ArTrackingIssue.None
        } else {
            when (failureReason) {
                TrackingFailureReason.NONE -> ArTrackingIssue.None
                TrackingFailureReason.BAD_STATE -> ArTrackingIssue.BadState
                TrackingFailureReason.INSUFFICIENT_LIGHT -> ArTrackingIssue.InsufficientLight
                TrackingFailureReason.EXCESSIVE_MOTION -> ArTrackingIssue.ExcessiveMotion
                TrackingFailureReason.INSUFFICIENT_FEATURES -> ArTrackingIssue.InsufficientFeatures
                TrackingFailureReason.CAMERA_UNAVAILABLE -> ArTrackingIssue.CameraUnavailable
                else -> ArTrackingIssue.Unknown
            }
        },
    )
}
