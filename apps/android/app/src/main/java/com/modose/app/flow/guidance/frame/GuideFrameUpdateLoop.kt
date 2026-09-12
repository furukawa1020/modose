package com.modose.app.flow.guidance.frame

class GuideFrameUpdateLoop {
    private val controller = GuideFrameHysteresisController()
    private var lastSeenTimestampNanos: Long? = null
    private var lastAcceptedTimestampNanos: Long? = null
    private var lastVisual: GuideFrameVisual? = null

    fun update(input: GuideFrameInput): GuideFrameUpdateResult {
        val lastSeen = lastSeenTimestampNanos
        if (lastSeen != null && input.frameTimestampNanos < lastSeen) {
            return GuideFrameUpdateResult.Rejected(
                GuideFrameRejection.TimestampMovedBackwards,
            )
        }
        lastSeenTimestampNanos = input.frameTimestampNanos

        if (input.trackingState != GuideTrackingState.Valid) {
            lastAcceptedTimestampNanos = null
            return controller.update(input)
        }

        val lastAccepted = lastAcceptedTimestampNanos
        if (
            lastAccepted != null &&
            input.frameTimestampNanos - lastAccepted <
            GuideFrameContract.MIN_UPDATE_INTERVAL_NANOS
        ) {
            return GuideFrameUpdateResult.Frozen(
                previousVisual = lastVisual,
                reason = GuideFrameFreezeReason.UpdateRateLimited,
            )
        }

        lastAcceptedTimestampNanos = input.frameTimestampNanos
        val result = controller.update(input)
        if (result is GuideFrameUpdateResult.Updated) {
            lastVisual = result.visual
        }
        return result
    }

    fun reset() {
        controller.reset()
        lastSeenTimestampNanos = null
        lastAcceptedTimestampNanos = null
        lastVisual = null
    }
}
