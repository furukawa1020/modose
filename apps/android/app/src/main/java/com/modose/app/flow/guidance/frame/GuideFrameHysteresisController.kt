package com.modose.app.flow.guidance.frame

class GuideFrameHysteresisController {
    private val stability = GuideAlignmentStabilityTracker()
    private var completedObjectId: String? = null
    private var previousVisual: GuideFrameVisual? = null

    fun update(input: GuideFrameInput): GuideFrameUpdateResult {
        if (input.trackingState != GuideTrackingState.Valid) {
            stability.reset()
            completedObjectId = null
            return GuideArrowCalculator.calculate(input, previousVisual)
        }

        val calculated = GuideArrowCalculator.calculate(input, previousVisual)
        if (calculated !is GuideFrameUpdateResult.Updated) {
            return calculated
        }
        val arrow = calculated.visual as GuideFrameVisual.Arrow

        if (completedObjectId == input.sceneObjectId) {
            val visual = if (
                arrow.distanceMeters >= GuideFrameContract.ALIGNMENT_EXIT_METERS
            ) {
                completedObjectId = null
                stability.reset()
                arrow
            } else {
                GuideFrameVisual.LocallyCompleted(
                    sceneObjectId = input.sceneObjectId,
                    distanceMeters = arrow.distanceMeters,
                )
            }
            previousVisual = visual
            return GuideFrameUpdateResult.Updated(visual)
        }

        val result = stability.update(input, previousVisual)
        if (result is GuideFrameUpdateResult.Updated) {
            previousVisual = result.visual
            if (result.visual is GuideFrameVisual.LocallyCompleted) {
                completedObjectId = input.sceneObjectId
            }
        }
        return result
    }

    fun reset() {
        stability.reset()
        completedObjectId = null
        previousVisual = null
    }
}
