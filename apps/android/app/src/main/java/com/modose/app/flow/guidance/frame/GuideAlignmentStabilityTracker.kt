package com.modose.app.flow.guidance.frame

class GuideAlignmentStabilityTracker {
    private var activeObjectId: String? = null
    private var alignmentStartedNanos: Long? = null

    fun update(
        input: GuideFrameInput,
        previousVisual: GuideFrameVisual? = null,
    ): GuideFrameUpdateResult {
        if (activeObjectId != input.sceneObjectId) {
            activeObjectId = input.sceneObjectId
            alignmentStartedNanos = null
        }

        val calculated = GuideArrowCalculator.calculate(input, previousVisual)
        if (calculated !is GuideFrameUpdateResult.Updated) {
            return calculated
        }
        val arrow = calculated.visual as GuideFrameVisual.Arrow
        if (arrow.distanceMeters > GuideFrameContract.ALIGNMENT_ENTER_METERS) {
            alignmentStartedNanos = null
            return calculated
        }

        val started = alignmentStartedNanos ?: input.frameTimestampNanos.also {
            alignmentStartedNanos = it
        }
        val elapsedNanos = input.frameTimestampNanos - started
        if (elapsedNanos < 0L) {
            alignmentStartedNanos = null
            return GuideFrameUpdateResult.Rejected(
                GuideFrameRejection.TimestampMovedBackwards,
            )
        }

        val stableForMillis = elapsedNanos / 1_000_000L
        return if (stableForMillis >= GuideFrameContract.REQUIRED_STABLE_MILLIS) {
            GuideFrameUpdateResult.Updated(
                GuideFrameVisual.LocallyCompleted(
                    sceneObjectId = input.sceneObjectId,
                    distanceMeters = arrow.distanceMeters,
                ),
            )
        } else {
            GuideFrameUpdateResult.Updated(
                GuideFrameVisual.AlignmentRing(
                    sceneObjectId = input.sceneObjectId,
                    distanceMeters = arrow.distanceMeters,
                    stableForMillis = stableForMillis,
                ),
            )
        }
    }

    fun reset() {
        activeObjectId = null
        alignmentStartedNanos = null
    }
}
