package com.modose.app.performance

enum class ProcessingSpanKind {
    Jni,
    ImageProcessing,
}

data class ProcessingSpanToken(
    val id: Long,
    val kind: ProcessingSpanKind,
    val startedAtNanos: Long,
)

enum class ProcessingTimingFailure {
    InvalidTimestamp,
    TooManySamples,
    UnknownSpan,
    TokenMismatch,
    ClockMovedBackward,
    SpanTooLong,
    OpenSpansRemain,
    MissingSamples,
}

sealed interface ProcessingTimingResult {
    data class Started(val token: ProcessingSpanToken) :
        ProcessingTimingResult

    data class Completed(val sample: DurationSample) :
        ProcessingTimingResult

    data class Finished(
        val jniDurations: List<DurationSample>,
        val imageProcessingDurations: List<DurationSample>,
    ) : ProcessingTimingResult

    data class Rejected(val reason: ProcessingTimingFailure) :
        ProcessingTimingResult
}

class ProcessingSpanRecorder(
    private val clock: MonotonicNanosClock,
) {
    private val openSpans = mutableMapOf<Long, ProcessingSpanToken>()
    private val jni = mutableListOf<DurationSample>()
    private val image = mutableListOf<DurationSample>()
    private var nextId = 0L
    private var finished = false

    @Synchronized
    fun start(kind: ProcessingSpanKind): ProcessingTimingResult {
        if (finished || nextId == Long.MAX_VALUE) {
            return rejected(ProcessingTimingFailure.TooManySamples)
        }
        val timestamp = now() ?: return rejected(
            ProcessingTimingFailure.InvalidTimestamp,
        )
        if (timestamp < 0L) {
            return rejected(ProcessingTimingFailure.InvalidTimestamp)
        }
        val token = ProcessingSpanToken(++nextId, kind, timestamp)
        openSpans[token.id] = token
        return ProcessingTimingResult.Started(token)
    }

    @Synchronized
    fun finish(token: ProcessingSpanToken): ProcessingTimingResult {
        if (finished) {
            return rejected(ProcessingTimingFailure.UnknownSpan)
        }
        val opened = openSpans[token.id]
            ?: return rejected(ProcessingTimingFailure.UnknownSpan)
        if (opened != token) {
            return rejected(ProcessingTimingFailure.TokenMismatch)
        }
        val target = when (token.kind) {
            ProcessingSpanKind.Jni -> jni
            ProcessingSpanKind.ImageProcessing -> image
        }
        if (target.size >= AndroidPerformanceContract.MAX_SAMPLES_PER_SERIES) {
            return rejected(ProcessingTimingFailure.TooManySamples)
        }
        val endedAt = now() ?: return rejected(
            ProcessingTimingFailure.InvalidTimestamp,
        )
        if (endedAt <= token.startedAtNanos) {
            return rejected(ProcessingTimingFailure.ClockMovedBackward)
        }
        val duration = endedAt - token.startedAtNanos
        if (
            duration >
            AndroidPerformanceContract.MAX_SAMPLE_DURATION_NANOS
        ) {
            return rejected(ProcessingTimingFailure.SpanTooLong)
        }
        if (
            target.lastOrNull()?.timestampNanos?.let {
                endedAt <= it
            } == true
        ) {
            return rejected(ProcessingTimingFailure.ClockMovedBackward)
        }
        val sample = DurationSample(endedAt, duration)
        target += sample
        openSpans.remove(token.id)
        return ProcessingTimingResult.Completed(sample)
    }

    @Synchronized
    fun finishRun(): ProcessingTimingResult {
        if (finished) {
            return rejected(ProcessingTimingFailure.UnknownSpan)
        }
        if (openSpans.isNotEmpty()) {
            return rejected(ProcessingTimingFailure.OpenSpansRemain)
        }
        if (jni.isEmpty() || image.isEmpty()) {
            return rejected(ProcessingTimingFailure.MissingSamples)
        }
        finished = true
        return ProcessingTimingResult.Finished(
            jniDurations = jni.toList(),
            imageProcessingDurations = image.toList(),
        )
    }

    private fun now(): Long? = try {
        clock.nowNanos()
    } catch (_: RuntimeException) {
        null
    }

    private fun rejected(reason: ProcessingTimingFailure) =
        ProcessingTimingResult.Rejected(reason)
}

data class ProcessingLatencyAssessment(
    val jniP95Nanos: Long,
    val imageProcessingP95Nanos: Long,
)

sealed interface ProcessingLatencyResult {
    data class Assessed(val assessment: ProcessingLatencyAssessment) :
        ProcessingLatencyResult

    data object MissingSamples : ProcessingLatencyResult
}

object ProcessingLatencyAnalyzer {
    fun analyze(
        jni: List<DurationSample>,
        image: List<DurationSample>,
    ): ProcessingLatencyResult {
        if (jni.isEmpty() || image.isEmpty()) {
            return ProcessingLatencyResult.MissingSamples
        }
        return ProcessingLatencyResult.Assessed(
            ProcessingLatencyAssessment(
                jniP95Nanos = p95(jni),
                imageProcessingP95Nanos = p95(image),
            ),
        )
    }

    private fun p95(samples: List<DurationSample>): Long {
        val sorted = samples.map(DurationSample::durationNanos).sorted()
        val rank = (sorted.size * 95 + 99) / 100
        return sorted[rank - 1]
    }
}
