package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies recorder segment semantics. Does not simulate GLSurfaceView or
 * establish that Android lifecycle callbacks execute in the expected order.
 */
class CameraFrameRecorderSegmentTest {
    @Test
    fun resumedSegmentExcludesBackgroundTime() {
        val recorder = CameraFrameRecorder()
        recorder.record(100L, 100L)
        recorder.record(101L, 33_333_433L)
        val beforePause = recorder.snapshot()
        recorder.reset()
        val resumedAt = 60_000_000_000L
        recorder.record(102L, resumedAt)
        assertEquals(
            CameraFpsResult.Rejected(CameraFpsFailure.InsufficientFrames),
            recorder.snapshot(),
        )
        recorder.record(103L, resumedAt + 33_333_333L)
        val afterResume = (recorder.snapshot() as CameraFpsResult.Measured).measurement
        assertEquals(1, afterResume.frameDurations.size)
        assertEquals(33_333_333L, afterResume.frameDurations.single().durationNanos)
        assertTrue(afterResume.meetsThirtyFpsTarget)
        assertFalse(afterResume.violatesLowFpsContinuityBudget)
        assertEquals(
            1,
            (beforePause as CameraFpsResult.Measured).measurement.frameDurations.size,
        )
    }

    @Test
    fun sourceReplacementCanStartWithAnEarlierCameraTimestamp() {
        val recorder = CameraFrameRecorder()
        recorder.record(100L, 100L)
        recorder.record(101L, 200L)
        recorder.reset()
        recorder.record(1L, 300L)
        recorder.record(2L, 400L)
        val measurement = (recorder.snapshot() as CameraFpsResult.Measured).measurement
        assertEquals(listOf(DurationSample(400L, 100L)), measurement.frameDurations)
    }

    @Test
    fun repeatedResetLeavesNoPreviousMeasurement() {
        val recorder = CameraFrameRecorder()
        recorder.record(1L, 100L)
        recorder.record(2L, 200L)
        repeat(3) { recorder.reset() }
        assertEquals(
            CameraFpsResult.Rejected(CameraFpsFailure.InsufficientFrames),
            recorder.snapshot(),
        )
    }

    @Test
    fun gapWithinActiveSegmentIsNotSilentlyReset() {
        val recorder = CameraFrameRecorder()
        recorder.record(1L, 0L)
        recorder.record(2L, 1_000_000_000L)
        val measurement = (recorder.snapshot() as CameraFpsResult.Measured).measurement
        assertTrue(measurement.violatesLowFpsContinuityBudget)
        assertEquals(1_000_000_000L, measurement.maximumLowFpsStreakNanos)
    }
}
