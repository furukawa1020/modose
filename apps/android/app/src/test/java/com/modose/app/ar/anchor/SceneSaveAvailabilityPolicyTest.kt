package com.modose.app.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneSaveAvailabilityPolicyTest {
    @Test
    fun `allows saving only while the anchor is tracking`() {
        assertEquals(
            SceneSaveAvailability.Ready,
            SceneSaveAvailabilityPolicy.resolve(SceneAnchorState.Tracking(anchor())),
        )
        assertEquals(
            SceneSaveAvailability.Blocked(SceneSaveBlockedReason.AnchorPaused),
            SceneSaveAvailabilityPolicy.resolve(SceneAnchorState.Paused(anchor())),
        )
        assertEquals(
            SceneSaveAvailability.Blocked(SceneSaveBlockedReason.AnchorLost),
            SceneSaveAvailabilityPolicy.resolve(SceneAnchorState.Lost(4)),
        )
        assertEquals(
            SceneSaveAvailability.Blocked(SceneSaveBlockedReason.AnchorFailed),
            SceneSaveAvailabilityPolicy.resolve(
                SceneAnchorState.Failed(SceneAnchorFailureReason.CreationFailed),
            ),
        )
    }

    private fun anchor() = SceneAnchorSnapshot(
        id = 4,
        pose = SceneAnchorPose(0f, 0f, 0f, 0f, 0f, 0f, 1f),
    )
}
