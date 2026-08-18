package com.modose.app.ar.render

import com.modose.app.ar.session.ArTrackingDiagnostics
import com.modose.app.ar.session.ArTrackingIssue
import com.modose.app.ar.session.ArTrackingPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingDiagnosticsDeduplicatorTest {
    @Test
    fun emitsOnlyWhenDiagnosticsChange() {
        val deduplicator = TrackingDiagnosticsDeduplicator()
        val paused = ArTrackingDiagnostics(
            ArTrackingPhase.Paused,
            ArTrackingIssue.InsufficientLight,
        )
        val tracking = ArTrackingDiagnostics(ArTrackingPhase.Tracking, ArTrackingIssue.None)

        assertTrue(deduplicator.shouldEmit(paused))
        assertFalse(deduplicator.shouldEmit(paused))
        assertTrue(deduplicator.shouldEmit(tracking))
    }

    @Test
    fun resetAllowsCurrentDiagnosticsToBeEmittedAgain() {
        val deduplicator = TrackingDiagnosticsDeduplicator()
        val tracking = ArTrackingDiagnostics(ArTrackingPhase.Tracking, ArTrackingIssue.None)
        deduplicator.shouldEmit(tracking)

        deduplicator.reset()

        assertTrue(deduplicator.shouldEmit(tracking))
    }
}
