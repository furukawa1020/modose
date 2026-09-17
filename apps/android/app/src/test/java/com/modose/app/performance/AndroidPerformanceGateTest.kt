package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPerformanceGateTest {
    @Test
    fun evaluate_passesReleaseMeasurementsInsideAllBudgets() {
        val result = AndroidPerformanceGate.evaluate(measurements())
            as PerformanceGateResult.Evaluated

        assertTrue(result.report.passed)
        assertTrue(result.report.violations.isEmpty())
        assertEquals("perf-001", result.report.runId)
    }

    @Test
    fun evaluate_reportsEachBudgetViolation() {
        val slowFrames = frameDurations(
            List(20) { 60_000_000L },
        )
        val result = AndroidPerformanceGate.evaluate(
            measurements(
                camera = slowFrames,
                memory = listOf(MemorySample(1L, 350_000_001L)),
                jni = listOf(DurationSample(1L, 10_000_000L)),
                image = listOf(DurationSample(1L, 100_000_001L)),
            ),
        ) as PerformanceGateResult.Evaluated

        assertFalse(result.report.passed)
        assertEquals(
            PerformanceBudgetViolation.entries.toSet(),
            result.report.violations,
        )
    }

    @Test
    fun evaluate_preservesStrictJniAndInclusiveImageBoundaries() {
        val atBoundaries = AndroidPerformanceGate.evaluate(
            measurements(
                memory = listOf(MemorySample(1L, 350_000_000L)),
                jni = listOf(DurationSample(1L, 9_999_999L)),
                image = listOf(DurationSample(1L, 100_000_000L)),
            ),
        ) as PerformanceGateResult.Evaluated

        assertTrue(atBoundaries.report.passed)
    }

    @Test
    fun evaluate_rejectsDiscontinuousCameraSeries() {
        val result = AndroidPerformanceGate.evaluate(
            measurements(
                camera = listOf(
                    DurationSample(33_000_000L, 33_000_000L),
                    DurationSample(100_000_000L, 33_000_000L),
                ),
            ),
        )

        assertEquals(
            PerformanceGateResult.Rejected(
                PerformanceGateFailure.InvalidCameraSamples,
            ),
            result,
        )
    }

    @Test
    fun evaluate_rejectsDirectlyConstructedInvalidBatches() {
        val valid = measurements()
        val invalid = listOf(
            valid.copy(metadata = valid.metadata.copy(
                buildType = PerformanceBuildType.Debug,
            )) to PerformanceContractFailure.NonReleaseBuild,
            valid.copy(endedAtNanos = 0L) to
                PerformanceContractFailure.InvalidMeasurementWindow,
            valid.copy(cameraFrameDurations = emptyList()) to
                PerformanceContractFailure.MissingSamples,
            valid.copy(memorySamples = emptyList()) to
                PerformanceContractFailure.MissingSamples,
            valid.copy(jniDurations = emptyList()) to
                PerformanceContractFailure.MissingSamples,
            valid.copy(jniDurations = listOf(
                DurationSample(4_000_000_000L, 1L),
            )) to PerformanceContractFailure.TimestampOutsideWindow,
            valid.copy(jniDurations = listOf(
                DurationSample(2L, 1L), DurationSample(1L, 1L),
            )) to PerformanceContractFailure.TimestampNotIncreasing,
        )

        invalid.forEach { (batch, reason) ->
            assertEquals(
                PerformanceGateResult.Rejected(
                    PerformanceGateFailure.InvalidMeasurementContract,
                    reason,
                ),
                AndroidPerformanceGate.evaluate(batch),
            )
        }
    }

    private fun measurements(
        camera: List<DurationSample> =
            frameDurations(List(60) { 33_333_333L }),
        memory: List<MemorySample> =
            listOf(MemorySample(1L, 100_000_000L)),
        jni: List<DurationSample> =
            listOf(DurationSample(1L, 1_000_000L)),
        image: List<DurationSample> =
            listOf(DurationSample(1L, 50_000_000L)),
    ) = AndroidPerformanceMeasurements(
        metadata = PerformanceRunMetadata(
            runId = "perf-001",
            appVersion = "1.0.0",
            deviceModel = "Pixel 8",
            buildType = PerformanceBuildType.Release,
        ),
        startedAtNanos = 0L,
        endedAtNanos = 3_000_000_000L,
        cameraFrameDurations = camera,
        memorySamples = memory,
        jniDurations = jni,
        imageProcessingDurations = image,
    )

    private fun frameDurations(
        intervals: List<Long>,
    ): List<DurationSample> {
        var timestamp = 0L
        return intervals.map { interval ->
            timestamp += interval
            DurationSample(timestamp, interval)
        }
    }
}
