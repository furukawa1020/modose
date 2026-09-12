package com.modose.app.flow.guidance.rotation

class RotationGuideHysteresis {
    private var visibleObjectId: String? = null

    fun update(input: RotationGuideInput): RotationGuideResult {
        val calculated = ShortestRotationGuideCalculator.calculate(input)
        if (calculated !is RotationGuideResult.Decided) {
            visibleObjectId = null
            return calculated
        }

        val decision = calculated.decision
        if (decision !is RotationGuideDecision.Show) {
            visibleObjectId = null
            return calculated
        }

        val threshold = if (visibleObjectId == input.sceneObjectId) {
            RotationGuideContract.HIDE_THRESHOLD_DEGREES
        } else {
            RotationGuideContract.SHOW_THRESHOLD_DEGREES
        }
        return if (decision.degrees >= threshold) {
            visibleObjectId = input.sceneObjectId
            calculated
        } else {
            visibleObjectId = null
            RotationGuideResult.Decided(
                RotationGuideDecision.Hidden(
                    sceneObjectId = input.sceneObjectId,
                    reason = RotationGuideHiddenReason.BelowDisplayThreshold,
                ),
            )
        }
    }

    fun reset() {
        visibleObjectId = null
    }
}
