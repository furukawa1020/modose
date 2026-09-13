package com.modose.app.ar.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuideAssertionEngineTest {
    @Test
    fun assertFrame_passesArrowAndDistance() {
        val engine = ReplayGuideAssertionEngine()

        val result = engine.assertFrame(
            frame(timestamp = 1_000L, currentX = 0.20),
            ReplayGuideExpectation(
                sceneObjectId = "wallet",
                visual = ReplayGuideVisualExpectation.Arrow,
                expectedDistanceMeters = 0.20,
            ),
        )

        assertTrue(result is ReplayGuideAssertionResult.Passed)
    }

    @Test
    fun assertFrame_replaysRingThenLocalCompletion() {
        val engine = ReplayGuideAssertionEngine()
        val ring = engine.assertFrame(
            frame(timestamp = 0L, currentX = 0.01),
            ReplayGuideExpectation(
                "wallet",
                ReplayGuideVisualExpectation.AlignmentRing,
                expectedDistanceMeters = 0.01,
            ),
        )
        val completed = engine.assertFrame(
            frame(timestamp = 800_000_000L, currentX = 0.01),
            ReplayGuideExpectation(
                "wallet",
                ReplayGuideVisualExpectation.LocallyCompleted,
                expectedDistanceMeters = 0.01,
            ),
        )

        assertTrue(ring is ReplayGuideAssertionResult.Passed)
        assertTrue(completed is ReplayGuideAssertionResult.Passed)
    }

    @Test
    fun assertFrame_mapsAmbiguousMatchToFrozenGuide() {
        val engine = ReplayGuideAssertionEngine()

        val result = engine.assertFrame(
            frame(
                timestamp = 1_000L,
                currentX = 0.20,
                matchState = ReplayMatchState.Ambiguous,
            ),
            ReplayGuideExpectation(
                "wallet",
                ReplayGuideVisualExpectation.FrozenAmbiguous,
            ),
        )

        assertTrue(result is ReplayGuideAssertionResult.Passed)
    }

    @Test
    fun assertFrame_mapsPausedSessionToTrackingLost() {
        val engine = ReplayGuideAssertionEngine()

        val result = engine.assertFrame(
            frame(
                timestamp = 1_000L,
                currentX = 0.20,
                trackingState = ReplayTrackingState.Paused,
            ),
            ReplayGuideExpectation(
                "wallet",
                ReplayGuideVisualExpectation.FrozenTrackingLost,
            ),
        )

        assertTrue(result is ReplayGuideAssertionResult.Passed)
    }

    @Test
    fun assertFrame_reportsVisualMismatch() {
        val result = ReplayGuideAssertionEngine().assertFrame(
            frame(timestamp = 1_000L, currentX = 0.20),
            ReplayGuideExpectation(
                "wallet",
                ReplayGuideVisualExpectation.LocallyCompleted,
            ),
        )

        assertTrue(result is ReplayGuideAssertionResult.Failed)
        assertEquals(
            ReplayGuideAssertionFailure.VisualMismatch,
            (result as ReplayGuideAssertionResult.Failed).reason,
        )
    }

    @Test
    fun assertFrame_reportsDistanceOutsideTolerance() {
        val result = ReplayGuideAssertionEngine().assertFrame(
            frame(timestamp = 1_000L, currentX = 0.20),
            ReplayGuideExpectation(
                sceneObjectId = "wallet",
                visual = ReplayGuideVisualExpectation.Arrow,
                expectedDistanceMeters = 0.25,
                distanceToleranceMeters = 0.001,
            ),
        )

        assertEquals(
            ReplayGuideAssertionFailure.DistanceOutsideTolerance,
            (result as ReplayGuideAssertionResult.Failed).reason,
        )
    }

    @Test
    fun assertFrame_rejectsMissingObjectAndInvalidTolerance() {
        val engine = ReplayGuideAssertionEngine()
        val currentFrame = frame(timestamp = 1_000L, currentX = 0.20)

        val missing = engine.assertFrame(
            currentFrame,
            ReplayGuideExpectation(
                "keys",
                ReplayGuideVisualExpectation.Arrow,
            ),
        )
        val invalid = engine.assertFrame(
            currentFrame,
            ReplayGuideExpectation(
                sceneObjectId = "wallet",
                visual = ReplayGuideVisualExpectation.Arrow,
                distanceToleranceMeters = -0.1,
            ),
        )

        assertEquals(
            ReplayGuideAssertionFailure.ObjectNotRecorded,
            (missing as ReplayGuideAssertionResult.Failed).reason,
        )
        assertEquals(
            ReplayGuideAssertionFailure.InvalidExpectation,
            (invalid as ReplayGuideAssertionResult.Failed).reason,
        )
    }

    private fun frame(
        timestamp: Long,
        currentX: Double,
        matchState: ReplayMatchState = ReplayMatchState.Accepted,
        trackingState: ReplayTrackingState = ReplayTrackingState.Tracking,
    ): ReplayFrame =
        ReplayFrame(
            index = 0,
            timestampNanos = timestamp,
            trackingState = trackingState,
            cameraPose = ReplayPose(
                ReplayVector3(0.0, 1.0, 0.0),
                ReplayQuaternion(0.0, 0.0, 0.0, 1.0),
            ),
            objects = listOf(
                ReplayTrackedObject(
                    objectId = "wallet",
                    currentX = currentX,
                    currentZ = 0.0,
                    targetX = 0.0,
                    targetZ = 0.0,
                    matchState = matchState,
                    trackingValid = true,
                ),
            ),
        )
}
