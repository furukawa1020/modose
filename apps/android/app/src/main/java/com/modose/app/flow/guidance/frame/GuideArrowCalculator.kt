package com.modose.app.flow.guidance.frame

import kotlin.math.hypot

object GuideArrowCalculator {
    fun calculate(
        input: GuideFrameInput,
        previousVisual: GuideFrameVisual? = null,
    ): GuideFrameUpdateResult {
        validate(input)?.let {
            return GuideFrameUpdateResult.Rejected(it)
        }

        when (input.trackingState) {
            GuideTrackingState.Lost ->
                return GuideFrameUpdateResult.Frozen(
                    previousVisual = previousVisual,
                    reason = GuideFrameFreezeReason.TrackingLost,
                )
            GuideTrackingState.Ambiguous ->
                return GuideFrameUpdateResult.Frozen(
                    previousVisual = previousVisual,
                    reason = GuideFrameFreezeReason.AmbiguousTracking,
                )
            GuideTrackingState.Valid -> Unit
        }

        val deltaX = input.targetPosition.xMeters - input.currentPosition.xMeters
        val deltaZ = input.targetPosition.zMeters - input.currentPosition.zMeters
        val distance = hypot(deltaX, deltaZ)
        if (!distance.isFinite()) {
            return GuideFrameUpdateResult.Rejected(
                GuideFrameRejection.NonFiniteDistance,
            )
        }

        return GuideFrameUpdateResult.Updated(
            GuideFrameVisual.Arrow(
                sceneObjectId = input.sceneObjectId,
                distanceMeters = distance,
                vector = GuideArrowVector(
                    start = input.currentPosition,
                    end = input.targetPosition,
                    deltaXMeters = deltaX,
                    deltaZMeters = deltaZ,
                    distanceMeters = distance,
                ),
            ),
        )
    }

    private fun validate(input: GuideFrameInput): GuideFrameRejection? {
        if (input.sceneObjectId.isBlank()) {
            return GuideFrameRejection.BlankSceneObjectId
        }
        if (input.frameTimestampNanos < 0L) {
            return GuideFrameRejection.InvalidTimestamp
        }
        if (
            !input.currentPosition.xMeters.isFinite() ||
            !input.currentPosition.zMeters.isFinite() ||
            !input.targetPosition.xMeters.isFinite() ||
            !input.targetPosition.zMeters.isFinite()
        ) {
            return GuideFrameRejection.NonFiniteCoordinate
        }
        return null
    }
}
