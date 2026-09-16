package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPerformanceContractTest {
    @Test
    fun create_acceptsCompleteReleaseMeasurements() {
        val frames = mutableListOf(DurationSample(10, 33_000_000))

        val result = create(camera = frames)

        assertTrue(result is PerformanceContractResult.Accepted)
        val accepted =
            (result as PerformanceContractResult.Accepted).measurements
        assertEquals(PerformanceBuildType.Release, accepted.metadata.buildType)
        assertEquals(1, accepted.cameraFrameDurations.size)
        assertNotSame(frames, accepted.cameraFrameDurations)
    }

    @Test
    fun create_rejectsDebugMeasurements() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.NonReleaseBuild,
            ),
            create(
                metadata = metadata().copy(
                    buildType = PerformanceBuildType.Debug,
                ),
            ),
        )
    }

    @Test
    fun create_rejectsMissingSeries() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.MissingSamples,
            ),
            create(jni = emptyList()),
        )
    }

    @Test
    fun create_rejectsTimestampOutsideWindowAndRegression() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.TimestampOutsideWindow,
            ),
            create(
                camera = listOf(DurationSample(101, 1)),
            ),
        )
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.TimestampNotIncreasing,
            ),
            create(
                camera = listOf(
                    DurationSample(20, 1),
                    DurationSample(20, 1),
                ),
            ),
        )
    }

    @Test
    fun create_rejectsInvalidDuration() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.InvalidDuration,
            ),
            create(
                image = listOf(DurationSample(10, 0)),
            ),
        )
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.InvalidDuration,
            ),
            create(
                image = listOf(
                    DurationSample(
                        10,
                        AndroidPerformanceContract
                            .MAX_SAMPLE_DURATION_NANOS + 1,
                    ),
                ),
            ),
        )
    }

    @Test
    fun create_rejectsInvalidMemory() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.InvalidMemory,
            ),
            create(
                memory = listOf(MemorySample(10, 0)),
            ),
        )
    }

    @Test
    fun create_rejectsInvalidMetadataAndWindow() {
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.InvalidMetadata,
            ),
            create(metadata = metadata().copy(runId = "../run")),
        )
        assertEquals(
            PerformanceContractResult.Rejected(
                PerformanceContractFailure.InvalidMeasurementWindow,
            ),
            create(started = 100, ended = 100),
        )
    }

    private fun create(
        metadata: PerformanceRunMetadata = metadata(),
        started: Long = 0,
        ended: Long = 100,
        camera: List<DurationSample> =
            listOf(DurationSample(10, 33_000_000)),
        memory: List<MemorySample> =
            listOf(MemorySample(10, 100_000_000)),
        jni: List<DurationSample> =
            listOf(DurationSample(10, 1_000_000)),
        image: List<DurationSample> =
            listOf(DurationSample(10, 50_000_000)),
    ): PerformanceContractResult =
        AndroidPerformanceContract.create(
            metadata = metadata,
            startedAtNanos = started,
            endedAtNanos = ended,
            cameraFrameDurations = camera,
            memorySamples = memory,
            jniDurations = jni,
            imageProcessingDurations = image,
        )

    private fun metadata() = PerformanceRunMetadata(
        runId = "perf-001",
        appVersion = "1.0.0-release",
        deviceModel = "Pixel 8",
        buildType = PerformanceBuildType.Release,
    )
}
