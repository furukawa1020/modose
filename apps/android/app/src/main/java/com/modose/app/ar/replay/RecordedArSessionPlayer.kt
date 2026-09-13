package com.modose.app.ar.replay

sealed interface ReplayAdvanceResult {
    data class FrameReady(
        val frame: ReplayFrame,
        val anchorPose: ReplayPose,
    ) : ReplayAdvanceResult

    data class TrackingUnavailable(
        val frame: ReplayFrame,
        val state: ReplayTrackingState,
    ) : ReplayAdvanceResult

    data class Finished(
        val finalFrame: ReplayFrame,
    ) : ReplayAdvanceResult

    data class Rejected(
        val reason: ReplayAdvanceRejection,
    ) : ReplayAdvanceResult
}

enum class ReplayAdvanceRejection {
    NegativeElapsedTime,
    ClockMovedBackward,
}

class RecordedArSessionPlayer(
    private val session: RecordedArSession,
) {
    private var lastElapsedNanos: Long? = null
    private var cursor = 0

    fun advanceTo(elapsedNanos: Long): ReplayAdvanceResult {
        if (elapsedNanos < 0L) {
            return ReplayAdvanceResult.Rejected(
                ReplayAdvanceRejection.NegativeElapsedTime,
            )
        }
        val previousElapsed = lastElapsedNanos
        if (previousElapsed != null && elapsedNanos < previousElapsed) {
            return ReplayAdvanceResult.Rejected(
                ReplayAdvanceRejection.ClockMovedBackward,
            )
        }
        lastElapsedNanos = elapsedNanos

        val firstTimestamp = session.frames.first().timestampNanos
        val requestedTimestamp = saturatedAdd(firstTimestamp, elapsedNanos)
        val finalFrame = session.frames.last()
        if (requestedTimestamp > finalFrame.timestampNanos) {
            cursor = session.frames.lastIndex
            return ReplayAdvanceResult.Finished(finalFrame)
        }

        while (
            cursor < session.frames.lastIndex &&
            session.frames[cursor + 1].timestampNanos <= requestedTimestamp
        ) {
            cursor += 1
        }

        val frame = session.frames[cursor]
        return when (frame.trackingState) {
            ReplayTrackingState.Tracking -> ReplayAdvanceResult.FrameReady(
                frame = frame,
                anchorPose = session.anchorPose,
            )
            ReplayTrackingState.Paused,
            ReplayTrackingState.Stopped,
            -> ReplayAdvanceResult.TrackingUnavailable(
                frame = frame,
                state = frame.trackingState,
            )
        }
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long =
        if (Long.MAX_VALUE - left < right) {
            Long.MAX_VALUE
        } else {
            left + right
        }
}
