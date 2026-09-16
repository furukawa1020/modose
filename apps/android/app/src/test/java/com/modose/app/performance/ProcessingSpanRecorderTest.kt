package com.modose.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingSpanRecorderTest {
    @Test
    fun finishRun_returnsJniAndImageSamples() {
        val times = ArrayDeque(listOf(10L, 20L, 30L, 50L))
        val recorder = ProcessingSpanRecorder(
            MonotonicNanosClock { times.removeFirst() },
        )
        val jni = (recorder.start(ProcessingSpanKind.Jni)
            as ProcessingTimingResult.Started).token
        assertEquals(
            ProcessingTimingResult.Completed(
                DurationSample(20L, 10L),
            ),
            recorder.finish(jni),
        )
        val image = (recorder.start(ProcessingSpanKind.ImageProcessing)
            as ProcessingTimingResult.Started).token
        recorder.finish(image)

        val finished = recorder.finishRun()
            as ProcessingTimingResult.Finished

        assertEquals(listOf(DurationSample(20L, 10L)), finished.jniDurations)
        assertEquals(
            listOf(DurationSample(50L, 20L)),
            finished.imageProcessingDurations,
        )
    }

    @Test
    fun finish_rejectsTokenFromDifferentRecorder() {
        val recorder = ProcessingSpanRecorder(MonotonicNanosClock { 10L })
        val opened = (recorder.start(ProcessingSpanKind.Jni)
            as ProcessingTimingResult.Started).token

        assertEquals(
            ProcessingTimingResult.Rejected(
                ProcessingTimingFailure.TokenMismatch,
            ),
            recorder.finish(opened.copy(kind = ProcessingSpanKind.ImageProcessing)),
        )
    }

    @Test
    fun finish_rejectsClockRegressionWithoutConsumingSpan() {
        val times = ArrayDeque(listOf(20L, 10L, 30L))
        val recorder = ProcessingSpanRecorder(
            MonotonicNanosClock { times.removeFirst() },
        )
        val token = (recorder.start(ProcessingSpanKind.Jni)
            as ProcessingTimingResult.Started).token

        assertEquals(
            ProcessingTimingResult.Rejected(
                ProcessingTimingFailure.ClockMovedBackward,
            ),
            recorder.finish(token),
        )
        assertEquals(
            ProcessingTimingResult.Completed(DurationSample(30L, 10L)),
            recorder.finish(token),
        )
    }

    @Test
    fun finishRun_rejectsOpenSpanAndMissingSeries() {
        val recorder = ProcessingSpanRecorder(
            MonotonicNanosClock { 1L },
        )
        recorder.start(ProcessingSpanKind.Jni)

        assertEquals(
            ProcessingTimingResult.Rejected(
                ProcessingTimingFailure.OpenSpansRemain,
            ),
            recorder.finishRun(),
        )
        val empty = ProcessingSpanRecorder(
            MonotonicNanosClock { 1L },
        )
        assertEquals(
            ProcessingTimingResult.Rejected(
                ProcessingTimingFailure.MissingSamples,
            ),
            empty.finishRun(),
        )
    }

    @Test
    fun finish_rejectsDoubleCompletion() {
        val times = ArrayDeque(listOf(1L, 2L))
        val recorder = ProcessingSpanRecorder(
            MonotonicNanosClock { times.removeFirst() },
        )
        val token = (recorder.start(ProcessingSpanKind.Jni)
            as ProcessingTimingResult.Started).token
        recorder.finish(token)

        assertEquals(
            ProcessingTimingResult.Rejected(
                ProcessingTimingFailure.UnknownSpan,
            ),
            recorder.finish(token),
        )
    }

    @Test
    fun analyze_usesNearestRankP95() {
        val jni = (1L..20L).map {
            DurationSample(it, it * 1_000_000L)
        }
        val image = listOf(DurationSample(1L, 50_000_000L))

        val result = ProcessingLatencyAnalyzer.analyze(jni, image)

        assertTrue(result is ProcessingLatencyResult.Assessed)
        val assessment =
            (result as ProcessingLatencyResult.Assessed).assessment
        assertEquals(19_000_000L, assessment.jniP95Nanos)
        assertEquals(
            50_000_000L,
            assessment.imageProcessingP95Nanos,
        )
    }

    @Test
    fun analyze_rejectsMissingSeries() {
        assertEquals(
            ProcessingLatencyResult.MissingSamples,
            ProcessingLatencyAnalyzer.analyze(
                emptyList(),
                listOf(DurationSample(1L, 1L)),
            ),
        )
    }
}
