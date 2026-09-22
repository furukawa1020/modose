package com.modose.app.ar.render

/** A capture remains valid only during one uninterrupted anchor-tracking interval. */
internal class BaselineCaptureValidity(val anchorId: Long) {
    @Volatile
    var isCurrent: Boolean = true
        private set

    fun invalidate() { isCurrent = false }

    fun requireCurrent() {
        check(isCurrent) { "The capture anchor is no longer current" }
    }
}
