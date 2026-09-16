package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPssMemorySamplerTest {
    @Test
    fun capture_convertsPssKilobytesToBytes() {
        val sampler = AndroidPssMemorySampler(
            reader = PssKilobytesReader { 100_000L },
            clock = MonotonicNanosClock { 42L },
        )

        assertEquals(
            MemoryCaptureResult.Captured(
                MemorySample(42L, 102_400_000L),
            ),
            sampler.capture(),
        )
    }

    @Test
    fun capture_rejectsInvalidPssAndTimestamp() {
        assertEquals(
            MemoryCaptureResult.Rejected(
                MemoryCaptureFailure.InvalidPss,
            ),
            sampler(pssKilobytes = 0L).capture(),
        )
        assertEquals(
            MemoryCaptureResult.Rejected(
                MemoryCaptureFailure.InvalidTimestamp,
            ),
            sampler(pssKilobytes = 1L, timestamp = -1L).capture(),
        )
    }

    @Test
    fun capture_rejectsOverflowAndReaderFailure() {
        assertEquals(
            MemoryCaptureResult.Rejected(
                MemoryCaptureFailure.PssTooLarge,
            ),
            sampler(pssKilobytes = Long.MAX_VALUE).capture(),
        )
        val unavailable = AndroidPssMemorySampler(
            reader = PssKilobytesReader {
                throw IllegalStateException("unavailable")
            },
            clock = MonotonicNanosClock { 1L },
        )
        assertEquals(
            MemoryCaptureResult.Rejected(
                MemoryCaptureFailure.ReaderUnavailable,
            ),
            unavailable.capture(),
        )
    }

    @Test
    fun analyze_acceptsPeakExactlyAtBudget() {
        val result = MemoryBudgetAnalyzer.analyze(
            listOf(
                MemorySample(1L, 100_000_000L),
                MemorySample(2L, 350_000_000L),
                MemorySample(3L, 200_000_000L),
            ),
        ) as MemoryBudgetResult.Assessed

        assertEquals(350_000_000L, result.assessment.peakPssBytes)
        assertTrue(result.assessment.withinBudget)
    }

    @Test
    fun analyze_flagsPeakOverBudget() {
        val result = MemoryBudgetAnalyzer.analyze(
            listOf(MemorySample(1L, 350_000_001L)),
        ) as MemoryBudgetResult.Assessed

        assertFalse(result.assessment.withinBudget)
    }

    @Test
    fun analyze_rejectsMissingOrRegressingSamples() {
        assertEquals(
            MemoryBudgetResult.Rejected(
                MemoryBudgetFailure.MissingSamples,
            ),
            MemoryBudgetAnalyzer.analyze(emptyList()),
        )
        assertEquals(
            MemoryBudgetResult.Rejected(
                MemoryBudgetFailure.InvalidSample,
            ),
            MemoryBudgetAnalyzer.analyze(
                listOf(
                    MemorySample(2L, 100L),
                    MemorySample(1L, 200L),
                ),
            ),
        )
    }

    private fun sampler(
        pssKilobytes: Long,
        timestamp: Long = 1L,
    ) = AndroidPssMemorySampler(
        reader = PssKilobytesReader { pssKilobytes },
        clock = MonotonicNanosClock { timestamp },
    )
}
