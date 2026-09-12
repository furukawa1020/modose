package com.modose.app.flow.guidance.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideFrameUpdateLoopTest {
    @Test
    fun calculatesArrowVectorAndDistance() {
        val result = GuideArrowCalculator.calculate(frame(0L, 0.05))
            as GuideFrameUpdateResult.Updated
        val arrow = result.visual as GuideFrameVisual.Arrow

        assertEquals(0.05, arrow.distanceMeters, 0.000001)
        assertEquals(-0.05, arrow.vector.deltaXMeters, 0.000001)
        assertEquals(0.0, arrow.vector.deltaZMeters, 0.000001)
    }

    @Test
    fun requiresFullEightHundredMillisecondsInsideThreeCentimeters() {
        val controller = GuideFrameHysteresisController()

        assertRing(controller.update(frame(0L, 0.03)), 0L)
        assertRing(controller.update(frame(799_000_000L, 0.03)), 799L)
        assertTrue(
            updated(controller.update(frame(800_000_000L, 0.03))) is
                GuideFrameVisual.LocallyCompleted,
        )
    }

    @Test
    fun completedStateExitsAtFiveCentimetersNotBefore() {
        val controller = GuideFrameHysteresisController()
        controller.update(frame(0L, 0.02))
        controller.update(frame(800_000_000L, 0.02))

        assertTrue(
            updated(controller.update(frame(900_000_000L, 0.049))) is
                GuideFrameVisual.LocallyCompleted,
        )
        assertTrue(
            updated(controller.update(frame(1_000_000_000L, 0.05))) is
                GuideFrameVisual.Arrow,
        )
    }

    @Test
    fun trackingLossFreezesVisualAndResetsStableTime() {
        val controller = GuideFrameHysteresisController()
        controller.update(frame(0L, 0.02))

        val lost = controller.update(
            frame(900_000_000L, 0.02, GuideTrackingState.Lost),
        ) as GuideFrameUpdateResult.Frozen
        assertEquals(GuideFrameFreezeReason.TrackingLost, lost.reason)

        assertRing(controller.update(frame(1_000_000_000L, 0.02)), 0L)
    }

    @Test
    fun processesEveryFrameAndReportsBelowFifteenHertz() {
        val loop = GuideFrameUpdateLoop()

        assertEquals(
            GuideFrameCadence.FirstValidFrame,
            loop.update(frame(0L, 0.10)).cadence,
        )
        val fast = loop.update(frame(60_000_000L, 0.09))
        assertEquals(GuideFrameCadence.MeetsMinimumRate, fast.cadence)
        assertTrue(fast.update is GuideFrameUpdateResult.Updated)

        val slow = loop.update(frame(130_000_000L, 0.08))
        assertEquals(GuideFrameCadence.BelowMinimumRate, slow.cadence)
        assertTrue(slow.update is GuideFrameUpdateResult.Updated)
    }

    @Test
    fun rejectsNonFiniteCoordinatesAndBackwardsTime() {
        assertEquals(
            GuideFrameUpdateResult.Rejected(
                GuideFrameRejection.NonFiniteCoordinate,
            ),
            GuideArrowCalculator.calculate(frame(0L, Double.NaN)),
        )

        val loop = GuideFrameUpdateLoop()
        loop.update(frame(100L, 0.10))
        val backwards = loop.update(frame(99L, 0.10))
        assertEquals(GuideFrameCadence.TimestampInvalid, backwards.cadence)
        assertEquals(
            GuideFrameUpdateResult.Rejected(
                GuideFrameRejection.TimestampMovedBackwards,
            ),
            backwards.update,
        )
    }

    private fun assertRing(result: GuideFrameUpdateResult, stableMillis: Long) {
        val ring = updated(result) as GuideFrameVisual.AlignmentRing
        assertEquals(stableMillis, ring.stableForMillis)
    }

    private fun updated(result: GuideFrameUpdateResult): GuideFrameVisual =
        (result as GuideFrameUpdateResult.Updated).visual

    private fun frame(
        timestampNanos: Long,
        distanceMeters: Double,
        tracking: GuideTrackingState = GuideTrackingState.Valid,
    ) = GuideFrameInput(
        sceneObjectId = "object-1",
        frameTimestampNanos = timestampNanos,
        currentPosition = ScenePlanePoint(distanceMeters, 0.0),
        targetPosition = ScenePlanePoint(0.0, 0.0),
        trackingState = tracking,
    )
}
