package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameRecorderTest {
    @Test
    fun recordsDrawIntervalsInsteadOfCameraClockIntervals() {
        val recorder = CameraFrameRecorder()
        recorder.record(10L, 100L)
        recorder.record(20L, 33_333_433L)
        val measurement = measured(recorder)
        assertEquals(33_333_333L, measurement.frameDurations.single().durationNanos)
        assertTrue(measurement.meetsThirtyFpsTarget)
    }

    @Test
    fun duplicateCameraFrameDoesNotAddOrShiftDrawSample() {
        val recorder = CameraFrameRecorder()
        recorder.record(10L, 100L)
        recorder.record(10L, 200L)
        assertRejected(recorder, CameraFpsFailure.InsufficientFrames)
        recorder.record(11L, 300L)
        assertEquals(200L, measured(recorder).frameDurations.single().durationNanos)
    }

    @Test
    fun emptyAndSingleFrameCannotProduceMeasurement() {
        val recorder = CameraFrameRecorder()
        assertRejected(recorder, CameraFpsFailure.InsufficientFrames)
        recorder.record(1L, 1L)
        assertRejected(recorder, CameraFpsFailure.InsufficientFrames)
    }

    @Test
    fun rejectsNegativeCameraAndDrawTimestamps() {
        listOf(-1L to 0L, 0L to -1L).forEach { (camera, draw) ->
            val recorder = CameraFrameRecorder()
            recorder.record(camera, draw)
            assertRejected(recorder, CameraFpsFailure.NegativeTimestamp)
        }
    }

    @Test
    fun rejectsRegressingCameraTimestamp() {
        val recorder = CameraFrameRecorder()
        recorder.record(2L, 100L)
        recorder.record(1L, 200L)
        assertRejected(recorder, CameraFpsFailure.TimestampNotIncreasing)
    }

    @Test
    fun rejectsEqualOrRegressingDrawTimestamp() {
        listOf(99L, 100L).forEach { draw ->
            val recorder = CameraFrameRecorder()
            recorder.record(1L, 100L)
            recorder.record(2L, draw)
            assertRejected(recorder, CameraFpsFailure.TimestampNotIncreasing)
        }
    }

    @Test
    fun acceptsMaximumIntervalButRejectsOneNanosecondMore() {
        val maximum = AndroidPerformanceContract.MAX_SAMPLE_DURATION_NANOS
        val recorder = CameraFrameRecorder()
        recorder.record(1L, 0L)
        recorder.record(2L, maximum)
        assertEquals(maximum, measured(recorder).frameDurations.single().durationNanos)

        recorder.record(3L, maximum * 2L + 1L)
        assertRejected(recorder, CameraFpsFailure.FrameIntervalTooLong)
    }

    @Test
    fun retainsFirstFailureUntilExplicitReset() {
        val recorder = CameraFrameRecorder()
        recorder.record(-1L, 0L)
        recorder.record(1L, 100L)
        recorder.record(2L, 200L)
        assertRejected(recorder, CameraFpsFailure.NegativeTimestamp)
        recorder.reset()
        recorder.record(1L, 100L)
        recorder.record(2L, 200L)
        assertEquals(1, measured(recorder).frameDurations.size)
    }

    @Test
    fun acceptsCapacityThenRejectsOverflowWithoutDroppingOldSamples() {
        val recorder = CameraFrameRecorder()
        val capacity = AndroidPerformanceContract.MAX_SAMPLES_PER_SERIES + 1
        repeat(capacity) { recorder.record(it.toLong(), it.toLong()) }
        assertEquals(capacity - 1, measured(recorder).frameDurations.size)
        recorder.record((capacity - 1).toLong(), capacity.toLong())
        assertEquals(capacity - 1, measured(recorder).frameDurations.size)
        recorder.record(capacity.toLong(), capacity.toLong())
        assertRejected(recorder, CameraFpsFailure.TooManyFrames)
        recorder.record((capacity + 1).toLong(), (capacity + 1).toLong())
        assertRejected(recorder, CameraFpsFailure.TooManyFrames)
    }

    @Test
    fun snapshotDoesNotDrainOrExposeMutableRecorderStorage() {
        val recorder = CameraFrameRecorder()
        recorder.record(1L, 100L)
        recorder.record(2L, 200L)
        val first = recorder.snapshot()
        assertEquals(first, recorder.snapshot())
        recorder.record(3L, 300L)
        assertEquals(1, (first as CameraFpsResult.Measured).measurement.frameDurations.size)
        assertEquals(2, measured(recorder).frameDurations.size)
        recorder.reset()
        assertEquals(1, first.measurement.frameDurations.size)
    }

    private fun measured(recorder: CameraFrameRecorder): CameraFpsMeasurement =
        (recorder.snapshot() as CameraFpsResult.Measured).measurement

    private fun assertRejected(
        recorder: CameraFrameRecorder,
        reason: CameraFpsFailure,
    ) {
        assertEquals(CameraFpsResult.Rejected(reason), recorder.snapshot())
    }
}
