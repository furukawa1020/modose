package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFpsAnalyzerTest {
    @Test
    fun analyze_measuresThirtyFpsWithoutLowFpsViolation() {
        val result = CameraFpsAnalyzer.analyze(
            timestamps(List(60) { 33_333_333L }),
        )

        assertTrue(result is CameraFpsResult.Measured)
        val measurement = (result as CameraFpsResult.Measured).measurement
        assertTrue(measurement.averageFps >= 30.0)
        assertTrue(measurement.meetsThirtyFpsTarget)
        assertEquals(0L, measurement.maximumLowFpsStreakNanos)
        assertFalse(measurement.violatesLowFpsContinuityBudget)
        assertEquals(60, measurement.frameDurations.size)
    }

    @Test
    fun analyze_flagsTwentyFpsBelowForAtLeastOneSecond() {
        val interval = 52_631_579L
        val result = CameraFpsAnalyzer.analyze(
            timestamps(List(19) { interval }),
        ) as CameraFpsResult.Measured

        assertTrue(
            result.measurement.maximumLowFpsStreakNanos >=
                CameraFpsAnalyzer.MAX_LOW_FPS_STREAK_NANOS,
        )
        assertTrue(
            result.measurement.violatesLowFpsContinuityBudget,
        )
        assertFalse(result.measurement.meetsThirtyFpsTarget)
    }

    @Test
    fun analyze_allowsLowFpsStreakShorterThanOneSecond() {
        val result = CameraFpsAnalyzer.analyze(
            timestamps(List(18) { 52_631_579L }),
        ) as CameraFpsResult.Measured

        assertFalse(
            result.measurement.violatesLowFpsContinuityBudget,
        )
    }

    @Test
    fun analyze_resetsLowFpsStreakAfterRecoveryFrame() {
        val low = List(10) { 60_000_000L }
        val intervals = low + 40_000_000L + low

        val result = CameraFpsAnalyzer.analyze(
            timestamps(intervals),
        ) as CameraFpsResult.Measured

        assertEquals(
            600_000_000L,
            result.measurement.maximumLowFpsStreakNanos,
        )
        assertFalse(
            result.measurement.violatesLowFpsContinuityBudget,
        )
    }

    @Test
    fun analyze_doesNotTreatExactlyTwentyFpsAsBelowTarget() {
        val result = CameraFpsAnalyzer.analyze(
            timestamps(List(20) { 50_000_000L }),
        ) as CameraFpsResult.Measured

        assertEquals(0L, result.measurement.maximumLowFpsStreakNanos)
        assertFalse(
            result.measurement.violatesLowFpsContinuityBudget,
        )
    }

    @Test
    fun analyze_rejectsInvalidTimestampSequences() {
        assertEquals(
            CameraFpsResult.Rejected(
                CameraFpsFailure.InsufficientFrames,
            ),
            CameraFpsAnalyzer.analyze(listOf(0L)),
        )
        assertEquals(
            CameraFpsResult.Rejected(
                CameraFpsFailure.NegativeTimestamp,
            ),
            CameraFpsAnalyzer.analyze(listOf(-1L, 1L)),
        )
        assertEquals(
            CameraFpsResult.Rejected(
                CameraFpsFailure.TimestampNotIncreasing,
            ),
            CameraFpsAnalyzer.analyze(listOf(0L, 10L, 10L)),
        )
    }

    @Test
    fun analyze_rejectsFrameIntervalOverMeasurementContract() {
        assertEquals(
            CameraFpsResult.Rejected(
                CameraFpsFailure.FrameIntervalTooLong,
            ),
            CameraFpsAnalyzer.analyze(
                listOf(
                    0L,
                    AndroidPerformanceContract
                        .MAX_SAMPLE_DURATION_NANOS + 1,
                ),
            ),
        )
    }

    private fun timestamps(intervals: List<Long>): List<Long> {
        var timestamp = 0L
        return buildList {
            add(timestamp)
            intervals.forEach { interval ->
                timestamp += interval
                add(timestamp)
            }
        }
    }
}
