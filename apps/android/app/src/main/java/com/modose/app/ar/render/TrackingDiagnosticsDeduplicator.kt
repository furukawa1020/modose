package com.modose.app.ar.render

import com.modose.app.ar.session.ArTrackingDiagnostics

class TrackingDiagnosticsDeduplicator {
    private var previous: ArTrackingDiagnostics? = null

    fun shouldEmit(current: ArTrackingDiagnostics): Boolean {
        if (current == previous) return false
        previous = current
        return true
    }

    fun reset() {
        previous = null
    }
}
