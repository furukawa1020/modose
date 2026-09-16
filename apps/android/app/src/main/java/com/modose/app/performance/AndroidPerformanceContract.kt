package com.modose.app.performance

enum class PerformanceBuildType {
    Release,
    Debug,
}

data class PerformanceRunMetadata(
    val runId: String,
    val appVersion: String,
    val deviceModel: String,
    val buildType: PerformanceBuildType,
)

data class DurationSample(
    val timestampNanos: Long,
    val durationNanos: Long,
)

data class MemorySample(
    val timestampNanos: Long,
    val pssBytes: Long,
)

data class AndroidPerformanceMeasurements(
    val metadata: PerformanceRunMetadata,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val cameraFrameDurations: List<DurationSample>,
    val memorySamples: List<MemorySample>,
    val jniDurations: List<DurationSample>,
    val imageProcessingDurations: List<DurationSample>,
)

enum class PerformanceContractFailure {
    InvalidMetadata,
    NonReleaseBuild,
    InvalidMeasurementWindow,
    MissingSamples,
    TooManySamples,
    TimestampOutsideWindow,
    TimestampNotIncreasing,
    InvalidDuration,
    InvalidMemory,
}

sealed interface PerformanceContractResult {
    data class Accepted(
        val measurements: AndroidPerformanceMeasurements,
    ) : PerformanceContractResult

    data class Rejected(
        val reason: PerformanceContractFailure,
    ) : PerformanceContractResult
}

object AndroidPerformanceContract {
    fun create(
        metadata: PerformanceRunMetadata,
        startedAtNanos: Long,
        endedAtNanos: Long,
        cameraFrameDurations: List<DurationSample>,
        memorySamples: List<MemorySample>,
        jniDurations: List<DurationSample>,
        imageProcessingDurations: List<DurationSample>,
    ): PerformanceContractResult {
        if (!metadata.isValid()) {
            return rejected(PerformanceContractFailure.InvalidMetadata)
        }
        if (metadata.buildType != PerformanceBuildType.Release) {
            return rejected(PerformanceContractFailure.NonReleaseBuild)
        }
        if (
            startedAtNanos < 0L ||
            endedAtNanos <= startedAtNanos ||
            endedAtNanos - startedAtNanos > MAX_RUN_DURATION_NANOS
        ) {
            return rejected(PerformanceContractFailure.InvalidMeasurementWindow)
        }

        val durationGroups = listOf(
            cameraFrameDurations,
            jniDurations,
            imageProcessingDurations,
        )
        if (
            durationGroups.any(List<DurationSample>::isEmpty) ||
            memorySamples.isEmpty()
        ) {
            return rejected(PerformanceContractFailure.MissingSamples)
        }
        if (
            durationGroups.any { it.size > MAX_SAMPLES_PER_SERIES } ||
            memorySamples.size > MAX_SAMPLES_PER_SERIES
        ) {
            return rejected(PerformanceContractFailure.TooManySamples)
        }

        durationGroups.forEach { samples ->
            validateTimestamps(samples.map(DurationSample::timestampNanos), startedAtNanos, endedAtNanos)
                ?.let { return rejected(it) }
            if (samples.any { it.durationNanos !in 1..MAX_SAMPLE_DURATION_NANOS }) {
                return rejected(PerformanceContractFailure.InvalidDuration)
            }
        }
        validateTimestamps(
            memorySamples.map(MemorySample::timestampNanos),
            startedAtNanos,
            endedAtNanos,
        )?.let { return rejected(it) }
        if (memorySamples.any { it.pssBytes !in 1..MAX_PSS_BYTES }) {
            return rejected(PerformanceContractFailure.InvalidMemory)
        }

        return PerformanceContractResult.Accepted(
            AndroidPerformanceMeasurements(
                metadata = metadata,
                startedAtNanos = startedAtNanos,
                endedAtNanos = endedAtNanos,
                cameraFrameDurations = cameraFrameDurations.toList(),
                memorySamples = memorySamples.toList(),
                jniDurations = jniDurations.toList(),
                imageProcessingDurations =
                    imageProcessingDurations.toList(),
            ),
        )
    }

    private fun validateTimestamps(
        timestamps: List<Long>,
        startedAtNanos: Long,
        endedAtNanos: Long,
    ): PerformanceContractFailure? {
        if (timestamps.any { it !in startedAtNanos..endedAtNanos }) {
            return PerformanceContractFailure.TimestampOutsideWindow
        }
        if (timestamps.zipWithNext().any { (left, right) -> right <= left }) {
            return PerformanceContractFailure.TimestampNotIncreasing
        }
        return null
    }

    private fun PerformanceRunMetadata.isValid(): Boolean =
        SAFE_ID.matches(runId) &&
            SAFE_VERSION.matches(appVersion) &&
            deviceModel.isNotBlank() &&
            deviceModel.length <= 80

    private fun rejected(reason: PerformanceContractFailure) =
        PerformanceContractResult.Rejected(reason)

    const val MAX_SAMPLES_PER_SERIES = 100_000
    const val MAX_PSS_BYTES = 2_000_000_000L
    const val MAX_SAMPLE_DURATION_NANOS = 10_000_000_000L
    const val MAX_RUN_DURATION_NANOS = 1_800_000_000_000L
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
}
