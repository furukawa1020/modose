package com.modose.app.performance

import android.os.Debug
import android.os.SystemClock

fun interface PssKilobytesReader {
    fun readKilobytes(): Long
}

fun interface MonotonicNanosClock {
    fun nowNanos(): Long
}

enum class MemoryCaptureFailure {
    InvalidTimestamp,
    InvalidPss,
    PssTooLarge,
    ReaderUnavailable,
}

sealed interface MemoryCaptureResult {
    data class Captured(val sample: MemorySample) : MemoryCaptureResult
    data class Rejected(val reason: MemoryCaptureFailure) :
        MemoryCaptureResult
}

class AndroidPssMemorySampler(
    private val reader: PssKilobytesReader = PssKilobytesReader {
        Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong()
    },
    private val clock: MonotonicNanosClock = MonotonicNanosClock {
        SystemClock.elapsedRealtimeNanos()
    },
) {
    fun capture(): MemoryCaptureResult {
        val timestamp = try {
            clock.nowNanos()
        } catch (_: RuntimeException) {
            return rejected(MemoryCaptureFailure.ReaderUnavailable)
        }
        if (timestamp < 0L) {
            return rejected(MemoryCaptureFailure.InvalidTimestamp)
        }
        val pssKilobytes = try {
            reader.readKilobytes()
        } catch (_: RuntimeException) {
            return rejected(MemoryCaptureFailure.ReaderUnavailable)
        }
        if (pssKilobytes <= 0L) {
            return rejected(MemoryCaptureFailure.InvalidPss)
        }
        if (
            pssKilobytes >
            AndroidPerformanceContract.MAX_PSS_BYTES / BYTES_PER_KIB
        ) {
            return rejected(MemoryCaptureFailure.PssTooLarge)
        }
        return MemoryCaptureResult.Captured(
            MemorySample(
                timestampNanos = timestamp,
                pssBytes = pssKilobytes * BYTES_PER_KIB,
            ),
        )
    }

    private fun rejected(reason: MemoryCaptureFailure) =
        MemoryCaptureResult.Rejected(reason)

    private companion object {
        const val BYTES_PER_KIB = 1_024L
    }
}

data class MemoryBudgetAssessment(
    val peakPssBytes: Long,
    val withinBudget: Boolean,
)

enum class MemoryBudgetFailure {
    MissingSamples,
    InvalidSample,
}

sealed interface MemoryBudgetResult {
    data class Assessed(val assessment: MemoryBudgetAssessment) :
        MemoryBudgetResult

    data class Rejected(val reason: MemoryBudgetFailure) :
        MemoryBudgetResult
}

object MemoryBudgetAnalyzer {
    const val PEAK_PSS_BUDGET_BYTES = 350_000_000L

    fun analyze(samples: List<MemorySample>): MemoryBudgetResult {
        if (samples.isEmpty()) {
            return MemoryBudgetResult.Rejected(
                MemoryBudgetFailure.MissingSamples,
            )
        }
        if (
            samples.any {
                it.timestampNanos < 0L ||
                    it.pssBytes !in
                    1..AndroidPerformanceContract.MAX_PSS_BYTES
            } ||
            samples.zipWithNext().any { (left, right) ->
                right.timestampNanos <= left.timestampNanos
            }
        ) {
            return MemoryBudgetResult.Rejected(
                MemoryBudgetFailure.InvalidSample,
            )
        }
        val peak = samples.maxOf(MemorySample::pssBytes)
        return MemoryBudgetResult.Assessed(
            MemoryBudgetAssessment(
                peakPssBytes = peak,
                withinBudget = peak <= PEAK_PSS_BUDGET_BYTES,
            ),
        )
    }
}
