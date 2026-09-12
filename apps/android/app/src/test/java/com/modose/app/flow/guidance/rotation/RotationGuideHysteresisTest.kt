package com.modose.app.flow.guidance.rotation

import com.modose.app.flow.compare.CurrentObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationGuideHysteresisTest {
    @Test
    fun choosesShortestDirectionAcrossZeroDegrees() {
        val decision = shown(input(current = 350.0, target = 10.0))

        assertEquals(RotationDirection.CounterClockwise, decision.direction)
        assertEquals(20.0, decision.degrees, 0.000001)
    }

    @Test
    fun symmetryTreatsEquivalentAnglesAsAligned() {
        assertHidden(
            input(
                current = 10.0,
                target = 190.0,
                symmetry = RotationSymmetry.HalfTurn,
            ),
            RotationGuideHiddenReason.EquivalentBySymmetry,
        )
        val quarterTurn = shown(
            input(
                current = 0.0,
                target = 100.0,
                symmetry = RotationSymmetry.QuarterTurn,
            ),
        )
        assertEquals(10.0, quarterTurn.degrees, 0.000001)
    }

    @Test
    fun exactHalfPeriodUsesDeterministicClockwiseDirection() {
        val decision = shown(
            input(
                current = 0.0,
                target = 90.0,
                symmetry = RotationSymmetry.HalfTurn,
            ),
        )

        assertEquals(RotationDirection.Clockwise, decision.direction)
        assertEquals(90.0, decision.degrees, 0.000001)
    }

    @Test
    fun displayStartsAtTenDegreesAndHidesBelowFiveDegrees() {
        val hysteresis = RotationGuideHysteresis()

        assertThresholdHidden(hysteresis.update(input(0.0, 9.999)))
        assertTrue(decision(hysteresis.update(input(0.0, 10.0))) is RotationGuideDecision.Show)
        assertTrue(decision(hysteresis.update(input(0.0, 6.0))) is RotationGuideDecision.Show)
        assertTrue(decision(hysteresis.update(input(0.0, 5.0))) is RotationGuideDecision.Show)
        assertThresholdHidden(hysteresis.update(input(0.0, 4.999)))
    }

    @Test
    fun missingAmbiguousAndOrientationFreeObjectsNeverShowRotation() {
        assertHidden(
            input(null, null, state = CurrentObjectState.Missing),
            RotationGuideHiddenReason.ObjectMissing,
        )
        assertHidden(
            input(null, null, state = CurrentObjectState.Ambiguous),
            RotationGuideHiddenReason.ObjectAmbiguous,
        )
        assertHidden(
            input(0.0, 90.0, orientationMeaningful = false),
            RotationGuideHiddenReason.OrientationNotMeaningful,
        )
        assertHidden(
            input(0.0, 90.0, symmetry = RotationSymmetry.Continuous),
            RotationGuideHiddenReason.ContinuousSymmetry,
        )
    }

    @Test
    fun nonFiniteYawIsRejected() {
        assertEquals(
            RotationGuideResult.Rejected(RotationGuideFailure.NonFiniteYaw),
            ShortestRotationGuideCalculator.calculate(
                input(Double.NaN, 20.0),
            ),
        )
    }

    private fun shown(input: RotationGuideInput): RotationGuideDecision.Show =
        decision(ShortestRotationGuideCalculator.calculate(input))
            as RotationGuideDecision.Show

    private fun assertHidden(
        input: RotationGuideInput,
        reason: RotationGuideHiddenReason,
    ) {
        assertEquals(
            RotationGuideDecision.Hidden("object-1", reason),
            decision(ShortestRotationGuideCalculator.calculate(input)),
        )
    }

    private fun assertThresholdHidden(result: RotationGuideResult) {
        assertEquals(
            RotationGuideDecision.Hidden(
                "object-1",
                RotationGuideHiddenReason.BelowDisplayThreshold,
            ),
            decision(result),
        )
    }

    private fun decision(result: RotationGuideResult): RotationGuideDecision =
        (result as RotationGuideResult.Decided).decision

    private fun input(
        current: Double?,
        target: Double?,
        state: CurrentObjectState = CurrentObjectState.Rotated,
        symmetry: RotationSymmetry = RotationSymmetry.None,
        orientationMeaningful: Boolean = true,
    ) = RotationGuideInput(
        sceneObjectId = "object-1",
        state = state,
        orientationMeaningful = orientationMeaningful,
        symmetry = symmetry,
        currentYawDegrees = current,
        targetYawDegrees = target,
    )
}
