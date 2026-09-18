package com.modose.app.performance

/**
 * Records successful submissions of distinct camera frames, not GPU presentation.
 * A reset starts a new measurement segment; snapshots never drain the samples.
 */
class CameraFrameRecorder {
    private val timestamps = ArrayList<Long>()
    private var lastCameraTimestamp: Long? = null
    private var failure: CameraFpsFailure? = null

    @Synchronized
    fun record(cameraTimestampNanos: Long, drawnAtNanos: Long) {
        if (failure != null) return
        if (cameraTimestampNanos < 0L || drawnAtNanos < 0L) {
            failure = CameraFpsFailure.NegativeTimestamp
            return
        }
        val previousCameraTimestamp = lastCameraTimestamp
        if (previousCameraTimestamp != null) {
            if (cameraTimestampNanos == previousCameraTimestamp) return
            if (cameraTimestampNanos < previousCameraTimestamp) {
                failure = CameraFpsFailure.TimestampNotIncreasing
                return
            }
        }
        val previousDraw = timestamps.lastOrNull()
        if (previousDraw != null) {
            if (drawnAtNanos <= previousDraw) {
                failure = CameraFpsFailure.TimestampNotIncreasing
                return
            }
            if (
                drawnAtNanos - previousDraw >
                AndroidPerformanceContract.MAX_SAMPLE_DURATION_NANOS
            ) {
                failure = CameraFpsFailure.FrameIntervalTooLong
                return
            }
        }
        if (timestamps.size >= MAX_TIMESTAMPS) {
            failure = CameraFpsFailure.TooManyFrames
            return
        }
        timestamps += drawnAtNanos
        lastCameraTimestamp = cameraTimestampNanos
    }

    @Synchronized
    fun snapshot(): CameraFpsResult {
        failure?.let { return CameraFpsResult.Rejected(it) }
        return CameraFpsAnalyzer.analyze(timestamps)
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
        lastCameraTimestamp = null
        failure = null
    }

    private companion object {
        const val MAX_TIMESTAMPS =
            AndroidPerformanceContract.MAX_SAMPLES_PER_SERIES + 1
    }
}
