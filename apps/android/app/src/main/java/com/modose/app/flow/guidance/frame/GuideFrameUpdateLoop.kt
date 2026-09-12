package com.modose.app.flow.guidance.frame

enum class GuideFrameCadence {
    FirstValidFrame,
    MeetsMinimumRate,
    BelowMinimumRate,
    TrackingUnavailable,
    TimestampInvalid,
}

data class GuideFrameLoopResult(
    val update: GuideFrameUpdateResult,
    val cadence: GuideFrameCadence,
)

class GuideFrameUpdateLoop {
    private val controller = GuideFrameHysteresisController()
    private var lastSeenTimestampNanos: Long? = null
    private var lastValidTimestampNanos: Long? = null

    fun update(input: GuideFrameInput): GuideFrameLoopResult {
        val lastSeen = lastSeenTimestampNanos
        if (lastSeen != null && input.frameTimestampNanos < lastSeen) {
            return GuideFrameLoopResult(
                update = GuideFrameUpdateResult.Rejected(
                    GuideFrameRejection.TimestampMovedBackwards,
                ),
                cadence = GuideFrameCadence.TimestampInvalid,
            )
        }
        lastSeenTimestampNanos = input.frameTimestampNanos

        if (input.trackingState != GuideTrackingState.Valid) {
            lastValidTimestampNanos = null
            return GuideFrameLoopResult(
                update = controller.update(input),
                cadence = GuideFrameCadence.TrackingUnavailable,
            )
        }

        val previousValid = lastValidTimestampNanos
        val cadence = when {
            previousValid == null -> GuideFrameCadence.FirstValidFrame
            input.frameTimestampNanos - previousValid <=
                GuideFrameContract.MAX_UPDATE_INTERVAL_NANOS ->
                GuideFrameCadence.MeetsMinimumRate
            else -> GuideFrameCadence.BelowMinimumRate
        }
        lastValidTimestampNanos = input.frameTimestampNanos

        return GuideFrameLoopResult(
            update = controller.update(input),
            cadence = cadence,
        )
    }

    fun reset() {
        controller.reset()
        lastSeenTimestampNanos = null
        lastValidTimestampNanos = null
    }
}
