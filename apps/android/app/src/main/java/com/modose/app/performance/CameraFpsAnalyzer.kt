package com.modose.app.performance

data class CameraFpsMeasurement(
    val frameDurations: List<DurationSample>,
    val averageFps: Double,
    val maximumLowFpsStreakNanos: Long,
    val meetsThirtyFpsTarget: Boolean,
    val violatesLowFpsContinuityBudget: Boolean,
)

enum class CameraFpsFailure {
    InsufficientFrames,
    TooManyFrames,
    NegativeTimestamp,
    TimestampNotIncreasing,
    FrameIntervalTooLong,
}

sealed interface CameraFpsResult {
    data class Measured(
        val measurement: CameraFpsMeasurement,
    ) : CameraFpsResult

    data class Rejected(
        val reason: CameraFpsFailure,
    ) : CameraFpsResult
}

object CameraFpsAnalyzer {
    fun analyze(frameTimestampsNanos: List<Long>): CameraFpsResult {
        if (frameTimestampsNanos.size < 2) {
            return rejected(CameraFpsFailure.InsufficientFrames)
        }
        if (frameTimestampsNanos.size > MAX_FRAME_TIMESTAMPS) {
            return rejected(CameraFpsFailure.TooManyFrames)
        }
        if (frameTimestampsNanos.any { it < 0L }) {
            return rejected(CameraFpsFailure.NegativeTimestamp)
        }

        val durations = ArrayList<DurationSample>(
            frameTimestampsNanos.size - 1,
        )
        var currentLowStreak = 0L
        var maximumLowStreak = 0L
        frameTimestampsNanos.zipWithNext().forEach { (previous, current) ->
            if (current <= previous) {
                return rejected(CameraFpsFailure.TimestampNotIncreasing)
            }
            val interval = current - previous
            if (
                interval >
                AndroidPerformanceContract.MAX_SAMPLE_DURATION_NANOS
            ) {
                return rejected(CameraFpsFailure.FrameIntervalTooLong)
            }
            durations += DurationSample(
                timestampNanos = current,
                durationNanos = interval,
            )
            if (interval > TWENTY_FPS_INTERVAL_NANOS) {
                currentLowStreak += interval
                maximumLowStreak =
                    maxOf(maximumLowStreak, currentLowStreak)
            } else {
                currentLowStreak = 0L
            }
        }

        val elapsed = frameTimestampsNanos.last() -
            frameTimestampsNanos.first()
        val averageFps =
            durations.size.toDouble() * NANOS_PER_SECOND / elapsed
        return CameraFpsResult.Measured(
            CameraFpsMeasurement(
                frameDurations = durations.toList(),
                averageFps = averageFps,
                maximumLowFpsStreakNanos = maximumLowStreak,
                meetsThirtyFpsTarget = averageFps >= TARGET_FPS,
                violatesLowFpsContinuityBudget =
                    maximumLowStreak >= MAX_LOW_FPS_STREAK_NANOS,
            ),
        )
    }

    private fun rejected(reason: CameraFpsFailure) =
        CameraFpsResult.Rejected(reason)

    const val TARGET_FPS = 30.0
    const val TWENTY_FPS_INTERVAL_NANOS = 50_000_000L
    const val MAX_LOW_FPS_STREAK_NANOS = 1_000_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val MAX_FRAME_TIMESTAMPS =
        AndroidPerformanceContract.MAX_SAMPLES_PER_SERIES + 1
}
