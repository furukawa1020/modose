package com.modose.app.core

/** Identity, not sceneId equality, distinguishes restart generations. */
internal class NativeGuidanceEpoch internal constructor(val sceneId: String)

internal data class NativeRecognitionFrame(
    val epoch: NativeGuidanceEpoch,
    val observedAtMs: Long,
    val trackingValid: Boolean,
    val detections: List<CoreDetection>,
    val evidence: List<CorePairEvidence>,
)

internal enum class NativeFrameDropReason {
    CLOSED, FOREIGN_STREAM, REENTRANT, INVALID_TIMESTAMP, FUTURE, STALE, NON_INCREASING,
}

internal enum class NativePipelineFailure { CLOCK, NATIVE, PUBLICATION }

internal sealed interface NativeFrameProcessing {
    data class Published(val observedAtMs: Long) : NativeFrameProcessing
    data class Dropped(val reason: NativeFrameDropReason) : NativeFrameProcessing
    data class Failed(val reason: NativePipelineFailure) : NativeFrameProcessing
}

/** Must publish synchronously, without queuing an unguarded old snapshot. */
internal fun interface NativeGuidanceSink {
    fun publish(snapshot: NativeWorldFrameSnapshot): Boolean
}

/**
 * Owns native lifetime and serializes inference completions through publication.
 * Producers must not mutate input lists during submit. No scores are fabricated.
 */
internal class NativeGuidancePipeline private constructor(
    val epoch: NativeGuidanceEpoch,
    private val session: NativeSceneSession,
    private val monotonicMillis: () -> Long,
    private val sink: NativeGuidanceSink,
) : AutoCloseable {
    private var closed = false
    private var inFlight = false
    private var lastApplied: Long? = null
    private var lastClock: Long? = null

    @Synchronized
    fun submit(frame: NativeRecognitionFrame): NativeFrameProcessing {
        if (closed) return drop(NativeFrameDropReason.CLOSED)
        if (frame.epoch !== epoch) return drop(NativeFrameDropReason.FOREIGN_STREAM)
        if (inFlight) return drop(NativeFrameDropReason.REENTRANT)
        inFlight = true
        try {
            val now = try { clockTime() } catch (_: RuntimeException) {
                return fail(NativePipelineFailure.CLOCK)
            }
            if (closed) return drop(NativeFrameDropReason.CLOSED)
            if (frame.observedAtMs < 0) return drop(NativeFrameDropReason.INVALID_TIMESTAMP)
            if (frame.observedAtMs > now) return drop(NativeFrameDropReason.FUTURE)
            if (now - frame.observedAtMs > 200L) return drop(NativeFrameDropReason.STALE)
            if (lastApplied?.let { frame.observedAtMs <= it } == true) {
                return drop(NativeFrameDropReason.NON_INCREASING)
            }
            val snapshot = try {
                session.updateWorldGuidance(
                    frame.observedAtMs, frame.trackingValid, frame.detections, frame.evidence,
                )
            } catch (_: RuntimeException) {
                return fail(NativePipelineFailure.NATIVE)
            }
            lastApplied = frame.observedAtMs
            val accepted = try { sink.publish(snapshot) } catch (_: RuntimeException) {
                return fail(NativePipelineFailure.PUBLICATION)
            }
            if (closed) return drop(NativeFrameDropReason.CLOSED)
            if (!accepted) return fail(NativePipelineFailure.PUBLICATION)
            return NativeFrameProcessing.Published(frame.observedAtMs)
        } finally {
            inFlight = false
        }
    }

    @Synchronized
    fun submitImage(capture: NativeImageCapture, result: NativeImageRecognition): NativeImageSubmission {
        if (capture.epoch !== epoch || result.epoch !== epoch) {
            return NativeImageSubmission(NativeImageProjectionError.FOREIGN_STREAM,
                drop(NativeFrameDropReason.FOREIGN_STREAM))
        }
        return when (val projected = capture.project(result)) {
            is NativeImageProjection.Projected -> NativeImageSubmission(null, submit(
                NativeRecognitionFrame(epoch, capture.observedAtMs, true, projected.detections, result.evidence),
            ))
            is NativeImageProjection.Rejected -> NativeImageSubmission(projected.reason, submit(
                NativeRecognitionFrame(epoch, capture.observedAtMs, false, emptyList(), emptyList()),
            ))
        }
    }

    @Synchronized
    fun beginVerification(owner: NativeGuidanceEpoch): NativeVerificationTicket =
        verify(owner) { now -> session.beginVerification(now) }

    @Synchronized
    fun completeVerification(
        owner: NativeGuidanceEpoch,
        ticket: NativeVerificationTicket,
        result: NativeVerificationResult,
    ): CoreRestoreState = verify(owner) { now -> session.completeVerification(ticket, now, result) }

    private fun <T> verify(owner: NativeGuidanceEpoch, block: (Long) -> T): T {
        check(!closed && !inFlight) { "Guidance pipeline is unavailable" }
        require(owner === epoch) { "Verification stream mismatch" }
        inFlight = true
        try {
            val now = clockTime()
            check(!closed) { "Guidance pipeline is closed" }
            return block(now)
        } catch (failure: RuntimeException) {
            fail(NativePipelineFailure.NATIVE)
            throw failure
        } finally {
            inFlight = false
        }
    }

    private fun clockTime(): Long {
        val now = monotonicMillis()
        check(now >= 0 && (lastClock?.let { now >= it } != false)) { "Invalid monotonic clock" }
        lastClock = now
        return now
    }

    private fun fail(reason: NativePipelineFailure): NativeFrameProcessing.Failed {
        try { close() } catch (_: RuntimeException) {
            // The owning session is already retired; preserve the primary reason.
        }
        return NativeFrameProcessing.Failed(reason)
    }

    private fun drop(reason: NativeFrameDropReason) = NativeFrameProcessing.Dropped(reason)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        fun open(
            sceneId: String,
            geometry: DoubleArray,
            targetIds: IntArray,
            targets: DoubleArray,
            monotonicMillis: () -> Long,
            sink: NativeGuidanceSink,
        ): NativeGuidancePipeline {
            require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(sceneId)) { "Invalid scene ID" }
            return NativeGuidancePipeline(
                NativeGuidanceEpoch(sceneId), NativeSceneSession.create(geometry, targetIds, targets),
                monotonicMillis, sink,
            )
        }
    }
}
