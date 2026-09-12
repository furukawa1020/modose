package com.modose.app.flow.guidance.rotation

import com.modose.app.flow.compare.CurrentObjectState
import kotlin.math.abs

object ShortestRotationGuideCalculator {
    private const val EQUIVALENT_EPSILON_DEGREES = 0.000001

    fun calculate(input: RotationGuideInput): RotationGuideResult {
        if (input.sceneObjectId.isBlank()) {
            return RotationGuideResult.Rejected(
                RotationGuideFailure.BlankSceneObjectId,
            )
        }
        when (input.state) {
            CurrentObjectState.Missing -> return hidden(
                input,
                RotationGuideHiddenReason.ObjectMissing,
            )
            CurrentObjectState.Ambiguous -> return hidden(
                input,
                RotationGuideHiddenReason.ObjectAmbiguous,
            )
            CurrentObjectState.Rotated,
            CurrentObjectState.MovedRotated,
            -> Unit
            else -> return hidden(
                input,
                RotationGuideHiddenReason.StateDoesNotRequireRotation,
            )
        }
        if (!input.orientationMeaningful) {
            return hidden(input, RotationGuideHiddenReason.OrientationNotMeaningful)
        }
        val period = input.symmetry.equivalentPeriodDegrees
            ?: return hidden(input, RotationGuideHiddenReason.ContinuousSymmetry)
        val current = input.currentYawDegrees
            ?: return RotationGuideResult.Rejected(RotationGuideFailure.MissingYaw)
        val target = input.targetYawDegrees
            ?: return RotationGuideResult.Rejected(RotationGuideFailure.MissingYaw)
        if (!current.isFinite() || !target.isFinite()) {
            return RotationGuideResult.Rejected(RotationGuideFailure.NonFiniteYaw)
        }

        val signedDelta = normalizeSigned(target - current, period)
        if (abs(signedDelta) <= EQUIVALENT_EPSILON_DEGREES) {
            return hidden(input, RotationGuideHiddenReason.EquivalentBySymmetry)
        }
        return RotationGuideResult.Decided(
            RotationGuideDecision.Show(
                sceneObjectId = input.sceneObjectId,
                direction = if (signedDelta > 0.0) {
                    RotationDirection.CounterClockwise
                } else {
                    RotationDirection.Clockwise
                },
                degrees = abs(signedDelta),
            ),
        )
    }

    private fun normalizeSigned(value: Double, period: Double): Double {
        val positive = ((value % period) + period) % period
        return if (positive >= period / 2.0) positive - period else positive
    }

    private fun hidden(
        input: RotationGuideInput,
        reason: RotationGuideHiddenReason,
    ) = RotationGuideResult.Decided(
        RotationGuideDecision.Hidden(
            sceneObjectId = input.sceneObjectId,
            reason = reason,
        ),
    )
}
