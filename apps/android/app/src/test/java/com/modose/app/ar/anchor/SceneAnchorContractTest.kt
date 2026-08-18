package com.modose.app.ar.anchor

import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.plane.SelectedHorizontalPlane
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneAnchorContractTest {
    @Test
    fun `creates only when a horizontal plane is tracking`() {
        val decision = SceneAnchorCreationPolicy.decide(
            planeState = HorizontalPlaneState.Tracking(plane(9)),
            anchorState = SceneAnchorState.Unavailable,
        )

        assertEquals(SceneAnchorCreationDecision.CreateAtCenter(9), decision)
        assertEquals(
            SceneAnchorCreationDecision.Reject(SceneAnchorCreationRejection.SurfaceUnavailable),
            SceneAnchorCreationPolicy.decide(
                HorizontalPlaneState.Searching,
                SceneAnchorState.Unavailable,
            ),
        )
    }

    @Test
    fun `reuses an existing anchor instead of creating a duplicate`() {
        val anchor = anchor(12)

        assertEquals(
            SceneAnchorCreationDecision.ReuseExisting(12),
            SceneAnchorCreationPolicy.decide(
                HorizontalPlaneState.Tracking(plane(9)),
                SceneAnchorState.Tracking(anchor),
            ),
        )
    }

    @Test
    fun `reports paused and stopped without treating them as tracking`() {
        val tracker = SceneAnchorStateTracker()
        val anchor = anchor(12)

        assertEquals(
            SceneAnchorState.Paused(anchor),
            tracker.update(anchor, SceneAnchorTrackingState.Paused),
        )
        assertEquals(
            SceneAnchorState.Lost(12),
            tracker.update(anchor, SceneAnchorTrackingState.Stopped),
        )
        assertEquals(SceneAnchorState.Unavailable, tracker.update(null, null))
    }

    @Test
    fun `reports a disappeared anchor as lost once`() {
        val tracker = SceneAnchorStateTracker()
        tracker.update(anchor(12), SceneAnchorTrackingState.Tracking)

        assertEquals(SceneAnchorState.Lost(12), tracker.update(null, null))
        assertEquals(SceneAnchorState.Unavailable, tracker.update(null, null))
    }

    private fun plane(id: Long) = SelectedHorizontalPlane(
        id = id,
        distanceMeters = 0.4f,
        extentXMeters = 0.8f,
        extentZMeters = 0.6f,
    )

    private fun anchor(id: Long) = SceneAnchorSnapshot(
        id = id,
        pose = SceneAnchorPose(0f, 0f, 0f, 0f, 0f, 0f, 1f),
    )
}
