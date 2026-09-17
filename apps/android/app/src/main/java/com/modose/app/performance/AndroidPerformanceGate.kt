package com.modose.app.performance

enum class PerformanceBudgetViolation {
    AverageFpsBelowTarget,
    LowFpsContinuityExceeded,
    PeakPssExceeded,
    JniP95Exceeded,
    ImageProcessingP95Exceeded,
}

data class PerformanceBudgetReport(
    val runId: String,
    val averageFps: Double,
    val longestLowFpsNanos: Long,
    val peakPssBytes: Long,
    val jniP95Nanos: Long,
    val imageProcessingP95Nanos: Long,
    val violations: Set<PerformanceBudgetViolation>,
) {
    val passed: Boolean get() = violations.isEmpty()
}

enum class PerformanceGateFailure {
    InvalidMeasurementContract,
    InvalidCameraSamples,
    InvalidMemorySamples,
    MissingProcessingSamples,
    InvalidProcessingSamples,
}

sealed interface PerformanceGateResult {
    data class Evaluated(val report: PerformanceBudgetReport) :
        PerformanceGateResult

    data class Rejected(
        val reason: PerformanceGateFailure,
        val contractFailure: PerformanceContractFailure? = null,
    ) :
        PerformanceGateResult
}

object AndroidPerformanceGate {
    const val JNI_P95_BUDGET_NANOS = 10_000_000L
    const val IMAGE_P95_BUDGET_NANOS = 100_000_000L

    fun evaluate(
        measurements: AndroidPerformanceMeasurements,
    ): PerformanceGateResult {
        val validated = AndroidPerformanceContract.create(
            metadata = measurements.metadata,
            startedAtNanos = measurements.startedAtNanos,
            endedAtNanos = measurements.endedAtNanos,
            cameraFrameDurations = measurements.cameraFrameDurations,
            memorySamples = measurements.memorySamples,
            jniDurations = measurements.jniDurations,
            imageProcessingDurations = measurements.imageProcessingDurations,
        )
        if (validated is PerformanceContractResult.Rejected) {
            return PerformanceGateResult.Rejected(
                PerformanceGateFailure.InvalidMeasurementContract,
                validated.reason,
            )
        }
        return evaluateValidated(
            (validated as PerformanceContractResult.Accepted).measurements,
        )
    }

    private fun evaluateValidated(
        measurements: AndroidPerformanceMeasurements,
    ): PerformanceGateResult {
        val camera = cameraFromDurations(
            measurements.cameraFrameDurations,
        ) ?: return rejected(PerformanceGateFailure.InvalidCameraSamples)
        val cameraMeasurement = when (camera) {
            is CameraFpsResult.Measured -> camera.measurement
            is CameraFpsResult.Rejected -> return rejected(
                PerformanceGateFailure.InvalidCameraSamples,
            )
        }
        val memory = when (
            val result = MemoryBudgetAnalyzer.analyze(
                measurements.memorySamples,
            )
        ) {
            is MemoryBudgetResult.Assessed -> result.assessment
            is MemoryBudgetResult.Rejected -> return rejected(
                PerformanceGateFailure.InvalidMemorySamples,
            )
        }
        if (
            measurements.jniDurations.any { !it.validDuration() } ||
            measurements.imageProcessingDurations.any {
                !it.validDuration()
            }
        ) {
            return rejected(PerformanceGateFailure.InvalidProcessingSamples)
        }
        val processing = when (
            val result = ProcessingLatencyAnalyzer.analyze(
                measurements.jniDurations,
                measurements.imageProcessingDurations,
            )
        ) {
            is ProcessingLatencyResult.Assessed -> result.assessment
            ProcessingLatencyResult.MissingSamples -> return rejected(
                PerformanceGateFailure.MissingProcessingSamples,
            )
        }

        val violations = buildSet {
            if (!cameraMeasurement.meetsThirtyFpsTarget) {
                add(PerformanceBudgetViolation.AverageFpsBelowTarget)
            }
            if (cameraMeasurement.violatesLowFpsContinuityBudget) {
                add(PerformanceBudgetViolation.LowFpsContinuityExceeded)
            }
            if (!memory.withinBudget) {
                add(PerformanceBudgetViolation.PeakPssExceeded)
            }
            if (processing.jniP95Nanos >= JNI_P95_BUDGET_NANOS) {
                add(PerformanceBudgetViolation.JniP95Exceeded)
            }
            if (
                processing.imageProcessingP95Nanos >
                IMAGE_P95_BUDGET_NANOS
            ) {
                add(PerformanceBudgetViolation.ImageProcessingP95Exceeded)
            }
        }
        return PerformanceGateResult.Evaluated(
            PerformanceBudgetReport(
                runId = measurements.metadata.runId,
                averageFps = cameraMeasurement.averageFps,
                longestLowFpsNanos =
                    cameraMeasurement.maximumLowFpsStreakNanos,
                peakPssBytes = memory.peakPssBytes,
                jniP95Nanos = processing.jniP95Nanos,
                imageProcessingP95Nanos =
                    processing.imageProcessingP95Nanos,
                violations = violations,
            ),
        )
    }

    private fun cameraFromDurations(
        durations: List<DurationSample>,
    ): CameraFpsResult? {
        if (durations.isEmpty()) return null
        val timestamps = ArrayList<Long>(durations.size + 1)
        val first = durations.first()
        if (
            first.durationNanos <= 0L ||
            first.timestampNanos < first.durationNanos
        ) {
            return null
        }
        timestamps += first.timestampNanos - first.durationNanos
        durations.forEach { sample ->
            if (
                sample.durationNanos <= 0L ||
                sample.timestampNanos - timestamps.last() !=
                sample.durationNanos
            ) {
                return null
            }
            timestamps += sample.timestampNanos
        }
        return CameraFpsAnalyzer.analyze(timestamps)
    }

    private fun DurationSample.validDuration(): Boolean =
        timestampNanos >= 0L &&
            durationNanos in
            1..AndroidPerformanceContract.MAX_SAMPLE_DURATION_NANOS

    private fun rejected(reason: PerformanceGateFailure) =
        PerformanceGateResult.Rejected(reason)
}
