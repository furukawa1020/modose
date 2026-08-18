package com.modose.app.ar.render

import com.modose.app.ar.anchor.SceneAnchorState

class SceneAnchorStateDeduplicator {
    private var previous: SceneAnchorState? = null

    fun shouldEmit(state: SceneAnchorState): Boolean {
        if (state == previous) return false
        previous = state
        return true
    }

    fun reset() {
        previous = null
    }
}
