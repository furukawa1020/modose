package com.modose.app.ar.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedArSessionPlayerTest {
    @Test
    fun advanceTo_returnsLatestFrameAtReplayClock() {
        val session = session(
            frame(0, 1_000L, ReplayTrackingState.Tracking),
            frame(1, 1_100L, ReplayTrackingState.Tracking),
            frame(2, 1_300L, ReplayTrackingState.Tracking),
        )
        val player = RecordedArSessionPlayer(session)

        val initial = player.advanceTo(0L)
        val between = player.advanceTo(250L)

        assertTrue(initial is ReplayAdvanceResult.FrameReady)
        assertEquals(
            0,
            (initial as ReplayAdvanceResult.FrameReady).frame.index,
        )
        assertSame(session.anchorPose, initial.anchorPose)
        assertTrue(between is ReplayAdvanceResult.FrameReady)
        assertEquals(
            1,
            (between as ReplayAdvanceResult.FrameReady).frame.index,
        )
    }

    @Test
    fun advanceTo_doesNotExposePausedPoseAsReady() {
        val player = RecordedArSessionPlayer(
            session(
                frame(0, 1_000L, ReplayTrackingState.Tracking),
                frame(1, 1_100L, ReplayTrackingState.Paused),
            ),
        )

        val result = player.advanceTo(100L)

        assertTrue(result is ReplayAdvanceResult.TrackingUnavailable)
        result as ReplayAdvanceResult.TrackingUnavailable
        assertEquals(1, result.frame.index)
        assertEquals(ReplayTrackingState.Paused, result.state)
    }

    @Test
    fun advanceTo_rejectsClockMovingBackward() {
        val player = RecordedArSessionPlayer(
            session(
                frame(0, 1_000L, ReplayTrackingState.Tracking),
                frame(1, 1_100L, ReplayTrackingState.Tracking),
            ),
        )
        player.advanceTo(80L)

        val rejected = player.advanceTo(70L)
        val recovered = player.advanceTo(100L)

        assertEquals(
            ReplayAdvanceResult.Rejected(
                ReplayAdvanceRejection.ClockMovedBackward,
            ),
            rejected,
        )
        assertTrue(recovered is ReplayAdvanceResult.FrameReady)
        assertEquals(
            1,
            (recovered as ReplayAdvanceResult.FrameReady).frame.index,
        )
    }

    @Test
    fun advanceTo_rejectsNegativeElapsedTime() {
        val player = RecordedArSessionPlayer(
            session(frame(0, 1_000L, ReplayTrackingState.Tracking)),
        )

        assertEquals(
            ReplayAdvanceResult.Rejected(
                ReplayAdvanceRejection.NegativeElapsedTime,
            ),
            player.advanceTo(-1L),
        )
    }

    @Test
    fun advanceTo_finishesAfterLastRecordedTimestamp() {
        val finalFrame = frame(1, 1_100L, ReplayTrackingState.Stopped)
        val player = RecordedArSessionPlayer(
            session(
                frame(0, 1_000L, ReplayTrackingState.Tracking),
                finalFrame,
            ),
        )

        val result = player.advanceTo(101L)

        assertEquals(ReplayAdvanceResult.Finished(finalFrame), result)
    }

    @Test
    fun advanceTo_saturatesTimestampOverflow() {
        val finalFrame = frame(
            index = 0,
            timestamp = Long.MAX_VALUE - 1L,
            state = ReplayTrackingState.Tracking,
        )
        val player = RecordedArSessionPlayer(session(finalFrame))

        val result = player.advanceTo(10L)

        assertEquals(ReplayAdvanceResult.Finished(finalFrame), result)
    }

    private fun session(vararg frames: ReplayFrame): RecordedArSession =
        RecordedArSession(
            sessionId = "session-1",
            anchorPose = pose(0.0),
            frames = frames.toList(),
        )

    private fun frame(
        index: Int,
        timestamp: Long,
        state: ReplayTrackingState,
    ): ReplayFrame =
        ReplayFrame(
            index = index,
            timestampNanos = timestamp,
            trackingState = state,
            cameraPose = pose(index.toDouble()),
            objects = listOf(
                ReplayTrackedObject(
                    objectId = "wallet",
                    currentX = index.toDouble(),
                    currentZ = 0.0,
                    targetX = 0.0,
                    targetZ = 0.0,
                    matchState = ReplayMatchState.Accepted,
                    trackingValid = state == ReplayTrackingState.Tracking,
                ),
            ),
        )

    private fun pose(x: Double): ReplayPose =
        ReplayPose(
            translationMeters = ReplayVector3(x, 0.0, 0.0),
            rotation = ReplayQuaternion(0.0, 0.0, 0.0, 1.0),
        )
}
