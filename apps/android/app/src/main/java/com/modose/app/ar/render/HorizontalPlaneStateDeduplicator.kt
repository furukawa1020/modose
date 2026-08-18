package com.modose.app.ar.render

import com.modose.app.ar.plane.HorizontalPlaneState

class HorizontalPlaneStateDeduplicator {
    private var previous: HorizontalPlaneState? = null

    fun shouldEmit(state: HorizontalPlaneState): Boolean {
        if (state == previous) return false
        previous = state
        return true
    }

    fun reset() {
        previous = null
    }
}
